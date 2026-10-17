package io.github.andreygrigoriev.yfin.spi;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * An HTTP request handed to a {@link Transport}. Immutable: headers are copied into an
 * unmodifiable, case-insensitive map and the body array is defensively copied.
 *
 * @param method HTTP method, upper case ({@code GET}, {@code POST})
 * @param uri absolute request URI including the query string
 * @param headers request headers, case-insensitive names
 * @param body request body, or {@code null} for none
 */
public record HttpRequest(String method, URI uri, Map<String, List<String>> headers, byte @Nullable [] body) {

    public HttpRequest {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(headers, "headers");
        if (method.isBlank()) {
            throw new IllegalArgumentException("method is blank");
        }
        if (!uri.isAbsolute()) {
            throw new IllegalArgumentException("uri is not absolute: " + uri);
        }
        method = method.toUpperCase(Locale.ROOT);
        headers = Headers.copyOf(headers);
        body = body == null ? null : body.clone();
    }

    /** A {@code GET} request without headers. */
    public static HttpRequest get(URI uri) {
        return new HttpRequest("GET", uri, Map.of(), null);
    }

    /** A {@code POST} request with a JSON body and {@code Content-Type: application/json}. */
    public static HttpRequest postJson(URI uri, String json) {
        return new HttpRequest(
                "POST",
                uri,
                Map.of("Content-Type", List.of("application/json")),
                json.getBytes(StandardCharsets.UTF_8));
    }

    /** Copy of this request with {@code value} added to header {@code name}. */
    public HttpRequest withHeader(String name, String value) {
        var copy = new LinkedHashMap<String, List<String>>(headers);
        copy.merge(name, List.of(value), (a, b) -> {
            var merged = new ArrayList<>(a);
            merged.addAll(b);
            return merged;
        });
        return new HttpRequest(method, uri, copy, body);
    }

    /** Copy of the body, or {@code null}. */
    @Override
    public byte @Nullable [] body() {
        return body == null ? null : body.clone();
    }

    /** The body, if any. */
    public Optional<byte[]> bodyBytes() {
        return Optional.ofNullable(body());
    }

    /** The body decoded as UTF-8, if any. */
    public Optional<String> bodyAsString() {
        return body == null ? Optional.empty() : Optional.of(new String(body, StandardCharsets.UTF_8));
    }

    /** First value of header {@code name} (case-insensitive). */
    public Optional<String> header(String name) {
        return Headers.first(headers, name);
    }

    @Override
    public boolean equals(@Nullable Object o) {
        return o instanceof HttpRequest other
                && method.equals(other.method)
                && uri.equals(other.uri)
                && headers.equals(other.headers)
                && Arrays.equals(body, other.body);
    }

    @Override
    public int hashCode() {
        return Objects.hash(method, uri, headers, Arrays.hashCode(body));
    }

    @Override
    public String toString() {
        return "HttpRequest[" + method + " " + uri + ", headers=" + headers.keySet()
                + (body == null ? "" : ", body=" + body.length + " bytes") + "]";
    }
}
