package io.andreygrigoriev.yfin.internal.endpoint.domain;

import com.fasterxml.jackson.databind.JsonNode;
import io.andreygrigoriev.yfin.exception.YFDataException;
import io.andreygrigoriev.yfin.exception.YFSymbolNotFoundException;
import io.andreygrigoriev.yfin.internal.http.YahooSession;
import io.andreygrigoriev.yfin.model.SectorData;
import io.andreygrigoriev.yfin.model.SectorIndustry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** {@code GET https://query1.finance.yahoo.com/v1/finance/sectors/<key>} as yfinance 1.7.0. */
public final class SectorEndpoint {

    private static final String ALL_INDUSTRIES = "All Industries";

    private final YahooSession session;

    public SectorEndpoint(YahooSession session) {
        this.session = Objects.requireNonNull(session, "session");
    }

    /**
     * The sector {@code key}, scoped to {@code region} (an ISO 3166-1 alpha-2 code).
     *
     * @throws YFSymbolNotFoundException when Yahoo does not know {@code key}
     * @throws YFDataException when Yahoo is down, reports an error or the payload is unexpected
     */
    public SectorData sector(String key, String region) {
        return parse(key, DomainJson.fetch(session, "sectors", key, region));
    }

    static SectorData parse(String key, JsonNode data) {
        String k = DomainJson.text(data.path("key"));
        return new SectorData(
                k != null ? k : key,
                DomainJson.text(data.path("name")),
                DomainJson.text(data.path("symbol")),
                DomainJson.overview(data.path("overview")),
                DomainJson.topCompanies(data.path("topCompanies")),
                symbolNames(data.path("topETFs")),
                symbolNames(data.path("topMutualFunds")),
                industries(data.path("industries")),
                DomainJson.maps(data.path("researchReports")),
                DomainJson.map(data));
    }

    private static Map<String, String> symbolNames(JsonNode array) {
        var out = new LinkedHashMap<String, String>();
        for (JsonNode e : array) {
            String symbol = DomainJson.text(e.path("symbol"));
            if (symbol != null) {
                String name = DomainJson.text(e.path("name"));
                out.put(symbol, name != null ? name : symbol);
            }
        }
        return out;
    }

    private static List<SectorIndustry> industries(JsonNode array) {
        var out = new ArrayList<SectorIndustry>(array.size());
        for (JsonNode i : array) {
            String key = DomainJson.text(i.path("key"));
            String name = DomainJson.text(i.path("name"));
            if (key == null || ALL_INDUSTRIES.equals(name)) {
                continue;
            }
            out.add(new SectorIndustry(
                    key, name, DomainJson.text(i.path("symbol")), DomainJson.number(i.path("marketWeight"))));
        }
        return out;
    }
}
