package io.andreygrigoriev.yfin.internal.endpoint.quotesummary;

import static io.andreygrigoriev.yfin.internal.endpoint.quotesummary.ModuleNodes.instant;
import static io.andreygrigoriev.yfin.internal.endpoint.quotesummary.ModuleNodes.isoDate;
import static io.andreygrigoriev.yfin.internal.endpoint.quotesummary.ModuleNodes.str;

import com.fasterxml.jackson.databind.JsonNode;
import io.andreygrigoriev.yfin.model.SecFiling;
import java.util.LinkedHashMap;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Maps {@code secFilings} the way yfinance's {@code sec_filings} does: the ISO {@code date} becomes
 * a date and the exhibit list a map from exhibit type to URL.
 */
public final class SecFilingsMapper {

    private SecFilingsMapper() {}

    /** The filings in Yahoo's order (newest first); empty when the module is missing. */
    public static List<SecFiling> filings(@Nullable JsonNode module) {
        return ModuleNodes.list(module, "filings", SecFilingsMapper::filing);
    }

    private static SecFiling filing(JsonNode node) {
        var exhibits = new LinkedHashMap<String, String>();
        for (JsonNode e : node.path("exhibits")) {
            String type = str(e, "type");
            String url = str(e, "url");
            if (type != null && url != null) {
                exhibits.put(type, url);
            }
        }
        return new SecFiling(
                isoDate(node, "date"),
                instant(node, "epochDate"),
                str(node, "type"),
                str(node, "title"),
                str(node, "edgarUrl"),
                exhibits);
    }
}
