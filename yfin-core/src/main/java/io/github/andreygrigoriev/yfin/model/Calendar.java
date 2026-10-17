package io.github.andreygrigoriev.yfin.model;

import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Upcoming events (Yahoo {@code calendarEvents}, yfinance {@code calendar}): next earnings date
 * with the analyst estimate ranges, and dividend dates. Dates are UTC. Any scalar is {@code null}
 * when Yahoo omits it.
 *
 * @param earningsDates the next earnings date, or a two-date window when Yahoo has no exact date;
 *     unmodifiable, empty when unknown
 * @param earningsDateEstimate whether the earnings date is an estimate rather than announced
 * @param earningsHigh highest EPS estimate for the reported quarter
 * @param earningsLow lowest EPS estimate
 * @param earningsAverage mean EPS estimate
 * @param revenueHigh highest revenue estimate
 * @param revenueLow lowest revenue estimate
 * @param revenueAverage mean revenue estimate
 * @param exDividendDate latest or next ex-dividend date
 * @param dividendDate latest or next dividend payment date
 */
public record Calendar(
        List<LocalDate> earningsDates,
        @Nullable Boolean earningsDateEstimate,
        @Nullable Double earningsHigh,
        @Nullable Double earningsLow,
        @Nullable Double earningsAverage,
        @Nullable Double revenueHigh,
        @Nullable Double revenueLow,
        @Nullable Double revenueAverage,
        @Nullable LocalDate exDividendDate,
        @Nullable LocalDate dividendDate) {

    public Calendar {
        earningsDates = List.copyOf(earningsDates);
    }
}
