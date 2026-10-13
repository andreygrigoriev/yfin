package io.andreygrigoriev.yfin.tablesaw;

import io.andreygrigoriev.yfin.model.Bar;
import io.andreygrigoriev.yfin.model.CapitalGain;
import io.andreygrigoriev.yfin.model.Dividend;
import io.andreygrigoriev.yfin.model.EarningsDate;
import io.andreygrigoriev.yfin.model.FinancialStatement;
import io.andreygrigoriev.yfin.model.Holder;
import io.andreygrigoriev.yfin.model.LineItem;
import io.andreygrigoriev.yfin.model.OptionContract;
import io.andreygrigoriev.yfin.model.PriceHistory;
import io.andreygrigoriev.yfin.model.Split;
import io.andreygrigoriev.yfin.request.Interval;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.function.DoubleBinaryOperator;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;
import org.jspecify.annotations.Nullable;
import tech.tablesaw.api.BooleanColumn;
import tech.tablesaw.api.DateColumn;
import tech.tablesaw.api.DateTimeColumn;
import tech.tablesaw.api.DoubleColumn;
import tech.tablesaw.api.LongColumn;
import tech.tablesaw.api.StringColumn;
import tech.tablesaw.api.Table;

/**
 * Converts yfin results into Tablesaw {@link Table}s shaped like the corresponding yfinance
 * DataFrames, with yfinance column names.
 *
 * <p>Tablesaw has no zoned date-time column: timestamps become {@link DateTimeColumn}s holding the
 * local date-time in the exchange timezone (the zone the records already use). Missing prices stay
 * {@link Double#NaN}, which Tablesaw treats as missing; missing integers and dates are appended as
 * Tablesaw missing values. Every method returns a new table and accepts empty input, returning a
 * table with all columns and no rows.
 */
public final class TablesawAdapter {

    private TablesawAdapter() {}

    /**
     * Price history as yfinance's {@code history()} DataFrame: {@code Date} ({@code Datetime} for
     * intraday intervals), {@code Open}, {@code High}, {@code Low}, {@code Close}, {@code Adj Close},
     * {@code Volume}, then {@code Dividends}, {@code Stock Splits} and {@code Capital Gains} when the
     * history has any such event, and {@code Repaired?} when price repair changed any bar.
     *
     * <p>Event columns are {@code 0} on bars without an event. An event lands on the bar containing
     * it: for intraday intervals the first bar of the event's date, otherwise the last bar starting at
     * or before the event, if the event falls within that bar's nominal duration. Other events (outside
     * the bars' range, or on a day without a bar) are dropped. The table is named after the
     * symbol.
     */
    public static Table toTable(PriceHistory history) {
        Objects.requireNonNull(history, "history");
        List<Bar> bars = history.bars();
        int n = bars.size();
        Interval interval = history.interval();
        boolean intraday = interval.isIntraday();
        var time = DateTimeColumn.create(intraday ? "Datetime" : "Date");
        var open = DoubleColumn.create("Open");
        var high = DoubleColumn.create("High");
        var low = DoubleColumn.create("Low");
        var close = DoubleColumn.create("Close");
        var adjClose = DoubleColumn.create("Adj Close");
        var volume = LongColumn.create("Volume");
        boolean anyRepaired = false;
        for (Bar bar : bars) {
            time.append(bar.time().toLocalDateTime());
            open.append(bar.open());
            high.append(bar.high());
            low.append(bar.low());
            close.append(bar.close());
            adjClose.append(bar.adjClose());
            volume.append(bar.volume());
            anyRepaired |= bar.repaired();
        }
        Table table = Table.create(history.symbol(), time, open, high, low, close, adjClose, volume);
        if (!history.dividends().isEmpty()) {
            table.addColumns(eventColumn(
                    "Dividends",
                    bars,
                    interval,
                    history.dividends(),
                    Dividend::time,
                    Dividend::amount,
                    TablesawAdapter::sum));
        }
        if (!history.splits().isEmpty()) {
            table.addColumns(eventColumn(
                    "Stock Splits",
                    bars,
                    interval,
                    history.splits(),
                    Split::time,
                    Split::ratio,
                    TablesawAdapter::product));
        }
        if (!history.capitalGains().isEmpty()) {
            table.addColumns(eventColumn(
                    "Capital Gains",
                    bars,
                    interval,
                    history.capitalGains(),
                    CapitalGain::time,
                    CapitalGain::amount,
                    TablesawAdapter::sum));
        }
        if (anyRepaired) {
            var repaired = BooleanColumn.create("Repaired?", n);
            for (int i = 0; i < n; i++) {
                repaired.set(i, bars.get(i).repaired());
            }
            table.addColumns(repaired);
        }
        return table;
    }

