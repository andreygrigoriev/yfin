package io.andreygrigoriev.yfin.tablesaw;

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
}
