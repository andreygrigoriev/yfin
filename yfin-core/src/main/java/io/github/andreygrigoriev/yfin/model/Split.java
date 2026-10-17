package io.github.andreygrigoriev.yfin.model;

import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * A stock split of {@code numerator} new shares for {@code denominator} old ones; a 4:1 split has
 * {@code ratio() == 4}, a 1:10 reverse split {@code 0.1}.
 *
 * @param time effective date in the exchange's timezone; midnight for daily and longer intervals
 */
public record Split(ZonedDateTime time, double numerator, double denominator) {

    public Split {
        Objects.requireNonNull(time, "time");
        if (!(denominator > 0) || !(numerator > 0)) {
            throw new IllegalArgumentException("split terms must be positive: " + numerator + ":" + denominator);
        }
    }

    /** {@code numerator / denominator}. */
    public double ratio() {
        return numerator / denominator;
    }
}
