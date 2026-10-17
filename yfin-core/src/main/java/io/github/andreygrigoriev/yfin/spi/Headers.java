package io.github.andreygrigoriev.yfin.spi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Header map helpers shared by {@link HttpRequest} and {@link HttpResponse}. */
final class Headers {

    private Headers() {}

    /** Immutable copy with case-insensitive keys; later duplicates (by case) are merged. */
    static Map<String, List<String>> copyOf(Map<String, List<String>> headers) {
        var copy = new TreeMap<String, List<String>>(String.CASE_INSENSITIVE_ORDER);
        headers.forEach((name, values) -> copy.merge(name, List.copyOf(values), (a, b) -> {
            var merged = new ArrayList<>(a);
            merged.addAll(b);
            return List.copyOf(merged);
        }));
        return Collections.unmodifiableMap(copy);
    }

    static Optional<String> first(Map<String, List<String>> headers, String name) {
        List<String> values = headers.get(name);
        return values == null || values.isEmpty() ? Optional.empty() : Optional.of(values.getFirst());
    }
}
