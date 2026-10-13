package io.andreygrigoriev.yfin.internal.endpoint.screener;

import static io.andreygrigoriev.yfin.internal.http.YahooJson.plainMap;
import static io.andreygrigoriev.yfin.internal.http.YahooJson.text;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import io.andreygrigoriev.yfin.Screener;
import io.andreygrigoriev.yfin.exception.YFDataException;
import io.andreygrigoriev.yfin.internal.endpoint.Uris;
import io.andreygrigoriev.yfin.internal.http.YahooJson;
import io.andreygrigoriev.yfin.internal.http.YahooSession;
import io.andreygrigoriev.yfin.model.ScreenerResult;
import io.andreygrigoriev.yfin.request.ScreenerQuery;
import io.andreygrigoriev.yfin.request.SortField;
import io.andreygrigoriev.yfin.spi.HttpRequest;
import io.andreygrigoriev.yfin.spi.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Yahoo's screener with yfinance 1.7.0's parameters: custom queries are {@code POST
 * https://query1.finance.yahoo.com/v1/finance/screener} with a JSON body, predefined screens
 * {@code GET …/v1/finance/screener/predefined/saved?scrIds=<name>}. Results are {@code
 * finance.result[0]}.
 */
public final class ScreenerEndpoint {

    private static final Logger LOG = LoggerFactory.getLogger(ScreenerEndpoint.class);

    static final String URL = "https://query1.finance.yahoo.com/v1/finance/screener";
    static final String PREDEFINED_URL = URL + "/predefined/saved";

    private final YahooSession session;

    public ScreenerEndpoint(YahooSession session) {
        this.session = Objects.requireNonNull(session, "session");
    }

    /**
     * Runs {@code screener}.
     *
     * @throws IllegalArgumentException when a predefined screen with an offset is not one of
     *     {@link Screener#predefinedNames()}
     * @throws YFDataException when Yahoo Finance is down, reports an error or the payload is
     *     unexpected
     */
    public ScreenerResult screen(Screener screener) {
        Objects.requireNonNull(screener, "screener");
        String name = screener.predefinedName();
        HttpRequest request;
        if (name == null) {
            request = custom(
                    Objects.requireNonNull(screener.query()),
                    screener.offset(),
                    screener.size(),
                    screener.sort() != null ? screener.sort() : SortField.DEFAULT);
        } else if (screener.offset() != null) {
            // the predefined endpoint ignores offsets: run the equivalent custom query (as yfinance)
            Screener equivalent = Screener.predefinedQuery(name);
            request = custom(
                    Objects.requireNonNull(equivalent.query()),
                    screener.offset(),
                    screener.size(),
                    screener.sort() != null ? screener.sort() : Objects.requireNonNull(equivalent.sort()));
        } else {
            request = predefined(name, screener.size(), screener.sort());
        }
        LOG.debug("{}: {} {}", name != null ? name : "custom screen", request.method(), request.uri());
        HttpResponse response = session.execute(request);
        YahooJson.checkNotDown(response);
        return parse(YahooJson.read(response, null));
    }

    private static Map<String, String> baseParams() {
        var params = new LinkedHashMap<String, String>();
        params.put("corsDomain", "finance.yahoo.com");
        params.put("formatted", "false");
        params.put("lang", "en-US");
        params.put("region", "US");
        return params;
    }

    private static HttpRequest predefined(String name, @Nullable Integer size, @Nullable SortField sort) {
        Map<String, String> params = baseParams();
        params.put("scrIds", name);
        if (size != null) {
            params.put("count", Integer.toString(size));
        }
        if (sort != null) {
            params.put("sortField", sort.field());
            params.put("sortType", sort.sortType());
        }
        return HttpRequest.get(Uris.uri(PREDEFINED_URL, params));
    }

    static HttpRequest custom(ScreenerQuery query, @Nullable Integer offset, @Nullable Integer size, SortField sort) {
        return HttpRequest.postJson(Uris.uri(URL, baseParams()), body(query, offset, size, sort));
    }

    /** The JSON request body of a custom screen, members in yfinance's order. */
    static String body(ScreenerQuery query, @Nullable Integer offset, @Nullable Integer size, SortField sort) {
        int n = size != null ? size : Screener.DEFAULT_SIZE;
        var body = new LinkedHashMap<String, Object>();
        body.put("offset", offset != null ? offset : 0);
        body.put("count", n);
        body.put("size", n);
        body.put("sortField", sort.field());
        body.put("userId", "");
        body.put("userIdType", "guid");
        body.put("sortType", sort.sortType());
        body.put("quoteType", query.quoteType());
        body.put("query", query.toMap());
        try {
            return YahooJson.mapper().writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize screener query", e);
        }
    }

    static ScreenerResult parse(JsonNode root) {
        JsonNode results = root.path("finance").path("result");
        if (!results.isArray() || results.isEmpty() || !results.get(0).isObject()) {
            throw new YFDataException("screener result is missing", root.toString());
        }
        JsonNode r = results.get(0);
        var quotes = new ArrayList<Map<String, Object>>();
        for (JsonNode q : r.path("quotes")) {
            if (q.isObject()) {
                quotes.add(plainMap(q));
            }
        }
        return new ScreenerResult(
                text(r.path("id")),
                text(r.path("title")),
                text(r.path("description")),
                r.path("start").asInt(0),
                r.path("count").isNumber() ? r.path("count").asInt() : quotes.size(),
                r.path("total").isNumber() ? r.path("total").asLong() : null,
                List.copyOf(quotes),
                plainMap(r));
    }
}
