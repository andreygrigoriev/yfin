package io.andreygrigoriev.yfin.dflib;

import io.andreygrigoriev.yfin.model.Bar;
import io.andreygrigoriev.yfin.model.CapitalGain;
import io.andreygrigoriev.yfin.model.Dividend;
import io.andreygrigoriev.yfin.model.EarningsDate;
import io.andreygrigoriev.yfin.model.EarningsEvent;
import io.andreygrigoriev.yfin.model.EconomicEvent;
import io.andreygrigoriev.yfin.model.FinancialStatement;
import io.andreygrigoriev.yfin.model.Holder;
import io.andreygrigoriev.yfin.model.IpoEvent;
import io.andreygrigoriev.yfin.model.OptionContract;
import io.andreygrigoriev.yfin.model.PriceHistory;
import io.andreygrigoriev.yfin.model.Split;
import io.andreygrigoriev.yfin.model.SplitEvent;
import io.andreygrigoriev.yfin.request.Interval;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.DoubleBinaryOperator;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import java.util.function.ToLongFunction;
import org.dflib.BooleanSeries;
import org.dflib.DataFrame;
import org.dflib.DoubleSeries;
import org.dflib.LongSeries;
import org.dflib.Series;
import org.jspecify.annotations.Nullable;

/**
 * Converts yfin results into DFLib {@link DataFrame}s shaped like the corresponding yfinance
 * DataFrames, with yfinance column names.
 *
 * <p>Timestamps stay {@link ZonedDateTime}s in the exchange timezone (the zone the records already
 * use), since DFLib columns hold any object. Prices are primitive {@link DoubleSeries} with missing
 * values kept as {@link Double#NaN}; non-null integers are {@link LongSeries}. Optional values
 * (nullable longs, strings, dates) are object series holding {@code null} when missing, which is
 * DFLib's convention. Every method returns a new data frame and accepts empty input, returning a
 * frame with all columns and no rows.
 */
public final class DFLibAdapter {

    private DFLibAdapter() {}

    /**
     * Price history as yfinance's {@code history()} DataFrame: {@code Date} ({@code Datetime} for
     * intraday intervals), {@code Open}, {@code High}, {@code Low}, {@code Close}, {@code Adj Close},
     * {@code Volume}, then {@code Dividends}, {@code Stock Splits} and {@code Capital Gains} when the
     * history has any such event, and {@code Repaired?} when price repair changed any bar.
     *
     * <p>Event columns are {@code 0} on bars without an event. An event lands on the bar containing
     * it: for intraday intervals the first bar of the event's date, otherwise the last bar starting at
     * or before the event, if the event falls within that bar's nominal duration. Other events (outside
     * the bars' range, or on a day without a bar) are dropped.
     */
    public static DataFrame toDataFrame(PriceHistory history) {
        Objects.requireNonNull(history, "history");
        List<Bar> bars = history.bars();
        Interval interval = history.interval();
        var names = new ArrayList<>(List.of(
                interval.isIntraday() ? "Datetime" : "Date", "Open", "High", "Low", "Close", "Adj Close", "Volume"));
        var columns = new ArrayList<Series<?>>(List.of(
                objects(bars, Bar::time),
                doubles(bars, Bar::open),
                doubles(bars, Bar::high),
                doubles(bars, Bar::low),
                doubles(bars, Bar::close),
                doubles(bars, Bar::adjClose),
                longs(bars, Bar::volume)));
        if (!history.dividends().isEmpty()) {
            names.add("Dividends");
            columns.add(eventSeries(
                    bars, interval, history.dividends(), Dividend::time, Dividend::amount, DFLibAdapter::sum));
        }
        if (!history.splits().isEmpty()) {
            names.add("Stock Splits");
            columns.add(
                    eventSeries(bars, interval, history.splits(), Split::time, Split::ratio, DFLibAdapter::product));
        }
        if (!history.capitalGains().isEmpty()) {
            names.add("Capital Gains");
            columns.add(eventSeries(
                    bars, interval, history.capitalGains(), CapitalGain::time, CapitalGain::amount, DFLibAdapter::sum));
        }
        if (bars.stream().anyMatch(Bar::repaired)) {
            names.add("Repaired?");
            columns.add(booleans(bars, Bar::repaired));
        }
        return frame(names, columns);
    }

