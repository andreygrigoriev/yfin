package io.github.andreygrigoriev.yfin.internal.repair;

import static io.github.andreygrigoriev.yfin.internal.repair.Frame.Col.ADJ_CLOSE;
import static io.github.andreygrigoriev.yfin.internal.repair.Frame.Col.CLOSE;
import static io.github.andreygrigoriev.yfin.internal.repair.Frame.Col.DIVIDENDS;
import static io.github.andreygrigoriev.yfin.internal.repair.Frame.Col.HIGH;
import static io.github.andreygrigoriev.yfin.internal.repair.Frame.Col.LOW;
import static io.github.andreygrigoriev.yfin.internal.repair.Frame.Col.OPEN;
import static io.github.andreygrigoriev.yfin.internal.repair.Frame.Col.VOLUME;

import io.github.andreygrigoriev.yfin.exception.YFRateLimitException;
import io.github.andreygrigoriev.yfin.internal.repair.Frame.Col;
import io.github.andreygrigoriev.yfin.request.Interval;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * yfinance's {@code _reconstruct_intervals_batch}: replaces tagged values with values aggregated
 * from finer-grained history (1wk from 1d, 1d from 1h, 1h from 30m, ... 2m from 1m), calibrated
 * against the untagged bars of the same block. Nested reconstruction stops at a depth of two.
 */
final class Reconstruct {

    private static final Logger LOG = LoggerFactory.getLogger(Reconstruct.class);

    /** The tag repairs put into values that need reconstruction. */
    static final double TAG = -1.0;

    private static final Col[] PRICES = Frame.PRICES;
    private static final Col[] DATA = {OPEN, HIGH, LOW, CLOSE, ADJ_CLOSE, VOLUME};

    /** Finer data older than this many days is not served for 1h bars. */
    private static final long MAX_HOURLY_AGE_DAYS = 729;
    /** Finer data older than this many days is not served for 30m/15m bars. */
    private static final long MAX_MINUTE_AGE_DAYS = 59;
    /** A calibration ratio within this of 1 (or of 100, 0.01) counts as that value. */
    private static final double CALIBRATION_TOLERANCE = 0.1;

    private static final Map<Interval, Interval> NEXT = new LinkedHashMap<>();

    static {
        NEXT.put(Interval.ONE_WEEK, Interval.ONE_DAY);
        NEXT.put(Interval.ONE_DAY, Interval.ONE_HOUR);
        NEXT.put(Interval.ONE_HOUR, Interval.THIRTY_MINUTES);
        NEXT.put(Interval.THIRTY_MINUTES, Interval.FIFTEEN_MINUTES);
        NEXT.put(Interval.FIFTEEN_MINUTES, Interval.FIVE_MINUTES);
        NEXT.put(Interval.FIVE_MINUTES, Interval.TWO_MINUTES);
        NEXT.put(Interval.TWO_MINUTES, Interval.ONE_MINUTE);
    }

    private Reconstruct() {}

    /** Whether {@code e} reports an interrupt, or the current thread has been interrupted. */
    static boolean isInterrupt(RuntimeException e) {
        return Thread.currentThread().isInterrupted() || e.getCause() instanceof InterruptedException;
    }

