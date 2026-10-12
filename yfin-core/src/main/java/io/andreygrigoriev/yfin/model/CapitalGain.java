package io.andreygrigoriev.yfin.model;

import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * A capital gains distribution per share (funds and ETFs).
 *
 * @param time distribution date in the exchange's timezone; midnight for daily and longer intervals
 * @param amount amount per share in the price currency
 */
public record CapitalGain(ZonedDateTime time, double amount) {

    public CapitalGain {
        Objects.requireNonNull(time, "time");
    }
}
