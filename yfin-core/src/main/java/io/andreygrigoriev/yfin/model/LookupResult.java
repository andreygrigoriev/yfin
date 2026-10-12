package io.andreygrigoriev.yfin.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One instrument found by a symbol lookup (a row of yfinance {@code Lookup.get_*}).
 *
 * @param symbol the symbol
 * @param shortName short name
 * @param quoteType Yahoo's lower-case quote type, e.g. {@code equity}, {@code etf}
 * @param exchange exchange code, e.g. {@code NMS}
 * @param regularMarketPrice last regular-market price
 * @param regularMarketChange regular-market change
 * @param regularMarketPercentChange regular-market change in percent ({@code 0.99} = 0.99%)
 * @param rank Yahoo's popularity rank
 * @param raw the document as Yahoo returns it, plain values as in {@link TickerInfo#raw()};
 *     unmodifiable
 */
public record LookupResult(
        String symbol,
        @Nullable String shortName,
        @Nullable String quoteType,
        @Nullable String exchange,
        @Nullable Double regularMarketPrice,
        @Nullable Double regularMarketChange,
        @Nullable Double regularMarketPercentChange,
        @Nullable Long rank,
        Map<String, Object> raw) {

    public LookupResult {
        Objects.requireNonNull(symbol, "symbol");
        raw = Collections.unmodifiableMap(new LinkedHashMap<>(raw));
    }
}
