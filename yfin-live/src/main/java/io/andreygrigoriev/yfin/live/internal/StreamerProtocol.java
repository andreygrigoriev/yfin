package io.andreygrigoriev.yfin.live.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.andreygrigoriev.yfin.exception.YFDataException;
import io.andreygrigoriev.yfin.live.PriceTick;
import java.util.Collection;
import org.jspecify.annotations.Nullable;

/**
 * JSON envelope of the Yahoo streamer: outbound {@code {"subscribe":[...]}} / {@code
 * {"unsubscribe":[...]}}, inbound {@code {"type":"pricing","message":"<base64 PricingData>"}}.
 * Uses the Jackson tree model only, so it needs no reflection metadata.
 */
public final class StreamerProtocol {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private StreamerProtocol() {}

    /** {@code {"subscribe":[symbols...]}}. */
    public static String subscribe(Collection<String> symbols) {
        return command("subscribe", symbols);
    }

    /** {@code {"unsubscribe":[symbols...]}}. */
    public static String unsubscribe(Collection<String> symbols) {
        return command("unsubscribe", symbols);
    }

    /**
     * The tick in inbound text frame {@code frame}, or {@code null} for a frame that carries no
     * pricing message (yfinance decodes only the {@code message} field as well).
     *
     * @throws YFDataException when the frame is not JSON or its message cannot be decoded
     */
    public static @Nullable PriceTick decodeFrame(String frame) {
        JsonNode root;
        try {
            root = MAPPER.readTree(frame);
        } catch (JsonProcessingException e) {
            throw new YFDataException("streamer frame is not JSON: " + e.getOriginalMessage(), frame, e);
        }
        JsonNode message = root == null ? null : root.get("message");
        if (message == null || !message.isTextual() || message.asText().isEmpty()) {
            return null;
        }
        return PricingDecoder.decode(message.asText());
    }

    private static String command(String name, Collection<String> symbols) {
        ObjectNode root = MAPPER.createObjectNode();
        var array = root.putArray(name);
        symbols.forEach(array::add);
        return root.toString();
    }
}
