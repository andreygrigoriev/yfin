package io.andreygrigoriev.yfin.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One quote of a search result (an element of yfinance {@code Search.quotes}).
 *
 * @param symbol the symbol
 * @param shortName short name
 * @param longName long name
 * @param quoteType Yahoo's quote type, e.g. {@code EQUITY}, {@code ETF}, {@code FUTURE}
 * @param typeDisplay display form of the quote type, e.g. {@code Equity}
 * @param exchange exchange code, e.g. {@code NMS}
 * @param exchangeDisplay exchange name, e.g. {@code NASDAQ}
 * @param sector sector, when Yahoo has one
 * @param industry industry, when Yahoo has one
 * @param score Yahoo's relevance score
 * @param raw the quote as Yahoo returns it, plain values as in {@link TickerInfo#raw()};
 *     unmodifiable
 */
public record SearchQuote(
        String symbol,
        @Nullable String shortName,
        @Nullable String longName,
        @Nullable String quoteType,
        @Nullable String typeDisplay,
        @Nullable String exchange,
        @Nullable String exchangeDisplay,
        @Nullable String sector,
        @Nullable String industry,
        @Nullable Double score,
        Map<String, Object> raw) {

    public SearchQuote {
        Objects.requireNonNull(symbol, "symbol");
        raw = Collections.unmodifiableMap(new LinkedHashMap<>(raw));
    }
}
