package io.github.andreygrigoriev.yfin.model;

import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * Reported versus estimated EPS for one past quarter (Yahoo {@code earningsHistory}, yfinance
 * {@code earnings_history}). Any field is {@code null} when Yahoo omits it.
 *
 * @param quarter last day of the fiscal quarter
 * @param period quarter relative to now: {@code -1q} is the latest reported one
 * @param epsEstimate mean EPS estimate before the report
 * @param epsActual reported EPS
 * @param epsDifference {@code epsActual - epsEstimate}
 * @param surprisePercent {@code epsDifference / epsEstimate} ({@code 0.045} = 4.5% beat)
 * @param currency currency of the EPS figures
 */
public record EarningsHistoryEntry(
        @Nullable LocalDate quarter,
        @Nullable String period,
        @Nullable Double epsEstimate,
        @Nullable Double epsActual,
        @Nullable Double epsDifference,
        @Nullable Double surprisePercent,
        @Nullable String currency) {}