    /**
     * A financial statement with one row per line item (Yahoo website order) and one {@code double}
     * column per period, newest first, named by the ISO period end date ({@code 2025-09-30}). The
     * first column, {@code Line Item}, holds the Yahoo key ({@code TotalRevenue}). Missing values are
     * {@code NaN}. The table is named after the symbol.
     */
    public static Table toTable(FinancialStatement statement) {
        Objects.requireNonNull(statement, "statement");
        var items = StringColumn.create("Line Item");
        List<LocalDate> periods = statement.periods();
        var values = new DoubleColumn[periods.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = DoubleColumn.create(periods.get(i).toString());
        }
        for (Map.Entry<LineItem, SortedMap<LocalDate, Double>> row :
                statement.rows().entrySet()) {
            items.append(row.getKey().key());
            for (int i = 0; i < values.length; i++) {
                Double v = row.getValue().get(periods.get(i));
                values[i].append(v == null ? Double.NaN : v);
            }
        }
        Table table = Table.create(statement.symbol(), items);
        table.addColumns(values);
        return table;
    }

    /**
     * Institutional or mutual fund holders as yfinance's {@code institutional_holders} /
     * {@code mutualfund_holders}: {@code Date Reported}, {@code Holder}, {@code pctHeld}, {@code
     * Shares}, {@code Value}, {@code pctChange}.
     */
    public static Table holdersToTable(List<Holder> holders) {
        var date = DateColumn.create("Date Reported");
        var holder = StringColumn.create("Holder");
        var pctHeld = DoubleColumn.create("pctHeld");
        var shares = LongColumn.create("Shares");
        var value = LongColumn.create("Value");
        var pctChange = DoubleColumn.create("pctChange");
        for (Holder h : holders) {
            if (h.dateReported() == null) {
                date.appendMissing();
            } else {
                date.append(h.dateReported());
            }
            if (h.holder() == null) {
                holder.appendMissing();
            } else {
                holder.append(h.holder());
            }
            pctHeld.append(orNaN(h.pctHeld()));
            appendLong(shares, h.shares());
            appendLong(value, h.value());
            pctChange.append(orNaN(h.pctChange()));
        }
        return Table.create("holders", date, holder, pctHeld, shares, value, pctChange);
    }

    /**
     * Option contracts, e.g. {@code chain.calls()}, as yfinance's {@code option_chain().calls}:
     * {@code contractSymbol}, {@code lastTradeDate}, {@code strike}, {@code lastPrice}, {@code bid},
     * {@code ask}, {@code change}, {@code percentChange}, {@code volume}, {@code openInterest},
     * {@code impliedVolatility}, {@code inTheMoney}, {@code contractSize}, {@code currency}.
     */
    public static Table optionsToTable(List<OptionContract> contracts) {
        var symbol = StringColumn.create("contractSymbol");
        var lastTradeDate = DateTimeColumn.create("lastTradeDate");
        var strike = DoubleColumn.create("strike");
        var lastPrice = DoubleColumn.create("lastPrice");
        var bid = DoubleColumn.create("bid");
        var ask = DoubleColumn.create("ask");
        var change = DoubleColumn.create("change");
        var percentChange = DoubleColumn.create("percentChange");
        var volume = LongColumn.create("volume");
        var openInterest = LongColumn.create("openInterest");
        var impliedVolatility = DoubleColumn.create("impliedVolatility");
        var inTheMoney = BooleanColumn.create("inTheMoney");
        var contractSize = StringColumn.create("contractSize");
        var currency = StringColumn.create("currency");
        for (OptionContract c : contracts) {
            symbol.append(c.contractSymbol());
            ZonedDateTime traded = c.lastTradeDate();
            if (traded == null) {
                lastTradeDate.appendMissing();
            } else {
                lastTradeDate.append(traded.toLocalDateTime());
            }
            strike.append(c.strike());
            lastPrice.append(c.lastPrice());
            bid.append(c.bid());
            ask.append(c.ask());
            change.append(c.change());
            percentChange.append(c.percentChange());
            volume.append(c.volume());
            openInterest.append(c.openInterest());
            impliedVolatility.append(c.impliedVolatility());
            inTheMoney.append(c.inTheMoney());
            appendString(contractSize, c.contractSize());
            appendString(currency, c.currency());
        }
        return Table.create(
                "options",
                symbol,
                lastTradeDate,
                strike,
                lastPrice,
                bid,
                ask,
                change,
                percentChange,
                volume,
                openInterest,
                impliedVolatility,
                inTheMoney,
                contractSize,
                currency);
    }

