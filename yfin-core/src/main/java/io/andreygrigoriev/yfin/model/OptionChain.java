package io.andreygrigoriev.yfin.model;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Calls and puts of one expiration (yfinance {@code option_chain}), both ordered by strike as Yahoo
 * returns them.
 *
 * @param symbol the underlying symbol
 * @param expiration expiration date (UTC, as Yahoo lists it)
 * @param calls call contracts; unmodifiable
 * @param puts put contracts; unmodifiable
 * @param underlying quote of the underlying as Yahoo returns it (price, exchange, timezone, …);
 *     unmodifiable, in Yahoo's order, plain values as in {@link TickerInfo#raw()}
 */
public record OptionChain(
        String symbol,
        LocalDate expiration,
        List<OptionContract> calls,
        List<OptionContract> puts,
        Map<String, Object> underlying) {

    public OptionChain {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(expiration, "expiration");
        calls = List.copyOf(calls);
        puts = List.copyOf(puts);
        underlying = Collections.unmodifiableMap(new LinkedHashMap<>(underlying));
    }
}