    /**
     * A financial statement with one row per line item (Yahoo website order) and one {@code double}
     * column per period, newest first, named by the ISO period end date ({@code 2025-09-30}). The
     * first column, {@code Line Item}, holds the Yahoo key ({@code TotalRevenue}). Missing values are
     * {@code NaN}.
     */
    public static DataFrame toDataFrame(FinancialStatement statement) {
        Objects.requireNonNull(statement, "statement");
        List<LocalDate> periods = statement.periods();
        var rows = List.copyOf(statement.rows().entrySet());
        var names = new ArrayList<String>(periods.size() + 1);
        var columns = new ArrayList<Series<?>>(periods.size() + 1);
        names.add("Line Item");
        columns.add(objects(rows, row -> row.getKey().key()));
        for (LocalDate period : periods) {
            names.add(period.toString());
            columns.add(doubles(rows, row -> orNaN(row.getValue().get(period))));
        }
        return frame(names, columns);
    }

    /**
     * Institutional or mutual fund holders as yfinance's {@code institutional_holders} /
     * {@code mutualfund_holders}: {@code Date Reported}, {@code Holder}, {@code pctHeld}, {@code
     * Shares}, {@code Value}, {@code pctChange}.
     */
    public static DataFrame holdersToDataFrame(List<Holder> holders) {
        return DataFrame.byColumn("Date Reported", "Holder", "pctHeld", "Shares", "Value", "pctChange")
                .of(
                        objects(holders, Holder::dateReported),
                        objects(holders, Holder::holder),
                        doubles(holders, h -> orNaN(h.pctHeld())),
                        objects(holders, Holder::shares),
                        objects(holders, Holder::value),
                        doubles(holders, h -> orNaN(h.pctChange())));
    }

    /**
     * Option contracts, e.g. {@code chain.calls()}, as yfinance's {@code option_chain().calls}:
     * {@code contractSymbol}, {@code lastTradeDate}, {@code strike}, {@code lastPrice}, {@code bid},
     * {@code ask}, {@code change}, {@code percentChange}, {@code volume}, {@code openInterest},
     * {@code impliedVolatility}, {@code inTheMoney}, {@code contractSize}, {@code currency}.
     */
    public static DataFrame optionsToDataFrame(List<OptionContract> contracts) {
        return DataFrame.byColumn(
                        "contractSymbol",
                        "lastTradeDate",
                        "strike",
                        "lastPrice",
                        "bid",
                        "ask",
                        "change",
                        "percentChange",
                        "volume",
                        "openInterest",
                        "impliedVolatility",
                        "inTheMoney",
                        "contractSize",
                        "currency")
                .of(
                        objects(contracts, OptionContract::contractSymbol),
                        objects(contracts, OptionContract::lastTradeDate),
                        doubles(contracts, OptionContract::strike),
                        doubles(contracts, OptionContract::lastPrice),
                        doubles(contracts, OptionContract::bid),
                        doubles(contracts, OptionContract::ask),
                        doubles(contracts, OptionContract::change),
                        doubles(contracts, OptionContract::percentChange),
                        longs(contracts, OptionContract::volume),
                        longs(contracts, OptionContract::openInterest),
                        doubles(contracts, OptionContract::impliedVolatility),
                        booleans(contracts, OptionContract::inTheMoney),
                        objects(contracts, OptionContract::contractSize),
                        objects(contracts, OptionContract::currency));
    }

    /**
     * Earnings dates as yfinance's {@code earnings_dates}: {@code Earnings Date}, {@code EPS
     * Estimate}, {@code Reported EPS}, {@code Surprise(%)}.
     */
    public static DataFrame earningsDatesToDataFrame(List<EarningsDate> dates) {
        return DataFrame.byColumn("Earnings Date", "EPS Estimate", "Reported EPS", "Surprise(%)")
                .of(
                        objects(dates, EarningsDate::date),
                        doubles(dates, EarningsDate::epsEstimate),
                        doubles(dates, EarningsDate::reportedEps),
                        doubles(dates, EarningsDate::surprisePercent));
    }

    /**
     * The earnings calendar as yfinance's {@code Calendars.get_earnings_calendar()}: {@code Symbol},
     * {@code Company}, {@code Marketcap}, {@code Event Name}, {@code Event Start Date} (in {@code
     * America/New_York}), {@code Timing}, {@code EPS Estimate}, {@code Reported EPS}, {@code
     * Surprise(%)}.
     */
    public static DataFrame earningsCalendarToDataFrame(List<EarningsEvent> events) {
        return DataFrame.byColumn(
                        "Symbol",
                        "Company",
                        "Marketcap",
                        "Event Name",
                        "Event Start Date",
                        "Timing",
                        "EPS Estimate",
                        "Reported EPS",
                        "Surprise(%)")
                .of(
                        objects(events, EarningsEvent::symbol),
                        objects(events, EarningsEvent::companyName),
                        doubles(events, EarningsEvent::marketCap),
                        objects(events, EarningsEvent::eventName),
                        objects(events, EarningsEvent::startTime),
                        objects(events, EarningsEvent::timing),
                        doubles(events, EarningsEvent::epsEstimate),
                        doubles(events, EarningsEvent::reportedEps),
                        doubles(events, EarningsEvent::surprisePercent));
    }

