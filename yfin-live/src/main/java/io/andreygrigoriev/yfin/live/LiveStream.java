package io.andreygrigoriev.yfin.live;

import io.andreygrigoriev.yfin.exception.YFDataException;
import io.andreygrigoriev.yfin.exception.YFTransportException;
import io.andreygrigoriev.yfin.live.internal.ReconnectPolicy;
import io.andreygrigoriev.yfin.live.internal.StreamerProtocol;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.SequencedSet;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Live connection to the Yahoo Finance streamer (yfinance {@code WebSocket}); create one with
 * {@link YFinanceLive}.
 *
 * <pre>{@code
 * try (LiveStream stream = YFinanceLive.connect(yf, tick -> System.out.println(tick))) {
 *     stream.subscribe("AAPL", "BTC-USD");
 *     Thread.sleep(Duration.ofMinutes(1));
 * }
 * }</pre>
 *
 * <p>Every {@link #subscribe} sends the whole subscription set, like yfinance, and a heartbeat
 * re-sends it every 15s (Yahoo stops streaming otherwise). When the connection drops, the stream
 * reconnects with exponential backoff and resubscribes, reporting each failure to {@link
 * LiveListener#onError}; it never gives up until {@link #close()}. Subscribing while disconnected
 * only updates the set, which is sent on reconnect.
 *
 * <p>Thread-safe. Independent of the {@code YFinance} client it was configured from: closing the
 * client does not close the stream.
 */
public final class LiveStream implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LiveStream.class);

    private final URI uri;
    private final String userAgent;
    private final Duration connectTimeout;
    private final ReconnectPolicy reconnectPolicy;
    private final LiveListener listener;
    private final HttpClient client;
    /** Runs listener callbacks one at a time, in order. */
    private final ExecutorService callbacks;
    /** Runs the heartbeat and reconnect attempts. */
    private final ScheduledExecutorService scheduler;

    /**
     * Serializes sends: the JDK WebSocket allows one outstanding send, and taking the snapshot under
     * it keeps subscription frames in order. Acquired before {@link #lock}, never after it.
     */
    private final Object sendLock = new Object();

    private final Object lock = new Object();
    // guarded by lock
    private final SequencedSet<String> subscriptions = new LinkedHashSet<>();
    private @Nullable WebSocket socket;
    private int generation;
    /** Consecutive connections that failed or ended before delivering a frame. */
    private int failedAttempts;

    private boolean reconnectScheduled;
    private boolean closed;

    LiveStream(YFinanceLive.Builder b, LiveListener listener) {
        this.uri = b.uri;
        this.userAgent = b.userAgent;
        this.connectTimeout = b.connectTimeout;
        this.reconnectPolicy = b.reconnectPolicy;
        this.listener = Objects.requireNonNull(listener, "listener");
        var clientBuilder = HttpClient.newBuilder().connectTimeout(b.connectTimeout);
        if (b.proxy != null) {
            clientBuilder.proxy(b.proxy);
        }
        this.client = clientBuilder.build();
        this.callbacks = Executors.newSingleThreadExecutor(
                Thread.ofVirtual().name("yfin-live-callback").factory());
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("yfin-live-scheduler").factory());
        try {
            WebSocket ws = open(0);
            synchronized (lock) {
                if (generation == 0) {
                    socket = ws;
                } else {
                    ws.abort(); // lost before we got here: a reconnect is already scheduled
                }
            }
        } catch (RuntimeException e) {
            shutdownExecutors();
            throw e;
        }
        long heartbeat = b.heartbeatInterval.toNanos();
        scheduler.scheduleAtFixedRate(this::heartbeat, heartbeat, heartbeat, TimeUnit.NANOSECONDS);
        log.debug("connected to {}", uri);
    }

    /** Same as {@link #subscribe(Collection)}. */
    public void subscribe(String... symbols) {
        subscribe(List.of(symbols));
    }

    /**
     * Adds {@code symbols} (trimmed, upper-cased) to the subscription set and sends the whole set.
     *
     * @throws IllegalArgumentException when a symbol is blank
     * @throws IllegalStateException when the stream is closed
     */
    public void subscribe(Collection<String> symbols) {
        List<String> normalized = normalize(symbols);
        synchronized (sendLock) {
            Outgoing out;
            synchronized (lock) {
                ensureOpen();
                subscriptions.addAll(normalized);
                out = outgoingLocked(StreamerProtocol.subscribe(subscriptions));
            }
            send(out);
        }
    }

    /** Same as {@link #unsubscribe(Collection)}. */
    public void unsubscribe(String... symbols) {
        unsubscribe(List.of(symbols));
    }

    /**
     * Removes {@code symbols} from the subscription set and asks Yahoo to stop streaming them.
     *
     * @throws IllegalArgumentException when a symbol is blank
     * @throws IllegalStateException when the stream is closed
     */
    public void unsubscribe(Collection<String> symbols) {
        List<String> normalized = normalize(symbols);
        synchronized (sendLock) {
            Outgoing out;
            synchronized (lock) {
                ensureOpen();
                normalized.forEach(subscriptions::remove);
                out = outgoingLocked(StreamerProtocol.unsubscribe(normalized));
            }
            send(out);
        }
    }

    /** Current subscription set, in subscription order. */
    public List<String> subscriptions() {
        synchronized (lock) {
            return List.copyOf(subscriptions);
        }
    }

    /** Whether a connection is up right now (false while reconnecting and after close). */
    public boolean isConnected() {
        synchronized (lock) {
            return socket != null && !closed;
        }
    }

    /** Whether {@link #close()} has been called. */
    public boolean isClosed() {
        synchronized (lock) {
            return closed;
        }
    }

    /**
     * Stops the heartbeat and reconnects, closes the connection and the callback thread. Callbacks
     * already queued may still run. Idempotent.
     */
    @Override
    public void close() {
        WebSocket ws;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            ws = socket;
            socket = null;
            generation++;
        }
        scheduler.shutdownNow();
        if (ws != null) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "").get(connectTimeout.toNanos(), TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException | TimeoutException e) {
                log.debug("close handshake failed: {}", e.toString());
            } finally {
                ws.abort();
            }
        }
        shutdownExecutors();
        log.debug("closed stream to {}", uri);
    }

    @Override
    public String toString() {
        return "LiveStream[" + uri + ", connected=" + isConnected() + ", subscriptions=" + subscriptions() + "]";
    }

    // ---- connection management ----

    private WebSocket open(int gen) {
        try {
            return client.newWebSocketBuilder()
                    .connectTimeout(connectTimeout)
                    .header("User-Agent", userAgent)
                    .buildAsync(uri, new Handler(gen))
                    .get(connectTimeout.toNanos() * 2, TimeUnit.NANOSECONDS);
        } catch (ExecutionException e) {
            throw new YFTransportException("cannot connect to " + uri + ": " + e.getCause(), e.getCause());
        } catch (TimeoutException e) {
            throw new YFTransportException("timeout connecting to " + uri, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new YFTransportException("interrupted connecting to " + uri, e);
        }
    }

    /** A frame to send on the socket of one connection generation. */
    private record Outgoing(WebSocket socket, int generation, String text) {}

    /**
     * Snapshot of what to send on the current socket, or null while reconnecting (the subscription
     * set is sent once the new connection is up).
     */
    private @Nullable Outgoing outgoingLocked(String text) {
        WebSocket ws = socket;
        return ws == null ? null : new Outgoing(ws, generation, text);
    }

    /**
     * Sends {@code out} without holding {@link #lock}; the caller holds {@link #sendLock}. A failed
     * send starts a reconnect.
     */
    private void send(@Nullable Outgoing out) {
        if (out == null) {
            return;
        }
        try {
            out.socket().sendText(out.text(), true).get(connectTimeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new YFTransportException("interrupted sending to " + uri, e);
        } catch (ExecutionException | TimeoutException e) {
            Throwable cause = e instanceof ExecutionException ? e.getCause() : e;
            connectionLost(out.generation(), new YFTransportException("send to " + uri + " failed: " + cause, cause));
        }
    }

    private void heartbeat() {
        synchronized (sendLock) {
            Outgoing out = null;
            synchronized (lock) {
                if (!closed && !subscriptions.isEmpty()) {
                    out = outgoingLocked(StreamerProtocol.subscribe(subscriptions));
                }
            }
            send(out);
        }
    }

    /** First frame of connection {@code gen} arrived: the connection works, so reset the backoff. */
    private void connectionHealthy(int gen) {
        synchronized (lock) {
            if (gen == generation) {
                failedAttempts = 0;
            }
        }
    }

    private void connectionLost(int gen, YFTransportException error) {
        synchronized (lock) {
            connectionLostLocked(gen, error);
        }
    }

    private void connectionLostLocked(int gen, YFTransportException error) {
        if (closed || gen != generation) {
            return; // stale callback of a socket already replaced or closed
        }
        WebSocket old = socket;
        socket = null;
        generation++;
        // reset by connectionHealthy once a frame arrives, so a server that accepts and then drops
        // the connection straight away still gets a growing backoff
        failedAttempts++;
        if (old != null) {
            old.abort();
        }
        log.warn("{}; reconnecting", error.getMessage());
        dispatchError(error);
        scheduleReconnectLocked();
    }

    private void scheduleReconnectLocked() {
        if (reconnectScheduled || closed) {
            return;
        }
        reconnectScheduled = true;
        Duration delay = reconnectPolicy.delay(Math.max(1, failedAttempts));
        try {
            scheduler.schedule(this::reconnect, delay.toNanos(), TimeUnit.NANOSECONDS);
        } catch (RejectedExecutionException e) {
            reconnectScheduled = false; // closing
        }
    }

    private void reconnect() {
        int gen;
        synchronized (lock) {
            reconnectScheduled = false;
            if (closed) {
                return;
            }
            gen = generation;
        }
        WebSocket ws;
        try {
            ws = open(gen);
        } catch (YFTransportException e) {
            synchronized (lock) {
                if (closed) {
                    return;
                }
                failedAttempts++;
                log.warn("reconnect failed ({} consecutive failures): {}", failedAttempts, e.getMessage());
                dispatchError(e);
                scheduleReconnectLocked();
            }
            return;
        }
        synchronized (sendLock) {
            Outgoing out = null;
            synchronized (lock) {
                if (closed || gen != generation) {
                    ws.abort();
                    return;
                }
                socket = ws;
                log.info("reconnected to {}", uri);
                if (!subscriptions.isEmpty()) {
                    out = outgoingLocked(StreamerProtocol.subscribe(subscriptions));
                }
            }
            send(out);
        }
    }

    // ---- callbacks ----

    /**
     * Hands {@code frame} to the callback thread, which requests the next frame from {@code
     * webSocket} once the listener is done: a slow listener applies backpressure instead of
     * queueing frames without bound.
     */
    private void dispatchFrame(WebSocket webSocket, String frame) {
        try {
            callbacks.execute(() -> {
                try {
                    deliver(frame);
                } finally {
                    webSocket.request(1);
                }
            });
        } catch (RejectedExecutionException e) {
            // closed
        }
    }

    private void deliver(String frame) {
        PriceTick tick;
        try {
            tick = StreamerProtocol.decodeFrame(frame);
        } catch (YFDataException e) {
            log.debug("undecodable frame: {}", e.getMessage());
            notifyError(e);
            return;
        }
        if (tick == null) {
            return;
        }
        try {
            listener.onTick(tick);
        } catch (RuntimeException e) {
            log.debug("onTick threw", e);
            notifyError(e);
        }
    }

    private void dispatchError(Throwable error) {
        try {
            callbacks.execute(() -> notifyError(error));
        } catch (RejectedExecutionException e) {
            // closed
        }
    }

    private void notifyError(Throwable error) {
        try {
            listener.onError(error);
        } catch (RuntimeException e) {
            log.warn("onError threw", e);
        }
    }

    private void shutdownExecutors() {
        scheduler.shutdownNow();
        callbacks.shutdown();
        client.shutdown();
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("LiveStream is closed");
        }
    }

    private static List<String> normalize(Collection<String> symbols) {
        Objects.requireNonNull(symbols, "symbols");
        List<String> out = new ArrayList<>(symbols.size());
        for (String symbol : symbols) {
            String s = Objects.requireNonNull(symbol, "symbol").trim();
            if (s.isEmpty()) {
                throw new IllegalArgumentException("symbol is blank");
            }
            out.add(s.toUpperCase(Locale.ROOT));
        }
        return out;
    }

    /** WebSocket listener of one connection; events of a replaced connection are ignored. */
    private final class Handler implements WebSocket.Listener {

        private final int gen;
        private final StringBuilder text = new StringBuilder();
        private boolean healthy;

        Handler(int gen) {
            this.gen = gen;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public @Nullable CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            text.append(data);
            if (!last) {
                webSocket.request(1);
                return null;
            }
            String frame = text.toString();
            text.setLength(0);
            if (!healthy) {
                healthy = true;
                connectionHealthy(gen);
            }
            dispatchFrame(webSocket, frame); // requests the next frame once delivered
            return null;
        }

        @Override
        public @Nullable CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            connectionLost(
                    gen, new YFTransportException("streamer closed the connection: " + statusCode + " " + reason));
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            connectionLost(gen, new YFTransportException("streamer connection failed: " + error, error));
        }
    }
}
