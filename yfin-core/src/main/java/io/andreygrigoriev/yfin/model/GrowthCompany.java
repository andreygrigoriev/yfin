package io.andreygrigoriev.yfin.model;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One of the fastest growing companies of an industry (a row of yfinance {@code
 * Industry.top_growth_companies}).
 *
 * @param symbol the symbol
 * @param name company name
 * @param ytdReturn year-to-date return, a fraction
 * @param growthEstimate estimated earnings growth, a fraction
 */
public record GrowthCompany(
        String symbol,
        @Nullable String name,
        @Nullable Double ytdReturn,
        @Nullable Double growthEstimate) {

    public GrowthCompany {
        Objects.requireNonNull(symbol, "symbol");
    }
}
