package io.github.andreygrigoriev.yfin.model;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One of the best performing companies of an industry (a row of yfinance {@code
 * Industry.top_performing_companies}).
 *
 * @param symbol the symbol
 * @param name company name
 * @param ytdReturn year-to-date return, a fraction
 * @param lastPrice last price
 * @param targetPrice analysts' mean target price
 */
public record PerformingCompany(
        String symbol,
        @Nullable String name,
        @Nullable Double ytdReturn,
        @Nullable Double lastPrice,
        @Nullable Double targetPrice) {

    public PerformingCompany {
        Objects.requireNonNull(symbol, "symbol");
    }
}
