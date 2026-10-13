package io.andreygrigoriev.yfin.internal.repair;

import static io.andreygrigoriev.yfin.internal.repair.Frame.Col.ADJ_CLOSE;
import static io.andreygrigoriev.yfin.internal.repair.Frame.Col.CLOSE;
import static io.andreygrigoriev.yfin.internal.repair.Frame.Col.DIVIDENDS;
import static io.andreygrigoriev.yfin.internal.repair.Frame.Col.HIGH;
import static io.andreygrigoriev.yfin.internal.repair.Frame.Col.LOW;
import static io.andreygrigoriev.yfin.internal.repair.Frame.Col.OPEN;
import static io.andreygrigoriev.yfin.internal.repair.Frame.Col.SPLITS;
import static io.andreygrigoriev.yfin.internal.repair.Frame.Col.VOLUME;

import io.andreygrigoriev.yfin.internal.repair.Frame.Col;
import io.andreygrigoriev.yfin.request.Interval;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * yfinance's {@code _fix_prices_sudden_change}: finds ranges of bars whose prices jump by a given
 * factor against their neighbours and scales them back. Used with the currency subunit factor
 * (100, or 1000 for KWF) for unit switches and with a split ratio for missing split adjustments.
 * Works on the table sorted newest first, like yfinance.
 */
final class SuddenChange {

    private static final Logger LOG = LoggerFactory.getLogger(SuddenChange.class);

    private static final Col[] OHLC = Frame.OHLC;
    private static final Col[] PRICES_ADJ = {OPEN, HIGH, LOW, CLOSE, ADJ_CLOSE};

    /** Ratios in (min, max) are too close to 1 to tell from normal price moves. */
    private static final double MIN_RATIO_TO_REPAIR = 0.8;

    private static final double MAX_RATIO_TO_REPAIR = 1.25;
    /** Standard deviations of the daily change that count as the largest normal change. */
    private static final double VOLATILITY_SDS = 5;
    /** Volume z-score above which a flagged price move is a real event. */
    private static final double VOLUME_SPIKE_Z = 2;
    /** Unflagged rows before a move included in its volume window. */
    private static final int VOLUME_WINDOW_HALF = 15;
    /** Unflagged rows in the volume window around a move. */
    private static final int VOLUME_WINDOW = 31;
    /** A range multiplier below this scales prices down. */
    private static final double SCALED_DOWN = 0.99;

    /** A range of rows {@code [from, to)} (newest-first positions) and whether to apply the ratio itself. */
    record Range(int from, int to, boolean applySplit) {}

    private SuddenChange() {}

