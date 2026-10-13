package io.andreygrigoriev.yfin.dflib;

import static org.assertj.core.api.Assertions.assertThat;

import io.andreygrigoriev.yfin.model.Bar;
import io.andreygrigoriev.yfin.model.CapitalGain;
import io.andreygrigoriev.yfin.model.Dividend;
import io.andreygrigoriev.yfin.model.EarningsDate;
import io.andreygrigoriev.yfin.model.FinancialStatement;
import io.andreygrigoriev.yfin.model.HistoryMetadata;
import io.andreygrigoriev.yfin.model.Holder;
import io.andreygrigoriev.yfin.model.LineItem;
import io.andreygrigoriev.yfin.model.OptionContract;
import io.andreygrigoriev.yfin.model.PriceHistory;
import io.andreygrigoriev.yfin.model.Split;
import io.andreygrigoriev.yfin.request.Frequency;
import io.andreygrigoriev.yfin.request.Interval;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import org.dflib.BooleanSeries;
import org.dflib.DataFrame;
import org.dflib.DoubleSeries;
import org.dflib.LongSeries;
import org.dflib.Series;
import org.junit.jupiter.api.Test;

class DFLibAdapterTest {

    private static final ZoneId NY = ZoneId.of("America/New_York");

    private static ZonedDateTime day(int d) {
        return ZonedDateTime.of(2025, 3, d, 0, 0, 0, 0, NY);
    }

    private static ZonedDateTime at(int d, int hour, int minute) {
        return ZonedDateTime.of(2025, 3, d, hour, minute, 0, 0, NY);
    }

    private static PriceHistory history(
            Interval interval, List<Bar> bars, List<Dividend> dividends, List<Split> splits, List<CapitalGain> gains) {
        var meta = new HistoryMetadata(
                "AAPL", "USD", null, null, null, NY, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, List.of(), Map.of());
        return new PriceHistory("AAPL", NY, interval, "USD", bars, dividends, splits, gains, meta);
    }

    private static List<String> names(DataFrame df) {
        return List.of(df.getColumnsIndex().toArray());
    }

    private static double[] doubles(DataFrame df, String column) {
        return ((DoubleSeries) (Series<?>) df.getColumn(column)).toDoubleArray();
    }

    @Test
    void priceHistoryColumnsTypesAndOrder() {
        var bars = List.of(
                new Bar(day(3), 1, 2, 0.5, 1.5, 1.4, 100),
                new Bar(day(4), Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0));
        DataFrame df = DFLibAdapter.toDataFrame(history(Interval.ONE_DAY, bars, List.of(), List.of(), List.of()));

        assertThat(names(df)).containsExactly("Date", "Open", "High", "Low", "Close", "Adj Close", "Volume");
        assertThat(df.getColumn("Date").get(0)).isEqualTo(day(3));
        for (String c : List.of("Open", "High", "Low", "Close", "Adj Close")) {
            assertThat(df.getColumn(c)).isInstanceOf(DoubleSeries.class);
        }
        assertThat(df.getColumn("Volume")).isInstanceOf(LongSeries.class);
        assertThat(df.height()).isEqualTo(2);
        assertThat(doubles(df, "Close")[0]).isEqualTo(1.5);
        assertThat(doubles(df, "Adj Close")[0]).isEqualTo(1.4);
        assertThat(((LongSeries) (Series<?>) df.getColumn("Volume")).getLong(0)).isEqualTo(100L);
        assertThat(doubles(df, "Open")[1]).isNaN();
    }

    @Test
    void priceHistoryActionsAndRepairedColumns() {
        var bars = List.of(
                new Bar(day(3), 1, 1, 1, 1, 1, 1),
                new Bar(day(4), 1, 1, 1, 1, 1, 1, true),
                new Bar(day(5), 1, 1, 1, 1, 1, 1));
        DataFrame df = DFLibAdapter.toDataFrame(history(
                Interval.ONE_DAY,
                bars,
                List.of(new Dividend(day(4), 0.25), new Dividend(day(20), 9)),
                List.of(new Split(day(5), 4, 1)),
                List.of(new CapitalGain(day(3), 0.1))));

        assertThat(names(df))
                .containsExactly(
                        "Date",
                        "Open",
                        "High",
                        "Low",
                        "Close",
                        "Adj Close",
                        "Volume",
                        "Dividends",
                        "Stock Splits",
                        "Capital Gains",
                        "Repaired?");
        assertThat(doubles(df, "Dividends")).containsExactly(0, 0.25, 0);
        assertThat(doubles(df, "Stock Splits")).containsExactly(0, 0, 4);
        assertThat(doubles(df, "Capital Gains")).containsExactly(0.1, 0, 0);
        assertThat(df.getColumn("Repaired?")).isInstanceOf(BooleanSeries.class);
        assertThat(df.getColumn("Repaired?").toList()).containsExactly(false, true, false);
    }

