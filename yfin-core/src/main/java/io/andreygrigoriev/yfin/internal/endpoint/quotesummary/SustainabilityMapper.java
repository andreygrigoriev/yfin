package io.andreygrigoriev.yfin.internal.endpoint.quotesummary;

import static io.andreygrigoriev.yfin.internal.endpoint.quotesummary.ModuleNodes.dbl;
import static io.andreygrigoriev.yfin.internal.endpoint.quotesummary.ModuleNodes.lng;
import static io.andreygrigoriev.yfin.internal.endpoint.quotesummary.ModuleNodes.str;

import com.fasterxml.jackson.databind.JsonNode;
import io.andreygrigoriev.yfin.internal.http.YahooJson;
import io.andreygrigoriev.yfin.model.Sustainability;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Maps {@code esgScores} (yfinance {@code sustainability}). */
public final class SustainabilityMapper {

    private SustainabilityMapper() {}

    /** {@code esgScores}; empty when the module is missing. */
    @SuppressWarnings("unchecked")
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
        Object plain = YahooJson.toPlain(module);
        Map<String, Object> raw = plain instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
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