    static Frame fix(
            Frame df,
            Interval interval,
            double change,
            boolean unitSwitch,
            boolean correctVolume,
            boolean correctDividend,
            RepairContext ctx) {
        if (df.isEmpty()) {
            return df;
        }
        final double split = change;
        final double splitRcp = 1.0 / split;
        final double splitMax = Math.max(split, splitRcp);
        boolean notDaily = Intervals.interday(interval) && interval != Interval.ONE_DAY;
        String symbol = ctx.symbol();

        @Nullable LocalDate startMin = unitSwitch ? null : splitSearchStart(df, ctx);
        boolean individually = unitSwitch && anyRowSpreadAbove(df, 0.5 * splitMax);

        if (MIN_RATIO_TO_REPAIR < split && split < MAX_RATIO_TO_REPAIR) {
            LOG.debug("{}: split ratio too close to 1, won't repair", symbol);
            return df;
        }

        Frame sorted = df.inZone(ctx.zone()).sortedDescending();
        boolean[] nanRow = nanPriceRows(sorted);
        Frame df2Nan = sorted.select(nanRow);
        Frame df2 = sorted.select(not(nanRow));
        int n = df2.size();
        if (n == 0) {
            return df;
        }
        double[] vol = df2.col(VOLUME);
        if (allZero(vol)) {
            LOG.debug("{}: no volume data", symbol);
            return df;
        }
        boolean[] noActivity = new boolean[n];
        boolean anyNoActivity = false;
        for (int i = 0; i < n; i++) {
            noActivity[i] = vol[i] == 0;
            anyNoActivity |= noActivity[i];
        }
        boolean appearsSuspended = anyNoActivity && noActivity[0];
        Integer idxLatestActive = latestActiveIndex(noActivity);

        double[] denoised = denoisedChange(df2, notDaily, split);
        double bound = (splitMax - 1) * 0.5 + 1;
        double dMax = Arrays.stream(denoised).max().orElse(1.0);
        double dMin = Arrays.stream(denoised).min().orElse(1.0);
        if (dMax < bound && dMin > 1.0 / bound) {
            return df;
        }

        double sdPct = iqrSdPct(denoised);
        double largestChangePct = scaleForInterval(VOLATILITY_SDS * sdPct, interval, notDaily);
        if (Math.max(split, splitRcp) < 1.0 + largestChangePct) {
            LOG.debug("{}: split ratio too close to normal price volatility, won't repair", symbol);
            return df;
        }
        double threshold = 1 + (splitMax - 1 + largestChangePct) * 0.6;

        double[][] x = individually ? columnChanges(df2) : asColumn(denoised);
        int kk = x[0].length;
        boolean[][] fDown = new boolean[n][kk];
        boolean[][] fUp = new boolean[n][kk];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < kk; j++) {
                fDown[i][j] = x[i][j] < 1.0 / threshold;
                fUp[i][j] = x[i][j] > threshold;
            }
        }
        double[] oneD = new double[n];
        for (int i = 0; i < n; i++) {
            oneD[i] = Stats.round(denoised[i], 3);
        }

        ignoreDropsOnVolumeSpikes(df2, vol, fUp, fDown, symbol);
        if (!individually) {
            ignoreLocalVolatility(df2, interval, notDaily, splitMax, oneD, fUp, fDown, symbol);
        }
        boolean[][] f = new boolean[n][kk];
        boolean anyF = false;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < kk; j++) {
                f[i][j] = fDown[i][j] || fUp[i][j];
                anyF |= f[i][j];
            }
        }
        if (!anyF) {
            LOG.debug("{}: no {} detected", symbol, unitSwitch ? "100x errors" : "bad splits");
            return df;
        }

        // 100x changes soon after a stock split could be the split itself
        if (change == 100.0 || change == 0.01) {
            int[] splitRows = Frame.where(nonZero(df2.col(SPLITS)));
            if (splitRows.length > 0 && tooSoonAfterSplit(df2, f, splitRows, interval)) {
                LOG.info("{}: 100x changes are too soon after stock split events, aborting", symbol);
                return df;
            }
        }

        Integer idxRevLatestActive = idxLatestActive == null ? null : df.size() - 1 - idxLatestActive;
        boolean suspendedMidway = appearsSuspended && idxLatestActive != null && idxLatestActive >= firstRow(f);
        RangeFinder finder =
                new RangeFinder(df2, split, startMin, suspendedMidway, idxLatestActive, idxRevLatestActive);
        Signals signals = new Signals(f, fUp, fDown);
        boolean[] repairedHere = new boolean[n];
        boolean anyMLt1 = individually
                ? repairColumns(df2, vol, signals, finder, correctVolume, repairedHere, symbol)
                : repairRanges(
                        df2, vol, signals, finder, correctVolume, correctDividend, repairedHere, symbol, unitSwitch);

        if (unitSwitch && anyMLt1 && ctx.currencyRepaired()) {
            // the switch was repaired in favour of the major unit, which currency standardisation already did
            revertToMajorUnit(df2, vol, change, correctVolume, correctDividend, repairedHere);
        }
        boolean[] rep = df2.repaired();
        for (int i = 0; i < n; i++) {
            rep[i] |= repairedHere[i];
            if (correctVolume && !Double.isNaN(vol[i])) {
                vol[i] = Math.rint(vol[i]);
            }
        }
        return Frame.concat(df2, df2Nan).sortedAscending();
    }

    // ---- detection ----

    /** Bad splits are only looked for from a year before the first split. */
    private static @Nullable LocalDate splitSearchStart(Frame df, RepairContext ctx) {
        ZonedDateTime first = null;
        for (int i = 0; i < df.size(); i++) {
            if (df.get(SPLITS, i) != 0.0 && (first == null || df.time(i).isBefore(first))) {
                first = df.time(i);
            }
        }
        return first == null
                ? null
                : first.withZoneSameInstant(ctx.zone()).minusYears(1).toLocalDate();
    }

    /** Whether any bar's high/low spread exceeds {@code limit}: then columns are checked individually. */
    private static boolean anyRowSpreadAbove(Frame df, double limit) {
        for (int i = 0; i < df.size(); i++) {
            double mx = Double.NaN;
            double mn = Double.NaN;
            for (Col c : OHLC) {
                mx = Stats.nanMax(mx, df.get(c, i));
                mn = Stats.nanMin(mn, df.get(c, i));
            }
            if (Math.abs(mx / mn - 1) > limit) {
                return true;
            }
        }
        return false;
    }

    private static boolean[] nanPriceRows(Frame f) {
        boolean[] nanRow = new boolean[f.size()];
        for (int i = 0; i < f.size(); i++) {
            for (Col c : OHLC) {
                nanRow[i] |= Double.isNaN(f.get(c, i));
            }
        }
        return nanRow;
    }

    /** The newest row active on both itself and its neighbour, else the newest active row. */
    private static @Nullable Integer latestActiveIndex(boolean[] noActivity) {
        int n = noActivity.length;
        for (int i = 0; i < n; i++) {
            if (!noActivity[i] && !noActivity[(i - 1 + n) % n]) {
                return i;
            }
        }
        for (int i = 0; i < n; i++) {
            if (!noActivity[i]) {
                return i;
            }
        }
        return null;
    }

    /** Daily change per row on dividend-adjusted prices, denoised across the price columns. */
    private static double[] denoisedChange(Frame df2, boolean notDaily, double split) {
        int n = df2.size();
        Col[] priceCols = notDaily && split != 100.0 && split != 0.001 ? new Col[] {OPEN, CLOSE} : OHLC;
        int k = priceCols.length;
        double[][] priceData = new double[n][k];
        boolean[] closeZero = new boolean[n];
        for (int i = 0; i < n; i++) {
            closeZero[i] = df2.get(CLOSE, i) == 0;
            double adj = closeZero[i] ? 1.0 : df2.get(ADJ_CLOSE, i) / df2.get(CLOSE, i);
            for (int j = 0; j < k; j++) {
                double v = df2.get(priceCols[j], i);
                priceData[i][j] = (v == 0.0 ? 1.0 : v) * adj;
            }
        }
        double[][] change1d = new double[n][k];
        Arrays.fill(change1d[0], 1.0);
        for (int i = 1; i < n; i++) {
            for (int j = 0; j < k; j++) {
                change1d[i][j] = priceData[i][j] / priceData[i - 1][j];
            }
        }
        for (int i = 0; i < n; i++) {
            if (closeZero[i] || closeZero[(i - 1 + n) % n]) {
                Arrays.fill(change1d[i], 1.0);
            }
        }
        double[] denoised = new double[n];
        for (int i = 0; i < n; i++) {
            denoised[i] = notDaily ? Stats.mean(change1d[i]) : Stats.median(change1d[i]);
            if (Double.isNaN(denoised[i])) {
                denoised[i] = 1.0;
            }
        }
        return denoised;
    }

    /** Row-to-row change of each OHLC column, zeros treated as 1. */
    private static double[][] columnChanges(Frame df2) {
        int n = df2.size();
        double[][] x = new double[n][OHLC.length];
        Arrays.fill(x[0], 1.0);
        for (int i = 1; i < n; i++) {
            for (int j = 0; j < OHLC.length; j++) {
                double cur = df2.get(OHLC[j], i);
                double prev = df2.get(OHLC[j], i - 1);
                x[i][j] = (cur == 0.0 ? 1.0 : cur) / (prev == 0.0 ? 1.0 : prev);
            }
        }
        return x;
    }

    private static double[][] asColumn(double[] v) {
        double[][] x = new double[v.length][1];
        for (int i = 0; i < v.length; i++) {
            x[i][0] = v[i];
        }
        return x;
    }

    /** A price drop on a volume spike is probably a real event, not a missing split. */
    private static void ignoreDropsOnVolumeSpikes(
            Frame df2, double[] vol, boolean[][] fUp, boolean[][] fDown, String symbol) {
        boolean[] fUpShifts = anyPerRow(fUp);
        if (!Frame.any(fUpShifts)) {
            return;
        }
        int n = df2.size();
        boolean[] notUp = not(fUpShifts);
        int[] flat = Frame.where(notUp);
        List<Instant> downTimes = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (anyRow(fDown[i])) {
                downTimes.add(df2.time(i).toInstant());
            }
        }
        for (int idx : Frame.where(fUpShifts)) {
            int i = idx - 1;
            if (i < 0) {
                continue;
            }
            Instant dt = df2.time(i).toInstant();
            double[] after = volumesAfter(df2, vol, notUp, flat, downTimes, i);
            if (after == null || after.length == 0) {
                continue;
            }
            if (Math.max(zScore(vol[i], after), zScore(after[0], after)) > VOLUME_SPIKE_Z) {
                LOG.debug("{}: false-positive split error on {}, ignoring price drop", symbol, dt);
                Arrays.fill(fUp[idx], false);
            }
        }
    }

    /**
     * Volumes of the unflagged rows near row {@code i} from a day after it until a day before the
     * next drop, ascending; null if the next drop is on row {@code i} itself.
     */
    private static double @Nullable [] volumesAfter(
            Frame df2, double[] vol, boolean[] notUp, int[] flat, List<Instant> downTimes, int i) {
        Instant dt = df2.time(i).toInstant();
        int pos = 0;
        for (int q = 0; q < i; q++) {
            if (notUp[q]) {
                pos++;
            }
        }
        int start = Math.max(0, pos - VOLUME_WINDOW_HALF);
        int end = Math.min(flat.length, start + VOLUME_WINDOW);
        List<double[]> block = new ArrayList<>(); // {epochSecond, volume}, ascending
        for (int q = end - 1; q >= start; q--) {
            block.add(new double[] {df2.time(flat[q]).toEpochSecond(), vol[flat[q]]});
        }
        block.sort((a, b) -> Double.compare(a[0], b[0]));
        Instant from = dt.plus(Duration.ofDays(1));
        Instant to = null;
        Instant nextDown = null;
        for (Instant t : downTimes) {
            if (!t.isBefore(dt) && (nextDown == null || t.isBefore(nextDown))) {
                nextDown = t;
            }
        }
        if (nextDown != null) {
            if (nextDown.equals(dt)) {
                return null;
            }
            to = nextDown.minus(Duration.ofDays(1));
        }
        List<Double> after = new ArrayList<>();
        for (double[] b : block) {
            long t = (long) b[0];
            if (t >= from.getEpochSecond() && (to == null || t <= to.getEpochSecond())) {
                after.add(b[1]);
            }
        }
        return after.stream().mapToDouble(Double::doubleValue).toArray();
    }

    /** Drops signals whose change is within the volatility of the clean bars just before them. */
    private static void ignoreLocalVolatility(
            Frame df2,
            Interval interval,
            boolean notDaily,
            double splitMax,
            double[] oneD,
            boolean[][] fUp,
            boolean[][] fDown,
            String symbol) {
        int n = df2.size();
        boolean[] wf = new boolean[n];
        for (int i = 0; i < n; i++) {
            wf[i] = fDown[i][0] || fUp[i][0];
        }
        int lookback = interval.code().endsWith("d") ? 10 : interval.code().endsWith("m") ? 100 : 3;
        for (int idx : Frame.where(wf.clone())) {
            int idxEnd = Math.min(n - 1, idx + 2);
            int idxStart = Math.max(0, idx - lookback);
            List<Double> clean = new ArrayList<>();
            for (int q = idxStart; q < idxEnd; q++) {
                if (!wf[q]) {
                    clean.add(oneD[q]);
                }
            }
            double[] cc = clean.stream().mapToDouble(Double::doubleValue).toArray();
            double sp = Stats.std(cc, 0) / Stats.mean(cc);
            double lcp = scaleForInterval(VOLATILITY_SDS * sp, interval, notDaily);
            double thr = 1 + (splitMax - 1 + lcp) * 0.5;
            double big = oneD[idx];
            if (big < thr && big > 1.0 / thr) {
                LOG.debug(
                        "{}: unusual price action @ {} is similar to local volatility, ignoring",
                        symbol,
                        df2.time(idx));
                wf[idx] = false;
            }
        }
        for (int i = 0; i < n; i++) {
            fDown[i][0] &= wf[i];
            fUp[i][0] &= wf[i];
        }
    }

    // ---- repair ----

    /** Change signals per row and column: any, upwards and downwards. */
    private record Signals(boolean[][] f, boolean[][] up, boolean[][] down) {}

    /** Maps one column of signals to ranges, honouring a midway suspension and the search start. */
    private record RangeFinder(
            Frame df2,
            double split,
            @Nullable LocalDate startMin,
            boolean suspendedMidway,
            @Nullable Integer idxLatestActive,
            @Nullable Integer idxRevLatestActive) {

        List<Range> find(Signals s, int j) {
            boolean[] f = column(s.f(), j);
            boolean[] up = column(s.up(), j);
            boolean[] down = column(s.down(), j);
            List<Range> ranges = suspendedMidway
                    ? suspendedRanges(f, up, down, idxLatestActive, idxRevLatestActive, df2.size(), split)
                    : ranges(f, up, down, split);
            prune(ranges, df2, startMin);
            return ranges;
        }
    }

    /** Scales each OHLC column within its own ranges; returns whether any range was scaled down. */
    private static boolean repairColumns(
            Frame df2,
            double[] vol,
            Signals signals,
            RangeFinder finder,
            boolean correctVolume,
            boolean[] repairedHere,
            String symbol) {
        int n = df2.size();
        double split = finder.split();
        double splitRcp = 1.0 / split;
        boolean anyMLt1 = false;
        boolean[] corrected = new boolean[n];
        boolean[] openFixed = new boolean[n];
        boolean[] closeFixed = new boolean[n];
        List<List<Range>> perColumn = new ArrayList<>();
        int count = 0;
        for (int j = 0; j < OHLC.length; j++) {
            List<Range> ranges = finder.find(signals, j);
            perColumn.add(ranges);
            if (!ranges.isEmpty()) {
                count++;
            }
        }
        double lastMRcp = Double.NaN;
        if (count == 1) {
            LOG.debug("{}: potential 100x error in only one column, treating as false positive", symbol);
        } else if (count > 1) {
            for (int j = 0; j < OHLC.length; j++) {
                Col c = OHLC[j];
                for (Range r : perColumn.get(j)) {
                    double m = r.applySplit() ? split : splitRcp;
                    lastMRcp = r.applySplit() ? splitRcp : split;
                    anyMLt1 |= m < SCALED_DOWN;
                    for (int i = clamp(r.from(), n); i < clamp(r.to(), n); i++) {
                        df2.set(c, i, df2.get(c, i) * m);
                        if (c == CLOSE) {
                            df2.set(ADJ_CLOSE, i, df2.get(ADJ_CLOSE, i) * m);
                        }
                        if (c == OPEN) {
                            openFixed[i] = true;
                        } else if (c == CLOSE) {
                            closeFixed[i] = true;
                        }
                        corrected[i] = true;
                    }
                }
            }
            // Yahoo derived low/high from the bad prices
            for (int j = 0; j < OHLC.length; j++) {
                for (Range r : perColumn.get(j)) {
                    double m = r.applySplit() ? split : splitRcp;
                    for (int i = clamp(r.from(), n); i < clamp(r.to(), n); i++) {
                        if (m > 1) {
                            df2.set(LOW, i, Stats.nanMin(df2.get(OPEN, i), df2.get(CLOSE, i)));
                        } else {
                            df2.set(HIGH, i, Stats.nanMax(df2.get(OPEN, i), df2.get(CLOSE, i)));
                        }
                    }
                }
            }
        }
        if (correctVolume) {
            for (int i = 0; i < n; i++) {
                if (openFixed[i] && closeFixed[i]) {
                    vol[i] = Math.rint(vol[i] * lastMRcp);
                } else if (openFixed[i] ^ closeFixed[i]) {
                    vol[i] = Math.rint(vol[i] * 0.5 * lastMRcp);
                }
            }
        }
        for (int i = 0; i < n; i++) {
            repairedHere[i] |= corrected[i];
        }
        return anyMLt1;
    }

    /** Scales whole rows within the ranges; returns whether any range was scaled down. */
    private static boolean repairRanges(
            Frame df2,
            double[] vol,
            Signals signals,
            RangeFinder finder,
            boolean correctVolume,
            boolean correctDividend,
            boolean[] repairedHere,
            String symbol,
            boolean unitSwitch) {
        int n = df2.size();
        double split = finder.split();
        double splitRcp = 1.0 / split;
        boolean anyMLt1 = false;
        List<Range> ranges = finder.find(signals, 0);
        for (Range r : ranges) {
            double m = r.applySplit() ? split : splitRcp;
            double mRcp = r.applySplit() ? splitRcp : split;
            anyMLt1 |= m < SCALED_DOWN;
            for (int i = clamp(r.from(), n); i < clamp(r.to(), n); i++) {
                for (Col c : PRICES_ADJ) {
                    df2.set(c, i, df2.get(c, i) * m);
                }
                if (correctDividend) {
                    df2.set(DIVIDENDS, i, df2.get(DIVIDENDS, i) * m);
                }
                if (correctVolume) {
                    vol[i] = Math.rint(vol[i] * mRcp);
                }
                repairedHere[i] = true;
            }
        }
        if (!ranges.isEmpty()) {
            LOG.info(
                    "{}: corrected {} in {} {} bars",
                    symbol,
                    unitSwitch ? "100x error" : "bad split",
                    ranges.size() == 1 ? "one range of" : ranges.size() + " ranges of",
                    ranges.stream().mapToInt(r -> r.to() - r.from()).sum());
        }
        return anyMLt1;
    }

    /** Scales every row by {@code change} and flips which rows count as repaired. */
    private static void revertToMajorUnit(
            Frame df2,
            double[] vol,
            double change,
            boolean correctVolume,
            boolean correctDividend,
            boolean[] repairedHere) {
        for (int i = 0; i < df2.size(); i++) {
            for (Col c : PRICES_ADJ) {
                df2.set(c, i, df2.get(c, i) * change);
            }
            if (correctDividend) {
                df2.set(DIVIDENDS, i, df2.get(DIVIDENDS, i) * change);
            }
            if (correctVolume) {
                vol[i] = Math.rint(vol[i] / change);
            }
            repairedHere[i] = !repairedHere[i];
        }
    }

    // ---- range mapping ----

    /** yfinance's {@code map_signals_to_ranges}: pairs consecutive signals into ranges. */
    static List<Range> ranges(boolean[] f, boolean[] up, boolean[] down, double split) {
        List<Range> ranges = new ArrayList<>();
        int len = f.length;
        List<Integer> trues = new ArrayList<>();
        for (int i = 1; i < len; i++) { // a signal on the first row is nonsense
            if (f[i]) {
                trues.add(i);
            }
        }
        if (trues.isEmpty()) {
            return ranges;
        }
        for (int i = 0; i < trues.size() - 1; i++) {
            if (i % 2 == 0) {
                ranges.add(new Range(trues.get(i), trues.get(i + 1), applySplit(down[trues.get(i)], split)));
            }
        }
        if (trues.size() % 2 != 0) {
            int last = trues.getLast();
            ranges.add(new Range(last, len, applySplit(down[last], split)));
        }
        return ranges;
    }

    private static boolean applySplit(boolean down, double split) {
        return split > 1.0 ? down : !down;
    }

    /**
     * Ranges for a symbol suspended midway: signals before the suspension are read newest first, the
     * ones after it in reverse.
     */
    private static List<Range> suspendedRanges(
            boolean[] f,
            boolean[] up,
            boolean[] down,
            int idxLatestActive,
            int idxRevLatestActive,
            int n,
            double split) {
        List<Range> result = new ArrayList<>();
        for (Range r :
                ranges(tail(f, idxLatestActive), tail(up, idxLatestActive), tail(down, idxLatestActive), split)) {
            result.add(new Range(r.from() + idxLatestActive, r.to() + idxLatestActive, r.applySplit()));
        }
        int len = f.length;
        boolean[] revDown = new boolean[len];
        boolean[] revUp = new boolean[len];
        boolean[] rev = new boolean[len];
        for (int i = 0; i < len; i++) {
            // np.flip(np.roll(x, -1))
            int src = (len - 1 - i + 1) % len;
            revDown[i] = up[src];
            revUp[i] = down[src];
            rev[i] = revUp[i] || revDown[i];
        }
        for (Range r : ranges(
                tail(rev, idxRevLatestActive),
                tail(revUp, idxRevLatestActive),
                tail(revDown, idxRevLatestActive),
                split)) {
            int from = r.from() + idxRevLatestActive;
            int to = r.to() + idxRevLatestActive;
            result.add(new Range(n - to, n - from, r.applySplit()));
        }
        return result;
    }

    private static void prune(List<Range> ranges, Frame df2, @Nullable LocalDate startMin) {
        if (startMin == null) {
            return;
        }
        ranges.removeIf(r -> {
            int i = clamp(r.from(), df2.size() - 1);
            return df2.time(i).toLocalDate().isBefore(startMin);
        });
    }

    // ---- helpers ----

    private static boolean tooSoonAfterSplit(Frame df2, boolean[][] f, int[] splitRows, Interval interval) {
        List<Integer> fRows = new ArrayList<>();
        for (int i = 0; i < f.length; i++) {
            for (boolean b : f[i]) {
                if (b) {
                    fRows.add(i);
                }
            }
        }
        int gapMin = Integer.MAX_VALUE;
        int gapRow = -1;
        for (int b = 0; b < fRows.size(); b++) {
            for (int a : splitRows) {
                int gap = a - fRows.get(b); // newest first, so a later split has a smaller index
                if (gap > 0 && gap < gapMin) {
                    gapMin = gap;
                    gapRow = b;
                }
            }
        }
        if (gapRow < 0) {
            return false;
        }
        if (Intervals.interday(interval) || interval == Interval.FIVE_DAYS) {
            ZonedDateTime dt = df2.time(Math.min(gapRow, df2.size() - 1));
            return Intervals.plus(dt, interval, gapMin).isBefore(dt.plusDays(30));
        }
        return interval.nominalDuration().multipliedBy(gapMin).compareTo(Duration.ofDays(30)) < 0;
    }

    /** {@code sd / mean} of the values within 1.5 IQR of the quartiles. */
    static double iqrSdPct(double[] v) {
        double q1 = Stats.percentile(v, 25);
        double q3 = Stats.percentile(v, 75);
        double iqr = q3 - q1;
        double lo = q1 - 1.5 * iqr;
        double hi = q3 + 1.5 * iqr;
        double[] kept = Arrays.stream(v).filter(x -> x >= lo && x <= hi).toArray();
        return Stats.std(kept, 0) / Stats.mean(kept);
    }

    private static double scaleForInterval(double pct, Interval interval, boolean notDaily) {
        if (notDaily) {
            pct *= 3;
            if (interval == Interval.ONE_MONTH || interval == Interval.THREE_MONTHS) {
                pct *= 2;
            }
        }
        return pct;
    }

    private static double zScore(double volume, double[] values) {
        boolean allZero = true;
        for (double v : values) {
            allZero &= v == 0;
        }
        if (values.length <= 1 || allZero) {
            return 0;
        }
        double std = Stats.std(values, 1);
        if (std == 0.0) {
            return 0;
        }
        return (volume - Stats.mean(values)) / std;
    }

    private static int clamp(int i, int n) {
        return Math.max(0, Math.min(i, n));
    }

    private static boolean[] tail(boolean[] a, int from) {
        int f = clamp(from, a.length);
        return Arrays.copyOfRange(a, f, a.length);
    }

    private static boolean[] column(boolean[][] m, int j) {
        boolean[] c = new boolean[m.length];
        for (int i = 0; i < m.length; i++) {
            c[i] = m[i][j];
        }
        return c;
    }

    private static boolean[] anyPerRow(boolean[][] m) {
        boolean[] r = new boolean[m.length];
        for (int i = 0; i < m.length; i++) {
            r[i] = anyRow(m[i]);
        }
        return r;
    }

    private static boolean anyRow(boolean[] row) {
        for (boolean b : row) {
            if (b) {
                return true;
            }
        }
        return false;
    }

    private static int firstRow(boolean[][] m) {
        for (int i = 0; i < m.length; i++) {
            if (anyRow(m[i])) {
                return i;
            }
        }
        return -1;
    }

    private static boolean allZero(double[] a) {
        for (double v : a) {
            if (v != 0.0) {
                return false;
            }
        }
        return true;
    }

    static boolean[] not(boolean[] a) {
        boolean[] r = new boolean[a.length];
        for (int i = 0; i < a.length; i++) {
            r[i] = !a[i];
        }
        return r;
    }

    static boolean[] nonZero(double[] a) {
        boolean[] r = new boolean[a.length];
        for (int i = 0; i < a.length; i++) {
            r[i] = a[i] != 0.0;
        }
        return r;
    }
}