    static Frame reconstruct(Frame input, Interval interval, boolean prepost, RepairContext ctx) {
        if (interval == Interval.ONE_MINUTE) {
            return input;
        }
        // shorter than a day (5d counts too): unlike Interval.isIntraday()
        boolean subDaily = !Intervals.interday(interval);
        if (!subDaily) {
            prepost = true; // interday data always includes pre and post market
        }
        Interval sub = NEXT.get(interval);
        if (sub == null) {
            LOG.warn("{}: price reconstruction is not implemented for {}", ctx.symbol(), interval);
            return input;
        }
        if (ctx.reconstructStart() == null) {
            ctx.reconstructStart(interval);
        }
        Interval startInterval = ctx.reconstructStart();
        if (interval != startInterval && interval != NEXT.get(startInterval)) {
            LOG.debug("{}: hit max reconstruction depth of 2 ({} -> {})", ctx.symbol(), startInterval, interval);
            return input;
        }
        if (ctx.finer() == null) {
            return input;
        }

        Frame df = input.inZone(ctx.zone()).sortedAscending();
        int n = df.size();
        boolean[] repairRow = new boolean[n];
        for (int i = 0; i < n; i++) {
            repairRow[i] = isTagged(df, i, DATA);
        }
        @Nullable ZonedDateTime minDt = earliestFetchable(sub, ctx);
        if (minDt != null) {
            for (int i = 0; i < n; i++) {
                repairRow[i] &= !df.time(i).isBefore(minDt);
            }
            if (!Frame.any(repairRow)) {
                LOG.info("{}: too old to reconstruct", ctx.symbol());
                return df;
            }
        }
        int[] toRepair = Frame.where(repairRow);
        if (toRepair.length == 0) {
            return df;
        }

        Frame v2 = df.copy();
        List<Integer> goodRows = goodRows(df);
        List<TreeSet<Integer>> groups = groupBadBars(df, toRepair, sub);
        if (!goodRows.isEmpty()) {
            addCalibrationBars(df, groups, goodRows, minDt, subDaily);
        }

        BlockContext bc = new BlockContext(df, v2, interval, sub, startInterval, prepost, minDt, ctx);
        LocalDate today = ctx.clock().instant().atZone(ctx.zone()).toLocalDate();
        for (int gi = groups.size() - 1; gi >= 0; gi--) {
            int[] block = groups.get(gi).stream().mapToInt(Integer::intValue).toArray();
            reconstructBlock(bc, block, today);
        }
        return v2;
    }

    // ---- grouping the bad bars ----

    /** The first instant finer data can be fetched for, or null when the sub-interval has no limit. */
    private static @Nullable ZonedDateTime earliestFetchable(Interval sub, RepairContext ctx) {
        Duration lookback = minLookback(sub);
        if (lookback == null) {
            return null;
        }
        Instant m = ctx.clock().instant().minus(lookback.minusDays(1));
        ZonedDateTime z = m.atZone(ctx.zone());
        ZonedDateTime floor = z.toLocalDate().atStartOfDay(ctx.zone());
        return floor.equals(z) ? floor : z.toLocalDate().plusDays(1).atStartOfDay(ctx.zone());
    }

    /** Rows whose prices are all present and untagged. */
    private static List<Integer> goodRows(Frame df) {
        List<Integer> goodRows = new ArrayList<>();
        for (int i = 0; i < df.size(); i++) {
            boolean good = true;
            for (Col c : PRICES) {
                double v = df.get(c, i);
                good &= !Double.isNaN(v) && v != TAG;
            }
            if (good) {
                goodRows.add(i);
            }
        }
        return goodRows;
    }

    /** Groups nearby bad bars to reduce the number of fetches. */
    private static List<TreeSet<Integer>> groupBadBars(Frame df, int[] toRepair, Interval sub) {
        List<TreeSet<Integer>> groups = new ArrayList<>();
        TreeSet<Integer> current = new TreeSet<>();
        current.add(toRepair[0]);
        groups.add(current);
        for (int q = 1; q < toRepair.length; q++) {
            int i = toRepair[q];
            LocalDate groupStart = df.time(groups.getLast().first()).toLocalDate();
            if (df.time(i).toLocalDate().isBefore(plusGroupMax(groupStart, sub))) {
                groups.getLast().add(i);
            } else {
                TreeSet<Integer> g = new TreeSet<>();
                g.add(i);
                groups.add(g);
            }
        }
        return groups;
    }

