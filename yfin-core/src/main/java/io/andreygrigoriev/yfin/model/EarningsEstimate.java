package io.andreygrigoriev.yfin.model;

import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * Analyst EPS estimate for one period (Yahoo {@code earningsTrend.earningsEstimate}, yfinance
 * {@code earnings_estimate}). Any field is {@code null} when Yahoo omits it.
 *
 * @param period {@code 0q} current quarter, {@code +1q} next quarter, {@code 0y} current fiscal
 *     year, {@code +1y} next fiscal year
 * @param endDate last day of the period
 * @param numberOfAnalysts number of analysts contributing
 * @param avg mean estimate
 * @param low lowest estimate
 * @param high highest estimate
 * @param yearAgoEps EPS of the same period a year earlier
 * @param growth expected growth over {@code yearAgoEps} ({@code 0.07} = +7%)
 * @param currency currency of the estimates
 */
public record EarningsEstimate(
        @Nullable String period,
        @Nullable LocalDate endDate,
        @Nullable Long numberOfAnalysts,
        @Nullable Double avg,
        @Nullable Double low,
        @Nullable Double high,
        @Nullable Double yearAgoEps,
        @Nullable Double growth,
        @Nullable String currency) {}
