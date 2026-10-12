package io.andreygrigoriev.yfin.internal.endpoint.quotesummary;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Typed reads from unwrapped {@code quoteSummary} module nodes, shared by the mappers. A missing
 * field or one of the wrong JSON type reads as {@code null}, never an exception.
 */
final class ModuleNodes {

    private ModuleNodes() {}

    /** Maps every object element of the array {@code field} of {@code node}; empty when absent. */
    static <T> List<T> list(@Nullable JsonNode node, String field, Function<JsonNode, T> mapper) {
        if (node == null) {
            return List.of();
        }
        JsonNode items = node.path(field);
        if (!items.isArray()) {
            return List.of();
        }
        var out = new ArrayList<T>(items.size());
        for (JsonNode item : items) {
            if (item.isObject()) {
                out.add(mapper.apply(item));
            }
        }
        return List.copyOf(out);
    }

    static @Nullable String str(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isTextual() ? v.textValue() : null;
    }

    static @Nullable Double dbl(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isNumber() ? v.doubleValue() : null;
    }

    static @Nullable Long lng(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isNumber() && v.canConvertToLong() ? v.longValue() : null;
    }

    static @Nullable Boolean bool(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isBoolean() ? v.booleanValue() : null;
    }

    static @Nullable Instant instant(JsonNode node, String field) {
        Long seconds = lng(node, field);
        return seconds == null ? null : Instant.ofEpochSecond(seconds);
    }

    /** Epoch seconds as a UTC date, like yfinance's {@code pd.to_datetime(unit="s")}. */
    static @Nullable LocalDate date(JsonNode node, String field) {
        return toDate(node.path(field));
    }

    /** An epoch-seconds number as a UTC date, or {@code null} for any other node. */
    static @Nullable LocalDate toDate(JsonNode v) {
        return v.isNumber() && v.canConvertToLong()
                ? LocalDate.ofInstant(Instant.ofEpochSecond(v.longValue()), ZoneOffset.UTC)
                : null;
    }

    /** An ISO {@code yyyy-MM-dd} string field as a date, or {@code null}. */
    static @Nullable LocalDate isoDate(JsonNode node, String field) {
        String s = str(node, field);
        if (s == null) {
            return null;
        }
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