    /** Adds the good bars around each group (same day when sub-daily) for calibration. */
    private static void addCalibrationBars(
            Frame df,
            List<TreeSet<Integer>> groups,
            List<Integer> goodRows,
            @Nullable ZonedDateTime minDt,
            boolean subDaily) {
        for (TreeSet<Integer> g : groups) {
            int g0 = g.first();
            int gl = g.last();
            int i0 = nearest(df, goodRows, df.time(g0).toInstant());
            if (i0 > 0) {
                ZonedDateTime prev = df.time(goodRows.get(i0 - 1));
                if ((minDt == null || !prev.isBefore(minDt))
                        && (!subDaily || prev.toLocalDate().equals(df.time(g0).toLocalDate()))) {
                    i0--;
                }
            }
            int il = nearest(df, goodRows, df.time(gl).toInstant());
            if (il < goodRows.size() - 1) {
                ZonedDateTime next = df.time(goodRows.get(il + 1));
                if (!subDaily || next.toLocalDate().equals(df.time(gl).toLocalDate())) {
                    il++;
                }
            }
            for (int q = i0; q <= il; q++) {
                g.add(goodRows.get(q));
            }
        }
    }

    // ---- one block ----

    /** What every block of one reconstruction shares. */
    private record BlockContext(
            Frame df,
            Frame v2,
            Interval interval,
            Interval sub,
            Interval startInterval,
            boolean prepost,
            @Nullable ZonedDateTime minDt,
            RepairContext ctx) {}

    /** Fetches, aggregates and calibrates finer data for one block of rows and repairs its tagged values. */
    private static void reconstructBlock(BlockContext bc, int[] block, LocalDate today) {
        Frame df = bc.df();
        Interval interval = bc.interval();
        Interval sub = bc.sub();
        String symbol = bc.ctx().symbol();
        ZonedDateTime startDt = df.time(block[0]);
        ZonedDateTime endDt = df.time(block[block.length - 1]);
        LocalDate startD = startDt.toLocalDate();
        long age = ChronoUnit.DAYS.between(startD, today);
        if ((sub == Interval.ONE_HOUR && age > MAX_HOURLY_AGE_DAYS)
                || ((sub == Interval.THIRTY_MINUTES || sub == Interval.FIFTEEN_MINUTES) && age > MAX_MINUTE_AGE_DAYS)) {
            LOG.info("{}: cannot reconstruct block starting {}, too old", symbol, startDt);
            return;
        }
        Frame fine = fetchFiner(bc, startDt, endDt);
        if (fine == null || fine.isEmpty()) {
            LOG.info("{}: cannot reconstruct block starting {}, no finer-grained data", symbol, startDt);
            return;
        }
        fine = trimToBlock(fine.inZone(bc.ctx().zone()).sortedAscending(), startDt, endDt, interval);
        if (fine.isEmpty()) {
            LOG.info("{}: Yahoo returned no finer-grained data within block {}", symbol, startDt);
            return;
        }

        Agg agg = aggregate(fine, df, block, interval);
        Frame fresh = agg.frame;
        Map<Instant, Integer> freshPos = new LinkedHashMap<>();
        for (int i = 0; i < fresh.size(); i++) {
            freshPos.putIfAbsent(fresh.time(i).toInstant(), i);
        }
        if (!calibrate(df, block, agg, freshPos)) {
            LOG.info("{}: cannot calibrate {} block starting {}, aborting repair", symbol, interval, startD);
            return;
        }
        matchAdjustedClose(df, bc.v2(), block, fresh);
        repairBlock(bc, block, fine, fresh, freshPos);
    }

