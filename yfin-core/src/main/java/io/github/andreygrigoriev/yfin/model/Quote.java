package io.github.andreygrigoriev.yfin.model;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One entry of a batched {@code /v7/finance/quote} response: the flat real-time fields of a single
 * symbol. Every field except {@link #symbol()} is {@code null} when Yahoo omits it.
 *
 * @param symbol the symbol as Yahoo reports it
 * @param shortName short name, e.g. {@code Apple Inc.}
 * @param longName long name
 * @param quoteType Yahoo's quote type, e.g. {@code EQUITY}
 * @param currency listing currency
 * @param marketState Yahoo's market state, e.g. {@code REGULAR}, {@code CLOSED}
 * @param price regular market price
 * @param change regular market change from the previous close
 * @param changePercent regular market change from the previous close, in percent
 * @param previousClose previous close
 * @param volume regular market volume so far today
 * @param averageDailyVolume3Month average daily volume over three months
 * @param marketCap market capitalization
 * @param fiftyDayAverage 50-day average price
 * @param twoHundredDayAverage 200-day average price
 * @param fiftyTwoWeekHigh 52-week high (includes today)
 * @param fiftyTwoWeekLow 52-week low (includes today)
 * @param dayHigh regular market day high
 * @param dayLow regular market day low
 * @param time time of the last regular market price
 * @param raw the quote as Yahoo returns it, plain values as in {@link TickerInfo#raw()};
 *     unmodifiable
 */
public record Quote(
        String symbol,
        @Nullable String shortName,
        @Nullable String longName,
        @Nullable String quoteType,
        @Nullable String currency,
        @Nullable String marketState,
        @Nullable Double price,
        @Nullable Double change,
        @Nullable Double changePercent,
        @Nullable Double previousClose,
        @Nullable Long volume,
        @Nullable Long averageDailyVolume3Month,
        @Nullable Double marketCap,
        @Nullable Double fiftyDayAverage,
        @Nullable Double twoHundredDayAverage,
        @Nullable Double fiftyTwoWeekHigh,
        @Nullable Double fiftyTwoWeekLow,
        @Nullable Double dayHigh,
        @Nullable Double dayLow,
        @Nullable Instant time,
        Map<String, Object> raw) {

    public Quote {
        Objects.requireNonNull(symbol, "symbol");
        raw = Collections.unmodifiableMap(new LinkedHashMap<>(raw));
    }
}
