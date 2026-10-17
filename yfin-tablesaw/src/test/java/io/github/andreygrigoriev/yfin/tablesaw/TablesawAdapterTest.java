package io.github.andreygrigoriev.yfin.tablesaw;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.andreygrigoriev.yfin.model.Bar;
import io.github.andreygrigoriev.yfin.model.CapitalGain;
import io.github.andreygrigoriev.yfin.model.Dividend;
import io.github.andreygrigoriev.yfin.model.EarningsDate;
import io.github.andreygrigoriev.yfin.model.EarningsEvent;
import io.github.andreygrigoriev.yfin.model.EconomicEvent;
import io.github.andreygrigoriev.yfin.model.FinancialStatement;
import io.github.andreygrigoriev.yfin.model.HistoryMetadata;
import io.github.andreygrigoriev.yfin.model.Holder;
import io.github.andreygrigoriev.yfin.model.IpoEvent;
import io.github.andreygrigoriev.yfin.model.LineItem;
import io.github.andreygrigoriev.yfin.model.OptionContract;
import io.github.andreygrigoriev.yfin.model.PriceHistory;
import io.github.andreygrigoriev.yfin.model.Split;
import io.github.andreygrigoriev.yfin.model.SplitEvent;
import io.github.andreygrigoriev.yfin.request.Frequency;
import io.github.andreygrigoriev.yfin.request.Interval;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import tech.tablesaw.api.ColumnType;
import tech.tablesaw.api.Table;

class TablesawAdapterTest {

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

    @Test
    void priceHistoryColumnsTypesAndOrder() {
        var bars = List.of(
                new Bar(day(3), 1, 2, 0.5, 1.5, 1.4, 100),
                new Bar(day(4), Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0));
        Table t = TablesawAdapter.toTable(history(Interval.ONE_DAY, bars, List.of(), List.of(), List.of()));

        assertThat(t.name()).isEqualTo("AAPL");
        assertThat(t.columnNames()).containsExactly("Date", "Open", "High", "Low", "Close", "Adj Close", "Volume");
        assertThat(t.types())
                .containsExactly(
                        ColumnType.LOCAL_DATE_TIME,
                        ColumnType.DOUBLE,
                        ColumnType.DOUBLE,
                        ColumnType.DOUBLE,
                        ColumnType.DOUBLE,
                        ColumnType.DOUBLE,
                        ColumnType.LONG);
        assertThat(t.rowCount()).isEqualTo(2);
        assertThat(t.dateTimeColumn("Date").get(0)).isEqualTo(LocalDateTime.of(2025, 3, 3, 0, 0));
        assertThat(t.doubleColumn("Close").get(0)).isEqualTo(1.5);
        assertThat(t.doubleColumn("Adj Close").get(0)).isEqualTo(1.4);
        assertThat(t.longColumn("Volume").get(0)).isEqualTo(100L);
        assertThat(t.doubleColumn("Open").getDouble(1)).isNaN();
        assertThat(t.doubleColumn("Open").isMissing().size()).isEqualTo(1);
    }

    @Test
    void priceHistoryActionsAndRepairedColumns() {
        var bars = List.of(
                new Bar(day(3), 1, 1, 1, 1, 1, 1),
                new Bar(day(4), 1, 1, 1, 1, 1, 1, true),
                new Bar(day(5), 1, 1, 1, 1, 1, 1));
        Table t = TablesawAdapter.toTable(history(
                Interval.ONE_DAY,
                bars,
                List.of(new Dividend(day(4), 0.25), new Dividend(day(20), 9)),
                List.of(new Split(day(5), 4, 1)),
                List.of(new CapitalGain(day(3), 0.1))));

        assertThat(t.columnNames())
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
        assertThat(t.doubleColumn("Dividends").asDoubleArray()).containsExactly(0, 0.25, 0);
        assertThat(t.doubleColumn("Stock Splits").asDoubleArray()).containsExactly(0, 0, 4);
        assertThat(t.doubleColumn("Capital Gains").asDoubleArray()).containsExactly(0.1, 0, 0);
        assertThat(t.booleanColumn("Repaired?").asList()).containsExactly(false, true, false);
    }

