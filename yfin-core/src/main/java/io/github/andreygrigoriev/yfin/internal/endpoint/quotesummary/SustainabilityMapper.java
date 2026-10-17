package io.github.andreygrigoriev.yfin.internal.endpoint.quotesummary;

import static io.github.andreygrigoriev.yfin.internal.endpoint.quotesummary.ModuleNodes.dbl;
import static io.github.andreygrigoriev.yfin.internal.endpoint.quotesummary.ModuleNodes.lng;
import static io.github.andreygrigoriev.yfin.internal.endpoint.quotesummary.ModuleNodes.str;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.andreygrigoriev.yfin.internal.http.YahooJson;
import io.github.andreygrigoriev.yfin.model.Sustainability;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Maps {@code esgScores} (yfinance {@code sustainability}). */
public final class SustainabilityMapper {

    private SustainabilityMapper() {}

    /** {@code esgScores}; empty when the module is missing. */
    public static Optional<Sustainability> sustainability(@Nullable JsonNode module) {
        if (module == null || !module.isObject()) {
            return Optional.empty();
        }
        var controversies = new ArrayList<String>();
        for (JsonNode c : module.path("relatedControversy")) {
            if (c.isTextual()) {
                controversies.add(c.textValue());
            }
        }
        Map<String, Object> raw = YahooJson.plainMap(module);
        return Optional.of(new Sustainability(
                dbl(module, "totalEsg"),
                dbl(module, "environmentScore"),
                dbl(module, "socialScore"),
                dbl(module, "governanceScore"),
                dbl(module, "percentile"),
                dbl(module, "highestControversy"),
                str(module, "esgPerformance"),
                str(module, "peerGroup"),
                lng(module, "peerCount"),
                lng(module, "ratingYear"),
                lng(module, "ratingMonth"),
                List.copyOf(controversies),
                raw));
    }
}
