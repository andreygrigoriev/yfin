package io.andreygrigoriev.yfin.internal.endpoint.domain;

import static io.andreygrigoriev.yfin.internal.http.YahooJson.plainMap;
import static io.andreygrigoriev.yfin.internal.http.YahooJson.plainMaps;
import static io.andreygrigoriev.yfin.internal.http.YahooJson.text;

import com.fasterxml.jackson.databind.JsonNode;
import io.andreygrigoriev.yfin.exception.YFDataException;
import io.andreygrigoriev.yfin.exception.YFSymbolNotFoundException;
import io.andreygrigoriev.yfin.internal.http.YahooSession;
import io.andreygrigoriev.yfin.model.GrowthCompany;
import io.andreygrigoriev.yfin.model.IndustryData;
import io.andreygrigoriev.yfin.model.PerformingCompany;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** {@code GET https://query1.finance.yahoo.com/v1/finance/industries/<key>} as yfinance 1.7.0. */
public final class IndustryEndpoint {

    private final YahooSession session;

    public IndustryEndpoint(YahooSession session) {
        this.session = Objects.requireNonNull(session, "session");
    }

    /**
     * The industry {@code key}, scoped to {@code region} (an ISO 3166-1 alpha-2 code).
     *
     * @throws YFSymbolNotFoundException when Yahoo does not know {@code key}
     * @throws YFDataException when Yahoo is down, reports an error or the payload is unexpected
     */
    public IndustryData industry(String key, String region) {
        return parse(key, DomainJson.fetch(session, "industries", key, region));
    }

    static IndustryData parse(String key, JsonNode data) {
        String k = text(data.path("key"));
        return new IndustryData(
                k != null ? k : key,
                text(data.path("name")),
                text(data.path("symbol")),
                text(data.path("sectorKey")),
                text(data.path("sectorName")),
                DomainJson.overview(data.path("overview")),
                DomainJson.topCompanies(data.path("topCompanies")),
                performing(data.path("topPerformingCompanies")),
                growth(data.path("topGrowthCompanies")),
                plainMaps(data.path("researchReports")),
                plainMap(data));
    }

    private static List<PerformingCompany> performing(JsonNode array) {
        var out = new ArrayList<PerformingCompany>(array.size());
        for (JsonNode c : array) {
            String symbol = text(c.path("symbol"));
            if (symbol != null) {
                out.add(new PerformingCompany(
                        symbol,
                        text(c.path("name")),
                        DomainJson.number(c.path("ytdReturn")),
                        DomainJson.number(c.path("lastPrice")),
                        DomainJson.number(c.path("targetPrice"))));
            }
        }
        return out;
    }

    private static List<GrowthCompany> growth(JsonNode array) {
        var out = new ArrayList<GrowthCompany>(array.size());
        for (JsonNode c : array) {
            String symbol = text(c.path("symbol"));
            if (symbol != null) {
                out.add(new GrowthCompany(
                        symbol,
                        text(c.path("name")),
                        DomainJson.number(c.path("ytdReturn")),
                        DomainJson.number(c.path("growthEstimate"))));
            }
        }
        return out;
    }
}
