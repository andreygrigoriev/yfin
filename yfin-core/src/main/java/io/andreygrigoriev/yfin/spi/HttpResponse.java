package io.andreygrigoriev.yfin.spi;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * An HTTP response returned by a {@link Transport}. Immutable: headers are copied into an
 * unmodifiable, case-insensitive map and the body array is defensively copied.
 *
 * @param status HTTP status code
 * @param headers response headers, case-insensitive names
 * @param body response body, empty array when there is none
 */
public record HttpResponse(int status, Map<String, List<String>> headers, byte[] body) {

    public HttpResponse {
        if (status < 100 || status > 999) {
            throw new IllegalArgumentException("invalid status: " + status);
        }
        headers = Headers.copyOf(Objects.requireNonNull(headers, "headers"));
        body = Objects.requireNonNull(body, "body").clone();
    }

    /** A response with a UTF-8 text body and no headers. */
    public static HttpResponse of(int status, String body) {
        return new HttpResponse(status, Map.of(), body.getBytes(StandardCharsets.UTF_8));
    }

    /** Copy of the body. */
    @Override
    public byte[] body() {
        return body.clone();
    }

    /** The body decoded as UTF-8. */
    public String bodyAsString() {
        return new String(body, StandardCharsets.UTF_8);
    }

    /** {@code true} for 2xx statuses. */
    public boolean isSuccess() {
        return status >= 200 && status < 300;
    }

    /** First value of header {@code name} (case-insensitive). */
    public Optional<String> header(String name) {
        return Headers.first(headers, name);
    }

    /** All values of header {@code name} (case-insensitive). */
    public List<String> headerValues(String name) {
        return headers.getOrDefault(name, List.of());
    }

    @Override
    public boolean equals(@Nullable Object o) {
        return o instanceof HttpResponse other
                && status == other.status
                && headers.equals(other.headers)
                && Arrays.equals(body, other.body);
    }

    @Override
    public int hashCode() {
        return Objects.hash(status, headers, Arrays.hashCode(body));
    }

    @Override
    public String toString() {
        return "HttpResponse[" + status + ", headers=" + headers.keySet() + ", body=" + body.length + " bytes]";
    }
}
