package io.andreygrigoriev.yfin.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One page of screener results (yfinance {@code screen}'s {@code finance.result[0]}).
 *
 * @param id Yahoo's id of a predefined screen (a UUID); {@code null} for custom screens
 * @param title screen title, for predefined screens
 * @param description screen description, for predefined screens
 * @param start index of the first quote
 * @param count number of quotes returned
 * @param total number of instruments matching the screen, when Yahoo reports it
 * @param quotes the matching instruments as Yahoo returns them (quote fields such as {@code
 *     symbol}, {@code shortName}, {@code regularMarketPrice}), plain values as in {@link
 *     TickerInfo#raw()}; unmodifiable
 * @param raw the whole result as Yahoo returns it; unmodifiable
 */
public record ScreenerResult(
        @Nullable String id,
        @Nullable String title,
        @Nullable String description,
        int start,
        int count,
        @Nullable Long total,
        List<Map<String, Object>> quotes,
        Map<String, Object> raw) {

    public ScreenerResult {
        quotes = quotes.stream()
                .map(q -> Collections.unmodifiableMap(new LinkedHashMap<>(q)))
                .toList();
        raw = Collections.unmodifiableMap(new LinkedHashMap<>(raw));
    }

    /** The symbols of {@link #quotes()}, in order; quotes without a symbol are skipped. */
    public List<String> symbols() {
        var out = new ArrayList<String>(quotes.size());
        for (Map<String, Object> q : quotes) {
            if (q.get("symbol") instanceof String s) {
                out.add(s);
            }
        }
        return List.copyOf(out);
    }
}