    /** Fetches the finer data around the block, or null when the fetch failed. */
    private static @Nullable Frame fetchFiner(BlockContext bc, ZonedDateTime startDt, ZonedDateTime endDt) {
        Interval interval = bc.interval();
        LocalDate startD = startDt.toLocalDate();
        LocalDate fetchStart;
        LocalDate fetchEnd;
        if (interval == Interval.ONE_WEEK) {
            fetchStart = startD.minusWeeks(1).minusDays(1);
            fetchEnd = endDt.toLocalDate().plusWeeks(1).plusDays(1);
        } else if (interval == Interval.ONE_DAY) {
            fetchStart = startD.minusDays(1);
            fetchEnd = endDt.toLocalDate().plusDays(1).plusDays(1);
        } else {
            fetchStart = startDt.minusDays(1).toLocalDate();
            fetchEnd =
                    Intervals.plus(endDt, interval, 1).plusDays(1).toLocalDate().plusDays(1);
        }
        ZonedDateTime minDt = bc.minDt();
        if (minDt != null && minDt.toLocalDate().isAfter(fetchStart)) {
            fetchStart = minDt.toLocalDate();
        }
        RepairContext ctx = bc.ctx();
        try {
            return ctx.finer().fetch(bc.sub(), fetchStart, fetchEnd, bc.prepost(), bc.startInterval());
        } catch (YFRateLimitException e) {
            throw e; // more requests would only be rate-limited too
        } catch (RuntimeException e) {
            if (isInterrupt(e)) {
                throw e; // cancelled (e.g. a download): stop instead of returning unrepaired data
            }
            LOG.debug("{}: fetching {} data for reconstruction failed: {}", ctx.symbol(), bc.sub(), e.toString());
            return null;
        }
    }

    /** Discards the buffer around the block (yfinance pads by the coarse interval). */
    private static Frame trimToBlock(Frame fine, ZonedDateTime startDt, ZonedDateTime endDt, Interval interval) {
        Instant lo = startDt.toInstant();
        Instant hi = Intervals.plus(endDt, interval, 1).toInstant().minusMillis(1);
        List<Integer> keep = new ArrayList<>();
        for (int i = 0; i < fine.size(); i++) {
            Instant t = fine.time(i).toInstant();
            if (!t.isBefore(lo) && !t.isAfter(hi)) {
                keep.add(i);
            }
        }
        return fine.rows(keep.stream().mapToInt(Integer::intValue).toArray());
    }

    /**
     * Scales the aggregated prices to match the good values of the block (unit mix-ups, differing
     * split adjustment); false when there is nothing to calibrate against.
     */
    private static boolean calibrate(Frame df, int[] block, Agg agg, Map<Instant, Integer> freshPos) {
        Frame fresh = agg.frame;
        List<double[]> ratioWeights = new ArrayList<>();
        boolean anyCommon = false;
        for (int bi : block) {
            Integer fi = freshPos.get(df.time(bi).toInstant());
            if (fi == null) {
                continue;
            }
            anyCommon = true;
            for (Col c : new Col[] {OPEN, CLOSE}) {
                double b = df.get(c, bi);
                double f = fresh.get(c, fi);
                if (b != TAG && !Double.isNaN(f) && !Double.isNaN(b)) {
                    ratioWeights.add(new double[] {b / f, agg.sizes[fi]});
                }
            }
        }
        if (!anyCommon || ratioWeights.isEmpty()) {
            return false;
        }
        double[] ratios = ratioWeights.stream().mapToDouble(r -> r[0]).toArray();
        double[] weights = ratioWeights.stream().mapToDouble(r -> r[1]).toArray();
        if (ratios.length > 1) {
            ratios = keepLargestCluster(ratios);
        }
        double ratio = Stats.weightedAverage(ratios, weights);
        if (Math.abs(ratio - 1) < CALIBRATION_TOLERANCE) {
            ratio = 1;
        } else if (Math.abs(ratio / 100 - 1) < CALIBRATION_TOLERANCE) {
            scalePrices(fresh, 100);
            ratio = 1;
        } else if (Math.abs(ratio / 0.01 - 1) < CALIBRATION_TOLERANCE) {
            scalePrices(fresh, 0.01);
            ratio = 1;
        }
        double ratioRcp = Stats.round(1.0 / ratio, 1);
        ratio = Stats.round(ratio, 1);
        if (!(ratio == 1 && ratioRcp == 1)) {
            // finer data has a different split adjustment; match it to the coarse data
            if (ratio > 1) {
                scalePrices(fresh, ratio);
                scaleVolume(fresh, 1.0 / ratio);
            } else if (ratioRcp > 1) {
                scalePrices(fresh, 1.0 / ratioRcp);
                scaleVolume(fresh, ratioRcp);
            }
        }
        return true;
    }

