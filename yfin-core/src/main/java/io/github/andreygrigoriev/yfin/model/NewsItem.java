package io.github.andreygrigoriev.yfin.model;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One news article about a symbol (an element of yfinance {@code news}).
 *
 * @param id Yahoo's article id
 * @param title headline
 * @param summary short summary, when Yahoo has one
 * @param publisher name of the publishing provider
 * @param link article URL
 * @param publishTime publication time
 * @param contentType Yahoo's content type, e.g. {@code STORY} or {@code VIDEO}
 * @param thumbnailUrl URL of the original-size thumbnail, when there is one
 * @param relatedTickers symbols Yahoo links to the article; unmodifiable
 * @param raw the article as Yahoo returns it, plain values as in {@link TickerInfo#raw()};
 *     unmodifiable
 */
public record NewsItem(
        String id,
        String title,
        @Nullable String summary,
        @Nullable String publisher,
        @Nullable String link,
        @Nullable Instant publishTime,
        @Nullable String contentType,
        @Nullable String thumbnailUrl,
        List<String> relatedTickers,
        Map<String, Object> raw) {

    public NewsItem {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        relatedTickers = List.copyOf(relatedTickers);
        raw = Collections.unmodifiableMap(new LinkedHashMap<>(raw));
    }
}
