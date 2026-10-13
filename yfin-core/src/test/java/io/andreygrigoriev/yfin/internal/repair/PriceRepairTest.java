package io.andreygrigoriev.yfin.internal.repair;

import static io.andreygrigoriev.yfin.internal.repair.RepairCsv.assertColumnClose;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.andreygrigoriev.yfin.exception.YFRateLimitException;
import io.andreygrigoriev.yfin.exception.YFTransportException;
import io.andreygrigoriev.yfin.internal.repair.Frame.Col;
import io.andreygrigoriev.yfin.request.Interval;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Repairs that need finer-grained data (yfinance tests these against live Yahoo data): sporadic
 * 100x errors and missing prices, reconstructed here from a recorded daily series served as the
 * finer data of its own weekly resample. Plus pipeline invariants.
 */
class PriceRepairTest {

    private static final ZoneId NY = ZoneId.of("America/New_York");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2024-09-01T00:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.of(2024, 9, 1);

    private final Frame daily = RepairCsv.read("CALM-1d-no-bad-divs", NY).slice(0, 130);
    private final Frame weekly = Resample.resample(daily, Interval.ONE_WEEK, NY, false, TODAY);
    private final List<Interval> fetched = new ArrayList<>();

    private RepairContext withFinerData() {
        RepairContext.FinerHistory finer = (sub, start, end, prepost, outer) -> {
            fetched.add(sub);
            int[] rows = IntStream.range(0, daily.size())
                    .filter(i -> {
                        LocalDate d = daily.time(i).toLocalDate();
                        return !d.isBefore(start) && d.isBefore(end);
                    })
                    .toArray();
            return daily.rows(rows);
        };
        return new RepairContext("CALM", NY, "USD", null, CLOCK, finer, null);
    }

    private static RepairContext offline() {
        return RepairContext.offline("CALM", NY, "USD", CLOCK);
    }

    @Test
    void weeklyResampleStartsOnMondays() {
        assertThat(weekly.size()).isBetween(26, 28);
        for (int i = 0; i < weekly.size(); i++) {
            assertThat(weekly.time(i).getDayOfWeek()).isEqualTo(java.time.DayOfWeek.MONDAY);
        }
        assertThat(weekly.get(Col.OPEN, 0)).isEqualTo(daily.get(Col.OPEN, 0));
        assertThat(weekly.get(Col.VOLUME, 0))
                .isEqualTo(IntStream.range(0, 5)
                        .mapToDouble(i -> daily.get(Col.VOLUME, i))
                        .sum());
    }

    @Test
    void sporadic100xErrorsReconstructedFromFinerData() {
        Frame bad = weekly.copy();
        bad.set(Col.CLOSE, 5, bad.get(Col.CLOSE, 5) * 100);
        bad.set(Col.LOW, 9, bad.get(Col.LOW, 9) * 100);
        bad.set(Col.OPEN, 14, bad.get(Col.OPEN, 14) * 100);

        Frame repaired = UnitMixups.fixRandom(bad, Interval.ONE_WEEK, false, withFinerData());

        for (Col c : Frame.PRICES) {
            assertColumnClose("weekly", repaired, weekly, c, 1e-2);
        }
        assertThat(repaired.repaired()[5]).isTrue();
        assertThat(repaired.repaired()[9]).isTrue();
        assertThat(repaired.repaired()[14]).isTrue();
        assertThat(repaired.repaired()[2]).isFalse();
        assertThat(fetched).isNotEmpty().containsOnly(Interval.ONE_DAY);
    }

    @Test
    void sporadic100xErrorsFixedCrudelyWithoutFinerData() {
        Frame bad = weekly.copy();
        bad.set(Col.CLOSE, 5, bad.get(Col.CLOSE, 5) * 100);
        bad.set(Col.OPEN, 14, bad.get(Col.OPEN, 14) / 100);

        Frame repaired = UnitMixups.fixRandom(bad, Interval.ONE_WEEK, false, offline());

        assertThat(repaired.get(Col.CLOSE, 5)).isCloseTo(weekly.get(Col.CLOSE, 5), within(weekly.get(Col.CLOSE, 5)));
        assertThat(repaired.get(Col.OPEN, 14)).isCloseTo(weekly.get(Col.OPEN, 14), within(weekly.get(Col.OPEN, 14)));
    }

    @Test
    void missingPricesReconstructedFromFinerData() {
        Frame bad = weekly.copy();
        for (Col c : Frame.PRICES) {
            bad.set(c, 7, Double.NaN);
        }
        bad.set(Col.VOLUME, 7, 0);

        Frame repaired = Zeroes.fix(bad, Interval.ONE_WEEK, false, withFinerData());

        for (Col c : Frame.OHLC) {
            assertColumnClose("weekly", repaired, weekly, c, 5e-3);
        }
        assertThat(repaired.get(Col.VOLUME, 7)).isEqualTo(weekly.get(Col.VOLUME, 7));
        assertThat(repaired.repaired()[7]).isTrue();
        assertThat(repaired.repaired()[6]).isFalse();
    }

