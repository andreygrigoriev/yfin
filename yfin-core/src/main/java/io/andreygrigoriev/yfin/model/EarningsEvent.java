package io.andreygrigoriev.yfin.model;

import java.time.ZonedDateTime;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One row of the market-wide earnings calendar (yfinance {@code
 * Calendars.get_earnings_calendar()}). Values Yahoo does not have, such as the reported EPS of an
 * upcoming announcement, are {@link Double#NaN}.
 *
 * @param symbol ticker symbol
 * @param companyName short company name
 * @param marketCap intraday market cap
 * @param eventName Yahoo's event title, e.g. {@code "Q3 2026 Earnings Call"}
 * @param startTime announcement time, in {@code America/New_York}
 * @param timing Yahoo's raw time-of-day code: {@code BMO} (before market open), {@code AMC} (after
 *     market close), {@code TAS} (time as scheduled) or {@code TNS} (time not supplied)
 * @param epsEstimate consensus EPS estimate
 * @param reportedEps reported EPS
 * @param surprisePercent EPS surprise in percent, as Yahoo reports it
 */
public record EarningsEvent(
        String symbol,
        @Nullable String companyName,
        double marketCap,
        @Nullable String eventName,
        ZonedDateTime startTime,
        @Nullable String timing,
        double epsEstimate,
        double reportedEps,
        double surprisePercent) {

    public EarningsEvent {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(startTime, "startTime");
    }

    /** Whether the EPS has been reported, i.e. the announcement is in the past. */
    public boolean isReported() {
        return !Double.isNaN(reportedEps);
    }
}