    /**
     * Earnings dates as yfinance's {@code earnings_dates}: {@code Earnings Date}, {@code EPS
     * Estimate}, {@code Reported EPS}, {@code Surprise(%)}.
     */
    public static Table earningsDatesToTable(List<EarningsDate> dates) {
        var date = DateTimeColumn.create("Earnings Date");
        var estimate = DoubleColumn.create("EPS Estimate");
        var reported = DoubleColumn.create("Reported EPS");
        var surprise = DoubleColumn.create("Surprise(%)");
        for (EarningsDate d : dates) {
            date.append(d.date().toLocalDateTime());
            estimate.append(d.epsEstimate());
            reported.append(d.reportedEps());
            surprise.append(d.surprisePercent());
        }
        return Table.create("earnings_dates", date, estimate, reported, surprise);
    }

    private static <E> DoubleColumn eventColumn(
            String name,
            List<Bar> bars,
            Interval interval,
            List<E> events,
            Function<E, ZonedDateTime> timeOf,
            ToDoubleFunction<E> valueOf,
            DoubleBinaryOperator merge) {
        double[] values = new double[bars.size()];
        for (E event : events) {
            int i = barIndex(bars, timeOf.apply(event), interval);
            if (i >= 0) {
                values[i] = merge.applyAsDouble(values[i], valueOf.applyAsDouble(event));
            }
        }
        return DoubleColumn.create(name, values);
    }

    /** Sums amounts (dividends, capital gains) of events that fall on one bar. */
    private static double sum(double merged, double value) {
        return merged + value;
    }

    /**
     * Multiplies split ratios that fall on one bar, as yfinance's {@code replace(0, 1).prod()}:
     * a 2:1 and a 3:1 split in one weekly bar give 6, not 5.
     */
    private static double product(double merged, double value) {
        return merged == 0 ? value : merged * (value == 0 ? 1 : value);
    }

    /** Index of the bar an event belongs to, {@code -1} when none. */
    static int barIndex(List<Bar> bars, ZonedDateTime eventTime, Interval interval) {
        if (interval.isIntraday()) {
            LocalDate day = eventTime.toLocalDate();
            for (int i = 0; i < bars.size(); i++) {
                if (bars.get(i).time().toLocalDate().equals(day)) {
                    return i;
                }
            }
            return -1;
        }
        int found = -1;
        for (int i = 0; i < bars.size(); i++) {
            if (bars.get(i).time().isAfter(eventTime)) {
                break;
            }
            found = i;
        }
        if (found >= 0 && !eventTime.isBefore(bars.get(found).time().plus(interval.nominalDuration()))) {
            return -1;
        }
        return found;
    }

    private static double orNaN(@Nullable Double value) {
        return value == null ? Double.NaN : value;
    }

    private static void appendLong(LongColumn column, @Nullable Long value) {
        if (value == null) {
            column.appendMissing();
        } else {
            column.append(value);
        }
    }

    private static void appendString(StringColumn column, @Nullable String value) {
        if (value == null) {
            column.appendMissing();
        } else {
            column.append(value);
        }
    }
}
