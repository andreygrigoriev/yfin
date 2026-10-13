package io.andreygrigoriev.yfin.internal.endpoint.search;

import com.fasterxml.jackson.databind.JsonNode;
import org.jspecify.annotations.Nullable;

/** JSON helpers shared by the search endpoints. */
final class Plain {

    private Plain() {}

    static @Nullable Double number(JsonNode node) {
        return node.isNumber() ? node.doubleValue() : null;
    }

    static @Nullable Long integral(JsonNode node) {
        return node.isNumber() && node.canConvertToLong() ? node.longValue() : null;
    }
}
