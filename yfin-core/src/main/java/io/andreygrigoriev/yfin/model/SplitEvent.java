package io.andreygrigoriev.yfin.model;

import java.time.LocalDate;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One row of the market-wide splits calendar (yfinance {@code Calendars.get_splits_calendar()}):
 * {@code oldShareWorth} old shares become {@code newShareWorth} new ones, so a 4:1 split has {@code
 * ratio() == 4}. Unknown terms are {@link Double#NaN}.
 *
 * @param symbol ticker symbol
 * @param companyName short company name
 * @param payableDate date the split takes effect, in {@code America/New_York}
 * @param optionable whether the stock has listed options
 * @param oldShareWorth number of shares before the split
 * @param newShareWorth number of shares after the split
 */
public record SplitEvent(
        String symbol,
        @Nullable String companyName,
        LocalDate payableDate,
        @Nullable Boolean optionable,
        double oldShareWorth,
        double newShareWorth) {

    public SplitEvent {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(payableDate, "payableDate");
    }

    /** {@code newShareWorth / oldShareWorth}, as in {@link Split#ratio()}; {@code NaN} when unknown. */
    public double ratio() {
        return oldShareWorth > 0 ? newShareWorth / oldShareWorth : Double.NaN;
    }
}