    @Test
    void eventsOnOneBarAreMergedSplitsMultiplied() {
        var bars = List.of(new Bar(day(3), 1, 1, 1, 1, 1, 1), new Bar(day(10), 1, 1, 1, 1, 1, 1));
        var x = TablesawAdapter.toTable(history(
                Interval.ONE_WEEK,
                bars,
                List.of(new Dividend(day(4), 0.25), new Dividend(day(6), 0.5)),
                List.of(new Split(day(4), 2, 1), new Split(day(7), 3, 1)),
                List.of()));

        assertThat(x.doubleColumn("Dividends").asDoubleArray()).containsExactly(0.75, 0);
        assertThat(x.doubleColumn("Stock Splits").asDoubleArray())
                .containsExactly(6, 0); // like yfinance's prod(), not 5
    }

    @Test
    void eventsOutsideEveryBarAreDropped() {
        // daily bars on Mon 3rd and Wed 5th: before the first bar, on the bar-less 4th, after the last
        var bars = List.of(new Bar(day(3), 1, 1, 1, 1, 1, 1), new Bar(day(5), 1, 1, 1, 1, 1, 1));
        var x = TablesawAdapter.toTable(history(
                Interval.ONE_DAY,
                bars,
                List.of(new Dividend(day(2), 1), new Dividend(day(4), 2), new Dividend(day(6), 3)),
                List.of(),
                List.of()));

        assertThat(x.doubleColumn("Dividends").asDoubleArray()).containsExactly(0, 0);
    }

    @Test
    void eventOnABarBoundaryBelongsToTheLaterBar() {
        var bars = List.of(new Bar(day(3), 1, 1, 1, 1, 1, 1), new Bar(day(10), 1, 1, 1, 1, 1, 1));
        var x = TablesawAdapter.toTable(
                history(Interval.ONE_WEEK, bars, List.of(new Dividend(day(10), 0.5)), List.of(), List.of()));

        assertThat(x.doubleColumn("Dividends").asDoubleArray()).containsExactly(0, 0.5);
    }

    @Test
    void monthlyBarsCoverTheirNominal31Days() {
        var jan = ZonedDateTime.of(2025, 1, 1, 0, 0, 0, 0, NY);
        var feb = ZonedDateTime.of(2025, 2, 1, 0, 0, 0, 0, NY);
        var bars = List.of(new Bar(jan, 1, 1, 1, 1, 1, 1), new Bar(feb, 1, 1, 1, 1, 1, 1));
        var x = TablesawAdapter.toTable(history(
                Interval.ONE_MONTH,
                bars,
                List.of(
                        new Dividend(feb.withDayOfMonth(20), 0.5),
                        new Dividend(feb.plusDays(31), 9)), // Mar 4: past Feb 1 + 31 days
                List.of(),
                List.of()));

        assertThat(x.doubleColumn("Dividends").asDoubleArray()).containsExactly(0, 0.5);
    }

    @Test
    void weeklyEventLandsOnContainingBar() {
        var bars = List.of(new Bar(day(3), 1, 1, 1, 1, 1, 1), new Bar(day(10), 1, 1, 1, 1, 1, 1));
        Table t = TablesawAdapter.toTable(
                history(Interval.ONE_WEEK, bars, List.of(new Dividend(day(5), 0.5)), List.of(), List.of()));

        assertThat(t.doubleColumn("Dividends").asDoubleArray()).containsExactly(0.5, 0);
    }

    @Test
    void intradayUsesDatetimeAndFirstBarOfEventDay() {
        var bars = List.of(
                new Bar(at(3, 15, 30), 1, 1, 1, 1, 1, 1),
                new Bar(at(4, 9, 30), 1, 1, 1, 1, 1, 1),
                new Bar(at(4, 9, 31), 1, 1, 1, 1, 1, 1));
        Table t = TablesawAdapter.toTable(
                history(Interval.ONE_MINUTE, bars, List.of(new Dividend(day(4), 0.3)), List.of(), List.of()));

        assertThat(t.column(0).name()).isEqualTo("Datetime");
        assertThat(t.dateTimeColumn("Datetime").get(1)).isEqualTo(LocalDateTime.of(2025, 3, 4, 9, 30));
        assertThat(t.doubleColumn("Dividends").asDoubleArray()).containsExactly(0, 0.3, 0);
    }