    @Test
    void eventsOnOneBarAreMergedSplitsMultiplied() {
        var bars = List.of(new Bar(day(3), 1, 1, 1, 1, 1, 1), new Bar(day(10), 1, 1, 1, 1, 1, 1));
        var x = DFLibAdapter.toDataFrame(history(
                Interval.ONE_WEEK,
                bars,
                List.of(new Dividend(day(4), 0.25), new Dividend(day(6), 0.5)),
                List.of(new Split(day(4), 2, 1), new Split(day(7), 3, 1)),
                List.of()));

        assertThat(doubles(x, "Dividends")).containsExactly(0.75, 0);
        assertThat(doubles(x, "Stock Splits")).containsExactly(6, 0); // like yfinance's prod(), not 5
    }

    @Test
    void eventsOutsideEveryBarAreDropped() {
        // daily bars on Mon 3rd and Wed 5th: before the first bar, on the bar-less 4th, after the last
        var bars = List.of(new Bar(day(3), 1, 1, 1, 1, 1, 1), new Bar(day(5), 1, 1, 1, 1, 1, 1));
        var x = DFLibAdapter.toDataFrame(history(
                Interval.ONE_DAY,
                bars,
                List.of(new Dividend(day(2), 1), new Dividend(day(4), 2), new Dividend(day(6), 3)),
                List.of(),
                List.of()));

        assertThat(doubles(x, "Dividends")).containsExactly(0, 0);
    }

    @Test
    void eventOnABarBoundaryBelongsToTheLaterBar() {
        var bars = List.of(new Bar(day(3), 1, 1, 1, 1, 1, 1), new Bar(day(10), 1, 1, 1, 1, 1, 1));
        var x = DFLibAdapter.toDataFrame(
                history(Interval.ONE_WEEK, bars, List.of(new Dividend(day(10), 0.5)), List.of(), List.of()));

        assertThat(doubles(x, "Dividends")).containsExactly(0, 0.5);
    }

    @Test
    void monthlyBarsCoverTheirNominal31Days() {
        var jan = ZonedDateTime.of(2025, 1, 1, 0, 0, 0, 0, NY);
        var feb = ZonedDateTime.of(2025, 2, 1, 0, 0, 0, 0, NY);
        var bars = List.of(new Bar(jan, 1, 1, 1, 1, 1, 1), new Bar(feb, 1, 1, 1, 1, 1, 1));
        var x = DFLibAdapter.toDataFrame(history(
                Interval.ONE_MONTH,
                bars,
                List.of(
                        new Dividend(feb.withDayOfMonth(20), 0.5),
                        new Dividend(feb.plusDays(31), 9)), // Mar 4: past Feb 1 + 31 days
                List.of(),
                List.of()));

        assertThat(doubles(x, "Dividends")).containsExactly(0, 0.5);
    }

    @Test
    void weeklyEventLandsOnContainingBar() {
        var bars = List.of(new Bar(day(3), 1, 1, 1, 1, 1, 1), new Bar(day(10), 1, 1, 1, 1, 1, 1));
        DataFrame df = DFLibAdapter.toDataFrame(
                history(Interval.ONE_WEEK, bars, List.of(new Dividend(day(5), 0.5)), List.of(), List.of()));

        assertThat(doubles(df, "Dividends")).containsExactly(0.5, 0);
    }

    @Test
    void intradayUsesDatetimeAndFirstBarOfEventDay() {
        var bars = List.of(
                new Bar(at(3, 15, 30), 1, 1, 1, 1, 1, 1),
                new Bar(at(4, 9, 30), 1, 1, 1, 1, 1, 1),
                new Bar(at(4, 9, 31), 1, 1, 1, 1, 1, 1));
        DataFrame df = DFLibAdapter.toDataFrame(
                history(Interval.ONE_MINUTE, bars, List.of(new Dividend(day(4), 0.3)), List.of(), List.of()));

        assertThat(names(df).getFirst()).isEqualTo("Datetime");
        assertThat(df.getColumn("Datetime").get(1)).isEqualTo(at(4, 9, 30));
        assertThat(doubles(df, "Dividends")).containsExactly(0, 0.3, 0);
    }

    @Test
    void emptyHistoryGivesEmptyFrameWithColumns() {
        DataFrame df = DFLibAdapter.toDataFrame(history(Interval.ONE_DAY, List.of(), List.of(), List.of(), List.of()));

        assertThat(df.height()).isZero();
        assertThat(names(df)).containsExactly("Date", "Open", "High", "Low", "Close", "Adj Close", "Volume");
    }

