package io.github.andreygrigoriev.yfin.live;

import io.github.andreygrigoriev.yfin.YFinance;
import io.github.andreygrigoriev.yfin.exception.YFTransportException;
import io.github.andreygrigoriev.yfin.live.internal.ReconnectPolicy;
import java.net.ProxySelector;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Entry point for real-time streaming (yfinance {@code WebSocket}).
 *
 * <pre>{@code
 * try (YFinance yf = YFinance.create();
 *         LiveStream stream = YFinanceLive.connect(yf, tick -> System.out.println(tick))) {
 *     stream.subscribe("AAPL", "BTC-USD");
 *     Thread.sleep(Duration.ofSeconds(30));
 * }
 * }</pre>
 */
public final class YFinanceLive {

    /** Yahoo's streamer endpoint. */
    public static final URI DEFAULT_URI = URI.create("wss://streamer.finance.yahoo.com/?version=2");
    /** How often the subscription set is re-sent, like yfinance. */
    public static final Duration DEFAULT_HEARTBEAT_INTERVAL = Duration.ofSeconds(15);

    private YFinanceLive() {}

    /**
     * Connects to the streamer with the proxy, User-Agent and connect timeout of {@code client}.
     *
     * @throws YFTransportException when the first connection cannot be established
     */
    public static LiveStream connect(YFinance client, LiveListener listener) {
        return builder(client).connect(listener);
    }

    /** Stream settings starting from the proxy, User-Agent and connect timeout of {@code client}. */
    public static Builder builder(YFinance client) {
        Objects.requireNonNull(client, "client");
        return new Builder(client);
    }

    /** Settings for a {@link LiveStream}. */
    public static final class Builder {

        URI uri = DEFAULT_URI;

        @Nullable
        ProxySelector proxy;

        String userAgent;
        Duration connectTimeout;
        Duration heartbeatInterval = DEFAULT_HEARTBEAT_INTERVAL;
        ReconnectPolicy reconnectPolicy = ReconnectPolicy.defaults();

        private Builder(YFinance client) {
            this.proxy = client.proxy().orElse(null);
            this.userAgent = client.userAgent();
            this.connectTimeout = client.connectTimeout();
        }

        /** Streamer URI ({@code ws} or {@code wss}); default {@link #DEFAULT_URI}. */
        public Builder uri(URI uri) {
            Objects.requireNonNull(uri, "uri");
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("ws") && !scheme.equals("wss")) {
                throw new IllegalArgumentException("not a ws/wss URI: " + uri);
            }
            this.uri = uri;
            return this;
        }

        /** Proxy selector, or {@code null} for a direct connection. */
        public Builder proxy(@Nullable ProxySelector proxy) {
            this.proxy = proxy;
            return this;
        }

        /** User-Agent of the WebSocket handshake. */
        public Builder userAgent(String userAgent) {
            if (Objects.requireNonNull(userAgent, "userAgent").isBlank()) {
                throw new IllegalArgumentException("userAgent is blank");
            }
            this.userAgent = userAgent;
            return this;
        }

        /** Timeout of the handshake and of each outbound message. */
        public Builder connectTimeout(Duration timeout) {
            this.connectTimeout = positive(timeout, "connectTimeout");
            return this;
        }

        /** How often the subscription set is re-sent; default 15s. */
        public Builder heartbeatInterval(Duration interval) {
            this.heartbeatInterval = positive(interval, "heartbeatInterval");
            return this;
        }

        /**
         * Reconnect backoff: attempt {@code n} waits between half and all of {@code min(max,
         * initial * 2^(n-1))}; default 1s and 30s.
         */
        public Builder reconnectDelay(Duration initial, Duration max) {
            positive(max, "max");
            this.reconnectPolicy = new ReconnectPolicy(initial, max); // validates initial and max >= initial
            return this;
        }

        /**
         * Opens the connection; returns once the handshake has completed.
         *
         * @throws YFTransportException when the connection cannot be established
         */
        public LiveStream connect(LiveListener listener) {
            return new LiveStream(this, listener);
        }

        private static Duration positive(Duration d, String name) {
            Objects.requireNonNull(d, name);
            if (d.isNegative() || d.isZero()) {
                throw new IllegalArgumentException(name + " must be positive: " + d);
            }
            return d;
        }
    }
}
