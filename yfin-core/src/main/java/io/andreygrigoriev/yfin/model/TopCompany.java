package io.andreygrigoriev.yfin.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One of the largest companies of a sector or an industry (a row of yfinance {@code
 * top_companies}).
 *
 * @param symbol the symbol
 * @param name company name
 * @param rating analyst rating, e.g. {@code Strong Buy}
 * @param marketWeight weight in the sector or industry, a fraction
 * @param raw the entry as Yahoo returns it (also market cap, target price, YTD return), plain
 *     values as in {@link TickerInfo#raw()}; unmodifiable
 */
public record TopCompany(
        String symbol,
        @Nullable String name,
        @Nullable String rating,
        @Nullable Double marketWeight,
        Map<String, Object> raw) {

    public TopCompany {
        Objects.requireNonNull(symbol, "symbol");
        raw = Collections.unmodifiableMap(new LinkedHashMap<>(raw));
    }
}
