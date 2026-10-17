package io.github.andreygrigoriev.yfin.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Result of a Yahoo Finance search (yfinance {@code Search}). Lists, research reports and
 * navigation links are semi-structured, so they are kept as plain values as in {@link
 * TickerInfo#raw()}. All collections are unmodifiable.
 *
 * @param query the query searched for
 * @param quotes matching quotes, entries without a symbol (e.g. private companies) dropped
 * @param news matching news articles
 * @param lists matching lists: watchlists and predefined screeners
 * @param research research reports, when requested
 * @param nav navigation links, when requested
 * @param raw the whole response as Yahoo returns it
 */
public record SearchResult(
        String query,
        List<SearchQuote> quotes,
        List<NewsItem> news,
        List<Map<String, Object>> lists,
        List<Map<String, Object>> research,
        List<Map<String, Object>> nav,
        Map<String, Object> raw) {

    public SearchResult {
        Objects.requireNonNull(query, "query");
        quotes = List.copyOf(quotes);
        news = List.copyOf(news);
        lists = List.copyOf(lists);
        research = List.copyOf(research);
        nav = List.copyOf(nav);
        raw = Collections.unmodifiableMap(new LinkedHashMap<>(raw));
    }

    /** Whether Yahoo found nothing in any category. */
    public boolean isEmpty() {
        return quotes.isEmpty() && news.isEmpty() && lists.isEmpty() && research.isEmpty() && nav.isEmpty();
    }
}
