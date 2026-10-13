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
        boolean interday = Intervals.interday(interval);
        boolean multiday = Intervals.multiday(interval);
        boolean notDaily = interday && interval != Interval.ONE_DAY;

        @Nullable LocalDate startMin = null;
        if (!unitSwitch) {
            ZonedDateTime first = null;
            for (int i = 0; i < df.size(); i++) {
                if (df.get(SPLITS, i) != 0.0 && (first == null || df.time(i).isBefore(first))) {
                    first = df.time(i);
                }
            }
            if (first != null) {
                startMin = first.withZoneSameInstant(ctx.zone()).minusYears(1).toLocalDate();
            }
        }

        boolean individually = false;
        if (unitSwitch) {
            for (int i = 0; i < df.size(); i++) {
                double mx = Double.NaN;
                double mn = Double.NaN;
                for (Col c : OHLC) {
                    mx = Stats.nanMax(mx, df.get(c, i));
                    mn = Stats.nanMin(mn, df.get(c, i));
                }
                if (Math.abs(mx / mn - 1) > 0.5 * splitMax) {
                    individually = true;
                    break;
                }
            }
        }

        if (0.8 < split && split < 1.25) {
            LOG.debug("{}: split ratio too close to 1, won't repair", ctx.symbol());
            return df;
        }

        Frame sorted = df.inZone(ctx.zone()).sortedDescending();
        boolean[] nanRow = new boolean[sorted.size()];
        for (int i = 0; i < sorted.size(); i++) {
            for (Col c : OHLC) {
                nanRow[i] |= Double.isNaN(sorted.get(c, i));
            }
        }
        Frame df2Nan = sorted.select(nanRow);
        Frame df2 = sorted.select(not(nanRow));
        int n = df2.size();
        if (n == 0) {
            return df;
        }
        double[] vol = df2.col(VOLUME);

        boolean[] noActivity = new boolean[n];
        boolean anyNoActivity = false;
        for (int i = 0; i < n; i++) {
            noActivity[i] = vol[i] == 0;
            anyNoActivity |= noActivity[i];
        }
        boolean appearsSuspended = anyNoActivity && noActivity[0];
        Integer idxLatestActive = null;
        for (int i = 0; i < n && idxLatestActive == null; i++) {
            if (!noActivity[i] && !noActivity[(i - 1 + n) % n]) {
                idxLatestActive = i;
            }
        }
        for (int i = 0; i < n && idxLatestActive == null; i++) {
            if (!noActivity[i]) {
                idxLatestActive = i;
            }
        }

        // daily change per price column, on dividend-adjusted prices
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
        for (int j = 0; j < k; j++) {
            change1d[0][j] = 1.0;
        }
        for (int i = 1; i < n; i++) {
            for (int j = 0; j < k; j++) {
                change1d[i][j] = priceData[i][j] / priceData[i - 1][j];
            }
        }
        boolean allVolZero = true;
        for (double v : vol) {
            allVolZero &= v == 0.0;
        }
        if (allVolZero) {
            LOG.debug("{}: no volume data", ctx.symbol());
            return df;
        }
        for (int i = 0; i < n; i++) {
            if (closeZero[i] || closeZero[(i - 1 + n) % n]) {
                java.util.Arrays.fill(change1d[i], 1.0);
            }
        }
        double[] denoised = new double[n];
        for (int i = 0; i < n; i++) {
            denoised[i] = notDaily ? Stats.mean(change1d[i]) : Stats.median(change1d[i]);
            if (Double.isNaN(denoised[i])) {
                denoised[i] = 1.0;
            }
        }

        double bound = (splitMax - 1) * 0.5 + 1;
        double dMax = java.util.Arrays.stream(denoised).max().orElse(1.0);
        double dMin = java.util.Arrays.stream(denoised).min().orElse(1.0);
        if (dMax < bound && dMin > 1.0 / bound) {
            return df;
        }

        double sdPct = iqrSdPct(denoised);
        double largestChangePct = scaleForInterval(5 * sdPct, interval, notDaily);
        if (Math.max(split, splitRcp) < 1.0 + largestChangePct) {
            LOG.debug("{}: split ratio too close to normal price volatility, won't repair", ctx.symbol());
            return df;
        }
        double threshold = 1 + (splitMax - 1 + largestChangePct) * 0.6;

        int kk = individually ? 4 : 1;
        double[][] x = new double[n][kk];
        if (individually) {
            for (int j = 0; j < 4; j++) {
                x[0][j] = 1.0;
            }
            for (int i = 1; i < n; i++) {
                for (int j = 0; j < 4; j++) {
                    double cur = df2.get(OHLC[j], i);
                    double prev = df2.get(OHLC[j], i - 1);
                    x[i][j] = (cur == 0.0 ? 1.0 : cur) / (prev == 0.0 ? 1.0 : prev);
                }
            }
        } else {
            for (int i = 0; i < n; i++) {
                x[i][0] = denoised[i];
            }
        }
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

        // a price drop on a volume spike is probably a real event, not a missing split
        boolean[] fUpShifts = anyPerRow(fUp);
        if (Frame.any(fUpShifts)) {
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
                double v = vol[i];
                int pos = 0;
                for (int q = 0; q < i; q++) {
                    if (notUp[q]) {
                        pos++;
                    }
                }
                int start = Math.max(0, pos - 15);
                int end = Math.min(flat.length, start + 31);
                List<double[]> block = new ArrayList<>(); // {epochSecond, volume}, ascending
                for (int q = end - 1; q >= start; q--) {
                    block.add(new double[] {df2.time(flat[q]).toEpochSecond(), vol[flat[q]]});
                }
                block.sort((a, b) -> Double.compare(a[0], b[0]));
                Instant from = dt.plus(Duration.ofDays(1));
                Instant to = null;
                boolean none = false;
                Instant nextDown = null;
                for (Instant t : downTimes) {
                    if (!t.isBefore(dt) && (nextDown == null || t.isBefore(nextDown))) {
                        nextDown = t;
                    }
                }
                if (nextDown != null) {
                    if (nextDown.equals(dt)) {
                        none = true;
                    } else {
                        to = nextDown.minus(Duration.ofDays(1));
                    }
                }
                if (none) {
                    continue;
                }
                List<Double> after = new ArrayList<>();
                for (double[] b : block) {
                    long t = (long) b[0];
                    if (t >= from.getEpochSecond() && (to == null || t <= to.getEpochSecond())) {
                        after.add(b[1]);
                    }
                }
                if (after.isEmpty()) {
                    continue;
                }
                double[] a = after.stream().mapToDouble(Double::doubleValue).toArray();
                if (Math.max(zScore(v, a), zScore(a[0], a)) > 2) {
                    LOG.debug("{}: false-positive split error on {}, ignoring price drop", ctx.symbol(), dt);
                    java.util.Arrays.fill(fUp[idx], false);
                }
            }
        }

        if (!individually) {
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
                double lcp = scaleForInterval(5 * sp, interval, notDaily);
                double thr = 1 + (splitMax - 1 + lcp) * 0.5;
                double big = oneD[idx];
                if (big < thr && big > 1.0 / thr) {
                    LOG.debug(
                            "{}: unusual price action @ {} is similar to local volatility, ignoring",
                            ctx.symbol(),
                            df2.time(idx));
                    wf[idx] = false;
                }
            }
            for (int i = 0; i < n; i++) {
                fDown[i][0] &= wf[i];
                fUp[i][0] &= wf[i];
            }
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
            LOG.debug("{}: no {} detected", ctx.symbol(), unitSwitch ? "100x errors" : "bad splits");
            return df;
        }

        // 100x changes soon after a stock split could be the split itself
        if (change == 100.0 || change == 0.01) {
            int[] splitRows = Frame.where(nonZero(df2.col(SPLITS)));
            if (splitRows.length > 0 && tooSoonAfterSplit(df2, f, splitRows, interval)) {
                LOG.info("{}: 100x changes are too soon after stock split events, aborting", ctx.symbol());
                return df;
            }
        }

        boolean anyMLt1 = false;
        Integer idxRevLatestActive = idxLatestActive == null ? null : df.size() - 1 - idxLatestActive;
        boolean[] repairedHere = new boolean[n];
        int idxFirstF = firstRow(f);
        boolean suspendedMidway = appearsSuspended && idxLatestActive != null && idxLatestActive >= idxFirstF;

        if (individually) {
            boolean[] corrected = new boolean[n];
            boolean[] openFixed = new boolean[n];
            boolean[] closeFixed = new boolean[n];
            List<List<Range>> perColumn = new ArrayList<>();
            int count = 0;
            for (int j = 0; j < 4; j++) {
                boolean[] fj = column(f, j);
                boolean[] uj = column(fUp, j);
                boolean[] dj = column(fDown, j);
                List<Range> ranges = suspendedMidway
                        ? suspendedRanges(fj, uj, dj, idxLatestActive, idxRevLatestActive, n, split)
                        : ranges(fj, uj, dj, split);
                prune(ranges, df2, startMin);
                perColumn.add(ranges);
                if (!ranges.isEmpty()) {
                    count++;
                }
            }
            double lastMRcp = Double.NaN;
            if (count == 1) {
                LOG.debug("{}: potential 100x error in only one column, treating as false positive", ctx.symbol());
            } else if (count > 1) {
                for (int j = 0; j < 4; j++) {
                    Col c = OHLC[j];
                    for (Range r : perColumn.get(j)) {
                        double m = r.applySplit() ? split : splitRcp;
                        lastMRcp = r.applySplit() ? splitRcp : split;
                        anyMLt1 |= m < 0.99;
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
                for (int j = 0; j < 4; j++) {
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
        } else {
            boolean[] f1 = column(f, 0);
            boolean[] u1 = column(fUp, 0);
            boolean[] d1 = column(fDown, 0);
            List<Range> ranges = suspendedMidway
                    ? suspendedRanges(f1, u1, d1, idxLatestActive, idxRevLatestActive, n, split)
                    : ranges(f1, u1, d1, split);
            prune(ranges, df2, startMin);
            for (Range r : ranges) {
                double m = r.applySplit() ? split : splitRcp;
                double mRcp = r.applySplit() ? splitRcp : split;
                anyMLt1 |= m < 0.99;
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
                        ctx.symbol(),
                        unitSwitch ? "100x error" : "bad split",
                        ranges.size() == 1 ? "one range of" : ranges.size() + " ranges of",
                        ranges.stream().mapToInt(r -> r.to() - r.from()).sum());
            }
        }

        if (unitSwitch && anyMLt1 && ctx.currencyRepaired()) {
            // the switch was repaired in favour of the major unit, which currency standardisation already did
            for (int i = 0; i < n; i++) {
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
        boolean[] rep = df2.repaired();
        for (int i = 0; i < n; i++) {
            rep[i] |= repairedHere[i];
            if (correctVolume && !Double.isNaN(vol[i])) {
                vol[i] = Math.rint(vol[i]);
            }
        }
        return Frame.concat(df2, df2Nan).sortedAscending();
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
        double[] kept =
                java.util.Arrays.stream(v).filter(x -> x >= lo && x <= hi).toArray();
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
        return java.util.Arrays.copyOfRange(a, f, a.length);
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
