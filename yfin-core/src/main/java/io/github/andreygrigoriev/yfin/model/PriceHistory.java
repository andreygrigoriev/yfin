package io.github.andreygrigoriev.yfin.model;

import io.github.andreygrigoriev.yfin.request.Interval;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

/**
 * Price bars and corporate actions of one symbol, all sorted by time.
 *
 * @param exchangeZone the exchange's timezone; every timestamp is in this zone
 * @param interval the bar size returned (the requested one)
 * @param currency the price currency, empty when Yahoo does not report one
 * @param dividends empty when actions were not requested
 * @param splits empty when actions were not requested
 * @param capitalGains empty when actions were not requested or the symbol is not a fund or ETF
 * @param metadata chart metadata returned with the bars
 */
public record PriceHistory(
        String symbol,
        ZoneId exchangeZone,
        Interval interval,
        String currency,
        List<Bar> bars,
        List<Dividend> dividends,
        List<Split> splits,
        List<CapitalGain> capitalGains,
        HistoryMetadata metadata) {

    public PriceHistory {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(exchangeZone, "exchangeZone");
        Objects.requireNonNull(interval, "interval");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(metadata, "metadata");
        bars = List.copyOf(bars);
        dividends = List.copyOf(dividends);
        splits = List.copyOf(splits);
        capitalGains = List.copyOf(capitalGains);
    }

    /** Whether there are no bars. */
    public boolean isEmpty() {
        return bars.isEmpty();
    }

    /** Dividends, splits and capital gains together. */
    public Actions actions() {
        return new Actions(dividends, splits, capitalGains);
    }
}
