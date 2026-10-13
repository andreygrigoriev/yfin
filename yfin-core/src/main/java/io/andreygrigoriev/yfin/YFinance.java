package io.andreygrigoriev.yfin;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.andreygrigoriev.yfin.exception.YFDownloadException;
import io.andreygrigoriev.yfin.exception.YFTransportException;
import io.andreygrigoriev.yfin.exception.YFinanceException;
import io.andreygrigoriev.yfin.internal.cache.FileCache;
import io.andreygrigoriev.yfin.internal.endpoint.screener.ScreenerEndpoint;
import io.andreygrigoriev.yfin.internal.endpoint.search.LookupEndpoint;
import io.andreygrigoriev.yfin.internal.endpoint.search.SearchEndpoint;
import io.andreygrigoriev.yfin.internal.http.JdkTransport;
import io.andreygrigoriev.yfin.internal.http.RequestLimiter;
import io.andreygrigoriev.yfin.internal.http.RetryPolicy;
import io.andreygrigoriev.yfin.internal.http.YahooJson;
import io.andreygrigoriev.yfin.internal.http.YahooSession;
import io.andreygrigoriev.yfin.model.LookupResult;
import io.andreygrigoriev.yfin.model.PriceHistory;
import io.andreygrigoriev.yfin.model.ScreenerResult;
import io.andreygrigoriev.yfin.model.SearchResult;
import io.andreygrigoriev.yfin.request.HistoryRequest;
import io.andreygrigoriev.yfin.request.MarketRegion;
import io.andreygrigoriev.yfin.request.ScreenerQuery;
import io.andreygrigoriev.yfin.spi.Cache;
import io.andreygrigoriev.yfin.spi.Transport;
import java.net.ProxySelector;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SequencedSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;

/**
 * Yahoo Finance client. Holds the HTTP transport, the cookie/crumb session, the request limiter
 * and the cache; create one per application and share it across threads.
 *
 * <pre>{@code
 * try (YFinance yf = YFinance.create()) {
 *     Ticker aapl = yf.ticker("AAPL");
 * }
 * }</pre>
 *
 * <p>Thread-safe. {@link #close()} releases the transport the client created itself; after
 * closing, every method except {@code close()} throws {@link IllegalStateException}.
 */
public final class YFinance implements AutoCloseable {

    /** Default connect timeout. */
    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    /** Default timeout for a single request. */
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final Transport transport;
    private final boolean ownsTransport;
    private final Cache cache;
    private final RequestLimiter limiter;
    private final RetryPolicy retry;
    private final boolean repair;
    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final String userAgent;
    private final @Nullable ProxySelector proxy;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object sessionLock = new Object();
    private volatile @Nullable YahooSession session;

    private YFinance(Builder b) {
        this.connectTimeout = b.connectTimeout;
        this.requestTimeout = b.requestTimeout;
        this.userAgent = b.userAgent;
        this.proxy = b.proxy;
        this.repair = b.repair;
        this.limiter = new RequestLimiter(b.maxConcurrentRequests);
        this.retry = RetryPolicy.of(b.maxRetries);
        this.cache = b.cache != null
                ? b.cache
                : b.cacheDir != null ? new FileCache(b.cacheDir) : FileCache.atDefaultLocation();
        if (b.transport != null) {
            this.transport = b.transport;
            this.ownsTransport = false;
        } else {
            var tb = JdkTransport.builder()
                    .connectTimeout(b.connectTimeout)
                    .requestTimeout(b.requestTimeout)
                    .userAgent(b.userAgent)
                    .proxy(b.proxy);
            this.transport = tb.build();
            this.ownsTransport = true;
        }
    }

    /** Client with default settings. */
    public static YFinance create() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Handle for {@code symbol}, trimmed and upper-cased. No request is made.
     *
     * @throws IllegalArgumentException when {@code symbol} is blank
     * @throws IllegalStateException when the client is closed
     */
    public Ticker ticker(String symbol) {
        ensureOpen();
        return new Ticker(this, normalizeSymbol(symbol));
    }

    /**
     * Handles for {@code symbols}, normalized like {@link #ticker(String)} and deduplicated, in
     * input order. No request is made.
     *
     * @throws IllegalArgumentException when a symbol is blank
     * @throws IllegalStateException when the client is closed
     */
    public Tickers tickers(Collection<String> symbols) {
        ensureOpen();
        return new Tickers(this, normalizeSymbols(symbols));
    }

    /** Same as {@link #tickers(Collection)}. */
    public Tickers tickers(String... symbols) {
        return tickers(List.of(symbols));
    }

