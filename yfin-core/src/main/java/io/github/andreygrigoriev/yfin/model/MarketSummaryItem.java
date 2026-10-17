package io.github.andreygrigoriev.yfin.model;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One instrument of a market summary (a value of yfinance {@code Market.summary}): a headline
 * index, rate, commodity or currency pair.
 *
 * @param symbol the symbol, e.g. {@code ^GSPC}
 * @param exchange exchange code, e.g. {@code SNP}
 * @param shortName short name, e.g. {@code S&P 500}
 * @param quoteType Yahoo's quote type, e.g. {@code INDEX}
 * @param price regular market price
 * @param change change from the previous close
 * @param changePercent change from the previous close, in percent
 * @param previousClose previous close
 * @param time time of the last regular market price
 * @param marketState Yahoo's market state, e.g. {@code REGULAR}, {@code CLOSED}
 * @param raw the entry as Yahoo returns it, plain values as in {@link TickerInfo#raw()};
 *     unmodifiable
 */
public record MarketSummaryItem(
        String symbol,
        @Nullable String exchange,
        @Nullable String shortName,
        @Nullable String quoteType,
        @Nullable Double price,
        @Nullable Double change,
        @Nullable Double changePercent,
        @Nullable Double previousClose,
        @Nullable Instant time,
        @Nullable String marketState,
        Map<String, Object> raw) {

    public MarketSummaryItem {
        Objects.requireNonNull(symbol, "symbol");
        raw = Collections.unmodifiableMap(new LinkedHashMap<>(raw));
    }
}
