package io.github.andreygrigoriev.yfin.internal.endpoint;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;

/** URI building shared by the endpoints. */
public final class Uris {

    private Uris() {}

    /** {@code base} followed by {@code params} as an encoded query string (none when empty). */
    public static URI uri(String base, Map<String, String> params) {
        String query = params.entrySet().stream()
                .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(Collectors.joining("&"));
        return URI.create(base + (query.isEmpty() ? "" : "?" + query));
    }

    /** URL-encodes {@code s} for a path segment or query component (spaces as {@code %20}). */
    public static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
