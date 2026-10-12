package io.andreygrigoriev.yfin.internal.endpoint.chart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.andreygrigoriev.yfin.exception.YFDataException;
import io.andreygrigoriev.yfin.internal.http.YahooJson;
import io.andreygrigoriev.yfin.model.Bar;
import io.andreygrigoriev.yfin.model.PriceHistory;
import io.andreygrigoriev.yfin.request.HistoryRequest;
import io.andreygrigoriev.yfin.request.Interval;
import io.andreygrigoriev.yfin.request.Period;
import io.andreygrigoriev.yfin.testing.FakeTransport;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChartMapperTest {

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    private static JsonNode tree(String fixture) throws IOException {
        return YahooJson.mapper()
                .readTree(FakeTransport.DEFAULT_FIXTURE_ROOT
                        .resolve("chart")
                        .resolve(fixture)
                        .toFile());
    }

    private static ChartDto dto(String fixture) throws IOException {
        return ChartDto.parse(tree(fixture));
    }

    private static ChartQuery range(String symbol, HistoryRequest request) {
        return new ChartQuery(
                symbol, request, null, null, request.period().orElseThrow().code());
    }

    private static PriceHistory map(String fixture, String symbol, HistoryRequest request) throws IOException {
        return ChartMapper.map(dto(fixture), range(symbol, request));
    }

    private static ZonedDateTime ny(int year, int month, int day) {
        return LocalDate.of(year, month, day).atStartOfDay(NEW_YORK);
    }

    @Test
    void dailyEquity() throws IOException {
        PriceHistory h = map("aapl-1d-1mo.json", "AAPL", HistoryRequest.of(Period.ONE_MONTH));

        assertThat(h.symbol()).isEqualTo("AAPL");
        assertThat(h.exchangeZone()).isEqualTo(NEW_YORK);
        assertThat(h.interval()).isEqualTo(Interval.ONE_DAY);
        assertThat(h.currency()).isEqualTo("USD");
        assertThat(h.bars()).hasSize(21);
        Bar first = h.bars().getFirst();
        assertThat(first.time()).isEqualTo(ny(2026, 9, 14));
        assertThat(first.open()).isEqualTo(334.7900085449219);
        assertThat(first.high()).isEqualTo(335.5);
        assertThat(first.low()).isEqualTo(331.3399963378906);
        assertThat(first.close()).isEqualTo(333.0799865722656);
        assertThat(first.adjClose()).isEqualTo(333.0799865722656);
        assertThat(first.volume()).isEqualTo(39_269_100L);
        assertThat(h.bars().getLast().time()).isEqualTo(ny(2026, 10, 12));
        assertThat(h.bars()).allSatisfy(b -> assertThat(b.time().toLocalTime()).isEqualTo(LocalTime.MIDNIGHT));
        assertThat(h.dividends()).isEmpty();
        assertThat(h.splits()).isEmpty();
        assertThat(h.capitalGains()).isEmpty();
    }

    @Test
    void metadataMapped() throws IOException {
        var m = map("aapl-1d-1mo.json", "AAPL", HistoryRequest.of(Period.ONE_MONTH))
                .metadata();

        assertThat(m.symbol()).isEqualTo("AAPL");
        assertThat(m.currency()).isEqualTo("USD");
        assertThat(m.exchangeName()).isEqualTo("NMS");
        assertThat(m.fullExchangeName()).isEqualTo("NasdaqGS");
        assertThat(m.instrumentType()).isEqualTo("EQUITY");
        assertThat(m.exchangeZone()).isEqualTo(NEW_YORK);
        assertThat(m.timezone()).isEqualTo("EDT");
        assertThat(m.gmtOffset()).isEqualTo(-14_400L);
        assertThat(m.firstTradeDate())
                .isEqualTo(Instant.ofEpochSecond(345_479_400).atZone(NEW_YORK));
        assertThat(m.regularMarketPrice()).isEqualTo(341.115);
        assertThat(m.chartPreviousClose()).isEqualTo(332.27);
        assertThat(m.priceHint()).isEqualTo(2);
        assertThat(m.dataGranularity()).isEqualTo("1d");
        assertThat(m.range()).isEqualTo("1mo");
        assertThat(m.validRanges()).contains("1d", "ytd", "max");
        assertThat(m.raw()).containsEntry("symbol", "AAPL").containsKey("currentTradingPeriod");
    }

    @Test
    void intradayWithPrepostMergesLiveBar() throws IOException {
        PriceHistory h = map(
                "aapl-15m-prepost.json",
                "AAPL",
                HistoryRequest.builder()
                        .period(Period.ONE_DAY)
                        .interval(Interval.FIFTEEN_MINUTES)
                        .prepost(true)
                        .build());

        // 30 timestamps, the last one a live tick inside the 11:00 bar
        assertThat(h.bars()).hasSize(29);
        assertThat(h.bars().getFirst().time())
                .isEqualTo(LocalDate.of(2026, 10, 12).atTime(4, 0).atZone(NEW_YORK));
        Bar last = h.bars().getLast();
        assertThat(last.time())
                .isEqualTo(LocalDate.of(2026, 10, 12).atTime(11, 0).atZone(NEW_YORK));
        assertThat(last.open()).isEqualTo(340.80999755859375);
        assertThat(last.high()).isEqualTo(341.1400146484375);
        assertThat(last.low()).isEqualTo(340.80999755859375);
        assertThat(last.close()).isEqualTo(341.114990234375);
        assertThat(last.volume()).isEqualTo(63_787L);
        // intraday bars carry no adjusted close: adjClose equals close
        assertThat(h.bars()).allSatisfy(b -> assertThat(b.adjClose()).isEqualTo(b.close()));
    }

    @Test
    void intradayWithoutPrepostDropsBarsOutsideRegularHours() throws IOException {
        PriceHistory h = map(
                "aapl-15m-prepost.json",
                "AAPL",
                HistoryRequest.builder()
                        .period(Period.ONE_DAY)
                        .interval(Interval.FIFTEEN_MINUTES)
                        .build());

        assertThat(h.bars()).hasSize(7);
        assertThat(h.bars().getFirst().time().toLocalTime()).isEqualTo(LocalTime.of(9, 30));
        assertThat(h.bars().getLast().time().toLocalTime()).isEqualTo(LocalTime.of(11, 0));
    }

    @Test
    void thirtyMinutesAggregatedFromFifteen() throws IOException {
        PriceHistory h = map(
                "aapl-15m-prepost.json",
                "AAPL",
                HistoryRequest.builder()
                        .period(Period.ONE_DAY)
                        .interval(Interval.THIRTY_MINUTES)
                        .prepost(true)
                        .build());

        assertThat(h.interval()).isEqualTo(Interval.THIRTY_MINUTES);
        assertThat(h.bars()).hasSize(15);
        Bar first = h.bars().getFirst();
        assertThat(first.time())
                .isEqualTo(LocalDate.of(2026, 10, 12).atTime(4, 0).atZone(NEW_YORK));
        assertThat(first.open()).isEqualTo(337.26);
        assertThat(first.high()).isEqualTo(337.83);
        assertThat(first.low()).isEqualTo(337.0);
        assertThat(first.close()).isEqualTo(337.17);
        assertThat(h.bars().get(1).time().toLocalTime()).isEqualTo(LocalTime.of(4, 30));
        Bar last = h.bars().getLast();
        assertThat(last.time().toLocalTime()).isEqualTo(LocalTime.of(11, 0));
        assertThat(last.close()).isEqualTo(341.114990234375);
        assertThat(last.volume()).isEqualTo(63_787L);
    }

    @Test
    void mutualFundMonthlyWithDividends() throws IOException {
        PriceHistory h = map(
                "agthx-1mo-2y.json",
                "AGTHX",
                HistoryRequest.builder()
                        .period(Period.TWO_YEARS)
                        .interval(Interval.ONE_MONTH)
                        .build());

        assertThat(h.bars()).hasSize(24);
        assertThat(h.bars().getFirst().time()).isEqualTo(ny(2024, 11, 1));
        // auto-adjusted: close is Yahoo's adjusted close
        assertThat(h.bars().getFirst().close()).isEqualTo(68.56546783447266);
        assertThat(h.bars().getFirst().open()).isCloseTo(77.76000213623047 * 68.56546783447266 / 82.5, within(1e-9));
        assertThat(h.dividends())
                .extracting(d -> d.time(), d -> d.amount())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(ny(2024, 12, 18), 6.691),
                        org.assertj.core.groups.Tuple.tuple(ny(2025, 12, 17), 8.604));
    }

    @Test
    void etfWithCapitalGains() throws IOException {
        PriceHistory h = map(
                "schd-1wk-capital-gains.json",
                "SCHD",
                HistoryRequest.builder()
                        .period(Period.SIX_MONTHS)
                        .interval(Interval.ONE_WEEK)
                        .build());

        assertThat(h.metadata().instrumentType()).isEqualTo("ETF");
        assertThat(h.bars()).hasSize(27);
        assertThat(h.dividends()).hasSize(2);
        assertThat(h.capitalGains()).singleElement().satisfies(g -> {
            assertThat(g.time()).isEqualTo(ny(2026, 9, 23));
            assertThat(g.amount()).isEqualTo(0.1234);
        });
    }

    @Test
    void capitalGainsIgnoredForEquities() throws IOException {
        ObjectNode root = (ObjectNode) tree("aapl-1d-1mo.json");
        ObjectNode result = (ObjectNode) root.path("chart").path("result").get(0);
        result.putObject("events")
                .putObject("capitalGains")
                .putObject("1789963200")
                .put("amount", 1.0)
                .put("date", 1_789_963_200L);

        PriceHistory h = ChartMapper.map(ChartDto.parse(root), range("AAPL", HistoryRequest.of(Period.ONE_MONTH)));

        assertThat(h.capitalGains()).isEmpty();
    }

    @Test
    void cryptoTradesAroundTheClockInUtc() throws IOException {
        PriceHistory h = map("btc-usd-1d-5d.json", "BTC-USD", HistoryRequest.of(Period.FIVE_DAYS));

        assertThat(h.exchangeZone()).isEqualTo(ZoneId.of("UTC"));
        assertThat(h.metadata().instrumentType()).isEqualTo("CRYPTOCURRENCY");
        assertThat(h.bars())
                .extracting(b -> b.time().toLocalDate())
                .containsExactly(
                        LocalDate.of(2026, 10, 8),
                        LocalDate.of(2026, 10, 9),
                        LocalDate.of(2026, 10, 10),
                        LocalDate.of(2026, 10, 11),
                        LocalDate.of(2026, 10, 12));
        assertThat(h.bars().getFirst().time().toLocalTime()).isEqualTo(LocalTime.MIDNIGHT);
        assertThat(h.bars().getFirst().volume()).isEqualTo(46_221_063_868L);
    }

    @Test
    void splitsAndDividends() throws IOException {
        var request = HistoryRequest.builder()
                .start(LocalDate.of(2020, 8, 1))
                .end(LocalDate.of(2020, 9, 15))
                .build();
        PriceHistory h = ChartMapper.map(
                dto("aapl-split-dividend-2020.json"),
                new ChartQuery("AAPL", request, 1_596_254_400L, 1_600_142_400L, null));

        assertThat(h.bars()).hasSize(30);
        assertThat(h.dividends()).singleElement().satisfies(d -> {
            assertThat(d.time()).isEqualTo(ny(2020, 8, 7));
            assertThat(d.amount()).isEqualTo(0.205);
        });
        assertThat(h.splits()).singleElement().satisfies(s -> {
            assertThat(s.time()).isEqualTo(ny(2020, 8, 31));
            assertThat(s.numerator()).isEqualTo(4.0);
            assertThat(s.denominator()).isEqualTo(1.0);
            assertThat(s.ratio()).isEqualTo(4.0);
        });
        Bar first = h.bars().getFirst();
        double ratio = 105.38542938232422 / 108.9375;
        assertThat(first.close()).isEqualTo(105.38542938232422);
        assertThat(first.open()).isCloseTo(108.19999694824219 * ratio, within(1e-9));
        assertThat(first.high()).isCloseTo(111.63749694824219 * ratio, within(1e-9));
        assertThat(first.low()).isCloseTo(107.89250183105469 * ratio, within(1e-9));
    }

    @Test
    void eventsFromFirstBarDayUntilEnd() throws IOException {
        var request = HistoryRequest.builder()
                .start(LocalDate.of(2020, 8, 1))
                .end(LocalDate.of(2020, 8, 28))
                .build();
        // end before the split; the window starts at the first bar's day, so the dividend stays
        PriceHistory h = ChartMapper.map(
                dto("aapl-split-dividend-2020.json"),
                new ChartQuery("AAPL", request, 1_596_254_400L, 1_598_587_200L, null));

        assertThat(h.dividends()).hasSize(1);
        assertThat(h.splits()).isEmpty();
    }

    @Test
    void eventsBeforeFirstBarDayDropped() {
        String json = """
                {"chart":{"result":[{"meta":{"exchangeTimezoneName":"UTC"},
                "timestamp":[1704182400],
                "indicators":{"quote":[{"open":[1.0],"high":[1.0],"low":[1.0],"close":[1.0],"volume":[1]}]},
                "events":{"dividends":{"1704096000":{"amount":0.5,"date":1704096000},
                "1704186000":{"amount":0.7,"date":1704186000}}}}],"error":null}}""";
        var request = HistoryRequest.builder()
                .start(LocalDate.of(2024, 1, 1))
                .end(LocalDate.of(2024, 1, 5))
                .build();
        PriceHistory h =
                ChartMapper.map(parse(json), new ChartQuery("X", request, 1_704_067_200L, 1_704_412_800L, null));

        assertThat(h.dividends())
                .singleElement()
                .satisfies(d -> assertThat(d.amount()).isEqualTo(0.7));
    }

    @Test
    void lastBarPastEndDropped() throws IOException {
        var request = HistoryRequest.builder()
                .start(LocalDate.of(2020, 8, 1))
                .end(LocalDate.of(2020, 9, 14))
                .build();
        // period2 = 2020-09-14 00:00 New York; Yahoo's last bar opens 09:30 that day
        PriceHistory h = ChartMapper.map(
                dto("aapl-split-dividend-2020.json"),
                new ChartQuery("AAPL", request, 1_596_254_400L, 1_600_056_000L, null));

        assertThat(h.bars()).hasSize(29);
        assertThat(h.bars().getLast().time()).isEqualTo(ny(2020, 9, 11));
    }

    @Test
    void rawPricesWithoutAdjustment() throws IOException {
        var request = HistoryRequest.builder()
                .start(LocalDate.of(2020, 8, 1))
                .end(LocalDate.of(2020, 9, 15))
                .autoAdjust(false)
                .build();
        Bar first = ChartMapper.map(
                        dto("aapl-split-dividend-2020.json"),
                        new ChartQuery("AAPL", request, 1_596_254_400L, 1_600_142_400L, null))
                .bars()
                .getFirst();

        assertThat(first.open()).isEqualTo(108.19999694824219);
        assertThat(first.close()).isEqualTo(108.9375);
        assertThat(first.adjClose()).isEqualTo(105.38542938232422);
    }

    @Test
    void backAdjustKeepsRawClose() throws IOException {
        var request = HistoryRequest.builder()
                .start(LocalDate.of(2020, 8, 1))
                .end(LocalDate.of(2020, 9, 15))
                .autoAdjust(false)
                .backAdjust(true)
                .build();
        Bar first = ChartMapper.map(
                        dto("aapl-split-dividend-2020.json"),
                        new ChartQuery("AAPL", request, 1_596_254_400L, 1_600_142_400L, null))
                .bars()
                .getFirst();

        assertThat(first.open()).isCloseTo(108.19999694824219 * 105.38542938232422 / 108.9375, within(1e-9));
        assertThat(first.close()).isEqualTo(108.9375);
        assertThat(first.adjClose()).isEqualTo(105.38542938232422);
    }

    @Test
    void actionsOmittedWhenNotRequested() throws IOException {
        var request = HistoryRequest.builder()
                .start(LocalDate.of(2020, 8, 1))
                .end(LocalDate.of(2020, 9, 15))
                .actions(false)
                .build();
        PriceHistory h = ChartMapper.map(
                dto("aapl-split-dividend-2020.json"),
                new ChartQuery("AAPL", request, 1_596_254_400L, 1_600_142_400L, null));

        assertThat(h.bars()).hasSize(30);
        assertThat(h.actions().isEmpty()).isTrue();
    }

    @Test
    void nullBarsDroppedUnlessKeepNa() throws IOException {
        var request = HistoryRequest.builder()
                .period(Period.ONE_DAY)
                .interval(Interval.ONE_MINUTE)
                .build();
        // 94 timestamps, 10 of them all-null; the last is a live tick merged into the (null) 11:02 bar
        PriceHistory dropped = map("azo-1m-nulls.json", "AZO", request);
        PriceHistory kept =
                map("azo-1m-nulls.json", "AZO", request.toBuilder().keepNa(true).build());

        assertThat(dropped.bars()).hasSize(84).noneMatch(Bar::isEmpty);
        assertThat(kept.bars()).hasSize(93);
        assertThat(kept.bars().stream().filter(Bar::isEmpty)).hasSize(9);
        assertThat(kept.bars().stream().filter(Bar::isEmpty))
                .allSatisfy(b -> assertThat(b.volume()).isZero());
        Bar merged = kept.bars().getLast();
        assertThat(merged.time().toLocalTime()).isEqualTo(LocalTime.of(11, 2));
        assertThat(merged.close()).isEqualTo(2890.85498046875);
        assertThat(merged.open()).isNotNaN();
    }

    @Test
    void partiallyMissingPricesBecomeNaN() {
        String json = """
                {"chart":{"result":[{"meta":{"symbol":"X","currency":"EUR","exchangeTimezoneName":"Europe/Berlin"},
                "timestamp":[1704096000,1704182400],
                "indicators":{"quote":[{"open":[null,2.0],"high":[1.5,2.5],"low":[0.5,null],"close":[1.0,2.2],
                "volume":[null,100]}],"adjclose":[{"adjclose":[1.0,2.2]}]}}],"error":null}}""";
        PriceHistory h = ChartMapper.map(parse(json), range("X", HistoryRequest.of(Period.FIVE_DAYS)));

        assertThat(h.currency()).isEqualTo("EUR");
        assertThat(h.bars()).hasSize(2);
        assertThat(h.bars().get(0).open()).isNaN();
        assertThat(h.bars().get(0).close()).isEqualTo(1.0);
        assertThat(h.bars().get(0).volume()).isZero();
        assertThat(h.bars().get(1).low()).isNaN();
        assertThat(h.bars().get(1).volume()).isEqualTo(100);
    }

    @Test
    void emptyResultGivesEmptyHistory() throws IOException {
        var request = HistoryRequest.builder()
                .start(LocalDate.of(2026, 10, 10))
                .end(LocalDate.of(2026, 10, 11))
                .build();
        PriceHistory h = ChartMapper.map(
                dto("aapl-weekend-empty.json"), new ChartQuery("AAPL", request, 1_791_604_800L, 1_791_691_200L, null));

        assertThat(h.isEmpty()).isTrue();
        assertThat(h.metadata().symbol()).isEqualTo("AAPL");
        assertThat(h.exchangeZone()).isEqualTo(NEW_YORK);
    }

    @Test
    void dailyBarJustBeforeMidnightMovedToNextDay() {
        ZoneId saoPaulo = ZoneId.of("America/Sao_Paulo");
        long epoch = LocalDate.of(2022, 1, 3).atTime(23, 0).atZone(saoPaulo).toEpochSecond();

        assertThat(ChartMapper.barTime(epoch, Interval.ONE_DAY, saoPaulo))
                .isEqualTo(LocalDate.of(2022, 1, 4).atStartOfDay(saoPaulo));
        assertThat(ChartMapper.barTime(epoch, Interval.ONE_MONTH, saoPaulo))
                .isEqualTo(LocalDate.of(2022, 1, 3).atStartOfDay(saoPaulo));
        assertThat(ChartMapper.barTime(epoch, Interval.ONE_HOUR, saoPaulo))
                .isEqualTo(Instant.ofEpochSecond(epoch).atZone(saoPaulo));
    }

    @Test
    void dailyLiveDuplicateKeepsLatestRow() {
        ZonedDateTime day = LocalDate.of(2026, 10, 12).atStartOfDay(ZoneOffset.UTC);
        var older = new Bar(day, 1, 2, 0.5, 1.5, 1.5, 10);
        var live = new Bar(day, 1, 2.5, 0.5, 2.0, 2.0, 12);
        var prev = new Bar(day.minusDays(1), 1, 1, 1, 1, 1, 1);

        assertThat(ChartMapper.mergeLiveBar(List.of(prev, older, live), Interval.ONE_DAY, false))
                .containsExactly(prev, live);
    }

    @Test
    void weeklyLiveBarMergedIntoWeek() {
        ZonedDateTime week = LocalDate.of(2026, 10, 5).atStartOfDay(NEW_YORK);
        var weekBar = new Bar(week, 10, 12, 9, 11, 11, 100);
        var live = new Bar(week.plusDays(3), 11, 13, 8, 12, 12, 20);

        assertThat(ChartMapper.mergeLiveBar(List.of(weekBar, live), Interval.ONE_WEEK, false))
                .containsExactly(new Bar(week, 10, 13, 8, 12, 12, 120));
    }

    @Test
    void sameIntervalRules() {
        ZonedDateTime jan = LocalDate.of(2026, 1, 15).atStartOfDay(ZoneOffset.UTC);
        assertThat(ChartMapper.sameInterval(jan, jan.plusDays(10), Interval.ONE_MONTH))
                .isTrue();
        assertThat(ChartMapper.sameInterval(jan, jan.plusDays(20), Interval.ONE_MONTH))
                .isFalse();
        assertThat(ChartMapper.sameInterval(jan, jan.plusDays(6), Interval.ONE_WEEK))
                .isTrue();
        assertThat(ChartMapper.sameInterval(jan, jan.plusDays(7), Interval.ONE_WEEK))
                .isFalse();
        ZonedDateTime quarter = LocalDate.of(2026, 1, 1).atStartOfDay(ZoneOffset.UTC);
        assertThat(ChartMapper.sameInterval(quarter, quarter.plusMonths(2), Interval.THREE_MONTHS))
                .isTrue();
        assertThat(ChartMapper.sameInterval(quarter, quarter.plusMonths(3), Interval.THREE_MONTHS))
                .isFalse();
        assertThat(ChartMapper.sameInterval(jan, jan.plusMinutes(4), Interval.FIVE_MINUTES))
                .isTrue();
        assertThat(ChartMapper.sameInterval(jan, jan.plusMinutes(5), Interval.FIVE_MINUTES))
                .isFalse();
    }

    @Test
    void malformedPayloadsRejected() {
        assertThatThrownBy(() -> parse("{\"chart\":{\"result\":[],\"error\":null}}"))
                .isInstanceOf(YFDataException.class)
                .hasMessageContaining("no result");
        assertThatThrownBy(() -> parse("{\"chart\":{\"result\":[{\"timestamp\":[1]}],\"error\":null}}"))
                .isInstanceOf(YFDataException.class)
                .hasMessageContaining("no meta");
        assertThatThrownBy(() -> parse("""
                        {"chart":{"result":[{"meta":{"exchangeTimezoneName":"UTC"},"timestamp":[1,2],
                        "indicators":{"quote":[{"open":[1.0],"close":[1.0,2.0]}]}}],"error":null}}"""))
                .isInstanceOf(YFDataException.class)
                .hasMessageContaining("open has 1 values for 2 timestamps");
        assertThatThrownBy(() -> parse("""
                        {"chart":{"result":[{"meta":{"exchangeTimezoneName":"UTC"},"timestamp":[1],
                        "indicators":{"quote":[{"close":["x"]}]}}],"error":null}}"""))
                .isInstanceOf(YFDataException.class)
                .hasMessageContaining("close[0] is not a number");
        // missing price arrays are all-NaN, not an error
        assertThat(parse("""
                        {"chart":{"result":[{"meta":{},"timestamp":[1],
                        "indicators":{"quote":[{"close":[1.0]}]}}],"error":null}}""").rows())
                .singleElement()
                .satisfies(r -> assertThat(r.open()).isNaN());
    }

    @Test
    void missingOrInvalidTimezoneRejected() {
        ChartDto noZone = parse("{\"chart\":{\"result\":[{\"meta\":{\"symbol\":\"X\"}}],\"error\":null}}");
        ChartDto badZone =
                parse("{\"chart\":{\"result\":[{\"meta\":{\"exchangeTimezoneName\":\"Mars/Base\"}}],\"error\":null}}");
        ChartQuery q = range("X", HistoryRequest.of(Period.ONE_MONTH));

        assertThatThrownBy(() -> ChartMapper.map(noZone, q))
                .isInstanceOf(YFDataException.class)
                .hasMessageContaining("exchangeTimezoneName");
        assertThatThrownBy(() -> ChartMapper.map(badZone, q))
                .isInstanceOf(YFDataException.class)
                .hasMessageContaining("Mars/Base");
    }

    private static ChartDto parse(String json) {
        try {
            return ChartDto.parse(YahooJson.mapper().readTree(json));
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
