package io.andreygrigoriev.yfin.model;

import org.jspecify.annotations.Nullable;

/**
 * Analyst recommendation counts for one month (Yahoo {@code recommendationTrend}, yfinance {@code
 * recommendations}). Any count is {@code null} when Yahoo omits it.
 *
 * @param period month relative to now: {@code 0m} is the current month, {@code -1m} the previous
 * @param strongBuy number of strong buy ratings
 * @param buy number of buy ratings
 * @param hold number of hold ratings
 * @param sell number of sell ratings
 * @param strongSell number of strong sell ratings
 */
public record Recommendation(
        @Nullable String period,
        @Nullable Long strongBuy,
        @Nullable Long buy,
        @Nullable Long hold,
        @Nullable Long sell,
        @Nullable Long strongSell) {}