    @Test
    void statementRowsAreLineItemsColumnsArePeriods() {
        LocalDate newer = LocalDate.of(2025, 9, 30);
        LocalDate older = LocalDate.of(2024, 9, 30);
        var rows = new LinkedHashMap<LineItem, SortedMap<LocalDate, Double>>();
        rows.put(LineItem.TOTAL_REVENUE, new TreeMap<>(Map.of(newer, 100.0, older, 90.0)));
        rows.put(LineItem.NET_INCOME, new TreeMap<>(Map.of(newer, 10.0)));
        var statement = new FinancialStatement("AAPL", Frequency.ANNUAL, "USD", List.of(older, newer), rows);

        DataFrame df = DFLibAdapter.toDataFrame(statement);

        assertThat(names(df)).containsExactly("Line Item", "2025-09-30", "2024-09-30");
        assertThat(df.getColumn("Line Item").toList()).containsExactly("TotalRevenue", "NetIncome");
        assertThat(df.getColumn("2025-09-30")).isInstanceOf(DoubleSeries.class);
        assertThat(doubles(df, "2025-09-30")).containsExactly(100, 10);
        assertThat(doubles(df, "2024-09-30")[0]).isEqualTo(90);
        assertThat(doubles(df, "2024-09-30")[1]).isNaN();
    }

    @Test
    void emptyStatementGivesEmptyFrame() {
        DataFrame df =
                DFLibAdapter.toDataFrame(new FinancialStatement("SPY", Frequency.QUARTERLY, null, List.of(), Map.of()));

        assertThat(df.height()).isZero();
        assertThat(names(df)).containsExactly("Line Item");
    }

    @Test
    void holdersWithMissingFields() {
        DataFrame df = DFLibAdapter.holdersToDataFrame(List.of(
                new Holder(LocalDate.of(2025, 6, 30), "Vanguard", 1000L, 200000L, 0.09, 0.016),
                new Holder(null, null, null, null, null, null)));

        assertThat(names(df)).containsExactly("Date Reported", "Holder", "pctHeld", "Shares", "Value", "pctChange");
        assertThat(df.getColumn("Date Reported").get(0)).isEqualTo(LocalDate.of(2025, 6, 30));
        assertThat(df.getColumn("Shares").get(0)).isEqualTo(1000L);
        assertThat(df.getColumn("Date Reported").get(1)).isNull();
        assertThat(df.getColumn("Holder").get(1)).isNull();
        assertThat(df.getColumn("Shares").get(1)).isNull();
        assertThat(doubles(df, "pctHeld")[1]).isNaN();
        assertThat(DFLibAdapter.holdersToDataFrame(List.of()).height()).isZero();
    }

    @Test
    void optionContracts() {
        var traded = ZonedDateTime.of(2025, 3, 3, 15, 59, 0, 0, NY);
        DataFrame df = DFLibAdapter.optionsToDataFrame(List.of(
                new OptionContract(
                        "AAPL250321C00200000",
                        200,
                        5,
                        Double.NaN,
                        5.2,
                        0.1,
                        2.0,
                        10,
                        100,
                        0.25,
                        true,
                        traded,
                        "REGULAR",
                        "USD"),
                new OptionContract("AAPL250321C00210000", 210, 1, 0.9, 1.1, 0, 0, 0, 0, 0.3, false, null, null, null)));

        assertThat(names(df))
                .containsExactly(
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
                        "currency");
        assertThat(df.getColumn("lastTradeDate").get(0)).isEqualTo(traded);
        assertThat(df.getColumn("lastTradeDate").get(1)).isNull();
        assertThat(doubles(df, "bid")[0]).isNaN();
        assertThat(df.getColumn("volume")).isInstanceOf(LongSeries.class);
        assertThat(df.getColumn("inTheMoney").toList()).containsExactly(true, false);
        assertThat(df.getColumn("contractSize").get(1)).isNull();
        assertThat(DFLibAdapter.optionsToDataFrame(List.of()).height()).isZero();
    }

    @Test
    void earningsDates() {
        DataFrame df = DFLibAdapter.earningsDatesToDataFrame(List.of(
                new EarningsDate(at(30, 16, 30), 1.6, Double.NaN, Double.NaN),
                new EarningsDate(at(3, 16, 30), 1.5, 1.6, 6.67)));

        assertThat(names(df)).containsExactly("Earnings Date", "EPS Estimate", "Reported EPS", "Surprise(%)");
        assertThat(df.getColumn("Earnings Date").get(0)).isEqualTo(at(30, 16, 30));
        assertThat(doubles(df, "Reported EPS")[0]).isNaN();
        assertThat(doubles(df, "Surprise(%)")[1]).isEqualTo(6.67);
        assertThat(DFLibAdapter.earningsDatesToDataFrame(List.of()).height()).isZero();
    }
}
