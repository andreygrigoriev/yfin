package io.andreygrigoriev.yfin.model;

import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * A cash dividend per share.
 *
 * @param time ex-dividend date in the exchange's timezone; midnight for daily and longer intervals
 * @param amount amount per share in the price currency
 */
public record Dividend(ZonedDateTime time, double amount) {

    public Dividend {
        Objects.requireNonNull(time, "time");
    }
}
