package io.andreygrigoriev.yfin.model;

import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * How the mean EPS estimate for one period moved (Yahoo {@code earningsTrend.epsTrend}, yfinance
 * {@code eps_trend}). Any field is {@code null} when Yahoo omits it.
 *
 * @param period {@code 0q}, {@code +1q}, {@code 0y} or {@code +1y}, as in {@link EarningsEstimate}
 * @param endDate last day of the period
 * @param current current mean estimate
 * @param sevenDaysAgo mean estimate 7 days ago
 * @param thirtyDaysAgo mean estimate 30 days ago
 * @param sixtyDaysAgo mean estimate 60 days ago
 * @param ninetyDaysAgo mean estimate 90 days ago
 * @param currency currency of the estimates
 */
public record EpsTrend(
        @Nullable String period,
        @Nullable LocalDate endDate,
        @Nullable Double current,
        @Nullable Double sevenDaysAgo,
        @Nullable Double thirtyDaysAgo,
        @Nullable Double sixtyDaysAgo,
        @Nullable Double ninetyDaysAgo,
        @Nullable String currency) {}
