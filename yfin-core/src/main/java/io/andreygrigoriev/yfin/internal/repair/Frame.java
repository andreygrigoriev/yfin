package io.andreygrigoriev.yfin.internal.repair;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.stream.IntStream;

/**
 * A mutable price table, the counterpart of the pandas DataFrame yfinance repairs: one row per bar
 * with OHLC, adjusted close, volume and the actions that fall on the bar, plus a repaired flag per
 * row. Volume is a {@code double} so that it can hold a repair tag or NaN while repairing. Stock
 * splits are ratios, {@code 0} when there is none (yfinance's convention).
 */
public final class Frame {

    /** A value column. */
    public enum Col {
        OPEN,
        HIGH,
        LOW,
        CLOSE,
        ADJ_CLOSE,
        VOLUME,
        DIVIDENDS,
        SPLITS,
        CAPITAL_GAINS
    }

    /** yfinance's {@code _PRICE_COLNAMES_}: Open, High, Low, Close, Adj Close. */
    static final Col[] PRICES = {Col.OPEN, Col.HIGH, Col.LOW, Col.CLOSE, Col.ADJ_CLOSE};

    static final Col[] OHLC = {Col.OPEN, Col.HIGH, Col.LOW, Col.CLOSE};

    private final ZonedDateTime[] time;
    private final double[][] cols;
    private final boolean[] repaired;
    private final boolean hasCapitalGains;

    /** An all-zero table of {@code time.length} rows; prices must be filled in by the caller. */
    public Frame(ZonedDateTime[] time, boolean hasCapitalGains) {
        this.time = time.clone();
        this.cols = new double[Col.values().length][time.length];
        this.repaired = new boolean[time.length];
        this.hasCapitalGains = hasCapitalGains;
    }

    private Frame(ZonedDateTime[] time, double[][] cols, boolean[] repaired, boolean hasCapitalGains) {
        this.time = time;
        this.cols = cols;
        this.repaired = repaired;
        this.hasCapitalGains = hasCapitalGains;
    }

    public int size() {
        return time.length;
    }

    public boolean isEmpty() {
        return time.length == 0;
    }

    /** Whether the table has a capital gains column (funds and ETFs). */
    public boolean hasCapitalGains() {
        return hasCapitalGains;
    }

    public ZonedDateTime time(int row) {
        return time[row];
    }

    /** The live column array; writes change the table. */
    public double[] col(Col c) {
        return cols[c.ordinal()];
    }

    public double get(Col c, int row) {
        return cols[c.ordinal()][row];
    }

    public void set(Col c, int row, double value) {
        cols[c.ordinal()][row] = value;
    }

    /** The live repaired flags; writes change the table. */
    public boolean[] repaired() {
        return repaired;
    }

    public Frame copy() {
        double[][] c = new double[cols.length][];
        for (int i = 0; i < cols.length; i++) {
            c[i] = cols[i].clone();
        }
        return new Frame(time.clone(), c, repaired.clone(), hasCapitalGains);
    }

    /** The given rows, in the given order. */
    public Frame rows(int[] idx) {
        ZonedDateTime[] t = new ZonedDateTime[idx.length];
        double[][] c = new double[cols.length][idx.length];
        boolean[] r = new boolean[idx.length];
        for (int k = 0; k < idx.length; k++) {
            t[k] = time[idx[k]];
            r[k] = repaired[idx[k]];
            for (int j = 0; j < cols.length; j++) {
                c[j][k] = cols[j][idx[k]];
            }
        }
        return new Frame(t, c, r, hasCapitalGains);
    }

    /** Rows where {@code mask} is true. */
    public Frame select(boolean[] mask) {
        return rows(where(mask));
    }

    /** Rows {@code [from, to)}, clamped to the table. */
    public Frame slice(int from, int to) {
        int f = Math.max(0, Math.min(from, size()));
        int t = Math.max(f, Math.min(to, size()));
        return rows(IntStream.range(f, t).toArray());
    }

    /** Rows sorted by time, oldest first (stable). */
    public Frame sortedAscending() {
        return rows(order(false));
    }

    /** Rows sorted by time, newest first (stable). */
    public Frame sortedDescending() {
        return rows(order(true));
    }

    private int[] order(boolean descending) {
        Comparator<Integer> byTime = Comparator.comparing(i -> time[i].toInstant());
        return IntStream.range(0, size())
                .boxed()
                .sorted(descending ? byTime.reversed() : byTime)
                .mapToInt(Integer::intValue)
                .toArray();
    }

    /** Whether rows are in ascending time order. */
    public boolean isAscending() {
        for (int i = 1; i < size(); i++) {
            if (time[i].toInstant().isBefore(time[i - 1].toInstant())) {
                return false;
            }
        }
        return true;
    }

    /** Rows of {@code a} followed by rows of {@code b}. */
    public static Frame concat(Frame a, Frame b) {
        int n = a.size() + b.size();
        ZonedDateTime[] t = Arrays.copyOf(a.time, n);
        System.arraycopy(b.time, 0, t, a.size(), b.size());
        double[][] c = new double[a.cols.length][];
        for (int j = 0; j < c.length; j++) {
            c[j] = Arrays.copyOf(a.cols[j], n);
            System.arraycopy(b.cols[j], 0, c[j], a.size(), b.size());
        }
        boolean[] r = Arrays.copyOf(a.repaired, n);
        System.arraycopy(b.repaired, 0, r, a.size(), b.size());
        return new Frame(t, c, r, a.hasCapitalGains || b.hasCapitalGains);
    }

    /** Copy with every timestamp moved to {@code zone} (same instants). */
    public Frame inZone(ZoneId zone) {
        Frame f = copy();
        for (int i = 0; i < f.time.length; i++) {
            f.time[i] = f.time[i].withZoneSameInstant(zone);
        }
        return f;
    }

    /** Position of the row at {@code t}, or {@code -1}. */
    public int indexOf(Instant t) {
        for (int i = 0; i < time.length; i++) {
            if (time[i].toInstant().equals(t)) {
                return i;
            }
        }
        return -1;
    }

    public int indexOf(ZonedDateTime t) {
        return indexOf(t.toInstant());
    }

    /** Number of rows (of a sorted table) at or before {@code t}: pandas {@code df.loc[:t]}. */
    public int countUpTo(Instant t) {
        int k = 0;
        while (k < time.length && !time[k].toInstant().isAfter(t)) {
            k++;
        }
        return k;
    }

    /** Positions where {@code mask} is true. */
    static int[] where(boolean[] mask) {
        return IntStream.range(0, mask.length).filter(i -> mask[i]).toArray();
    }

    static boolean any(boolean[] mask) {
        for (boolean b : mask) {
            if (b) {
                return true;
            }
        }
        return false;
    }
}
