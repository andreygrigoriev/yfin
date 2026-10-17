package io.github.andreygrigoriev.yfin.model;

import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * Number of analysts who revised their EPS estimate for one period (Yahoo {@code
 * earningsTrend.epsRevisions}, yfinance {@code eps_revisions}). Any field is {@code null} when
 * Yahoo omits it.
 *
 * @param period {@code 0q}, {@code +1q}, {@code 0y} or {@code +1y}, as in {@link EarningsEstimate}
 * @param endDate last day of the period
 * @param upLast7Days revisions up in the last 7 days
 * @param upLast30Days revisions up in the last 30 days
 * @param downLast7Days revisions down in the last 7 days
 * @param downLast30Days revisions down in the last 30 days
 * @param currency currency of the estimates
 */
public record EpsRevisions(
        @Nullable String period,
        @Nullable LocalDate endDate,
        @Nullable Long upLast7Days,
        @Nullable Long upLast30Days,
        @Nullable Long downLast7Days,
        @Nullable Long downLast30Days,
        @Nullable String currency) {}
