package io.andreygrigoriev.yfin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.andreygrigoriev.yfin.exception.YFTransportException;
import io.andreygrigoriev.yfin.internal.cache.FileCache;
import io.andreygrigoriev.yfin.internal.http.JdkTransport;
import io.andreygrigoriev.yfin.internal.http.RequestLimiter;
import io.andreygrigoriev.yfin.internal.http.RetryPolicy;
import io.andreygrigoriev.yfin.spi.Cache;
import io.andreygrigoriev.yfin.spi.HttpRequest;
import io.andreygrigoriev.yfin.spi.HttpResponse;
import io.andreygrigoriev.yfin.spi.Transport;
import io.andreygrigoriev.yfin.testing.FakeTransport;
import java.net.ProxySelector;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class YFinanceTest {

    private final FakeTransport fake = FakeTransport.create();

    private YFinance fakeClient() {
        return YFinance.builder().transport(fake).cache(Cache.none()).build();
    }

    @Test
    void builderDefaults() {
        try (YFinance yf = YFinance.builder().cache(Cache.none()).build()) {
            assertThat(yf.connectTimeout()).isEqualTo(Duration.ofSeconds(10));
            assertThat(yf.requestTimeout()).isEqualTo(Duration.ofSeconds(30));
            assertThat(yf.userAgent()).isEqualTo(JdkTransport.DEFAULT_USER_AGENT);
            assertThat(yf.retryPolicy().maxRetries()).isEqualTo(RetryPolicy.DEFAULT_MAX_RETRIES);
            assertThat(yf.limiter().maxConcurrentRequests()).isEqualTo(RequestLimiter.DEFAULT_MAX_CONCURRENT_REQUESTS);
            assertThat(yf.repair()).isFalse();
            assertThat(yf.proxy()).isEmpty();
            assertThat(yf.transport()).isInstanceOf(JdkTransport.class);
        }
    }

    @Test
    void defaultCacheIsFileCacheAtDefaultLocation() {
        try (YFinance yf = YFinance.create()) {
            assertThat(yf.cache())
                    .isInstanceOfSatisfying(
                            FileCache.class, c -> assertThat(c.dir()).isEqualTo(FileCache.defaultDir()));
        }
    }

    @Test
    void customSettingsApplied(@TempDir Path dir) {
        try (YFinance yf = YFinance.builder()
                .connectTimeout(Duration.ofSeconds(3))
                .requestTimeout(Duration.ofSeconds(4))
                .userAgent("test-agent")
                .proxy(ProxySelector.getDefault())
                .maxRetries(0)
                .maxConcurrentRequests(2)
                .cacheDir(dir)
                .repair(true)
                .build()) {
            assertThat(yf.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
            assertThat(yf.requestTimeout()).isEqualTo(Duration.ofSeconds(4));
            assertThat(yf.userAgent()).isEqualTo("test-agent");
            assertThat(yf.proxy()).containsSame(ProxySelector.getDefault());
            assertThat(yf.retryPolicy().maxRetries()).isZero();
            assertThat(yf.limiter().maxConcurrentRequests()).isEqualTo(2);
            assertThat(yf.repair()).isTrue();
            assertThat(yf.cache())
                    .isInstanceOfSatisfying(
                            FileCache.class, c -> assertThat(c.dir()).isEqualTo(dir));
        }
    }

    @Test
    void lastCacheSettingWins(@TempDir Path dir) {
        try (YFinance yf = YFinance.builder()
                .cacheDir(dir)
                .cache(Cache.none())
                .transport(fake)
                .build()) {
            assertThat(yf.cache()).isSameAs(Cache.none());
        }
        try (YFinance yf = YFinance.builder()
                .cache(Cache.none())
                .cacheDir(dir)
                .transport(fake)
                .build()) {
            assertThat(yf.cache()).isInstanceOf(FileCache.class);
        }
    }

    @Test
    void validationErrors() {
        var b = YFinance.builder();
        assertThatThrownBy(() -> b.connectTimeout(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> b.connectTimeout(Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> b.requestTimeout(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> b.maxRetries(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> b.maxConcurrentRequests(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> b.userAgent(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatNullPointerException().isThrownBy(() -> b.connectTimeout(null));
        assertThatNullPointerException().isThrownBy(() -> b.cache(null));
        assertThatNullPointerException().isThrownBy(() -> b.cacheDir(null));
        assertThatNullPointerException().isThrownBy(() -> b.transport(null));
        assertThatNullPointerException().isThrownBy(() -> b.userAgent(null));
        // zero retries is allowed
        assertThat(b.maxRetries(0)).isSameAs(b);
    }

    @Test
    void customTransportCarriesSessionRequests() {
        fake.onGet("").respond(404, "not found");
        fake.onGet("/v1/test/getcrumb").respond(200, "crumb1");
        fake.onGet("/v7/finance/quote").hasQuery("crumb").respond(200, "{}");

        try (YFinance yf = fakeClient()) {
            HttpResponse response = yf.session()
                    .execute(HttpRequest.get(
                            URI.create("https://query1.finance.yahoo.com/v7/finance/quote?symbols=AAPL")));

            assertThat(response.status()).isEqualTo(200);
            assertThat(yf.transport()).isSameAs(fake);
            assertThat(fake.requests())
                    .extracting(r -> r.uri().getPath())
                    .containsExactly("", "/v1/test/getcrumb", "/v7/finance/quote");
        }
    }

    @Test
    void sessionCreatedLazilyAndOnce() {
        try (YFinance yf = fakeClient()) {
            assertThat(yf.session()).isSameAs(yf.session());
            assertThat(fake.requests()).isEmpty();
        }
    }

    @Test
    void sessionUsesConfiguredCache(@TempDir Path dir) throws Exception {
        fake.onGet("").respond(404, "not found");
        fake.onGet("/v1/test/getcrumb").respond(200, "crumb1");
        try (YFinance yf = YFinance.builder().transport(fake).cacheDir(dir).build()) {
            assertThat(yf.session().crumb()).isEqualTo("crumb1");
        }
        assertThat(Files.readString(dir.resolve("cookie.json"))).contains("crumb1");
    }

    @Test
    void closeIsIdempotentAndBlocksFurtherUse() {
        YFinance yf = fakeClient();
        assertThat(yf.isClosed()).isFalse();

        yf.close();
        yf.close();

        assertThat(yf.isClosed()).isTrue();
        assertThatThrownBy(() -> yf.ticker("AAPL")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(yf::session).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(yf::cache).isInstanceOf(IllegalStateException.class);
    }

    /** Transport that counts close calls. */
    private static final class CloseableTransport implements Transport, AutoCloseable {
        final AtomicInteger closes = new AtomicInteger();

        @Override
        public HttpResponse send(HttpRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
            closes.incrementAndGet();
        }
    }

    @Test
    void doesNotCloseCallerSuppliedTransport() {
        var transport = new CloseableTransport();
        YFinance.builder().transport(transport).cache(Cache.none()).build().close();
        assertThat(transport.closes).hasValue(0);
    }

    @Test
    void closesOwnTransport() {
        YFinance yf = YFinance.builder().cache(Cache.none()).build();
        yf.close();
        // a closed JDK HttpClient refuses new requests
        var transport = yf.transport();
        assertThatThrownBy(() -> transport.send(HttpRequest.get(URI.create("https://127.0.0.1:1/"))))
                .isInstanceOf(YFTransportException.class)
                .hasMessageEndingWith("java.io.IOException: closed");
    }

    @Test
    void tickerNormalizesSymbol() {
        try (YFinance yf = fakeClient()) {
            Ticker t = yf.ticker("  brk-b ");
            assertThat(t.symbol()).isEqualTo("BRK-B");
            assertThat(t.client()).isSameAs(yf);
            assertThat(t).hasToString("Ticker[BRK-B]");
            assertThat(fake.requests()).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t\n", ".", " .. "})
    void tickerRejectsBlankAndDotOnly(String symbol) {
        try (YFinance yf = fakeClient()) {
            assertThatThrownBy(() -> yf.ticker(symbol)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void tickerRejectsNull() {
        try (YFinance yf = fakeClient()) {
            assertThatNullPointerException().isThrownBy(() -> yf.ticker(null));
        }
    }
}
