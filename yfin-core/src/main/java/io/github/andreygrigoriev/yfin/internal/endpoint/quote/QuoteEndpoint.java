package io.github.andreygrigoriev.yfin.internal.endpoint.quote;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.andreygrigoriev.yfin.exception.YFDataException;
import io.github.andreygrigoriev.yfin.internal.endpoint.Uris;
import io.github.andreygrigoriev.yfin.internal.http.YahooJson;
import io.github.andreygrigoriev.yfin.internal.http.YahooSession;
import io.github.andreygrigoriev.yfin.spi.HttpRequest;
import java.net.URI;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Yahoo {@code /v7/finance/quote}: flat real-time quote fields (prices, volumes, shares outstanding,
 * market cap, exchange) for one or more symbols. Unknown symbols are simply absent from the
 * response.
 */
public final class QuoteEndpoint {

    private static final Logger LOG = LoggerFactory.getLogger(QuoteEndpoint.class);

    static final String URL = "https://query1.finance.yahoo.com/v7/finance/quote";

    private final YahooSession session;

    public QuoteEndpoint(YahooSession session) {
        this.session = Objects.requireNonNull(session, "session");
    }

    /** The quote of {@code symbol}, or empty when Yahoo returns none. */
    public Optional<JsonNode> quote(String symbol) {
        return Optional.ofNullable(quotes(List.of(symbol)).get(symbol));
    }

    /**
     * Quotes of {@code symbols}, keyed by the symbol Yahoo reports, in response order.
     *
     * @throws IllegalArgumentException when {@code symbols} is empty
     * @throws YFDataException for an error envelope or an unexpected payload
     */
    public Map<String, JsonNode> quotes(Collection<String> symbols) {
        if (symbols.isEmpty()) {
            throw new IllegalArgumentException("no symbols requested");
        }
        URI uri = Uris.uri(URL, params(symbols));
        LOG.debug("GET {}", uri);
        String single = symbols.size() == 1 ? symbols.iterator().next() : null;
        JsonNode root = YahooJson.read(session.execute(HttpRequest.get(uri)), single);
        return parse(root);
    }

    static Map<String, String> params(Collection<String> symbols) {
        var params = new LinkedHashMap<String, String>();
        params.put("symbols", String.join(",", symbols));
        params.put("formatted", "false");
        params.put("lang", "en-US");
        params.put("region", "US");
        return params;
    }

    static Map<String, JsonNode> parse(JsonNode root) {
        JsonNode envelope = root.path("quoteResponse");
        if (!envelope.isObject()) {
            throw new YFDataException("quote response has no quoteResponse object", root.toString());
        }
        JsonNode results = envelope.path("result");
        var quotes = new LinkedHashMap<String, JsonNode>();
        if (results.isNull() || results.isMissingNode()) {
            return Collections.unmodifiableMap(quotes);
        }
        if (!results.isArray()) {
            throw new YFDataException("quote result is not a list", root.toString());
        }
        for (JsonNode quote : results) {
            String symbol = quote.path("symbol").asText("");
            if (quote.isObject() && !symbol.isEmpty()) {
                quotes.put(symbol, quote);
            }
        }
        return Collections.unmodifiableMap(quotes);
    }
}
