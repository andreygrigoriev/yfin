package io.github.andreygrigoriev.yfin.internal.repair;

import io.github.andreygrigoriev.yfin.request.Interval;
import java.time.ZonedDateTime;

/** yfinance's interval string tests and {@code _interval_to_timedelta}. */
public final class Intervals {

    private Intervals() {}

    /** {@code interval in ['1d', '1wk', '1mo', '3mo']}. */
    static boolean interday(Interval i) {
        return i == Interval.ONE_DAY || multiday(i);
    }

    /** {@code interval in ['1wk', '1mo', '3mo']}. */
    public static boolean multiday(Interval i) {
        return i == Interval.ONE_WEEK || i == Interval.ONE_MONTH || i == Interval.THREE_MONTHS;
    }

    /** {@code t + k * _interval_to_timedelta(interval)}, calendar-aware for days, weeks and months. */
    static ZonedDateTime plus(ZonedDateTime t, Interval i, long k) {
        return switch (i) {
            case ONE_DAY -> t.plusDays(k);
            case FIVE_DAYS -> t.plusDays(5 * k);
            case ONE_WEEK -> t.plusWeeks(k);
            case ONE_MONTH -> t.plusMonths(k);
            case THREE_MONTHS -> t.plusMonths(3 * k);
            default -> t.plus(i.nominalDuration().multipliedBy(k));
        };
    }
}
