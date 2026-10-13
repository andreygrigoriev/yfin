package io.andreygrigoriev.yfin.internal.http;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.andreygrigoriev.yfin.exception.YFDataException;
import io.andreygrigoriev.yfin.exception.YFSymbolNotFoundException;
import io.andreygrigoriev.yfin.spi.HttpResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Shared JSON handling for Yahoo responses: one configured {@link ObjectMapper} and envelope error
 * detection.
 *
 * <p>Yahoo reports failures inside the payload as {@code {"<envelope>":{"result":null,"error":
 * {"code":…,"description":…}}}}, with envelopes such as {@code finance}, {@code chart} and {@code
 * quoteSummary}. Parsing uses the tree model, so no reflection metadata is needed for native
 * images.
 */
public final class YahooJson {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private static final String DOWN_MARKER = "Will be right back";

    private YahooJson() {}

    /** The shared mapper. Thread-safe; do not reconfigure. */
    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /**
     * Parses {@code response} and checks for a Yahoo error envelope.
     *
     * @param symbol the symbol the request was about, or {@code null} when not symbol-specific
     * @throws YFSymbolNotFoundException when the envelope reports {@code Not Found} and {@code
     *     symbol} is known
     * @throws YFDataException for any other error envelope, a non-2xx status without one, or a body
     *     that is not valid JSON
     */
    public static JsonNode read(HttpResponse response, @Nullable String symbol) {
        byte[] body = response.body();
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (JacksonException e) {
            throw new YFDataException("invalid JSON (HTTP " + response.status() + ")", body, e);
        } catch (IOException e) {
            throw new YFDataException("cannot read JSON (HTTP " + response.status() + ")", body, e);
        }
        if (root == null || root.isMissingNode()) {
            throw new YFDataException("empty body (HTTP " + response.status() + ")", body, null);
        }
        checkErrorEnvelope(root, symbol, body);
        if (!response.isSuccess()) {
            throw new YFDataException("HTTP " + response.status(), body, null);
        }
        return root;
    }

    /**
     * Throws when {@code response} is Yahoo's "Will be right back" outage page.
     *
     * @throws YFDataException when Yahoo Finance is down
     */
    public static void checkNotDown(HttpResponse response) {
        byte[] body = response.body();
        String head = new String(body, 0, Math.min(body.length, 4096), StandardCharsets.UTF_8);
        if (head.contains(DOWN_MARKER)) {
            throw new YFDataException("Yahoo Finance is currently down", body, null);
        }
    }

    private static void checkErrorEnvelope(JsonNode root, @Nullable String symbol, byte[] body) {
        if (!root.isObject()) {
            return;
        }
        for (Map.Entry<String, JsonNode> envelope : root.properties()) {
            JsonNode error = envelope.getValue().path("error");
            if (!error.isObject()) {
                continue;
            }
            String code = error.path("code").asText("");
            String description = error.path("description").asText("");
            String message = "Yahoo " + envelope.getKey() + " error"
                    + (code.isEmpty() ? "" : " " + code)
                    + (description.isEmpty() ? "" : ": " + description);
            if (symbol != null && isNotFound(code)) {
                throw new YFSymbolNotFoundException(symbol, message + " (" + symbol + ")");
            }
            throw new YFDataException(message, body, null);
        }
    }

    /**
     * Converts a JSON tree to plain, unmodifiable Java values: objects to insertion-ordered {@code
     * Map<String, Object>} (null members dropped), arrays to {@code List<Object>} (null elements
     * dropped), integral numbers to {@code Long} (or {@code BigInteger} when too large), other
     * numbers to {@code Double}, plus {@code String} and {@code Boolean}.
     *
     * @return the value, or {@code null} for JSON {@code null} and missing nodes
     */
    public static @Nullable Object toPlain(JsonNode node) {
        if (node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isObject()) {
            var map = new LinkedHashMap<String, Object>();
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                Object value = toPlain(e.getValue());
                if (value != null) {
                    map.put(e.getKey(), value);
                }
            }
            return Collections.unmodifiableMap(map);
        }
        if (node.isArray()) {
            var list = new ArrayList<Object>(node.size());
            for (JsonNode element : node) {
                Object value = toPlain(element);
                if (value != null) {
                    list.add(value);
                }
            }
            return Collections.unmodifiableList(list);
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isIntegralNumber()) {
            return node.canConvertToLong() ? (Object) node.longValue() : node.bigIntegerValue();
        }
        if (node.isNumber()) {
            return node.doubleValue();
        }
        return node.asText();
    }

    private static boolean isNotFound(String code) {
        return code.toLowerCase(Locale.ROOT).replace(" ", "").equals("notfound");
    }

    /** The node's text, or {@code null} when it is not a string or is empty. */
    public static @Nullable String text(JsonNode node) {
        return node.isTextual() && !node.asText().isEmpty() ? node.asText() : null;
    }

    /** {@link #toPlain} of an object node; an empty map for anything else. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> plainMap(JsonNode node) {
        Object value = toPlain(node);
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    /** {@link #plainMap} of each object in array {@code node}; non-objects skipped. */
    public static List<Map<String, Object>> plainMaps(JsonNode node) {
        var out = new ArrayList<Map<String, Object>>(node.size());
        for (JsonNode element : node) {
            if (element.isObject()) {
                out.add(plainMap(element));
            }
        }
        return out;
    }
}