    @Test
    void emptyHistoryGivesEmptyTableWithColumns() {
        Table t = TablesawAdapter.toTable(history(Interval.ONE_DAY, List.of(), List.of(), List.of(), List.of()));

        assertThat(t.rowCount()).isZero();
        assertThat(t.columnNames()).containsExactly("Date", "Open", "High", "Low", "Close", "Adj Close", "Volume");
    }

    @Test
    void statementRowsAreLineItemsColumnsArePeriods() {
        LocalDate newer = LocalDate.of(2025, 9, 30);
        LocalDate older = LocalDate.of(2024, 9, 30);
        var rows = new LinkedHashMap<LineItem, SortedMap<LocalDate, Double>>();
        rows.put(LineItem.TOTAL_REVENUE, new TreeMap<>(Map.of(newer, 100.0, older, 90.0)));
        rows.put(LineItem.NET_INCOME, new TreeMap<>(Map.of(newer, 10.0)));
        var statement = new FinancialStatement("AAPL", Frequency.ANNUAL, "USD", List.of(older, newer), rows);

        Table t = TablesawAdapter.toTable(statement);

        assertThat(t.name()).isEqualTo("AAPL");
        assertThat(t.columnNames()).containsExactly("Line Item", "2025-09-30", "2024-09-30");
        assertThat(t.types()).containsExactly(ColumnType.STRING, ColumnType.DOUBLE, ColumnType.DOUBLE);
        assertThat(t.stringColumn("Line Item").asList()).containsExactly("TotalRevenue", "NetIncome");
        assertThat(t.doubleColumn("2025-09-30").asDoubleArray()).containsExactly(100, 10);
        assertThat(t.doubleColumn("2024-09-30").getDouble(0)).isEqualTo(90);
        assertThat(t.doubleColumn("2024-09-30").getDouble(1)).isNaN();
    }

    @Test
    void emptyStatementGivesEmptyTable() {
        Table t =
                TablesawAdapter.toTable(new FinancialStatement("SPY", Frequency.QUARTERLY, null, List.of(), Map.of()));

        assertThat(t.rowCount()).isZero();
        assertThat(t.columnNames()).containsExactly("Line Item");
    }

    @Test
    void holdersWithMissingFields() {
        Table t = TablesawAdapter.holdersToTable(List.of(
                new Holder(LocalDate.of(2025, 6, 30), "Vanguard", 1000L, 200000L, 0.09, 0.016),
                new Holder(null, null, null, null, null, null)));

        assertThat(t.columnNames())
                .containsExactly("Date Reported", "Holder", "pctHeld", "Shares", "Value", "pctChange");
        assertThat(t.types())
                .containsExactly(
                        ColumnType.LOCAL_DATE,
                        ColumnType.STRING,
                        ColumnType.DOUBLE,
                        ColumnType.LONG,
                        ColumnType.LONG,
                        ColumnType.DOUBLE);
        assertThat(t.dateColumn("Date Reported").get(0)).isEqualTo(LocalDate.of(2025, 6, 30));
        assertThat(t.longColumn("Shares").get(0)).isEqualTo(1000L);
        assertThat(t.dateColumn("Date Reported").isMissing(1)).isTrue();
        assertThat(t.longColumn("Shares").isMissing(1)).isTrue();
        assertThat(t.doubleColumn("pctHeld").getDouble(1)).isNaN();
        assertThat(TablesawAdapter.holdersToTable(List.of()).rowCount()).isZero();
    }

