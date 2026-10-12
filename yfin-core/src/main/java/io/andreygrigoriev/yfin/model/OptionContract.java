package io.andreygrigoriev.yfin.model;

import java.time.ZonedDateTime;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One call or put of an {@link OptionChain} (a row of yfinance's {@code option_chain().calls} /
 * {@code puts}). Prices Yahoo omits, typically the bid of a contract nobody bids for, are {@link
 * Double#NaN}; a missing volume or open interest is {@code 0}.
 *
 * @param contractSymbol OCC-style contract symbol, e.g. {@code AAPL261012C00245000}
 * @param strike strike price
 * @param lastPrice price of the last trade
 * @param bid current bid
 * @param ask current ask
 * @param change change of the last price since the previous close
 * @param percentChange {@code change} in percent ({@code 2.97} = +2.97%)
 * @param volume contracts traded today
 * @param openInterest open contracts
 * @param impliedVolatility implied volatility as a fraction ({@code 0.25} = 25%)
 * @param inTheMoney whether the contract is in the money at the underlying's current price
 * @param lastTradeDate time of the last trade, in the exchange timezone; {@code null} when unknown
 * @param contractSize contract size, {@code REGULAR} for standard contracts
 * @param currency currency of the prices
 */
public record OptionContract(
        String contractSymbol,
        double strike,
        double lastPrice,
        double bid,
        double ask,
        double change,
        double percentChange,
        long volume,
        long openInterest,
        double impliedVolatility,
        boolean inTheMoney,
        @Nullable ZonedDateTime lastTradeDate,
        @Nullable String contractSize,
        @Nullable String currency) {

    public OptionContract {
        Objects.requireNonNull(contractSymbol, "contractSymbol");
    }
}
