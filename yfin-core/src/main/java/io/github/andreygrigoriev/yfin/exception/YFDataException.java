package io.github.andreygrigoriev.yfin.exception;

import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.Nullable;

/**
 * Yahoo returned a payload that cannot be parsed or has an unexpected shape. Keeps a truncated
 * snippet of the raw payload for diagnostics.
 */
public class YFDataException extends YFinanceException {

    /** Maximum length of {@link #payloadSnippet()} in UTF-16 chars. */
    public static final int MAX_SNIPPET_LENGTH = 2048;

    private final @Nullable String payloadSnippet;

    public YFDataException(String message) {
        this(message, (String) null, null);
    }

    public YFDataException(String message, @Nullable String payload) {
        this(message, payload, null);
    }

    public YFDataException(String message, @Nullable String payload, @Nullable Throwable cause) {
        super(message, cause);
        this.payloadSnippet = truncate(payload);
    }

    public YFDataException(String message, byte @Nullable [] payload, @Nullable Throwable cause) {
        this(message, payload == null ? null : decodeHead(payload), cause);
    }

    /** The first {@value #MAX_SNIPPET_LENGTH} chars of the offending payload, or {@code null}. */
    public @Nullable String payloadSnippet() {
        return payloadSnippet;
    }

    @Override
    public String getMessage() {
        String message = super.getMessage();
        return payloadSnippet == null ? message : message + " [payload: " + payloadSnippet + "]";
    }

    private static String decodeHead(byte[] payload) {
        // a UTF-8 char is at most 4 bytes, so this many bytes always cover MAX_SNIPPET_LENGTH chars
        int len = Math.min(payload.length, MAX_SNIPPET_LENGTH * 4 + 4);
        return new String(payload, 0, len, StandardCharsets.UTF_8);
    }

    static @Nullable String truncate(@Nullable String payload) {
        if (payload == null || payload.length() <= MAX_SNIPPET_LENGTH) {
            return payload;
        }
        int end = MAX_SNIPPET_LENGTH;
        if (Character.isHighSurrogate(payload.charAt(end - 1))) {
            end--;
        }
        return payload.substring(0, end);
    }
}