    /**
     * Price history of every symbol, fetched concurrently on virtual threads; the number of
     * requests in flight is bounded by {@code maxConcurrentRequests}. Symbols are normalized like
     * {@link #ticker(String)} and deduplicated; the map iterates in input order.
     *
     * <p>Strict: waits for all symbols, then throws {@link YFDownloadException} if any failed,
     * with the failures and the histories that did succeed attached.
     *
     * @throws YFDownloadException when at least one symbol failed
     * @throws YFTransportException when interrupted while waiting; pending downloads are cancelled
     * @throws IllegalArgumentException when a symbol is blank
     * @throws IllegalStateException when the client is closed
     */
    public Map<String, PriceHistory> download(Collection<String> symbols, HistoryRequest request) {
        ensureOpen();
        Objects.requireNonNull(request, "request");
        return download(normalizeSymbols(symbols), request);
    }

    Map<String, PriceHistory> download(SequencedSet<String> symbols, HistoryRequest request) {
        ensureOpen();
        List<Future<PriceHistory>> futures = new ArrayList<>(symbols.size());
        Map<String, PriceHistory> results = new LinkedHashMap<>();
        Map<String, YFinanceException> failures = new LinkedHashMap<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (String symbol : symbols) {
                Ticker ticker = new Ticker(this, symbol);
                futures.add(executor.submit(() -> ticker.history(request)));
            }
            int i = 0;
            for (String symbol : symbols) {
                Future<PriceHistory> future = futures.get(i++);
                try {
                    results.put(symbol, future.get());
                } catch (ExecutionException e) {
                    failures.put(symbol, asYFinanceException(symbol, e.getCause()));
                } catch (InterruptedException e) {
                    executor.shutdownNow();
                    Thread.currentThread().interrupt();
                    throw new YFTransportException("interrupted while downloading " + symbols, e);
                }
            }
        }
        if (!failures.isEmpty()) {
            throw new YFDownloadException(failures, results);
        }
        return Collections.unmodifiableMap(results);
    }

    /**
     * Searches Yahoo Finance for {@code query} (a symbol or company name) with yfinance's default
     * options; see {@link Search}.
     *
     * @throws IllegalArgumentException when {@code query} is blank
     * @throws IllegalStateException when the client is closed
     */
    public SearchResult search(String query) {
        return search(Search.of(query));
    }

    /**
     * Runs {@code search} (yfinance {@code Search}): quotes, news, lists, research reports and
     * navigation links. Categories Yahoo has nothing for are empty.
     *
     * @throws IllegalStateException when the client is closed
     */
    public SearchResult search(Search search) {
        ensureOpen();
        return new SearchEndpoint(session()).search(Objects.requireNonNull(search, "search"));
    }

    /**
     * Instruments of every type matching {@code query}, at most {@value Lookup#DEFAULT_COUNT}.
     *
     * @throws IllegalArgumentException when {@code query} is blank
     * @throws IllegalStateException when the client is closed
     */
    public List<LookupResult> lookup(String query) {
        return lookup(Lookup.of(query));
    }

    /**
     * Runs {@code lookup} (yfinance {@code Lookup.get_*}), in Yahoo's order; empty when nothing
     * matches.
     *
     * @throws IllegalStateException when the client is closed
     */
    public List<LookupResult> lookup(Lookup lookup) {
        ensureOpen();
        return new LookupEndpoint(session()).lookup(Objects.requireNonNull(lookup, "lookup"));
    }

    /**
     * Runs custom screen {@code query} with yfinance's defaults: the first {@value
     * Screener#DEFAULT_SIZE} results, sorted by ticker descending.
     *
     * @throws IllegalStateException when the client is closed
     */
    public ScreenerResult screen(ScreenerQuery query) {
        return screen(Screener.of(query));
    }

    /**
     * Runs Yahoo's predefined screen {@code name}, e.g. {@code day_gainers}, {@code most_actives};
     * see {@link Screener#predefinedNames()}.
     *
     * @throws IllegalArgumentException when {@code name} is blank
     * @throws IllegalStateException when the client is closed
     */
    public ScreenerResult screen(String name) {
        return screen(Screener.predefined(name));
    }

    /**
     * Runs {@code screener} (yfinance {@code screen}).
     *
     * @throws IllegalArgumentException when a predefined screen with an offset is not one of
     *     {@link Screener#predefinedNames()}
     * @throws IllegalStateException when the client is closed
     */
    public ScreenerResult screen(Screener screener) {
        ensureOpen();
        return new ScreenerEndpoint(session()).screen(Objects.requireNonNull(screener, "screener"));
    }

    /** Default region of {@link #sector(String)} and {@link #industry(String)}. */
    public static final String DEFAULT_REGION = "US";

    /**
     * Handle for sector {@code key} (e.g. {@code technology}), trimmed and lower-cased, with
     * listings for the {@value #DEFAULT_REGION} region. No request is made.
     *
     * @throws IllegalArgumentException when {@code key} is blank
     * @throws IllegalStateException when the client is closed
     */
    public Sector sector(String key) {
        return sector(key, DEFAULT_REGION);
    }

    /**
     * Handle for sector {@code key} with top companies, ETFs and funds scoped to {@code region}
     * (ISO 3166-1 alpha-2, e.g. {@code GB}). No request is made.
     *
     * @throws IllegalArgumentException when {@code key} or {@code region} is blank
     * @throws IllegalStateException when the client is closed
     */
    public Sector sector(String key, String region) {
        ensureOpen();
        return new Sector(this, normalizeKey(key), normalizeRegion(region));
    }

    /**
     * Handle for industry {@code key} (e.g. {@code semiconductors}), trimmed and lower-cased, with
     * listings for the {@value #DEFAULT_REGION} region. No request is made.
     *
     * @throws IllegalArgumentException when {@code key} is blank
     * @throws IllegalStateException when the client is closed
     */
    public Industry industry(String key) {
        return industry(key, DEFAULT_REGION);
    }

    /**
     * Handle for industry {@code key} with company listings scoped to {@code region} (ISO 3166-1
     * alpha-2). No request is made.
     *
     * @throws IllegalArgumentException when {@code key} or {@code region} is blank
     * @throws IllegalStateException when the client is closed
     */
    public Industry industry(String key, String region) {
        ensureOpen();
        return new Industry(this, normalizeKey(key), normalizeRegion(region));
    }

    /**
     * Handle for market {@code region}. No request is made.
     *
     * @throws IllegalStateException when the client is closed
     */
    public Market market(MarketRegion region) {
        ensureOpen();
        return new Market(this, Objects.requireNonNull(region, "region"));
    }

    /**
     * Handle for the market named {@code region}, e.g. {@code US}, {@code EUROPE}; see {@link
     * MarketRegion}. No request is made.
     *
     * @throws IllegalArgumentException when {@code region} is not a {@link MarketRegion}
     * @throws IllegalStateException when the client is closed
     */
    public Market market(String region) {
        return market(MarketRegion.of(region));
    }

    private static String normalizeKey(String key) {
        String k = Objects.requireNonNull(key, "key").trim();
        if (k.isEmpty()) {
            throw new IllegalArgumentException("key is blank");
        }
        return k.toLowerCase(Locale.ROOT);
    }

    private static String normalizeRegion(String region) {
        String r = Objects.requireNonNull(region, "region").trim();
        if (r.isEmpty()) {
            throw new IllegalArgumentException("region is blank");
        }
        return r.toUpperCase(Locale.ROOT);
    }

    private static YFinanceException asYFinanceException(String symbol, Throwable cause) {
        return switch (cause) {
            case YFinanceException e -> e;
            case Error e -> throw e;
            default -> new YFinanceException("download failed for " + symbol + ": " + cause, cause);
        };
    }

    static SequencedSet<String> normalizeSymbols(Collection<String> symbols) {
        Objects.requireNonNull(symbols, "symbols");
        SequencedSet<String> normalized = new LinkedHashSet<>();
        for (String symbol : symbols) {
            normalized.add(normalizeSymbol(symbol));
        }
        return Collections.unmodifiableSequencedSet(normalized);
    }

    /** Whether price repair is applied by default. */
    public boolean repair() {
        return repair;
    }

    /**
     * Closes the transport if this client created it; a transport passed to the builder stays
     * open and remains the caller's to close. Idempotent.
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true) && ownsTransport && transport instanceof AutoCloseable c) {
            try {
                c.close();
            } catch (Exception e) {
                throw new IllegalStateException("failed to close transport", e);
            }
        }
    }

    /** Whether {@link #close()} has been called. */
    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public String toString() {
        return "YFinance[closed=" + closed.get() + "]";
    }

    // ---- internal accessors used by Ticker and the endpoints ----

    /** The cookie/crumb session, created on first use. */
    YahooSession session() {
        ensureOpen();
        YahooSession s = session;
        if (s == null) {
            synchronized (sessionLock) {
                s = session;
                if (s == null) {
                    s = YahooSession.create(transport, cache, limiter, retry);
                    session = s;
                }
            }
        }
        return s;
    }

    /** The shared JSON mapper. */
    ObjectMapper mapper() {
        ensureOpen();
        return YahooJson.mapper();
    }

    Cache cache() {
        ensureOpen();
        return cache;
    }

    Transport transport() {
        return transport;
    }

    RequestLimiter limiter() {
        return limiter;
    }

    RetryPolicy retryPolicy() {
        return retry;
    }

    /** Connect timeout from the builder; also used by {@code yfin-live} for its WebSocket. */
    public Duration connectTimeout() {
        return connectTimeout;
    }

    Duration requestTimeout() {
        return requestTimeout;
    }

    /** User-Agent from the builder; also sent by {@code yfin-live} on its WebSocket handshake. */
    public String userAgent() {
        return userAgent;
    }

    /** Proxy selector from the builder, empty for direct connections. */
    public Optional<ProxySelector> proxy() {
        return Optional.ofNullable(proxy);
    }

    void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("YFinance client is closed");
        }
    }

    static String normalizeSymbol(String symbol) {
        String s = Objects.requireNonNull(symbol, "symbol").trim();
        if (s.isEmpty()) {
            throw new IllegalArgumentException("symbol is blank");
        }
        return s.toUpperCase(Locale.ROOT);
    }

    /**
     * Settings for {@link YFinance}. {@code connectTimeout}, {@code requestTimeout}, {@code proxy}
     * and {@code userAgent} configure the default JDK transport and are ignored when a custom
     * {@link #transport(Transport)} is set.
     */
    public static final class Builder {

        private Duration connectTimeout = DEFAULT_CONNECT_TIMEOUT;
        private Duration requestTimeout = DEFAULT_REQUEST_TIMEOUT;
        private @Nullable ProxySelector proxy;
        private String userAgent = JdkTransport.DEFAULT_USER_AGENT;
        private int maxRetries = RetryPolicy.DEFAULT_MAX_RETRIES;
        private int maxConcurrentRequests = RequestLimiter.DEFAULT_MAX_CONCURRENT_REQUESTS;
        private @Nullable Cache cache;
        private @Nullable Path cacheDir;
        private @Nullable Transport transport;
        private boolean repair;

        private Builder() {}

        /** TCP connect timeout; default 10s. */
        public Builder connectTimeout(Duration timeout) {
            this.connectTimeout = positive(timeout, "connectTimeout");
            return this;
        }

        /** Timeout for a single request; default 30s. */
        public Builder requestTimeout(Duration timeout) {
            this.requestTimeout = positive(timeout, "requestTimeout");
            return this;
        }

        /** Proxy selector, or {@code null} for direct connections (default). */
        public Builder proxy(@Nullable ProxySelector proxy) {
            this.proxy = proxy;
            return this;
        }

        /** User-Agent header; defaults to a current desktop Chrome. */
        public Builder userAgent(String userAgent) {
            if (Objects.requireNonNull(userAgent, "userAgent").isBlank()) {
                throw new IllegalArgumentException("userAgent is blank");
            }
            this.userAgent = userAgent;
            return this;
        }

        /** Retries on 429/5xx with exponential backoff; default 2, {@code 0} disables. */
        public Builder maxRetries(int maxRetries) {
            if (maxRetries < 0) {
                throw new IllegalArgumentException("maxRetries must be >= 0: " + maxRetries);
            }
            this.maxRetries = maxRetries;
            return this;
        }

        /** Upper bound on requests in flight at once; default 8. */
        public Builder maxConcurrentRequests(int maxConcurrentRequests) {
            if (maxConcurrentRequests < 1) {
                throw new IllegalArgumentException("maxConcurrentRequests must be >= 1: " + maxConcurrentRequests);
            }
            this.maxConcurrentRequests = maxConcurrentRequests;
            return this;
        }

        /**
         * Cache for timezones, cookies and ISIN lookups; {@link Cache#none()} disables caching.
         * Replaces any {@link #cacheDir(Path)} setting. Default: a file cache in {@code
         * $XDG_CACHE_HOME/yfin} or {@code ~/.cache/yfin}.
         */
        public Builder cache(Cache cache) {
            this.cache = Objects.requireNonNull(cache, "cache");
            this.cacheDir = null;
            return this;
        }

        /** File cache in {@code dir}. Replaces any {@link #cache(Cache)} setting. */
        public Builder cacheDir(Path dir) {
            this.cacheDir = Objects.requireNonNull(dir, "dir");
            this.cache = null;
            return this;
        }

        /** Custom transport. The client does not close it. */
        public Builder transport(Transport transport) {
            this.transport = Objects.requireNonNull(transport, "transport");
            return this;
        }

        /**
         * Whether price history requests repair bad prices by default (see {@link
         * HistoryRequest#repair()}); default {@code false}.
         */
        public Builder repair(boolean repair) {
            this.repair = repair;
            return this;
        }

        public YFinance build() {
            return new YFinance(this);
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
