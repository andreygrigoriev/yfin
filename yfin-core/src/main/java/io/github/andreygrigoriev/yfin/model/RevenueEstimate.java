package io.github.andreygrigoriev.yfin.model;

import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * Analyst revenue estimate for one period (Yahoo {@code earningsTrend.revenueEstimate}, yfinance
 * {@code revenue_estimate}). Any field is {@code null} when Yahoo omits it.
 *
 * @param period {@code 0q}, {@code +1q}, {@code 0y} or {@code +1y}, as in {@link EarningsEstimate}
 * @param endDate last day of the period
 * @param numberOfAnalysts number of analysts contributing
 * @param avg mean estimate
 * @param low lowest estimate
 * @param high highest estimate
 * @param yearAgoRevenue revenue of the same period a year earlier
 * @param growth expected growth over {@code yearAgoRevenue} ({@code 0.1} = +10%)
 * @param currency currency of the estimates
 */
public record RevenueEstimate(
        @Nullable String period,
        @Nullable LocalDate endDate,
        @Nullable Long numberOfAnalysts,
        @Nullable Double avg,
        @Nullable Double low,
        @Nullable Double high,
        @Nullable Double yearAgoRevenue,
        @Nullable Double growth,
        @Nullable String currency) {}