    /** Scales the aggregated adjusted close to match the adjustment of the bar after the block. */
    private static void matchAdjustedClose(Frame df, Frame v2, int[] block, Frame fresh) {
        int last = fresh.size() - 1;
        int postIdx = block[block.length - 1] + 1;
        double postAdj;
        if (postIdx >= v2.size()) {
            double[] adj = new double[block.length];
            for (int q = 0; q < block.length; q++) {
                int bi = block[q];
                adj[q] = isTagged(df, bi, PRICES) ? Double.NaN : df.get(ADJ_CLOSE, bi) / df.get(CLOSE, bi);
            }
            postAdj = lastAfterFill(adj);
            if (Double.isNaN(postAdj)) {
                postAdj = 1.0;
            }
        } else {
            postAdj = v2.get(ADJ_CLOSE, postIdx) / v2.get(CLOSE, postIdx);
        }
        double freshAdjLast = fresh.get(ADJ_CLOSE, last) / fresh.get(CLOSE, last);
        if (Double.isNaN(freshAdjLast) && last >= 1) {
            freshAdjLast = fresh.get(ADJ_CLOSE, last - 1) / fresh.get(CLOSE, last - 1);
        }
        if (postIdx < v2.size() && v2.get(DIVIDENDS, postIdx) != 0) {
            postAdj *= 1 - v2.get(DIVIDENDS, postIdx) / fresh.get(CLOSE, last);
        }
        double adjCorrection = postAdj / freshAdjLast;
        if (adjCorrection != 1) {
            for (int i = 0; i < fresh.size(); i++) {
                fresh.set(ADJ_CLOSE, i, fresh.get(ADJ_CLOSE, i) * adjCorrection);
            }
        }
    }

    /** Replaces the tagged values of the block with the aggregated ones. */
    private static void repairBlock(
            BlockContext bc, int[] block, Frame fine, Frame fresh, Map<Instant, Integer> freshPos) {
        Frame df = bc.df();
        Frame v2 = bc.v2();
        boolean weekly = bc.interval() == Interval.ONE_WEEK;
        Frame fineLeft = fine;
        for (int bi : block) {
            if (!isTagged(df, bi, DATA)) {
                continue;
            }
            Instant dt = df.time(bi).toInstant();
            Integer fi = freshPos.get(dt);
            if (fi == null) {
                continue; // no finer data, so probably no trading
            }
            int prevWeek = Math.floorMod(fi - 1, fresh.size());
            if (weekly) {
                fineLeft = fromTime(fineLeft, dt);
            }
            if (df.get(HIGH, bi) == TAG) {
                v2.set(HIGH, bi, fresh.get(HIGH, fi));
            }
            if (df.get(LOW, bi) == TAG) {
                v2.set(LOW, bi, fresh.get(LOW, fi));
            }
            if (df.get(OPEN, bi) == TAG) {
                if (weekly
                        && !fineLeft.isEmpty()
                        && !fineLeft.time(0).toInstant().equals(dt)) {
                    // exchange closed on Monday: Yahoo sets the open to last week's close
                    double open = fresh.get(CLOSE, prevWeek);
                    v2.set(OPEN, bi, open);
                    double low = v2.get(LOW, bi);
                    v2.set(LOW, bi, low < open ? low : open);
                } else {
                    v2.set(OPEN, bi, fresh.get(OPEN, fi));
                }
            }
            if (df.get(CLOSE, bi) == TAG) {
                v2.set(CLOSE, bi, fresh.get(CLOSE, fi));
                v2.set(ADJ_CLOSE, bi, fresh.get(ADJ_CLOSE, fi));
            } else if (df.get(ADJ_CLOSE, bi) == TAG) {
                v2.set(ADJ_CLOSE, bi, fresh.get(ADJ_CLOSE, fi));
            }
            if (df.get(VOLUME, bi) == TAG) {
                v2.set(VOLUME, bi, Math.rint(fresh.get(VOLUME, fi)));
            }
            v2.repaired()[bi] = true;
        }
    }

