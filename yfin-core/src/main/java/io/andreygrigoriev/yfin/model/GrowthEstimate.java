package io.andreygrigoriev.yfin.model;

import org.jspecify.annotations.Nullable;

/**
 * Expected earnings growth for one period, for the symbol and its benchmarks (Yahoo {@code
 * earningsTrend}, {@code industryTrend}, {@code sectorTrend}, {@code indexTrend}; yfinance {@code
 * growth_estimates}). Growth is a fraction ({@code 0.07} = +7%); any field is {@code null} when
 * Yahoo has no figure.
 *
 * @param period {@code 0q}, {@code +1q}, {@code 0y}, {@code +1y}, or a benchmark-only period such as
 *     {@code LTG} (long-term growth)
 * @param stock growth expected for the symbol
 * @param industry growth expected for the symbol's industry
 * @param sector growth expected for the symbol's sector
 * @param index growth expected for the benchmark index (e.g. the S&amp;P 500)
 */
public record GrowthEstimate(
        String period,
        @Nullable Double stock,
        @Nullable Double industry,
        @Nullable Double sector,
        @Nullable Double index) {}
