package io.andreygrigoriev.yfin.model;

import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * One past or upcoming earnings announcement (yfinance {@code earnings_dates}). Values Yahoo does
 * not have, such as the reported EPS of a future date, are {@link Double#NaN}.
 *
 * @param date announcement time, in the exchange timezone
 * @param epsEstimate consensus EPS estimate
 * @param reportedEps reported EPS
 * @param surprisePercent {@code (reportedEps - epsEstimate) / |epsEstimate|} in percent ({@code
 *     6.74} = 6.74% beat), as Yahoo reports it; {@code NaN} when there is no reported EPS
 */
public record EarningsDate(ZonedDateTime date, double epsEstimate, double reportedEps, double surprisePercent) {

    public EarningsDate {
        Objects.requireNonNull(date, "date");
    }

    /** Whether the EPS has been reported, i.e. the announcement is in the past. */
    public boolean isReported() {
        return !Double.isNaN(reportedEps);
    }
}
