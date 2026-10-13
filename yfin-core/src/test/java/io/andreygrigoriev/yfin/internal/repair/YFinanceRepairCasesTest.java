package io.andreygrigoriev.yfin.internal.repair;

import static io.andreygrigoriev.yfin.internal.repair.RepairCsv.assertColumnClose;
import static org.assertj.core.api.Assertions.assertThat;

import io.andreygrigoriev.yfin.internal.repair.Frame.Col;
import io.andreygrigoriev.yfin.request.Interval;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * yfinance's {@code tests/test_price_repair.py} cases that run on recorded CSV data: unit switches,
 * bad stock splits, bad dividends and their false positives, and capital gains double counting.
 * The CSVs are yfinance's {@code tests/data} files.
 */
class YFinanceRepairCasesTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);

    private static final Col[] PRICES = {Col.LOW, Col.HIGH, Col.OPEN, Col.CLOSE, Col.ADJ_CLOSE};

    private static RepairContext ctx(String symbol, String zone, String currency) {
        return RepairContext.offline(symbol, ZoneId.of(zone), currency, CLOCK);
    }

    // ---- 100x unit switch ----

    static Stream<Arguments> unitSwitchBlock() {
        return Stream.of(
                Arguments.of("AET.L", "Europe/London", "GBp"), Arguments.of("SSW.JO", "Africa/Johannesburg", "ZAc"));
    }

    @ParameterizedTest
    @MethodSource
    void unitSwitchBlock(String symbol, String zone, String currency) {
        String name = file(symbol) + "-1d-100x-error";
        Frame bad = RepairCsv.read(name, ZoneId.of(zone));
        Frame expected = RepairCsv.read(name + "-fixed", ZoneId.of(zone));

        Frame repaired = UnitMixups.fixSwitch(bad, Interval.ONE_DAY, ctx(symbol, zone, currency))
                .sortedAscending();

        for (Col c : PRICES) {
            assertColumnClose(symbol, repaired, expected, c, 1e-2);
        }
        // every difference is ~1x or ~100x
        for (int i = 0; i < bad.size(); i++) {
            for (Col c : PRICES) {
                double ratio = Stats.round(bad.get(c, i) / expected.get(c, i), 2);
                if (ratio > 90) {
                    ratio = Math.rint(ratio / 10) * 10;
                }
                assertThat(ratio).isIn(1.0, 100.0, 0.01);
            }
        }
    }

    @ParameterizedTest
    @MethodSource("hourlyUnit")
    void unitSwitchHourly(String symbol) {
        ZoneId zone = ZoneId.of("Europe/London");
        Frame bad = RepairCsv.read("ASAI-L-1h-bad-unit", zone);
        Frame expected = RepairCsv.read("ASAI-L-1h-bad-unit-fixed", zone);

        Frame repaired = UnitMixups.fixSwitch(bad, Interval.ONE_HOUR, ctx(symbol, "Europe/London", "GBp"))
                .sortedAscending();

        for (Col c : Frame.OHLC) {
            assertColumnClose(symbol, repaired, expected, c, 1e-7);
        }
    }

    static Stream<String> hourlyUnit() {
        return Stream.of("ASAI.L");
    }

    // ---- bad stock splits ----

    static Stream<Arguments> badStockSplits() {
        return Stream.of(
                Arguments.of("4063.T", "Asia/Tokyo", "1d", 0),
                Arguments.of("AV.L", "Europe/London", "1wk", 0),
                Arguments.of("CNE.L", "Europe/London", "1d", 0),
                Arguments.of("MOB.ST", "Europe/Stockholm", "1d", 0),
                Arguments.of("SPM.MI", "Europe/Rome", "1d", 0),
                Arguments.of("LA.V", "America/Toronto", "1d", 0),
                Arguments.of("NRDY", "America/New_York", "1d", 0),
                // particularly bad data, also tested on its last 27 rows
                Arguments.of("NRDY", "America/New_York", "1d", 27));
    }

    @ParameterizedTest
    @MethodSource
    void badStockSplits(String symbol, String zone, String fileInterval, int tail) {
        String name = file(symbol) + "-" + fileInterval + "-bad-stock-split";
        Frame bad = RepairCsv.read(name, ZoneId.of(zone));
        Frame expected = RepairCsv.read(name + "-fixed", ZoneId.of(zone));
        if (tail > 0) {
            bad = bad.slice(bad.size() - tail, bad.size());
            expected = expected.slice(expected.size() - tail, expected.size());
        }

        // yfinance's test repairs every file as daily data
        Frame repaired =
                SplitRepair.fix(bad, Interval.ONE_DAY, ctx(symbol, zone, "")).sortedAscending();

        for (Col c : new Col[] {Col.OPEN, Col.LOW, Col.HIGH, Col.CLOSE, Col.ADJ_CLOSE, Col.VOLUME}) {
            assertColumnClose(symbol, repaired, expected, c, 5e-5);
        }
    }

    // ---- bad dividends ----

    static Stream<Arguments> badDividends() {
        return Stream.of(
                Arguments.of("LSC.L", "Europe/London"),
                Arguments.of("TEM.L", "Europe/London"),
                Arguments.of("KME.MI", "Europe/Rome"),
                Arguments.of("REL.L", "Europe/London"),
                Arguments.of("4063.T", "Asia/Tokyo"),
                Arguments.of("CLC.L", "Europe/London"),
                Arguments.of("RGL.L", "Europe/London"),
                Arguments.of("SERE.L", "Europe/London"),
                Arguments.of("ABDP.L", "Europe/London"),
                Arguments.of("ELCO.L", "Europe/London"),
                Arguments.of("PSH.L", "Europe/London"),
                Arguments.of("SCR.TO", "America/Toronto"),
                Arguments.of("NVT.L", "Europe/London"),
                Arguments.of("1398.HK", "Asia/Hong_Kong"),
                Arguments.of("3988.HK", "Asia/Hong_Kong"),
                Arguments.of("KEN.TA", "Asia/Jerusalem"),
                Arguments.of("KAP.IL", "Europe/London"),
                Arguments.of("TEP.PA", "Europe/Paris"),
                Arguments.of("SSNLF", "America/New_York"));
    }

    @ParameterizedTest
    @MethodSource
    void badDividends(String symbol, String zone) {
        String name = file(symbol) + "-1d-bad-div";
        Frame bad = RepairCsv.read(name, ZoneId.of(zone));
        Frame expected = RepairCsv.read(name + "-fixed", ZoneId.of(zone));

        Frame repaired = DividendRepair.fix(bad, Interval.ONE_DAY, false, ctx(symbol, zone, ""))
                .sortedAscending();

        assertColumnClose(symbol, repaired, expected, Col.DIVIDENDS, 1e-12);
        // SSNLF's huge numbers magnify tiny precision differences, as in yfinance's test
        assertColumnClose(symbol, repaired, expected, Col.ADJ_CLOSE, symbol.equals("SSNLF") ? 0.5 : 5e-5);
    }

    static Stream<Arguments> dividendFalsePositives() {
        return Stream.of(
                Arguments.of("CALM", "America/New_York", "1d", false),
                Arguments.of("EWG", "America/New_York", "1d", false),
                Arguments.of("HSBK.IL", "Europe/London", "1d", false),
                Arguments.of("IBE.MC", "Europe/Madrid", "1d", false),
                Arguments.of("KMR.L", "Europe/London", "1d", false),
                Arguments.of("TISG.MI", "Europe/Rome", "1d", false),
                // a huge one-off drop in pre-market
                Arguments.of("EA", "America/New_York", "15m", true));
    }

    @ParameterizedTest
    @MethodSource
    void dividendFalsePositives(String symbol, String zone, String interval, boolean prepost) {
        Frame df = RepairCsv.read(file(symbol) + "-" + interval + "-no-bad-divs", ZoneId.of(zone));

        Frame repaired = DividendRepair.fix(df, Interval.fromCode(interval), prepost, ctx(symbol, zone, ""))
                .sortedAscending();

        assertColumnClose(symbol, repaired, df, Col.DIVIDENDS, 1e-12);
        assertColumnClose(symbol, repaired, df, Col.ADJ_CLOSE, 1e-12);
    }

    // ---- capital gains ----

    @ParameterizedTest
    @MethodSource("capitalGainsSymbols")
    void capitalGainsDoubleCount(String symbol) {
        ZoneId zone = ZoneId.of("America/New_York");
        String name = symbol + "-1d-cg-double-count";
        Frame bad = RepairCsv.read(name, zone);
        Frame expected = RepairCsv.read(name + "-fixed", zone);

        Frame repaired = CapitalGainsRepair.fix(bad, ctx(symbol, "America/New_York", "USD"));

        for (Col c : new Col[] {Col.OPEN, Col.LOW, Col.HIGH, Col.CLOSE, Col.ADJ_CLOSE, Col.VOLUME}) {
            assertColumnClose(symbol, repaired, expected, c, 5e-6);
        }
    }

    static Stream<String> capitalGainsSymbols() {
        return Stream.of("DODFX", "VWILX", "JENYX");
    }

    private static String file(String symbol) {
        return symbol.replace('.', '-');
    }
}
