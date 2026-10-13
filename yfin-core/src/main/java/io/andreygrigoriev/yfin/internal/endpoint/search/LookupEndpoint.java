package io.andreygrigoriev.yfin.internal.endpoint.search;

import static io.andreygrigoriev.yfin.internal.http.YahooJson.plainMap;
import static io.andreygrigoriev.yfin.internal.http.YahooJson.text;

import com.fasterxml.jackson.databind.JsonNode;
import io.andreygrigoriev.yfin.Lookup;
import io.andreygrigoriev.yfin.exception.YFDataException;
import io.andreygrigoriev.yfin.internal.endpoint.Uris;
import io.andreygrigoriev.yfin.internal.http.YahooJson;
import io.andreygrigoriev.yfin.internal.http.YahooSession;
import io.andreygrigoriev.yfin.model.LookupResult;
import io.andreygrigoriev.yfin.spi.HttpRequest;
import io.andreygrigoriev.yfin.spi.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code GET https://query1.finance.yahoo.com/v1/finance/lookup} with yfinance 1.7.0's
 * parameters; results are {@code finance.result[0].documents}.
 */
public final class LookupEndpoint {

    private static final Logger LOG = LoggerFactory.getLogger(LookupEndpoint.class);

    static final String URL = "https://query1.finance.yahoo.com/v1/finance/lookup";

    private final YahooSession session;

    public LookupEndpoint(YahooSession session) {
        this.session = Objects.requireNonNull(session, "session");
    }

    /**
     * Runs {@code lookup}.
     *
     * @throws YFDataException when Yahoo Finance is down, reports an error or the payload is
     *     unexpected
     */
    public List<LookupResult> lookup(Lookup lookup) {
        Objects.requireNonNull(lookup, "lookup");
        var params = new LinkedHashMap<String, String>();
        params.put("query", lookup.query());
        params.put("type", lookup.type().yahooType());
        params.put("start", "0");
        params.put("count", Integer.toString(lookup.count()));
        params.put("formatted", "false");
        params.put("fetchPricingData", "true");
        params.put("lang", "en-US");
        params.put("region", "US");
        HttpRequest request = HttpRequest.get(Uris.uri(URL, params));
        LOG.debug("{}: GET {}", lookup.query(), request.uri());
        HttpResponse response = session.execute(request);
        YahooJson.checkNotDown(response);
        return parse(YahooJson.read(response, null));
    }

    static List<LookupResult> parse(JsonNode root) {
        JsonNode result = root.path("finance").path("result");
        if (result.isMissingNode() || result.isNull() || result.isEmpty()) {
            return List.of();
        }
        if (!result.isArray()) {
            throw new YFDataException("lookup result is not a list", root.toString());
        }
        JsonNode documents = result.get(0).path("documents");
        var out = new ArrayList<LookupResult>(documents.size());
        for (JsonNode d : documents) {
            String symbol = text(d.path("symbol"));
            if (symbol == null) {
                continue;
            }
            out.add(new LookupResult(
                    symbol,
                    text(d.path("shortName")),
                    text(d.path("quoteType")),
                    text(d.path("exchange")),
                    YahooJson.number(d.path("regularMarketPrice")),
                    YahooJson.number(d.path("regularMarketChange")),
                    YahooJson.number(d.path("regularMarketPercentChange")),
                    YahooJson.integral(d.path("rank")),
                    plainMap(d)));
        }
        return List.copyOf(out);
    }
}
