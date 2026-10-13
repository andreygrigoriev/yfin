package io.andreygrigoriev.yfin.internal.repair;

import static io.andreygrigoriev.yfin.internal.repair.Frame.Col.CLOSE;
import static io.andreygrigoriev.yfin.internal.repair.Frame.Col.HIGH;
import static io.andreygrigoriev.yfin.internal.repair.Frame.Col.LOW;
import static io.andreygrigoriev.yfin.internal.repair.Frame.Col.OPEN;
import static io.andreygrigoriev.yfin.internal.repair.Frame.Col.SPLITS;
import static io.andreygrigoriev.yfin.internal.repair.Frame.Col.VOLUME;
import static io.andreygrigoriev.yfin.internal.repair.Reconstruct.TAG;

import io.andreygrigoriev.yfin.internal.repair.Frame.Col;
import io.andreygrigoriev.yfin.request.Interval;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * yfinance's {@code _fix_zeroes}: prices that are zero or missing, OHLC that contradict each other
 * (close below low, ...), and volume of zero although the price moved, reconstructed from
 * finer-grained data. Most zero prices simply mean no trading, so values that cannot be
 * reconstructed are left as they were.
 */
final class Zeroes {

    private static final Logger LOG = LoggerFactory.getLogger(Zeroes.class);

    private static final Col[] PRICES = Frame.PRICES;

    private Zeroes() {}

    static Frame fix(Frame input, Interval interval, boolean prepost, RepairContext ctx) {
        if (input.isEmpty()) {
            return input;
        }
        boolean intraday = interval.isIntraday();
        Frame df = input.inZone(ctx.zone()).sortedAscending();
        Frame df2 = df.copy();
        int n = df2.size();
        boolean[][] bad = new boolean[n][PRICES.length];
        for (int i = 0; i < n; i++) {
            markZeroOrNaN(df2, i, bad[i]);
            double o = df2.get(OPEN, i);
            double h = df2.get(HIGH, i);
            double l = df2.get(LOW, i);
            double c = df2.get(CLOSE, i);
            if (c < l) {
                bad[i][3] = bad[i][2] = true;
            }
            if (c > h) {
                bad[i][3] = bad[i][1] = true;
            }
            if (o < l) {
                bad[i][0] = bad[i][2] = true;
            }
            if (o > h) {
                bad[i][0] = bad[i][1] = true;
            }
        }

        Frame reserve = null;
        if (intraday) {
            // ignore days where more than half the bars are bad
            Map<LocalDate, int[]> perDay = new HashMap<>();
            for (int i = 0; i < n; i++) {
                int[] counts = perDay.computeIfAbsent(df2.time(i).toLocalDate(), d -> new int[2]);
                counts[0] += anyRow(bad[i]) ? 1 : 0;
                counts[1]++;
            }
            boolean[] ignore = new boolean[n];
            for (int i = 0; i < n; i++) {
                int[] counts = perDay.get(df2.time(i).toLocalDate());
                ignore[i] = (double) counts[0] / counts[1] > 0.5;
            }
            reserve = df2.select(ignore);
            df2 = df2.select(SuddenChange.not(ignore));
            if (df2.isEmpty()) {
                return df;
            }
            n = df2.size();
            bad = new boolean[n][PRICES.length];
            for (int i = 0; i < n; i++) {
                markZeroOrNaN(df2, i, bad[i]);
            }
        }

        boolean[] change = new boolean[n];
        for (int i = 0; i < n; i++) {
            change[i] = df2.get(HIGH, i) != df2.get(LOW, i);
        }
        boolean[] volBad = null;
        if (!ctx.symbol().endsWith("=X")) { // FX volume is always 0
            volBad = new boolean[n];
            for (int i = 0; i < n; i++) {
                boolean hlGood = !Double.isNaN(df2.get(HIGH, i)) && !Double.isNaN(df2.get(LOW, i));
                boolean volZero = df2.get(VOLUME, i) == 0;
                volBad[i] = volZero && hlGood && change[i];
                if (!intraday && i > 0 && volZero) {
                    double diff = df2.get(CLOSE, i) - df2.get(CLOSE, i - 1);
                    volBad[i] |= Math.abs(diff / df2.get(CLOSE, i)) > 0.05;
                }
            }
        }
        // a split means trading happened
        for (int i = 0; i < n; i++) {
            if (df2.get(SPLITS, i) != 0.0 && !change[i]) {
                Arrays.fill(bad[i], true);
            }
        }

        boolean anyBad = false;
        int nBadPrices = 0;
        for (int i = 0; i < n; i++) {
            anyBad |= anyRow(bad[i]) || (volBad != null && volBad[i]);
            for (boolean b : bad[i]) {
                nBadPrices += b ? 1 : 0;
            }
        }
        if (!anyBad) {
            LOG.debug("{}: no price=0 errors to repair", ctx.symbol());
            return df;
        }
        if (nBadPrices == PRICES.length * n) {
            LOG.debug("{}: no good data for calibration, cannot fix price=0 bad data", ctx.symbol());
            return df;
        }

        for (int i = 0; i < n; i++) {
            for (int j = 0; j < PRICES.length; j++) {
                if (bad[i][j]) {
                    df2.set(PRICES[j], i, TAG);
                }
            }
            if (volBad != null && volBad[i]) {
                df2.set(VOLUME, i, TAG);
            }
            double v = df2.get(VOLUME, i);
            boolean volZeroOrNaN = v == 0 || Double.isNaN(v);
            if (volZeroOrNaN && (anyRow(bad[i]) || change[i])) {
                df2.set(VOLUME, i, TAG);
            }
        }
        int nBefore = countTags(df2);
        df2 = Reconstruct.reconstruct(df2, interval, prepost, ctx);
        int nAfter = countTags(df2);
        if (nBefore > nAfter) {
            LOG.debug(
                    "{}: fixed {}/{} value=0 errors in {} price data",
                    ctx.symbol(),
                    nBefore - nAfter,
                    nBefore,
                    interval);
        }
        if (reserve != null) {
            df2 = Frame.concat(df2, reserve).sortedAscending();
        }

        // restore original values where repair failed
        Map<Instant, Integer> origRow = new HashMap<>();
        for (int i = 0; i < df.size(); i++) {
            origRow.putIfAbsent(df.time(i).toInstant(), i);
        }
        Set<Col> data = new HashSet<>(List.of(PRICES));
        data.add(VOLUME);
        for (int i = 0; i < df2.size(); i++) {
            for (Col c : data) {
                if (df2.get(c, i) == TAG) {
                    df2.set(c, i, df.get(c, origRow.get(df2.time(i).toInstant())));
                }
            }
        }
        return df2;
    }

    private static void markZeroOrNaN(Frame f, int i, boolean[] row) {
        for (int j = 0; j < PRICES.length; j++) {
            double v = f.get(PRICES[j], i);
            row[j] = v == 0.0 || Double.isNaN(v);
        }
    }

    private static boolean anyRow(boolean[] row) {
        for (boolean b : row) {
            if (b) {
                return true;
            }
        }
        return false;
    }

    private static int countTags(Frame f) {
        int count = 0;
        for (int i = 0; i < f.size(); i++) {
            for (Col c : PRICES) {
                count += f.get(c, i) == TAG ? 1 : 0;
            }
            count += f.get(VOLUME, i) == TAG ? 1 : 0;
        }
        return count;
    }
}
