package io.andreygrigoriev.yfin.internal.repair;

import static io.andreygrigoriev.yfin.internal.repair.Frame.Col.ADJ_CLOSE;
import static io.andreygrigoriev.yfin.internal.repair.Frame.Col.CLOSE;
import static io.andreygrigoriev.yfin.internal.repair.Frame.Col.HIGH;
import static io.andreygrigoriev.yfin.internal.repair.Frame.Col.LOW;
import static io.andreygrigoriev.yfin.internal.repair.Frame.Col.OPEN;
import static io.andreygrigoriev.yfin.internal.repair.Reconstruct.TAG;

import io.andreygrigoriev.yfin.internal.repair.Frame.Col;
import io.andreygrigoriev.yfin.request.Interval;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Prices Yahoo returns in currency subunits (cents, pence) instead of the main unit, or the other
 * way round: yfinance's {@code _fix_unit_switch} (a sudden switch from some date on) and {@code
 * _fix_unit_random_mixups} (sporadic 100x outliers).
 */
final class UnitMixups {

    private static final Logger LOG = LoggerFactory.getLogger(UnitMixups.class);

    /** Order matters: separates high from low in the 3x3 median filter. */
    private static final Col[] DATA = {HIGH, OPEN, LOW, CLOSE, ADJ_CLOSE};

    private UnitMixups() {}

    /** {@code _fix_unit_mixups}: the unit switch first, then random mix-ups. */
    static Frame fix(Frame df, Interval interval, boolean prepost, RepairContext ctx) {
        if (df.isEmpty()) {
            return df;
        }
        return fixRandom(fixSwitch(df, interval, ctx), interval, prepost, ctx);
    }

    /** {@code _fix_unit_switch}. */
    static Frame fixSwitch(Frame df, Interval interval, RepairContext ctx) {
        boolean[] before = df.repaired().clone();
        Frame out = SuddenChange.fix(df, interval, ctx.currencyDivide(), true, false, true, ctx);
        boolean switched = false;
        if (out.size() == before.length) {
            // compare flags by time: the result may be sorted differently from the input
            Map<Instant, Boolean> was = new HashMap<>();
            for (int i = 0; i < df.size(); i++) {
                was.put(df.time(i).toInstant(), before[i]);
            }
            for (int i = 0; i < out.size(); i++) {
                switched |= out.repaired()[i] && !was.getOrDefault(out.time(i).toInstant(), false);
            }
        }
        if (switched) {
            String c = ctx.currency();
            switch (c) {
                case "GBp", "GBP" -> ctx.currency("GBP");
                case "ZAc", "ZAR" -> ctx.currency("ZAR");
                case "ILA", "ILS" -> ctx.currency("ILS");
                default -> {}
            }
        }
        return out;
    }

    /** {@code _fix_unit_random_mixups}: outliers ~100x the local 3x3 median. */
    static Frame fixRandom(Frame df, Interval interval, boolean prepost, RepairContext ctx) {
        if (df.size() <= 1) {
            return df;
        }
        Frame all = df.inZone(ctx.zone()).sortedAscending();
        boolean[] zeroRow = new boolean[all.size()];
        for (int i = 0; i < all.size(); i++) {
            for (Col c : DATA) {
                zeroRow[i] |= all.get(c, i) == 0;
            }
        }
        Frame zeroes = all.select(zeroRow);
        Frame df2 = all.select(SuddenChange.not(zeroRow));
        Frame orig = df2.copy();
        int n = df2.size();
        if (n <= 1) {
            LOG.info("{}: insufficient good data for detecting 100x price errors", ctx.symbol());
            return df;
        }
        int k = DATA.length;
        double[][] data = new double[n][k];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < k; j++) {
                data[i][j] = df2.get(DATA[j], i);
            }
        }
        double[][] median = medianFilter3x3Wrap(data);
        boolean[][] f = new boolean[n][k];
        boolean[][] fRcp = new boolean[n][k];
        boolean any = false;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < k; j++) {
                double ratio = data[i][j] / median[i][j];
                boolean big = Math.rint(ratio / 20) * 20 == 100;
                boolean small = Math.rint((1.0 / ratio) / 20) * 20 == 100;
                f[i][j] = big;
                fRcp[i][j] = big || small;
                any |= fRcp[i][j];
            }
        }
        if (!any) {
            LOG.debug("{}: no sporadic 100x errors", ctx.symbol());
            return df;
        }
        int nBefore = 0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < k; j++) {
                if (fRcp[i][j]) {
                    df2.set(DATA[j], i, TAG);
                    nBefore++;
                }
            }
        }
        df2 = Reconstruct.reconstruct(df2, interval, prepost, ctx);
        Frame origSorted = orig;
        if (countTags(df2) > 0) {
            // crude second pass: at least make high/low consistent with open/close
            crude(df2, origSorted, f, 0.01);
            crude(df2, origSorted, fRcp, 100.0);
        }
        int left = countTags(df2);
        if (nBefore - left > 0) {
            LOG.info("{}: fixed {}/{} currency unit mixups", ctx.symbol(), nBefore - left, nBefore);
        }
        // restore original values where repair failed
        for (int i = 0; i < df2.size(); i++) {
            for (Col c : DATA) {
                if (df2.get(c, i) == TAG) {
                    df2.set(c, i, origSorted.get(c, i));
                }
            }
        }
        if (!zeroes.isEmpty()) {
            df2 = Frame.concat(df2, zeroes).sortedAscending();
        }
        return df2;
    }

    private static void crude(Frame df2, Frame orig, boolean[][] mask, double m) {
        for (int i = 0; i < df2.size(); i++) {
            boolean[] fi = new boolean[DATA.length];
            boolean anyRow = false;
            for (int j = 0; j < DATA.length; j++) {
                fi[j] = mask[i][j] && df2.get(DATA[j], i) == TAG;
                anyRow |= fi[j];
            }
            if (!anyRow) {
                continue;
            }
            for (Col c : new Col[] {OPEN, CLOSE}) {
                if (fi[index(c)]) {
                    df2.set(c, i, orig.get(c, i) * m);
                }
            }
            if (fi[index(HIGH)]) {
                df2.set(HIGH, i, Stats.nanMax(df2.get(OPEN, i), df2.get(CLOSE, i)));
            }
            if (fi[index(LOW)]) {
                df2.set(LOW, i, Stats.nanMin(df2.get(OPEN, i), df2.get(CLOSE, i)));
            }
        }
    }

    private static int index(Col c) {
        for (int j = 0; j < DATA.length; j++) {
            if (DATA[j] == c) {
                return j;
            }
        }
        throw new IllegalArgumentException(c.toString());
    }

    private static int countTags(Frame f) {
        int count = 0;
        for (int i = 0; i < f.size(); i++) {
            for (Col c : DATA) {
                if (f.get(c, i) == TAG) {
                    count++;
                }
            }
        }
        return count;
    }

    /** scipy {@code ndimage.median_filter(size=(3, 3), mode='wrap')}. */
    static double[][] medianFilter3x3Wrap(double[][] data) {
        int n = data.length;
        int k = data[0].length;
        double[][] out = new double[n][k];
        double[] window = new double[9];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < k; j++) {
                int w = 0;
                for (int di = -1; di <= 1; di++) {
                    for (int dj = -1; dj <= 1; dj++) {
                        window[w++] = data[Math.floorMod(i + di, n)][Math.floorMod(j + dj, k)];
                    }
                }
                double[] s = window.clone();
                Arrays.sort(s);
                out[i][j] = s[4];
            }
        }
        return out;
    }
}
