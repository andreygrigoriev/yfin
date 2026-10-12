package io.andreygrigoriev.yfin.model;

import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * One price bar. Missing prices are {@link Double#NaN}; missing volume is {@code 0}.
 *
 * <p>With auto-adjust (the default) open, high, low and close are adjusted for dividends and splits
 * and {@code adjClose == close}. With back-adjust open, high and low are adjusted while close stays
 * raw. Without either, prices are as traded (Yahoo still split-adjusts them) and {@code adjClose}
 * is Yahoo's adjusted close.
 *
 * @param time bar start in the exchange's timezone; midnight for daily and longer bars
 */
public record Bar(
        ZonedDateTime time, double open, double high, double low, double close, double adjClose, long volume) {

    public Bar {
        Objects.requireNonNull(time, "time");
    }

    /** Whether every price is missing. */
    public boolean isEmpty() {
        return Double.isNaN(open) && Double.isNaN(high) && Double.isNaN(low) && Double.isNaN(close);
    }
}
