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

    private static boolean isNotFound(String code) {
        return code.toLowerCase(Locale.ROOT).replace(" ", "").equals("notfound");
    }
}
