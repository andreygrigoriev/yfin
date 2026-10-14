package io.andreygrigoriev.yfin.model;

import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One row of the economic-events calendar (yfinance {@code
 * Calendars.get_economic_events_calendar()}). Values Yahoo does not have are {@link Double#NaN}.
 *
 * @param event release name, e.g. {@code "CPI"}
 * @param region two-letter country or region code, e.g. {@code "US"} or {@code "EU"}
 * @param time release time; an {@link Instant} because the event is not tied to an exchange
 * @param period period the release covers, e.g. {@code "Sep"} or {@code "Q3"}
 * @param actual released value
 * @param expected consensus estimate
 * @param last value of the prior release
 * @param revised originally reported value of the prior release, when it was revised
 */
public record EconomicEvent(
        String event,
        @Nullable String region,
        Instant time,
        @Nullable String period,
        double actual,
        double expected,
        double last,
        double revised) {

    public EconomicEvent {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(time, "time");
    }
}