    private static boolean isTagged(Frame df, int row, Col[] cols) {
        boolean tagged = false;
        for (Col c : cols) {
            tagged |= df.get(c, row) == TAG;
        }
        return tagged;
    }

    // ---- aggregation of the finer data ----

    private record Agg(Frame frame, double[] sizes) {}

    private static Agg aggregate(Frame fine, Frame df, int[] block, Interval interval) {
        int nf = fine.size();
        Object[] keys = new Object[nf];
        if (interval == Interval.ONE_WEEK) {
            int startDow = df.time(block[0]).getDayOfWeek().getValue();
            for (int i = 0; i < nf; i++) {
                LocalDate d = fine.time(i).toLocalDate();
                int back = Math.floorMod(d.getDayOfWeek().getValue() - startDow, 7);
                keys[i] = d.minusDays(back);
            }
        } else if (interval == Interval.ONE_DAY) {
            for (int i = 0; i < nf; i++) {
                keys[i] = fine.time(i).toLocalDate();
            }
        } else {
            Set<Instant> blockTimes = new HashSet<>();
            for (int bi : block) {
                blockTimes.add(df.time(bi).toInstant());
            }
            int id = 0;
            for (int i = 0; i < nf; i++) {
                if (blockTimes.contains(fine.time(i).toInstant())) {
                    id++;
                }
                keys[i] = id;
            }
        }
        Map<Object, List<Integer>> groups = new LinkedHashMap<>();
        for (int i = 0; i < nf; i++) {
            groups.computeIfAbsent(keys[i], key -> new ArrayList<>()).add(i);
        }
        int ng = groups.size();
        ZonedDateTime[] times = new ZonedDateTime[ng];
        double[] sizes = new double[ng];
        int g = 0;
        for (Map.Entry<Object, List<Integer>> e : groups.entrySet()) {
            times[g] = e.getKey() instanceof LocalDate d
                    ? d.atStartOfDay(fine.time(0).getZone())
                    : fine.time(e.getValue().getFirst());
            sizes[g] = e.getValue().size();
            g++;
        }
        Frame out = new Frame(times, fine.hasCapitalGains());
        g = 0;
        for (List<Integer> rows : groups.values()) {
            double open = Double.NaN;
            double close = Double.NaN;
            double adjClose = Double.NaN;
            double low = Double.NaN;
            double high = Double.NaN;
            double div = 0;
            double vol = 0;
            boolean rep = false;
            for (int i : rows) {
                if (Double.isNaN(open)) {
                    open = fine.get(OPEN, i);
                }
                if (!Double.isNaN(fine.get(CLOSE, i))) {
                    close = fine.get(CLOSE, i);
                }
                if (!Double.isNaN(fine.get(ADJ_CLOSE, i))) {
                    adjClose = fine.get(ADJ_CLOSE, i);
                }
                low = Stats.nanMin(low, fine.get(LOW, i));
                high = Stats.nanMax(high, fine.get(HIGH, i));
                div += nz(fine.get(DIVIDENDS, i));
                vol += nz(fine.get(VOLUME, i));
                rep |= fine.repaired()[i];
            }
            out.set(OPEN, g, open);
            out.set(CLOSE, g, close);
            out.set(ADJ_CLOSE, g, adjClose);
            out.set(LOW, g, low);
            out.set(HIGH, g, high);
            out.set(DIVIDENDS, g, div);
            out.set(VOLUME, g, vol);
            out.repaired()[g] = rep;
            g++;
        }
        return new Agg(out, sizes);
    }

    // ---- helpers ----