    @Test
    void missingPricesLeftAloneWithoutFinerData() {
        Frame bad = weekly.copy();
        for (Col c : Frame.PRICES) {
            bad.set(c, 7, Double.NaN);
        }
        bad.set(Col.VOLUME, 7, 0);

        Frame repaired = Zeroes.fix(bad, Interval.ONE_WEEK, false, offline());

        for (Col c : Col.values()) {
            assertColumnClose("weekly", repaired, bad, c, 0);
        }
        assertThat(repaired.repaired()).doesNotContain(true);
    }

    @Test
    void rateLimitAndInterruptDuringFinerFetchPropagateOtherFailuresAreSkipped() {
        Frame bad = weekly.copy();
        for (Col c : Frame.PRICES) {
            bad.set(c, 7, Double.NaN);
        }
        bad.set(Col.VOLUME, 7, 0);

        var limited = new YFRateLimitException("HTTP 429");
        assertThatThrownBy(() -> Zeroes.fix(bad, Interval.ONE_WEEK, false, failingFiner(limited)))
                .isSameAs(limited);
        var interrupted = new YFTransportException("interrupted", new InterruptedException());
        assertThatThrownBy(() -> Zeroes.fix(bad, Interval.ONE_WEEK, false, failingFiner(interrupted)))
                .isSameAs(interrupted);

        Frame repaired =
                Zeroes.fix(bad, Interval.ONE_WEEK, false, failingFiner(new YFTransportException("connection reset")));
        assertThat(repaired.repaired()).doesNotContain(true);
    }

    private static RepairContext failingFiner(RuntimeException failure) {
        return new RepairContext(
                "CALM",
                NY,
                "USD",
                null,
                CLOCK,
                (s, a, b, p, o) -> {
                    throw failure;
                },
                null);
    }

    @Test
    void reconstructionStopsAtDepthTwo() {
        RepairContext ctx = new RepairContext(
                "CALM",
                NY,
                "USD",
                null,
                CLOCK,
                (s, a, b, p, o) -> {
                    throw new AssertionError("no fetch expected");
                },
                Interval.ONE_WEEK);
        Frame bad = daily.copy();
        bad.set(Col.CLOSE, 3, Reconstruct.TAG);

        Frame out = Reconstruct.reconstruct(bad, Interval.ONE_HOUR, false, ctx);

        assertThat(out.get(Col.CLOSE, 3)).isEqualTo(Reconstruct.TAG);
    }

    @Test
    void cleanDataUnchangedByPipeline() {
        Frame all = RepairCsv.read("CALM-1d-no-bad-divs", NY);

        Frame repaired = PriceRepair.repair(all, Interval.ONE_DAY, false, offline());

        for (Col c : Col.values()) {
            assertColumnClose("CALM", repaired, all, c, 1e-12);
        }
        assertThat(repaired.repaired()).doesNotContain(true);
    }

    @Test
    void pencePricesScaledForRepairAndRestored() {
        Frame all = daily.copy();
        double lastClose = all.get(Col.CLOSE, all.size() - 1);
        Clock justAfter = Clock.fixed(all.time(all.size() - 1).toInstant().plusSeconds(86_400), ZoneOffset.UTC);
        RepairContext ctx = new RepairContext("CALM.L", NY, "GBp", lastClose, justAfter, null, null);

        Frame repaired = PriceRepair.repair(all, Interval.ONE_DAY, false, ctx);

        for (Col c : Frame.PRICES) {
            assertColumnClose("CALM.L", repaired, all, c, 1e-12);
        }
        assertThat(ctx.currency()).isEqualTo("GBp");
    }

    @Test
    void poundPricesKeepTheMainUnit() {
        Frame all = daily.copy();
        double lastClose = all.get(Col.CLOSE, all.size() - 1);
        Clock justAfter = Clock.fixed(all.time(all.size() - 1).toInstant().plusSeconds(86_400), ZoneOffset.UTC);
        // the regular market price is in pence, so the bars are already in pounds
        RepairContext ctx = new RepairContext("CALM.L", NY, "GBp", lastClose * 100, justAfter, null, null);

        Frame repaired = PriceRepair.repair(all, Interval.ONE_DAY, false, ctx);

        for (Col c : Frame.PRICES) {
            assertColumnClose("CALM.L", repaired, all, c, 1e-12);
        }
        assertThat(ctx.currency()).isEqualTo("GBP");
    }

    @Test
    void medianFilterWrapsAroundBothAxes() {
        double[][] data = {{1, 2, 3}, {4, 5, 6}, {7, 8, 9}};
        double[][] m = UnitMixups.medianFilter3x3Wrap(data);
        // every 3x3 wrapped window of a 3x3 grid holds all nine values
        for (double[] row : m) {
            assertThat(row).containsOnly(5.0);
        }
    }

    @Test
    void dbscanKeepsLargestCluster() {
        double[] ratios = {1.0, 1.01, 0.99, 1.02, 100.0, 100.5};
        double[] kept = Reconstruct.keepLargestCluster(ratios);
        assertThat(kept[4]).isEqualTo(Stats.median(new double[] {1.0, 1.01, 0.99, 1.02}));
        assertThat(kept[0]).isEqualTo(1.0);
    }

    private static org.assertj.core.data.Offset<Double> within(double v) {
        return org.assertj.core.data.Offset.offset(Math.abs(v) * 1e-9);
    }
}
