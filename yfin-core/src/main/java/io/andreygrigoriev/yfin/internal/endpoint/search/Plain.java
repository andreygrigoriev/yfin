package io.andreygrigoriev.yfin.internal.endpoint.search;

import com.fasterxml.jackson.databind.JsonNode;
import io.andreygrigoriev.yfin.internal.http.YahooJson;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** JSON helpers shared by the search endpoints. */
final class Plain {

    private Plain() {}

    static @Nullable String text(JsonNode node) {
        return node.isTextual() && !node.asText().isEmpty() ? node.asText() : null;
    }

    static @Nullable Double number(JsonNode node) {
        return node.isNumber() ? node.doubleValue() : null;
    }

    static @Nullable Long integral(JsonNode node) {
        return node.isNumber() && node.canConvertToLong() ? node.longValue() : null;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(JsonNode node) {
        Object value = YahooJson.toPlain(node);
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    /** The objects of array {@code node}; empty when missing or null, non-objects skipped. */
    static List<Map<String, Object>> maps(JsonNode node) {
        var out = new ArrayList<Map<String, Object>>(node.size());
        for (JsonNode element : node) {
            if (element.isObject()) {
                out.add(map(element));
            }
        }
        return out;
    }
}
