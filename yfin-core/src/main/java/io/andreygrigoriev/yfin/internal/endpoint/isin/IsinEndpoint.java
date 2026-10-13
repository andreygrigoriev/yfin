package io.andreygrigoriev.yfin.internal.endpoint.isin;

import io.andreygrigoriev.yfin.exception.YFDataException;
import io.andreygrigoriev.yfin.internal.endpoint.Uris;
import io.andreygrigoriev.yfin.internal.http.YahooSession;
import io.andreygrigoriev.yfin.spi.Cache;
import io.andreygrigoriev.yfin.spi.HttpRequest;
import io.andreygrigoriev.yfin.spi.HttpResponse;
import java.net.URI;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ISIN of a symbol, ported from yfinance's experimental {@code get_isin}: the Business Insider
 * search suggestions for the symbol's short name, searched for {@code "SYMBOL|<isin>|…}. Results
 * are cached in the {@code isin} namespace: an ISIN for good, "none" ({@code -@<epoch second>})
 * for {@link #NONE_TTL} only, so one bad answer does not disable the lookup forever. The request
 * goes out without the Yahoo crumb.
 */
public final class IsinEndpoint {

    private static final Logger LOG = LoggerFactory.getLogger(IsinEndpoint.class);

    static final String CACHE_NAMESPACE = "isin";
    static final String NONE = "-";
    /** How long a "no ISIN" answer is cached. */
    static final Duration NONE_TTL = Duration.ofDays(1);

    static final String URL = "https://markets.businessinsider.com/ajax/SearchController_Suggest?max_results=25&query=";

    private final YahooSession session;
    private final Cache cache;
    private final Function<String, Optional<String>> shortNames;
    private final Clock clock;

    /**
     * @param shortNames Yahoo quote lookup of a symbol: empty when Yahoo has no quote, otherwise
     *     its short name, or the symbol itself when the quote has no short name
     */
    public IsinEndpoint(YahooSession session, Cache cache, Function<String, Optional<String>> shortNames) {
        this(session, cache, shortNames, Clock.systemUTC());
    }

    IsinEndpoint(YahooSession session, Cache cache, Function<String, Optional<String>> shortNames, Clock clock) {
        this.session = Objects.requireNonNull(session, "session");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.shortNames = Objects.requireNonNull(shortNames, "shortNames");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * The ISIN of {@code symbol}; empty for symbols with {@code -} or {@code ^} (currencies,
     * crypto, indices; no request), when the lookup finds none, or when Yahoo has no quote for the
     * symbol.
     */
    public Optional<String> isin(String symbol) {
        String ticker = Objects.requireNonNull(symbol, "symbol").toUpperCase(Locale.ROOT);
        if (ticker.contains("-") || ticker.contains("^")) {
            return Optional.empty();
        }
        Optional<String> cached = cache.get(CACHE_NAMESPACE, ticker);
        if (cached.isPresent()) {
            String value = cached.get();
            if (!value.startsWith(NONE)) {
                return Optional.of(value);
            }
            if (isFreshNone(value)) {
                return Optional.empty();
            }
        }
        Optional<String> query = shortNames.apply(ticker);
        if (query.isEmpty()) {
            LOG.debug("{}: no quote, no ISIN lookup", ticker);
            return Optional.empty();
        }
        URI uri = URI.create(URL + Uris.encode(query.get()));
        LOG.debug("{}: GET {}", ticker, uri);
        HttpResponse response = session.executeWithoutCrumb(HttpRequest.get(uri));
        if (!response.isSuccess()) {
            throw new YFDataException("ISIN lookup: HTTP " + response.status(), response.body(), null);
        }
        String isin = parse(response.bodyAsString(), ticker, query.get());
        if (NONE.equals(isin)) {
            cache.put(CACHE_NAMESPACE, ticker, noneEntry(clock.instant()));
            return Optional.empty();
        }
        cache.put(CACHE_NAMESPACE, ticker, isin);
        return Optional.of(isin);
    }

    /** Cache value of a "no ISIN" answer received at {@code at}. */
    static String noneEntry(Instant at) {
        return NONE + "@" + at.getEpochSecond();
    }

    /** Whether {@code value} is a "no ISIN" entry younger than {@link #NONE_TTL}. */
    private boolean isFreshNone(String value) {
        int at = value.indexOf('@');
        if (at < 0) {
            return false; // no timestamp: treat as expired
        }
        try {
            Instant stored = Instant.ofEpochSecond(Long.parseLong(value.substring(at + 1)));
            return stored.plus(NONE_TTL).isAfter(clock.instant());
        } catch (NumberFormatException | DateTimeException e) {
            return false;
        }
    }

    /**
     * yfinance's extraction: the text after {@code "TICKER|} up to the next {@code |} or quote.
     * Without that marker, the first {@code "|} entry when the response mentions {@code query}.
     *
     * @return the ISIN, or {@code -} when none is found
     */
    static String parse(String data, String ticker, String query) {
        String marker = "\"" + ticker + "|";
        if (!data.contains(marker)) {
            if (!data.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))) {
                return NONE;
            }
            marker = "\"|";
            if (!data.contains(marker)) {
                return NONE;
            }
        }
        String rest = data.substring(data.indexOf(marker) + marker.length());
        int quote = rest.indexOf('"');
        String keywords = quote < 0 ? rest : rest.substring(0, quote);
        int bar = keywords.indexOf('|');
        String isin = bar < 0 ? keywords : keywords.substring(0, bar);
        return isin.isEmpty() ? NONE : isin;
    }
}