    /**
     * sklearn DBSCAN on the log ratios (10% tolerance, at least 3 samples): values outside the
     * largest cluster are replaced by its median, so that unfixed jumps in the finer data do not
     * skew the calibration.
     */
    static double[] keepLargestCluster(double[] ratios) {
        double[] logx = Arrays.stream(ratios).map(Math::log).toArray();
        double eps = Math.max(Math.log1p(0.10), -Math.log1p(-0.10));
        int[] labels = Stats.dbscan1d(logx, eps, 3);
        int maxLabel = Arrays.stream(labels).max().orElse(-1);
        if (maxLabel < 0) {
            return ratios;
        }
        int largest = 0;
        int largestCount = -1;
        for (int l = 0; l <= maxLabel; l++) {
            int count = 0;
            for (int x : labels) {
                if (x == l) {
                    count++;
                }
            }
            if (count > largestCount) {
                largest = l;
                largestCount = count;
            }
        }
        final int keep = largest;
        double[] in = IntStream.range(0, ratios.length)
                .filter(i -> labels[i] == keep)
                .mapToDouble(i -> ratios[i])
                .toArray();
        double replacement = Stats.median(in);
        double[] out = ratios.clone();
        for (int i = 0; i < out.length; i++) {
            if (labels[i] != keep) {
                out[i] = replacement;
            }
        }
        return out;
    }

    /** pandas {@code get_indexer(method='nearest')} on the good rows' times (ties go to the later row). */
    private static int nearest(Frame df, List<Integer> goodRows, Instant t) {
        int lo = 0;
        int hi = goodRows.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (df.time(goodRows.get(mid)).toInstant().isBefore(t)) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        // lo = first index with time >= t
        int right = lo < goodRows.size() ? lo : -1;
        int left = right >= 0 && df.time(goodRows.get(right)).toInstant().equals(t) ? right : lo - 1;
        if (left == right) {
            return right;
        }
        if (right < 0) {
            return left;
        }
        if (left < 0) {
            return right;
        }
        long dl = Duration.between(df.time(goodRows.get(left)).toInstant(), t)
                .abs()
                .toMillis();
        long dr = Duration.between(df.time(goodRows.get(right)).toInstant(), t)
                .abs()
                .toMillis();
        return dl < dr ? left : right;
    }

    private static LocalDate plusGroupMax(LocalDate d, Interval sub) {
        return switch (sub) {
            case ONE_MONTH, ONE_WEEK, ONE_DAY -> d.plusYears(2);
            case ONE_HOUR -> d.plusYears(1);
            case ONE_MINUTE -> d.plusDays(5);
            default -> d.plusDays(30);
        };
    }

    private static @Nullable Duration minLookback(Interval sub) {
        return switch (sub) {
            case ONE_HOUR -> Duration.ofDays(730);
            case THIRTY_MINUTES, FIFTEEN_MINUTES, FIVE_MINUTES, TWO_MINUTES -> Duration.ofDays(60);
            case ONE_MINUTE -> Duration.ofDays(30);
            default -> null;
        };
    }

    private static Frame fromTime(Frame f, Instant t) {
        int k = 0;
        while (k < f.size() && f.time(k).toInstant().isBefore(t)) {
            k++;
        }
        return f.slice(k, f.size());
    }

    private static double lastAfterFill(double[] v) {
        // ffill then bfill, then take the last value
        double last = Double.NaN;
        for (double x : v) {
            if (!Double.isNaN(x)) {
                last = x;
            }
        }
        return last;
    }

    private static void scalePrices(Frame f, double m) {
        for (Col c : PRICES) {
            double[] a = f.col(c);
            for (int i = 0; i < a.length; i++) {
                a[i] *= m;
            }
        }
    }

    private static void scaleVolume(Frame f, double m) {
        double[] a = f.col(VOLUME);
        for (int i = 0; i < a.length; i++) {
            a[i] *= m;
        }
    }

    private static double nz(double v) {
        return Double.isNaN(v) ? 0 : v;
    }
}