    /**
     * The IPO calendar as yfinance's {@code Calendars.get_ipo_info_calendar()}: {@code Symbol},
     * {@code Company}, {@code Exchange}, {@code Filing Date}, {@code Date}, {@code Amended Date},
     * {@code Price From}, {@code Price To}, {@code Price}, {@code Currency}, {@code Shares}, {@code
     * Action}.
     */
    public static DataFrame ipoCalendarToDataFrame(List<IpoEvent> events) {
        return DataFrame.byColumn(
                        "Symbol",
                        "Company",
                        "Exchange",
                        "Filing Date",
                        "Date",
                        "Amended Date",
                        "Price From",
                        "Price To",
                        "Price",
                        "Currency",
                        "Shares",
                        "Action")
                .of(
                        objects(events, IpoEvent::symbol),
                        objects(events, IpoEvent::companyName),
                        objects(events, IpoEvent::exchange),
                        objects(events, IpoEvent::filingDate),
                        objects(events, IpoEvent::date),
                        objects(events, IpoEvent::amendedDate),
                        doubles(events, IpoEvent::priceFrom),
                        doubles(events, IpoEvent::priceTo),
                        doubles(events, IpoEvent::offerPrice),
                        objects(events, IpoEvent::currency),
                        doubles(events, IpoEvent::shares),
                        objects(events, IpoEvent::dealType));
    }

    /**
     * The economic events calendar as yfinance's {@code Calendars.get_economic_events_calendar()}:
     * {@code Event}, {@code Region}, {@code Event Time} (an {@link java.time.Instant}, since the
     * events have no exchange zone), {@code For}, {@code Actual}, {@code Expected}, {@code Last},
     * {@code Revised}.
     */
    public static DataFrame economicEventsToDataFrame(List<EconomicEvent> events) {
        return DataFrame.byColumn("Event", "Region", "Event Time", "For", "Actual", "Expected", "Last", "Revised")
                .of(
                        objects(events, EconomicEvent::event),
                        objects(events, EconomicEvent::region),
                        objects(events, EconomicEvent::time),
                        objects(events, EconomicEvent::period),
                        doubles(events, EconomicEvent::actual),
                        doubles(events, EconomicEvent::expected),
                        doubles(events, EconomicEvent::last),
                        doubles(events, EconomicEvent::revised));
    }

    /**
     * The splits calendar as yfinance's {@code Calendars.get_splits_calendar()}: {@code Symbol},
     * {@code Company}, {@code Payable On}, {@code Optionable} (a nullable {@code Boolean}), {@code Old
     * Share Worth}, {@code Share Worth}.
     */
    public static DataFrame splitsCalendarToDataFrame(List<SplitEvent> events) {
        return DataFrame.byColumn("Symbol", "Company", "Payable On", "Optionable", "Old Share Worth", "Share Worth")
                .of(
                        objects(events, SplitEvent::symbol),
                        objects(events, SplitEvent::companyName),
                        objects(events, SplitEvent::payableDate),
                        objects(events, SplitEvent::optionable),
                        doubles(events, SplitEvent::oldShareWorth),
                        doubles(events, SplitEvent::newShareWorth));
    }

    private static DataFrame frame(List<String> names, List<Series<?>> columns) {
        return DataFrame.byColumn(names.toArray(String[]::new)).of(columns.toArray(Series<?>[]::new));
    }

    private static <E, T> Series<@Nullable T> objects(List<E> items, Function<E, @Nullable T> f) {
        var values = new ArrayList<@Nullable T>(items.size());
        for (E item : items) {
            values.add(f.apply(item));
        }
        return Series.ofIterable(values);
    }

    private static <E> DoubleSeries doubles(List<E> items, ToDoubleFunction<E> f) {
        double[] values = new double[items.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = f.applyAsDouble(items.get(i));
        }
        return Series.ofDouble(values);
    }

    private static <E> LongSeries longs(List<E> items, ToLongFunction<E> f) {
        long[] values = new long[items.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = f.applyAsLong(items.get(i));
        }
        return Series.ofLong(values);
    }

    private static <E> BooleanSeries booleans(List<E> items, Predicate<E> f) {
        boolean[] values = new boolean[items.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = f.test(items.get(i));
        }
        return Series.ofBool(values);
    }

    private static <E> DoubleSeries eventSeries(
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
        return Series.ofDouble(values);
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
}
