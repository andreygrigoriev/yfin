package io.andreygrigoriev.yfin.model;

import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * Shares outstanding reported at one point in time (an entry of yfinance {@code get_shares_full}).
 *
 * @param time report time: Yahoo's UTC timestamp taken as a wall-clock time in the exchange
 *     timezone, as yfinance does ({@code tz_localize})
 * @param shares number of shares outstanding
 */
public record SharesCount(ZonedDateTime time, long shares) {

    public SharesCount {
        Objects.requireNonNull(time, "time");
    }
}
