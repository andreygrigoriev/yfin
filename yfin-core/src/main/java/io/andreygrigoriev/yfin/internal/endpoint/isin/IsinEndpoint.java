package io.andreygrigoriev.yfin.internal.endpoint.isin;

import io.andreygrigoriev.yfin.exception.YFDataException;
import io.andreygrigoriev.yfin.internal.endpoint.Uris;
import io.andreygrigoriev.yfin.internal.http.YahooSession;
import io.andreygrigoriev.yfin.spi.Cache;
import io.andreygrigoriev.yfin.spi.HttpRequest;
import io.andreygrigoriev.yfin.spi.HttpResponse;
import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ISIN of a symbol, ported from yfinance's experimental {@code get_isin}: the Business Insider
 * search suggestions for the symbol's short name, searched for {@code "SYMBOL|<isin>|…}. Results,
 * including "none" ({@code -}), are cached in the {@code isin} namespace. The request goes out
 * without the Yahoo crumb.
 */
public final class IsinEndpoint {

    private static final Logger LOG = LoggerFactory.getLogger(IsinEndpoint.class);

    static final String CACHE_NAMESPACE = "isin";
    static final String NONE = "-";
    static final String URL = "https://markets.businessinsider.com/ajax/SearchController_Suggest?max_results=25&query=";

    private final YahooSession session;
    private final Cache cache;
    private final Function<String, Optional<String>> shortNames;

    /**
     * @param shortNames Yahoo quote lookup of a symbol: empty when Yahoo has no quote, otherwise
     *     its short name, or the symbol itself when the quote has no short name
     */
    public IsinEndpoint(YahooSession session, Cache cache, Function<String, Optional<String>> shortNames) {
        this.session = Objects.requireNonNull(session, "session");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.shortNames = Objects.requireNonNull(shortNames, "shortNames");
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
            return found(cached.get());
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
        cache.put(CACHE_NAMESPACE, ticker, isin);
        return found(isin);
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

    private static Optional<String> found(String value) {
        return NONE.equals(value) ? Optional.empty() : Optional.of(value);
    }
}