    @Test
    void optionContracts() {
        var traded = ZonedDateTime.of(2025, 3, 3, 15, 59, 0, 0, NY);
        Table t = TablesawAdapter.optionsToTable(List.of(
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

        assertThat(t.columnNames())
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
        assertThat(t.dateTimeColumn("lastTradeDate").get(0)).isEqualTo(traded.toLocalDateTime());
        assertThat(t.dateTimeColumn("lastTradeDate").isMissing(1)).isTrue();
        assertThat(t.doubleColumn("bid").getDouble(0)).isNaN();
        assertThat(t.booleanColumn("inTheMoney").asList()).containsExactly(true, false);
        assertThat(t.stringColumn("contractSize").isMissing(1)).isTrue();
        assertThat(TablesawAdapter.optionsToTable(List.of()).rowCount()).isZero();
    }

    @Test
    void earningsDates() {
        Table t = TablesawAdapter.earningsDatesToTable(List.of(
                new EarningsDate(at(30, 16, 30), 1.6, Double.NaN, Double.NaN),
                new EarningsDate(at(3, 16, 30), 1.5, 1.6, 6.67)));

        assertThat(t.columnNames()).containsExactly("Earnings Date", "EPS Estimate", "Reported EPS", "Surprise(%)");
        assertThat(t.types())
                .containsExactly(ColumnType.LOCAL_DATE_TIME, ColumnType.DOUBLE, ColumnType.DOUBLE, ColumnType.DOUBLE);
        assertThat(t.doubleColumn("Reported EPS").getDouble(0)).isNaN();
        assertThat(t.doubleColumn("Surprise(%)").getDouble(1)).isEqualTo(6.67);
        assertThat(TablesawAdapter.earningsDatesToTable(List.of()).rowCount()).isZero();
    }

    @Test
    void earningsCalendar() {
        Table t = TablesawAdapter.earningsCalendarToTable(List.of(
                new EarningsEvent(
                        "AAPL",
                        "Apple Inc.",
                        3.5e12,
                        "Q1 2025 Earnings",
                        at(30, 16, 30),
                        "AMC",
                        1.6,
                        Double.NaN,
                        Double.NaN),
                new EarningsEvent("XYZ", null, 1e9, null, at(3, 8, 0), null, 0.5, 0.6, 20.0)));

        assertThat(t.name()).isEqualTo("earnings_calendar");
        assertThat(t.columnNames())
                .containsExactly(
                        "Symbol",
                        "Company",
                        "Marketcap",
                        "Event Name",
                        "Event Start Date",
                        "Timing",
                        "EPS Estimate",
                        "Reported EPS",
                        "Surprise(%)");
        assertThat(t.types())
                .containsExactly(
                        ColumnType.STRING,
                        ColumnType.STRING,
                        ColumnType.DOUBLE,
                        ColumnType.STRING,
                        ColumnType.LOCAL_DATE_TIME,
                        ColumnType.STRING,
                        ColumnType.DOUBLE,
                        ColumnType.DOUBLE,
                        ColumnType.DOUBLE);
        assertThat(t.stringColumn("Symbol").get(0)).isEqualTo("AAPL");
        assertThat(t.dateTimeColumn("Event Start Date").get(0)).isEqualTo(LocalDateTime.of(2025, 3, 30, 16, 30));
        assertThat(t.stringColumn("Timing").get(0)).isEqualTo("AMC");
        assertThat(t.doubleColumn("Reported EPS").getDouble(0)).isNaN();
        assertThat(t.stringColumn("Company").isMissing(1)).isTrue();
        assertThat(t.stringColumn("Event Name").isMissing(1)).isTrue();
        assertThat(t.stringColumn("Timing").isMissing(1)).isTrue();
        assertThat(t.doubleColumn("Surprise(%)").getDouble(1)).isEqualTo(20.0);
        Table empty = TablesawAdapter.earningsCalendarToTable(List.of());
        assertThat(empty.rowCount()).isZero();
        assertThat(empty.columnCount()).isEqualTo(9);
    }

    @Test
    void ipoCalendar() {
        Table t = TablesawAdapter.ipoCalendarToTable(List.of(
                new IpoEvent(
                        "NEWC",
                        "NewCo",
                        "NASDAQ",
                        LocalDate.of(2025, 3, 1),
                        LocalDate.of(2025, 3, 10),
                        null,
                        10,
                        12,
                        Double.NaN,
                        "USD",
                        5e6,
                        "Expected"),
                new IpoEvent(
                        "OLDC", null, null, null, null, null, Double.NaN, Double.NaN, 11, null, Double.NaN, null)));

        assertThat(t.name()).isEqualTo("ipo_calendar");
        assertThat(t.columnNames())
                .containsExactly(
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
                        "Action");
        assertThat(t.types())
                .containsExactly(
                        ColumnType.STRING,
                        ColumnType.STRING,
                        ColumnType.STRING,
                        ColumnType.LOCAL_DATE,
                        ColumnType.LOCAL_DATE,
                        ColumnType.LOCAL_DATE,
                        ColumnType.DOUBLE,
                        ColumnType.DOUBLE,
                        ColumnType.DOUBLE,
                        ColumnType.STRING,
                        ColumnType.DOUBLE,
                        ColumnType.STRING);
        assertThat(t.dateColumn("Date").get(0)).isEqualTo(LocalDate.of(2025, 3, 10));
        assertThat(t.dateColumn("Amended Date").isMissing(0)).isTrue();
        assertThat(t.doubleColumn("Price To").getDouble(0)).isEqualTo(12.0);
        assertThat(t.doubleColumn("Price").getDouble(0)).isNaN();
        assertThat(t.stringColumn("Action").get(0)).isEqualTo("Expected");
        assertThat(t.dateColumn("Filing Date").isMissing(1)).isTrue();
        assertThat(t.stringColumn("Currency").isMissing(1)).isTrue();
        assertThat(t.doubleColumn("Price").getDouble(1)).isEqualTo(11.0);
        Table empty = TablesawAdapter.ipoCalendarToTable(List.of());
        assertThat(empty.rowCount()).isZero();
        assertThat(empty.columnCount()).isEqualTo(12);
    }

    @Test
    void economicEventsCalendar() {
        Instant time = Instant.parse("2025-03-12T12:30:00Z");
        Table t = TablesawAdapter.economicEventsToTable(List.of(
                new EconomicEvent("CPI", "US", time, "Feb", 2.8, 2.9, 3.0, Double.NaN),
                new EconomicEvent("GDP", null, time, null, Double.NaN, Double.NaN, Double.NaN, Double.NaN)));

        assertThat(t.name()).isEqualTo("economic_events_calendar");
        assertThat(t.columnNames())
                .containsExactly("Event", "Region", "Event Time", "For", "Actual", "Expected", "Last", "Revised");
        assertThat(t.types())
                .containsExactly(
                        ColumnType.STRING,
                        ColumnType.STRING,
                        ColumnType.INSTANT,
                        ColumnType.STRING,
                        ColumnType.DOUBLE,
                        ColumnType.DOUBLE,
                        ColumnType.DOUBLE,
                        ColumnType.DOUBLE);
        assertThat(t.instantColumn("Event Time").get(0)).isEqualTo(time);
        assertThat(t.stringColumn("For").get(0)).isEqualTo("Feb");
        assertThat(t.doubleColumn("Expected").getDouble(0)).isEqualTo(2.9);
        assertThat(t.doubleColumn("Revised").getDouble(0)).isNaN();
        assertThat(t.stringColumn("Region").isMissing(1)).isTrue();
        assertThat(t.stringColumn("For").isMissing(1)).isTrue();
        Table empty = TablesawAdapter.economicEventsToTable(List.of());
        assertThat(empty.rowCount()).isZero();
        assertThat(empty.columnCount()).isEqualTo(8);
    }

    @Test
    void splitsCalendar() {
        Table t = TablesawAdapter.splitsCalendarToTable(List.of(
                new SplitEvent("NVDA", "NVIDIA", LocalDate.of(2025, 3, 10), true, 1, 10),
                new SplitEvent("XYZ", null, LocalDate.of(2025, 3, 11), null, 3, 1)));

        assertThat(t.name()).isEqualTo("splits_calendar");
        assertThat(t.columnNames())
                .containsExactly("Symbol", "Company", "Payable On", "Optionable", "Old Share Worth", "Share Worth");
        assertThat(t.types())
                .containsExactly(
                        ColumnType.STRING,
                        ColumnType.STRING,
                        ColumnType.LOCAL_DATE,
                        ColumnType.BOOLEAN,
                        ColumnType.DOUBLE,
                        ColumnType.DOUBLE);
        assertThat(t.dateColumn("Payable On").get(0)).isEqualTo(LocalDate.of(2025, 3, 10));
        assertThat(t.booleanColumn("Optionable").get(0)).isTrue();
        assertThat(t.doubleColumn("Share Worth").getDouble(0)).isEqualTo(10.0);
        assertThat(t.stringColumn("Company").isMissing(1)).isTrue();
        assertThat(t.booleanColumn("Optionable").isMissing(1)).isTrue();
        Table empty = TablesawAdapter.splitsCalendarToTable(List.of());
        assertThat(empty.rowCount()).isZero();
        assertThat(empty.columnCount()).isEqualTo(6);
    }
}
