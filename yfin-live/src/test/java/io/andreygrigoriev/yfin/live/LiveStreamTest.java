package io.andreygrigoriev.yfin.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.andreygrigoriev.yfin.YFinance;
import io.andreygrigoriev.yfin.exception.YFDataException;
import io.andreygrigoriev.yfin.exception.YFTransportException;
import io.andreygrigoriev.yfin.spi.Cache;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LiveStreamTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private YFinance yf;
    private TestWebSocketServer server;
    private final Recorder recorder = new Recorder();

    @BeforeEach
    void setUp() throws IOException {
        yf = YFinance.builder()
                .cache(Cache.none())
                .userAgent("yfin-test-agent")
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        server = new TestWebSocketServer();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.close();
        yf.close();
    }

    private YFinanceLive.Builder builder() {
        return YFinanceLive.builder(yf).uri(server.uri()).reconnectDelay(Duration.ofMillis(20), Duration.ofMillis(50));
    }

    private static String fixture(String name) throws IOException {
        try (InputStream in = LiveStreamTest.class.getResourceAsStream("/fixtures/streamer/" + name)) {
            assertThat(in).as(name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        }
    }

    @Test
    void handshakeUsesClientUserAgent() throws Exception {
        try (LiveStream stream = builder().connect(recorder)) {
            var conn = server.awaitConnection(TIMEOUT);
            assertThat(conn.header("User-Agent")).isEqualTo("yfin-test-agent");
            assertThat(stream.isConnected()).isTrue();
            assertThat(stream.isClosed()).isFalse();
        }
    }

    @Test
    void subscribeSendsWholeSetAndUnsubscribeSendsRemoved() throws Exception {
        try (LiveStream stream = builder().connect(recorder)) {
            var conn = server.awaitConnection(TIMEOUT);

            stream.subscribe(" aapl ", "BTC-USD");
            assertThat(conn.receive(TIMEOUT)).isEqualTo("{\"subscribe\":[\"AAPL\",\"BTC-USD\"]}");
            stream.subscribe("MSFT", "AAPL");
            assertThat(conn.receive(TIMEOUT)).isEqualTo("{\"subscribe\":[\"AAPL\",\"BTC-USD\",\"MSFT\"]}");
            stream.unsubscribe("btc-usd");
            assertThat(conn.receive(TIMEOUT)).isEqualTo("{\"unsubscribe\":[\"BTC-USD\"]}");

            assertThat(stream.subscriptions()).containsExactly("AAPL", "MSFT");
            assertThatIllegalArgumentException().isThrownBy(() -> stream.subscribe(" "));
        }
    }

    @Test
    void callbacksReceiveTicksInOrder() throws Exception {
        try (LiveStream stream = builder().connect(recorder)) {
            var conn = server.awaitConnection(TIMEOUT);
            conn.sendText(fixture("aapl.json"));
            conn.sendFragmented(fixture("btc-usd.json"), 3);
            conn.sendText("{\"type\":\"other\"}");
            conn.sendText(fixture("eurusd.json"));

            assertThat(recorder.nextTick().id()).isEqualTo("AAPL");
            PriceTick btc = recorder.nextTick();
            assertThat(btc.id()).isEqualTo("BTC-USD");
            assertThat(btc.price()).isEqualTo(83733.99);
            assertThat(recorder.nextTick().id()).isEqualTo("EURUSD=X");
            assertThat(recorder.callbackThreads)
                    .allSatisfy(t -> assertThat(t.isVirtual()).isTrue());
            assertThat(recorder.errors).isEmpty();
        }
    }

    @Test
    void decodeErrorsAndListenerExceptionsGoToOnError() throws Exception {
        var failing = new Recorder() {
            @Override
            public void onTick(PriceTick tick) {
                super.onTick(tick);
                if (tick.id().equals("AAPL")) {
                    throw new IllegalStateException("boom");
                }
            }
        };
        try (LiveStream stream = builder().connect(failing)) {
            var conn = server.awaitConnection(TIMEOUT);
            conn.sendText("{\"message\":\"%%%\"}");
            conn.sendText("garbage");
            conn.sendText(fixture("aapl.json"));
            conn.sendText(fixture("eurusd.json"));

            assertThat(failing.nextError()).isInstanceOf(YFDataException.class).hasMessageContaining("base64");
            assertThat(failing.nextError()).isInstanceOf(YFDataException.class).hasMessageContaining("JSON");
            assertThat(failing.nextError())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("boom");
            assertThat(failing.nextTick().id()).isEqualTo("AAPL");
            assertThat(failing.nextTick().id()).isEqualTo("EURUSD=X");
            assertThat(stream.isConnected()).isTrue();
        }
    }

    @Test
    void heartbeatResendsSubscriptions() throws Exception {
        try (LiveStream stream =
                builder().heartbeatInterval(Duration.ofMillis(100)).connect(recorder)) {
            var conn = server.awaitConnection(TIMEOUT);
            stream.subscribe("AAPL");
            for (int i = 0; i < 3; i++) {
                assertThat(conn.receive(TIMEOUT)).isEqualTo("{\"subscribe\":[\"AAPL\"]}");
            }
        }
    }

    @Test
    void heartbeatSendsNothingWithoutSubscriptions() throws Exception {
        try (LiveStream stream =
                builder().heartbeatInterval(Duration.ofMillis(50)).connect(recorder)) {
            var conn = server.awaitConnection(TIMEOUT);
            Thread.sleep(300);
            stream.subscribe("AAPL");
            assertThat(conn.receive(TIMEOUT)).isEqualTo("{\"subscribe\":[\"AAPL\"]}");
        }
    }

    @Test
    void droppedConnectionReconnectsAndResubscribes() throws Exception {
        try (LiveStream stream = builder().connect(recorder)) {
            var first = server.awaitConnection(TIMEOUT);
            stream.subscribe("AAPL", "BTC-USD");
            assertThat(first.receive(TIMEOUT)).contains("AAPL");

            first.drop();

            assertThat(recorder.nextError()).isInstanceOf(YFTransportException.class);
            var second = server.awaitConnection(TIMEOUT);
            assertThat(second.receive(TIMEOUT)).isEqualTo("{\"subscribe\":[\"AAPL\",\"BTC-USD\"]}");
            second.sendText(fixture("aapl.json"));
            assertThat(recorder.nextTick().id()).isEqualTo("AAPL");
            assertThat(stream.isConnected()).isTrue();
        }
    }

    @Test
    void serverCloseReconnects() throws Exception {
        try (LiveStream stream = builder().connect(recorder)) {
            var first = server.awaitConnection(TIMEOUT);
            stream.subscribe("ETH-USD");
            first.receive(TIMEOUT);

            first.sendClose(1001);

            assertThat(recorder.nextError())
                    .isInstanceOf(YFTransportException.class)
                    .hasMessageContaining("1001");
            var second = server.awaitConnection(TIMEOUT);
            assertThat(second.receive(TIMEOUT)).isEqualTo("{\"subscribe\":[\"ETH-USD\"]}");
        }
    }

    @Test
    void subscribeWhileDisconnectedIsSentOnReconnect() throws Exception {
        try (LiveStream stream = YFinanceLive.builder(yf)
                .uri(server.uri())
                .reconnectDelay(Duration.ofMillis(400), Duration.ofMillis(400))
                .connect(recorder)) {
            var first = server.awaitConnection(TIMEOUT);
            stream.subscribe("AAPL");
            first.receive(TIMEOUT);

            first.drop();
            recorder.nextError();
            assertThat(stream.isConnected()).isFalse();
            stream.subscribe("MSFT");

            var second = server.awaitConnection(TIMEOUT);
            assertThat(second.receive(TIMEOUT)).isEqualTo("{\"subscribe\":[\"AAPL\",\"MSFT\"]}");
        }
    }

    @Test
    void failedReconnectAttemptsAreReportedAndRetried() throws Exception {
        URI uri;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            uri = URI.create("ws://127.0.0.1:" + probe.getLocalPort() + "/");
        }
        // connect to a working server, then point reconnects at nothing by closing it
        try (LiveStream stream = builder().connect(recorder)) {
            server.awaitConnection(TIMEOUT);
            server.close();

            assertThat(recorder.nextError()).isInstanceOf(YFTransportException.class); // connection lost
            assertThat(recorder.nextError()).isInstanceOf(YFTransportException.class); // attempt 1
            assertThat(recorder.nextError()).isInstanceOf(YFTransportException.class); // attempt 2
            assertThat(stream.isConnected()).isFalse();
        }
        assertThatThrownBy(() -> builder().uri(uri).connect(recorder))
                .isInstanceOf(YFTransportException.class)
                .hasMessageContaining("cannot connect");
    }

    @Test
    void concurrentSubscribesAllArriveAndLastFrameHasWholeSet() throws Exception {
        try (LiveStream stream = builder().connect(recorder)) {
            var conn = server.awaitConnection(TIMEOUT);
            int threads = 8;
            var start = new CountDownLatch(1);
            List<Thread> workers = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String symbol = "SYM" + i;
                workers.add(Thread.ofVirtual().start(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    stream.subscribe(symbol);
                }));
            }
            start.countDown();
            for (Thread t : workers) {
                t.join(TIMEOUT);
            }

            assertThat(stream.subscriptions()).hasSize(threads);
            String last = null;
            for (int i = 0; i < threads; i++) {
                last = conn.receive(TIMEOUT);
            }
            // frames are snapshotted and sent in order, so the last one carries every symbol
            for (int i = 0; i < threads; i++) {
                assertThat(last).contains("\"SYM" + i + "\"");
            }
            assertThat(recorder.errors).isEmpty();
        }
    }

    @Test
    void backoffGrowsWhenServerDropsEveryConnectionBeforeAFrame() throws Exception {
        try (LiveStream stream = YFinanceLive.builder(yf)
                .uri(server.uri())
                .reconnectDelay(Duration.ofMillis(100), Duration.ofSeconds(2))
                .connect(recorder)) {
            long[] acceptedAt = new long[4];
            for (int i = 0; i < acceptedAt.length; i++) {
                var conn = server.awaitConnection(TIMEOUT);
                acceptedAt[i] = System.nanoTime();
                conn.drop();
            }
            // attempt n waits at least half of 100ms * 2^(n-1): the third reconnect waits >= 200ms;
            // without growth every wait would stay below 100ms
            assertThat(Duration.ofNanos(acceptedAt[3] - acceptedAt[2])).isGreaterThanOrEqualTo(Duration.ofMillis(200));
            assertThat(stream.isClosed()).isFalse();
        }
    }

    @Test
    void closeEndsConnectionAndStopsReconnecting() throws Exception {
        LiveStream stream = builder().connect(recorder);
        var conn = server.awaitConnection(TIMEOUT);
        stream.subscribe("AAPL");
        conn.receive(TIMEOUT);

        stream.close();
        stream.close();

        assertThat(stream.isClosed()).isTrue();
        assertThat(stream.isConnected()).isFalse();
        assertThatIllegalStateException().isThrownBy(() -> stream.subscribe("MSFT"));
        assertThatIllegalStateException().isThrownBy(() -> stream.unsubscribe("AAPL"));
        Thread.sleep(200); // 4x the 50ms maximum reconnect delay of builder()
        assertThat(server.connectionCount()).isEqualTo(1);
        assertThat(recorder.errors).isEmpty();
    }

    @Test
    void builderCopiesClientSettingsAndValidates() {
        try (YFinance proxied = YFinance.builder()
                .cache(Cache.none())
                .proxy(ProxySelector.getDefault())
                .build()) {
            YFinanceLive.Builder b = YFinanceLive.builder(proxied);
            assertThat(b.proxy).isSameAs(ProxySelector.getDefault());
            assertThat(b.userAgent).isEqualTo(proxied.userAgent());
            assertThat(b.connectTimeout).isEqualTo(YFinance.DEFAULT_CONNECT_TIMEOUT);
            assertThat(b.uri).isEqualTo(YFinanceLive.DEFAULT_URI);
            assertThat(b.heartbeatInterval).isEqualTo(Duration.ofSeconds(15));
        }
        var b = YFinanceLive.builder(yf);
        assertThatIllegalArgumentException().isThrownBy(() -> b.uri(URI.create("https://example.com")));
        assertThatIllegalArgumentException().isThrownBy(() -> b.heartbeatInterval(Duration.ZERO));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> b.reconnectDelay(Duration.ofSeconds(2), Duration.ofSeconds(1)));
        assertThatIllegalArgumentException().isThrownBy(() -> b.userAgent(" "));
    }

    /** Collects callbacks. */
    static class Recorder implements LiveListener {

        final BlockingQueue<PriceTick> ticks = new LinkedBlockingQueue<>();
        final BlockingQueue<Throwable> errors = new LinkedBlockingQueue<>();
        final BlockingQueue<Thread> callbackThreads = new LinkedBlockingQueue<>();

        @Override
        public void onTick(PriceTick tick) {
            callbackThreads.add(Thread.currentThread());
            ticks.add(tick);
        }

        @Override
        public void onError(Throwable error) {
            errors.add(error);
        }

        PriceTick nextTick() throws InterruptedException {
            PriceTick t = ticks.poll(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            assertThat(t).as("tick within " + TIMEOUT).isNotNull();
            return t;
        }

        Throwable nextError() throws InterruptedException {
            Throwable t = errors.poll(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            assertThat(t).as("error within " + TIMEOUT).isNotNull();
            return t;
        }
    }
}
