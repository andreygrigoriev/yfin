package io.andreygrigoriev.yfin.internal.endpoint.domain;

import static io.andreygrigoriev.yfin.internal.http.YahooJson.plainMap;
import static io.andreygrigoriev.yfin.internal.http.YahooJson.text;

import com.fasterxml.jackson.databind.JsonNode;
import io.andreygrigoriev.yfin.exception.YFDataException;
import io.andreygrigoriev.yfin.exception.YFSymbolNotFoundException;
import io.andreygrigoriev.yfin.internal.endpoint.Uris;
import io.andreygrigoriev.yfin.internal.http.YahooJson;
import io.andreygrigoriev.yfin.internal.http.YahooSession;
import io.andreygrigoriev.yfin.model.DomainOverview;
import io.andreygrigoriev.yfin.model.TopCompany;
import io.andreygrigoriev.yfin.spi.HttpRequest;
import io.andreygrigoriev.yfin.spi.HttpResponse;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Fetching and parsing shared by the sector and industry endpoints (yfinance {@code Domain}). */
final class DomainJson {

    private static final Logger LOG = LoggerFactory.getLogger(DomainJson.class);

    static final String BASE_URL = "https://query1.finance.yahoo.com/v1/finance/";

    private DomainJson() {}

    /** yfinance's {@code Domain._fetch} parameters. */
    static Map<String, String> params(String region) {
        var params = new LinkedHashMap<String, String>();
        params.put("formatted", "true");
        params.put("withReturns", "true");
        params.put("lang", "en-US");
        params.put("region", region);
        return params;
    }

    /**
     * {@code GET …/v1/finance/<kind>/<key>} and returns its {@code data} object.
     *
     * @throws YFSymbolNotFoundException when Yahoo does not know {@code key} (it answers 404 with
     *     an HTML page)
     * @throws YFDataException when Yahoo is down, reports an error or the payload has no {@code
     *     data} object
     */
    static JsonNode fetch(YahooSession session, String kind, String key, String region) {
        URI uri = Uris.uri(BASE_URL + kind + "/" + Uris.encode(key), params(region));
        LOG.debug("{} {}: GET {}", kind, key, uri);
        HttpResponse response = session.execute(HttpRequest.get(uri));
        YahooJson.checkNotDown(response);
        if (response.status() == 404) {
            throw new YFSymbolNotFoundException(key, "unknown " + singular(kind) + " key: " + key);
        }
        return data(YahooJson.read(response, key), kind, key);
    }

    static JsonNode data(JsonNode root, String kind, String key) {
        JsonNode data = root.path("data");
        if (!data.isObject()) {
            throw new YFDataException(singular(kind) + " " + key + ": response has no data object", root.toString());
        }
        return data;
    }

    private static String singular(String kind) {
        return kind.equals("industries") ? "industry" : "sector";
    }

    static DomainOverview overview(JsonNode overview) {
        return new DomainOverview(
                integral(overview.path("companiesCount")),
                number(overview.path("marketCap")),
                text(overview.path("messageBoardId")),
                text(overview.path("description")),
                integral(overview.path("industriesCount")),
                number(overview.path("marketWeight")),
                integral(overview.path("employeeCount")));
    }

    static List<TopCompany> topCompanies(JsonNode companies) {
        var out = new ArrayList<TopCompany>(companies.size());
        for (JsonNode c : companies) {
            String symbol = text(c.path("symbol"));
            if (symbol == null) {
                continue;
            }
            out.add(new TopCompany(
                    symbol, text(c.path("name")), text(c.path("rating")), number(c.path("marketWeight")), plainMap(c)));
        }
        return out;
    }

    /** A plain number, or the {@code raw} member of Yahoo's {@code {"raw":…,"fmt":…}} form. */
    static @Nullable Double number(JsonNode node) {
        JsonNode n = node.isObject() ? node.path("raw") : node;
        return n.isNumber() ? n.doubleValue() : null;
    }

    static @Nullable Long integral(JsonNode node) {
        JsonNode n = node.isObject() ? node.path("raw") : node;
        return n.isNumber() && n.canConvertToLong() ? n.longValue() : null;
    }
}
