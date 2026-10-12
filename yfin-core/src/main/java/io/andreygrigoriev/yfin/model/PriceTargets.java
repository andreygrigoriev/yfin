package io.andreygrigoriev.yfin.model;

import org.jspecify.annotations.Nullable;

/**
 * Analyst price targets (Yahoo {@code financialData}, yfinance {@code analyst_price_targets}), in
 * the symbol's currency. Any field is {@code null} when Yahoo omits it.
 *
 * @param current current price
 * @param high highest target
 * @param low lowest target
 * @param mean mean target
 * @param median median target
 */
public record PriceTargets(
        @Nullable Double current,
        @Nullable Double high,
        @Nullable Double low,
        @Nullable Double mean,
        @Nullable Double median) {}
