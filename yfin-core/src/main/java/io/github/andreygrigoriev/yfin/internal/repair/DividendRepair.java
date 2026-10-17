package io.github.andreygrigoriev.yfin.internal.repair;

import static io.github.andreygrigoriev.yfin.internal.repair.Frame.Col.ADJ_CLOSE;
import static io.github.andreygrigoriev.yfin.internal.repair.Frame.Col.CAPITAL_GAINS;
import static io.github.andreygrigoriev.yfin.internal.repair.Frame.Col.CLOSE;
import static io.github.andreygrigoriev.yfin.internal.repair.Frame.Col.DIVIDENDS;
import static io.github.andreygrigoriev.yfin.internal.repair.Frame.Col.HIGH;
import static io.github.andreygrigoriev.yfin.internal.repair.Frame.Col.LOW;
import static io.github.andreygrigoriev.yfin.internal.repair.Frame.Col.OPEN;
import static io.github.andreygrigoriev.yfin.internal.repair.Frame.Col.SPLITS;
import static io.github.andreygrigoriev.yfin.internal.repair.Frame.Col.VOLUME;

import io.github.andreygrigoriev.yfin.internal.repair.Frame.Col;
import io.github.andreygrigoriev.yfin.request.Interval;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * yfinance's {@code _fix_bad_div_adjust}: dividends ~100x too big or too small (currency unit
 * mix-ups), dividends missing a coincident split, phantom duplicate dividends, dividends on the
 * wrong date, and dividend adjustments in the adjusted close that are missing, too small or too
 * big. Each dividend is checked against the price action around it, then the verdicts are made
 * consistent within clusters of similar dividend yields before repairing. Intraday data is
 * analysed as daily bars and the repairs are carried back.
 */
final class DividendRepair {

    private static final Logger LOG = LoggerFactory.getLogger(DividendRepair.class);

    private static final Col[] OHLCA = Frame.PRICES;

    // ---- yfinance's thresholds ----

    /** Dividend yield above which a dividend is checked for being too big. */
    private static final double TOO_BIG_CHECK_THRESHOLD = 0.035;
    /** Dividends closer than this are candidates for phantom duplicates. */
    private static final Duration PHANTOM_PROXIMITY = Duration.ofDays(17);
    /** An adjusted close above this multiple of the close has overflowed (SSNLF). */
    private static final double ADJ_OVERFLOW_RATIO = 10;
    /** Scale applied to an overflowed adjusted close until it is plausible. */
    private static final double ADJ_OVERFLOW_SCALE = 0.001;
    /** Relative slack for "the pre-dividend close is below the low by the dividend". */
    private static final double DOUBLE_ADJUST_TOLERANCE = 0.01;
    /** Relative tolerance for "the low is ~100x (or ~0.01x) the previous close" and adjustment matches. */
    private static final double PRICE_MATCH_RTOL = 0.025;
    /** Rows in the window the typical volatility around a dividend is measured over. */
    private static final int VOLATILITY_WINDOW = 8;
    /** Rows of that window placed before the dividend (or after it, near the end). */
    private static final int VOLATILITY_LEAD = 4;
    /** Fewer rows than this around a dividend give no volatility estimate. */
    private static final int VOLATILITY_MIN_ROWS = 4;
    /** Without price action: a yield below this percentage is too small. */
    private static final double NO_ACTION_TOO_SMALL_PERCENT = 0.1;
    /** Without price action: share of zero-volume rows that makes a large yield suspicious. */
    private static final double NO_ACTION_ZERO_VOLUME_SHARE = 0.75;
    /** Without price action: yield that is too big on a mostly zero-volume series. */
    private static final double NO_ACTION_TOO_BIG_YIELD = 0.25;
    /** Without price action: yield that is always too big. */
    private static final double NO_ACTION_MAX_YIELD = 1.0;
    /** Intraday with pre/post: an open-to-close drop below this share of the drop means it recovered. */
    private static final double SESSION_RECOVERY_SHARE = 0.2;
    /** Implied yield below this multiple of the dividend yield: the adjustment is too small. */
    private static final double ADJ_TOO_SMALL_FACTOR = 0.1;
    /** Implied yield above this multiple of the dividend yield: the adjustment is too big. */
    private static final double ADJ_EXCEEDS_FACTOR = 10;
    /** Phantom if a suspicious dividend is within this relative difference of its neighbour. */
    private static final double PHANTOM_DIVERGENCE = 0.01;
    /** Phantom if consecutive dividends are within this relative difference. */
    private static final double PHANTOM_NEIGHBOUR_DIVERGENCE = 0.08;
    /** Of two phantom candidates, the one whose drop is not this much bigger is the phantom. */
    private static final double PHANTOM_DROP_RATIO = 1.5;
    /** Neighbour yield ratios within this distance of the currency factor flag a 0.01x dividend. */
    private static final double HUNDREDTH_RATIO_TOLERANCE = 20;
    /** How far after the reported date the dividend drop may come. */
    private static final Duration DIV_LOOKAHEAD = Duration.ofDays(35);
    /** Rows before the dividend included in the price-action check. */
    private static final int DIV_LOOKBACK_ROWS = 14;
    /** A daily change above this in the lookahead period is a bad split. */
    private static final double BAD_SPLIT_RISE = 2;
    /** A daily change below this in the lookahead period is a bad split. */
    private static final double BAD_SPLIT_FALL = -0.9;
    /** Only yields in (min, max) are checked against the price action. */
    private static final double PRICE_CHECK_MIN_YIELD = 0.05;

    private static final double PRICE_CHECK_MAX_YIELD = 1.0;
    /** An adjusted drop above this share of the adjusted dividend is a candidate adjustment step. */
    private static final double ADJ_DROP_SHARE = 0.6;
    /** The adjusted drop exceeds the unadjusted one by more than this factor: adjustment misplaced. */
    private static final double ADJ_RISE_TOLERANCE = 1.001;
    /** A reversal is an adjusted rise at least this share of the misplaced drop. */
    private static final double REVERSAL_SHARE = 0.8;
    /** Days after the reported date weigh this much less than days before when picking the true date. */
    private static final double LATE_DATE_PENALTY = 0.1;
    /** An adjusted drop above this share of the adjusted dividend means the adjustment exceeds prices. */
    private static final double EXCEEDS_PRICES_SHARE = 0.39;
    /** Slack when checking whether a split-adjusted dividend explains the drop. */
    private static final double PRE_SPLIT_SLACK = 1.1;
    /** Dividends of one cluster stay within this ratio of the cluster's mean yield. */
    private static final double CLUSTER_YIELD_RATIO = 7;
    /** A div-adjust correction smaller than this is not applied. */
    private static final double MIN_ADJ_CHANGE = 0.05;

    // cluster consensus: share of a cluster that must fail (or at most fail) to make all fail (or pass)
    private static final double TOO_BIG_TRUE_SHARE = 1.0;
    private static final double TOO_BIG_FALSE_SHARE = 0.5;
    private static final double TOO_BIG_NO_VOLATILITY_FALSE_SHARE = 2.0 / 3;
    private static final double TOO_BIG_ADJ_TOO_SMALL_TRUE_SHARE = 0.25;
    private static final double TOO_BIG_EXCEEDS_PRICES_TRUE_SHARE = 0.5;
    /** Tel Aviv: currency mix-ups are common. */
    private static final double TOO_BIG_TA_TRUE_SHARE = 0.74;

    private static final double EXCEEDS_PRICES_MAJORITY = 0.5;
    private static final double TOO_SMALL_TRUE_SHARE = 1.0;
    private static final double TOO_SMALL_FALSE_SHARE = 0.11;
    private static final double TOO_SMALL_CONFIRMED_TRUE_SHARE = 6.0 / 11;
    private static final double TOO_SMALL_CONFIRMED_FALSE_SHARE = 0.5;

    /** yfinance's div-status columns that flag a problem. */
    enum Check {
        DIV_TOO_BIG,
        DIV_TOO_SMALL,
        DIV_PRE_SPLIT,
        DIV_TOO_BIG_AND_PRE_SPLIT,
        DIV_TOO_SMALL_AND_PRE_SPLIT,
        ADJ_MISSING,
        ADJ_EXCEEDS_DIV,
        DIV_EXCEEDS_ADJ,
        PHANTOM,
        ADJ_EXCEEDS_PRICES,
        DIV_DATE_WRONG,
        ADJ_TOO_SMALL
    }

    /** The checks of the dividend amount against the price action. */
    private static final List<Check> DIV_CHECKS = List.of(
            Check.DIV_TOO_BIG,
            Check.DIV_TOO_SMALL,
            Check.DIV_PRE_SPLIT,
            Check.DIV_TOO_BIG_AND_PRE_SPLIT,
            Check.DIV_TOO_SMALL_AND_PRE_SPLIT);

    /** One row of yfinance's {@code div_status_df}. */
    private static final class Status {
        final ZonedDateTime date;
        final int idx;
        final double div;
        final double pct;
        final double drop;
        final double drop2Dmax;
        final double vol;
        double presentAdj;

        @Nullable
        ZonedDateTime trueDate;

        int cluster;
        final Set<Check> flags = EnumSet.noneOf(Check.class);

        Status(ZonedDateTime date, int idx, double div, double pct, double drop, double drop2Dmax, double vol) {
            this.date = date;
            this.idx = idx;
            this.div = div;
            this.pct = pct;
            this.drop = drop;
            this.drop2Dmax = drop2Dmax;
            this.vol = vol;
        }

        boolean is(Check c) {
            return flags.contains(c);
        }

        void set(Check c, boolean v) {
            if (v) {
                flags.add(c);
            } else {
                flags.remove(c);
            }
        }

        boolean anyOf(List<Check> checks) {
            for (Check c : checks) {
                if (is(c)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** A candidate verdict on a dividend and how far the corrected dividend is from the drop. */
    private record Possibility(Check check, double diff) {}

    /** Outcome of checking the present adjustment against the price action. */
    private record AdjustmentScan(
            boolean exceedsPrices, @Nullable ZonedDateTime trueDate) {
        static final AdjustmentScan NONE = new AdjustmentScan(false, null);
    }

    /** The checks a problem dividend failed, after dropping the redundant ones. */
    private static final class Verdict {
        boolean adjMissing;
        boolean divExceedsAdj;
        boolean adjExceedsDiv;
        boolean adjExceedsPrices;
        boolean divTooSmall;
        boolean divTooBig;
        boolean divPreSplit;
        boolean divDateWrong;
        boolean adjTooSmall;
        int failed;
    }

    private DividendRepair() {}

    static Frame fix(Frame input, Interval interval, boolean prepost, RepairContext ctx) {
        if (input.isEmpty() || Intervals.multiday(interval)) {
            return input;
        }
        if (input.hasCapitalGains()) {
            for (double g : input.col(CAPITAL_GAINS)) {
                if (g > 0) {
                    return input; // only dividends are considered here
                }
            }
        }
        if (!Frame.any(SuddenChange.nonZero(input.col(DIVIDENDS)))) {
            return input;
        }
        return new Pass(input, interval.isIntraday(), prepost, ctx).run();
    }

    /** One run of the repair over a table: the daily view and the state shared by the stages. */
    private static final class Pass {
        private final boolean intraday;
        private final boolean prepost;
        private final int cd;
        private final String symbol;

        /** The input, in the exchange zone and sorted ascending. */
        private final Frame df;
        /** Rows without a close, set aside and added back at the end. */
        private final Frame df2Nan;
        /** The rows analysed: daily bars (resampled when intraday). */
        private final Frame df2;
        /** Prices of rows that were forward-filled, restored at the end. */
        private final Map<Instant, double[]> nanPrices;
        /** The intraday rows the daily repairs are carried back to, or null for daily data. */
        private final @Nullable Frame intradayFrame;

        private final int n;
        private final double[] close;
        private final double[] adjClose;
        private final double[] low;
        private final double[] high;
        private final double[] open;
        private final double[] volume;
        private final double[] dividends;
        private final double[] splits;

        private boolean modified;
        private List<Status> statuses = new ArrayList<>();
        private final List<Check> checks = new ArrayList<>();
        private final Set<Check> present = EnumSet.noneOf(Check.class);

        Pass(Frame input, boolean intraday, boolean prepost, RepairContext ctx) {
            this.intraday = intraday;
            this.prepost = prepost;
            this.cd = ctx.currencyDivide();
            this.symbol = ctx.symbol();
            this.df = input.inZone(ctx.zone()).sortedAscending();
            boolean[] closeNaN = new boolean[df.size()];
            for (int i = 0; i < df.size(); i++) {
                closeNaN[i] = Double.isNaN(df.get(CLOSE, i));
            }
            this.df2Nan = df.select(closeNaN);
            Frame withClose = df.select(SuddenChange.not(closeNaN));
            this.nanPrices = forwardFillPrices(withClose);
            if (intraday) {
                this.intradayFrame = withClose.copy();
                LocalDate today = ctx.clock().instant().atZone(ctx.zone()).toLocalDate();
                this.df2 = Resample.resample(withClose, Interval.ONE_DAY, ctx.zone(), false, today);
            } else {
                this.intradayFrame = null;
                this.df2 = withClose;
            }
            this.n = df2.size();
            this.close = df2.col(CLOSE);
            this.adjClose = df2.col(ADJ_CLOSE);
            this.low = df2.col(LOW);
            this.high = df2.col(HIGH);
            this.open = df2.col(OPEN);
            this.volume = df2.col(VOLUME);
            this.dividends = df2.col(DIVIDENDS);
            this.splits = df2.col(SPLITS);
        }

        Frame run() {
            int[] divIndices = Frame.where(SuddenChange.nonZero(dividends));
            if (divIndices.length == 0) {
                return df;
            }
            sanitiseAdjClose();
            repairDoubleAdjustment(divIndices);

            statuses = detectDividendStatus(divIndices);
            if (statuses.isEmpty() && !modified) {
                return df;
            }
            statuses.sort(Comparator.comparing(s -> s.date.toInstant()));
            checks.addAll(DIV_CHECKS);

            checkPresentAdjustment();
            detectPhantoms();
            removeLonePhantoms();
            if (!anyCheck(statuses, checks) && statuses.size() > 1) {
                flagHundredthDividends();
            }
            if (!anyCheck(statuses, checks)) {
                return modified ? finish() : df;
            }

            for (Status s : statuses) {
                checkAdjustmentAgainstPrices(s);
            }
            checks.addAll(List.of(Check.ADJ_EXCEEDS_PRICES, Check.DIV_DATE_WRONG));
            checks.removeIf(c -> !anyCheck(statuses, List.of(c)));
            present.addAll(checks);

            applyClusterConsensus();
            resolveAdjTooSmall();
            mergeTooBigAndPreSplit();

            List<Status> problems = new ArrayList<>();
            for (Status s : statuses) {
                if (s.anyOf(checks)) {
                    problems.add(s);
                }
            }
            if (!problems.isEmpty()) {
                applyRepairs(problems);
            }
            return finish();
        }

        // ---- preparation ----

        /** Forward-fills missing prices (they can be missing on dividend rows) and returns the originals. */
        private static Map<Instant, double[]> forwardFillPrices(Frame f) {
            Map<Instant, double[]> saved = new HashMap<>();
            for (int i = 0; i < f.size(); i++) {
                boolean anyNaN = false;
                for (Col c : OHLCA) {
                    anyNaN |= Double.isNaN(f.get(c, i));
                }
                if (anyNaN) {
                    double[] row = new double[OHLCA.length];
                    for (int j = 0; j < OHLCA.length; j++) {
                        row[j] = f.get(OHLCA[j], i);
                    }
                    saved.put(f.time(i).toInstant(), row);
                }
            }
            if (!saved.isEmpty()) {
                for (Col c : OHLCA) {
                    double[] a = f.col(c);
                    for (int i = 1; i < a.length; i++) {
                        if (Double.isNaN(a[i])) {
                            a[i] = a[i - 1];
                        }
                    }
                }
            }
            return saved;
        }

        /** Undoes adjusted-close overflow (SSNLF) and fills missing adjusted closes from the ratio after. */
        private void sanitiseAdjClose() {
            boolean anyInf = false;
            for (double v : adjClose) {
                anyInf |= Double.isInfinite(v);
            }
            if (anyInf) {
                boolean huge = true;
                while (huge) {
                    huge = false;
                    for (int i = 0; i < n; i++) {
                        if (!Double.isInfinite(adjClose[i]) && adjClose[i] > close[i] * ADJ_OVERFLOW_RATIO) {
                            adjClose[i] *= ADJ_OVERFLOW_SCALE;
                            huge = true;
                        }
                    }
                }
            }
            double[] adjRatio = new double[n];
            for (int i = 0; i < n; i++) {
                if (Double.isInfinite(adjClose[i])) {
                    adjClose[i] = Double.NaN;
                }
                adjRatio[i] = adjClose[i] / close[i];
            }
            for (int i = n - 2; i >= 0; i--) {
                if (Double.isNaN(adjRatio[i])) {
                    adjRatio[i] = adjRatio[i + 1];
                }
            }
            for (int i = 0; i < n; i++) {
                if (Double.isNaN(adjClose[i]) && !Double.isNaN(close[i])) {
                    adjClose[i] = adjRatio[i] * close[i];
                }
            }
        }

        /** The close before the dividend is sometimes already adjusted (below the low): undo that. */
        private void repairDoubleAdjustment(int[] divIndices) {
            List<LocalDate> fixedDates = new ArrayList<>();
            for (int q = divIndices.length - 1; q >= 0; q--) {
                int di = divIndices[q];
                if (di == 0) {
                    continue;
                }
                double diff = low[di - 1] - close[di - 1];
                double div = dividends[di];
                if (diff > 0 && (diff / div - 1) < DOUBLE_ADJUST_TOLERANCE) {
                    double newClose = close[di - 1] + div;
                    if (newClose >= low[di - 1] && newClose <= high[di - 1]) {
                        close[di - 1] = newClose;
                        double adjAfter = adjClose[di] / close[di];
                        double adj = adjAfter * (1.0 - div / close[di - 1]);
                        adjClose[di - 1] = close[di - 1] * adj;
                        df2.repaired()[di - 1] = true;
                        modified = true;
                        fixedDates.add(df2.time(di).toLocalDate());
                    }
                }
            }
            if (!fixedDates.isEmpty()) {
                LOG.info("{}: repaired double-adjustment on div days {}", symbol, fixedDates);
            }
        }

        // ---- is each dividend too big or too small for the price action? ----

        private List<Status> detectDividendStatus(int[] divIndices) {
            List<Status> result = new ArrayList<>();
            for (int q = divIndices.length - 1; q >= 0; q--) {
                int di = divIndices[q];
                if (di != 0) {
                    result.add(detectStatus(di));
                }
            }
            return result;
        }

        private Status detectStatus(int di) {
            double div = dividends[di];
            double divPct = div / close[di - 1];
            double drop;
            if (Stats.isClose(low[di], close[di - 1] * 100, PRICE_MATCH_RTOL)) {
                // price jumped ~100x on the ex-div day
                drop = close[di - 1] * 100 - low[di];
                divPct = div / (close[di - 1] * 100);
                fixAdjustmentBefore(di, 1.0 - div / (close[di - 1] * 100));
            } else if (Stats.isClose(low[di], close[di - 1] * 0.01, PRICE_MATCH_RTOL)) {
                drop = close[di - 1] * 0.01 - low[di];
                divPct = div / (close[di - 1] * 0.01);
                fixAdjustmentBefore(di, 1.0 - div / (close[di - 1] * 100));
            } else {
                drop = close[di - 1] - low[di];
            }
            double[] drops;
            double drop2Dmax;
            if (di < n - 1) {
                drops = new double[] {drop, close[di] - low[di + 1]};
                drop2Dmax =
                        Double.isNaN(drops[0]) || Double.isNaN(drops[1]) ? Double.NaN : Math.max(drops[0], drops[1]);
            } else {
                drops = new double[] {drop};
                drop2Dmax = drop;
            }
            double typicalVolatility = typicalVolatility(di);

            boolean allDropsZero = true;
            for (double d : drops) {
                allDropsZero &= d == 0.0;
            }
            List<Possibility> possibilities = new ArrayList<>();
            if (allDropsZero && volume[di] == 0) {
                noPriceActionPossibilities(divPct, possibilities);
            } else {
                Double divPostSplit = null;
                if (splits[di] != 0.0) {
                    // Yahoo may not have applied a coincident split to the dividend
                    divPostSplit = div / splits[di];
                    double d = divPostSplit > div ? drop - typicalVolatility : drop2Dmax;
                    if (d > 0) {
                        double diff = Math.abs(div - d);
                        double diffPostSplit = Math.abs(divPostSplit - d);
                        if (diffPostSplit * 2 <= diff) {
                            possibilities.add(new Possibility(Check.DIV_PRE_SPLIT, diffPostSplit));
                        }
                    }
                }
                if (divPct > TOO_BIG_CHECK_THRESHOLD) {
                    tooBigPossibilities(div, drop2Dmax, divPostSplit, possibilities);
                }
                // too small: only the same-day change, to reduce false positives
                if (!Double.isNaN(typicalVolatility)) {
                    tooSmallPossibilities(di, div, drop - typicalVolatility, divPostSplit, possibilities);
                }
            }
            Status s = new Status(df2.time(di), di, div, divPct, drop, drop2Dmax, typicalVolatility);
            Possibility best = null;
            for (Possibility p : possibilities) {
                if (best == null || p.diff() < best.diff()) {
                    best = p;
                }
            }
            if (best != null) {
                s.set(best.check(), true);
            }
            return s;
        }

        /** Mean absolute close-to-next-low change over a window of rows around the dividend. */
        private double typicalVolatility(int di) {
            int start;
            int end;
            if (n - di < VOLATILITY_LEAD) {
                end = Math.min(n, di + VOLATILITY_LEAD);
                start = Math.max(0, end - VOLATILITY_WINDOW);
            } else {
                start = Math.max(0, di - VOLATILITY_LEAD);
                end = Math.min(n, start + VOLATILITY_WINDOW);
            }
            if (end - start < VOLATILITY_MIN_ROWS) {
                return Double.NaN;
            }
            double[] diffs = new double[end - 1 - start];
            for (int k = start; k < end - 1; k++) {
                diffs[k - start] = Math.abs(close[k] - low[k + 1]);
            }
            return Stats.mean(diffs);
        }

        /** No price action to analyse: crude heuristics. */
        private void noPriceActionPossibilities(double divPct, List<Possibility> out) {
            int zeroVol = 0;
            for (double v : volume) {
                zeroVol += v == 0.0 ? 1 : 0;
            }
            double pctZeroVol = (double) zeroVol / n;
            if (divPct * 100 < NO_ACTION_TOO_SMALL_PERCENT) {
                out.add(new Possibility(Check.DIV_TOO_SMALL, 0.0));
            } else if ((pctZeroVol > NO_ACTION_ZERO_VOLUME_SHARE && divPct > NO_ACTION_TOO_BIG_YIELD)
                    || divPct > NO_ACTION_MAX_YIELD) {
                out.add(new Possibility(Check.DIV_TOO_BIG, 0.0));
            }
        }

        private void tooBigPossibilities(
                double div, double drop2Dmax, @Nullable Double divPostSplit, List<Possibility> out) {
            if (drop2Dmax <= 0.0) {
                out.add(new Possibility(Check.DIV_TOO_BIG, 0.0));
                return;
            }
            double diff = Math.abs(div - drop2Dmax);
            double diffFx = Math.abs(div / cd - drop2Dmax);
            if (divPostSplit == null) {
                if (diffFx * 2 <= diff) {
                    out.add(new Possibility(Check.DIV_TOO_BIG, diffFx));
                }
            } else {
                double diffFxPostSplit = Math.abs(divPostSplit / cd - drop2Dmax);
                if (diffFx < diffFxPostSplit) {
                    if (diffFx * 2 <= diff) {
                        out.add(new Possibility(Check.DIV_TOO_BIG, diffFx));
                    }
                } else if (diffFxPostSplit * 2 <= diff) {
                    out.add(new Possibility(Check.DIV_TOO_BIG_AND_PRE_SPLIT, diffFxPostSplit));
                }
            }
        }

        private void tooSmallPossibilities(
                int di, double div, double dropWoVol, @Nullable Double divPostSplit, List<Possibility> out) {
            if (dropWoVol > 0 && intraday && prepost && (open[di] - close[di]) < SESSION_RECOVERY_SHARE * dropWoVol) {
                dropWoVol = 0; // price recovered by the end of the session
            }
            if (!(dropWoVol > 0)) {
                return;
            }
            double diff = Math.abs(div - dropWoVol);
            double diffFx = Math.abs(div * cd - dropWoVol);
            if (divPostSplit == null) {
                if (diffFx <= diff) {
                    out.add(new Possibility(Check.DIV_TOO_SMALL, diffFx));
                }
            } else {
                double diffFxPostSplit = Math.abs(divPostSplit * cd - dropWoVol);
                if (diffFx < diffFxPostSplit) {
                    if (diffFx * 2 <= diff) {
                        out.add(new Possibility(Check.DIV_TOO_SMALL, diffFx));
                    }
                } else if (diffFxPostSplit * 2 <= diff) {
                    out.add(new Possibility(Check.DIV_TOO_SMALL_AND_PRE_SPLIT, diffFxPostSplit));
                }
            }
        }

        // ---- is the present adjustment too big, too small or missing? ----

        private void checkPresentAdjustment() {
            for (Status s : statuses) {
                int di = s.idx;
                double divPct = s.div / close[di - 1];
                double preAdj = adjClose[di - 1] / close[di - 1];
                double postAdj = adjClose[di] / close[di];
                boolean missing = postAdj == preAdj;
                double presentAdj = preAdj / postAdj;
                double impliedYield = 1.0 - presentAdj;
                boolean tooSmall = impliedYield < ADJ_TOO_SMALL_FACTOR * divPct;
                boolean exceeds = impliedYield > ADJ_EXCEEDS_FACTOR * divPct;
                if (missing) {
                    tooSmall = false;
                }
                s.presentAdj = presentAdj;
                s.set(Check.ADJ_MISSING, missing);
                s.set(Check.ADJ_EXCEEDS_DIV, exceeds);
                s.set(Check.DIV_EXCEEDS_ADJ, tooSmall);
            }
            checks.addAll(List.of(Check.ADJ_MISSING, Check.ADJ_EXCEEDS_DIV, Check.DIV_EXCEEDS_ADJ));
        }

        // ---- phantom dividends: an almost equal dividend very close by ----

        private void detectPhantoms() {
            List<Integer> suspicious = new ArrayList<>();
            Set<Instant> suspiciousDates = new HashSet<>();
            for (int i = 0; i < statuses.size(); i++) {
                Status s = statuses.get(i);
                if (s.is(Check.DIV_TOO_BIG) || s.is(Check.DIV_EXCEEDS_ADJ)) {
                    suspicious.add(i);
                    suspiciousDates.add(s.date.toInstant());
                }
            }
            if (!suspicious.isEmpty() && statuses.size() > 1) {
                for (int i : suspicious) {
                    Status s = statuses.get(i);
                    Status other = i > 0 ? statuses.get(i - 1) : statuses.get(i + 1);
                    double ratio1 = (s.div / cd) / other.div;
                    double ratio2 = s.div / other.div;
                    double divergence = Math.min(Math.abs(ratio1 - 1.0), Math.abs(ratio2 - 1.0));
                    if (distance(s, other).compareTo(PHANTOM_PROXIMITY) <= 0
                            && !other.is(Check.PHANTOM)
                            && divergence < PHANTOM_DIVERGENCE) {
                        // both anomalous: the smaller drop is the phantom
                        Status phantom = suspiciousDates.contains(other.date.toInstant())
                                ? (s.drop > PHANTOM_DROP_RATIO * other.drop ? other : s)
                                : s;
                        markPhantom(phantom, checks);
                    }
                }
            }
            for (int i = 1; i < statuses.size(); i++) {
                Status s = statuses.get(i);
                Status last = statuses.get(i - 1);
                double ratio = s.div / last.div;
                if (distance(s, last).compareTo(PHANTOM_PROXIMITY) <= 0
                        && !last.is(Check.PHANTOM)
                        && !s.is(Check.PHANTOM)
                        && Math.abs(ratio - 1.0) < PHANTOM_NEIGHBOUR_DIVERGENCE) {
                    markPhantom(s.drop > PHANTOM_DROP_RATIO * last.drop ? last : s, checks);
                }
            }
        }

        /** Removes phantoms early, but only if nothing else is wrong with them. */
        private void removeLonePhantoms() {
            List<Status> phantoms = new ArrayList<>();
            for (Status s : statuses) {
                if (s.is(Check.PHANTOM) && !s.anyOf(checks)) {
                    phantoms.add(s);
                }
            }
            if (!phantoms.isEmpty()) {
                LOG.info(
                        "{}: removing phantom div(s): {}",
                        symbol,
                        phantoms.stream().map(s -> s.date.toLocalDate()).toList());
                for (Status s : phantoms) {
                    scaleAdjBefore(s.date, 1.0 / s.presentAdj);
                    dividends[s.idx] = 0;
                    modified = true;
                    statuses.remove(s);
                }
            }
            for (Status s : statuses) {
                s.flags.remove(Check.PHANTOM);
            }
        }

        /** A dividend ~0.01x of its neighbours is a 0.01x error. */
        private void flagHundredthDividends() {
            for (int i = 0; i < statuses.size(); i++) {
                Double pre = i > 0 ? statuses.get(i - 1).pct / statuses.get(i).pct : null;
                Double post = i < statuses.size() - 1 ? statuses.get(i + 1).pct / statuses.get(i).pct : null;
                Double rPre = pre == null || pre == 0 ? post : pre;
                Double rPost = post == null || post == 0 ? rPre : post;
                if (rPre != null
                        && rPost != null
                        && Math.abs(rPre - cd) < HUNDREDTH_RATIO_TOLERANCE
                        && Math.abs(rPost - cd) < HUNDREDTH_RATIO_TOLERANCE) {
                    statuses.get(i).set(Check.DIV_TOO_SMALL, true);
                }
            }
        }

        // ---- does the present adjustment contradict the price action? ----

        private void checkAdjustmentAgainstPrices(Status s) {
            int di = s.idx;
            double divPct = s.div / close[di - 1];
            int lookaheadIdx = lookaheadIndex(s.date, di);
            int lookbackIdx = Math.max(0, di - DIV_LOOKBACK_ROWS);
            AdjustmentScan scan = AdjustmentScan.NONE;
            if (lookaheadIdx > lookbackIdx && divPct > PRICE_CHECK_MIN_YIELD && divPct < PRICE_CHECK_MAX_YIELD) {
                scan = scanAdjustment(s, lookbackIdx, lookaheadIdx);
            }
            boolean exceedsPrices = scan.exceedsPrices();
            if (exceedsPrices && s.is(Check.DIV_EXCEEDS_ADJ)) {
                exceedsPrices = false; // contradiction: probably low-liquidity price action
            }
            if (exceedsPrices) {
                double split = splits[di];
                if (split != 0.0) {
                    double divPostSplit = s.div / split;
                    double d = divPostSplit > s.div ? s.drop - s.vol : s.drop2Dmax;
                    if (d > 0 && Math.abs(divPostSplit - d) <= Math.abs(s.div - d) * PRE_SPLIT_SLACK) {
                        s.set(Check.DIV_PRE_SPLIT, true);
                    }
                }
            }
            s.set(Check.ADJ_EXCEEDS_PRICES, exceedsPrices);
            s.set(Check.DIV_DATE_WRONG, scan.trueDate() != null);
            s.trueDate = scan.trueDate();
            for (Status o : statuses) {
                if (o.is(Check.DIV_DATE_WRONG)) {
                    o.set(Check.DIV_TOO_BIG, false);
                }
            }
        }

        /** Yahoo may report the dividend early, so the drop comes later; stops before bad splits. */
        private int lookaheadIndex(ZonedDateTime dt, int di) {
            Instant lookaheadDate = dt.toInstant().plus(DIV_LOOKAHEAD);
            int lookaheadIdx = 0;
            while (lookaheadIdx < n && df2.time(lookaheadIdx).toInstant().isBefore(lookaheadDate)) {
                lookaheadIdx++;
            }
            lookaheadIdx = Math.min(lookaheadIdx, n - 1);
            for (int k = di + 1; k <= lookaheadIdx; k++) {
                double chg = close[k] / close[k - 1] - 1;
                if (chg > BAD_SPLIT_RISE || chg < BAD_SPLIT_FALL) {
                    return k - 1;
                }
            }
            return lookaheadIdx;
        }

        /** Looks for the adjustment step in the adjusted prices and whether it lines up with the drop. */
        private AdjustmentScan scanAdjustment(Status s, int lookbackIdx, int lookaheadIdx) {
            int m = lookaheadIdx - lookbackIdx + 1;
            double[] adj = new double[m];
            double[] delta = new double[m];
            double[] adjDelta = new double[m];
            for (int k = 0; k < m; k++) {
                int r = lookbackIdx + k;
                adj[k] = adjClose[r] / close[r];
                if (k > 0) {
                    delta[k] = low[r] - close[r - 1];
                    adjDelta[k] = adj[k] * low[r] - adjClose[r - 1];
                }
            }
            double adjDiv = s.div * adj[0];
            for (int k = 0; k < m; k++) {
                if (!(adjDelta[k] > adjDiv * ADJ_DROP_SHARE)) {
                    continue;
                }
                double adjDeltaDrop = adjDelta[k];
                if (adjDeltaDrop > ADJ_RISE_TOLERANCE * delta[k]) {
                    // adjusted price rose more than the unadjusted: dividend applied too early?
                    List<Integer> near = new ArrayList<>();
                    for (int q = 0; q < m; q++) {
                        if ((-1 * adjDelta[q]) / adjDeltaDrop >= REVERSAL_SHARE) {
                            near.add(q);
                        }
                    }
                    boolean preSplit = s.is(Check.DIV_PRE_SPLIT);
                    if ((splits[s.idx] == 0.0 || !preSplit) && !near.isEmpty()) {
                        int reversal = closestReversal(near, lookbackIdx, s.date);
                        return new AdjustmentScan(false, df2.time(lookbackIdx + reversal));
                    } else if (adjDeltaDrop > EXCEEDS_PRICES_SHARE * adjDiv) {
                        for (double a : adj) {
                            if (a < 1.0) {
                                return new AdjustmentScan(true, null);
                            }
                        }
                        return AdjustmentScan.NONE;
                    }
                }
            }
            return AdjustmentScan.NONE;
        }

        /** The reversal nearest the reported date, preferring later dates. */
        private int closestReversal(List<Integer> near, int lookbackIdx, ZonedDateTime dt) {
            int reversal = near.getFirst();
            if (near.size() > 1) {
                double best = Double.POSITIVE_INFINITY;
                for (int q : near) {
                    ZonedDateTime dti = df2.time(lookbackIdx + q);
                    double penalty = dti.isBefore(dt)
                            ? Duration.between(dti, dt).toDays()
                            : LATE_DATE_PENALTY * Duration.between(dt, dti).toDays();
                    if (penalty < best) {
                        best = penalty;
                        reversal = q;
                    }
                }
            }
            return reversal;
        }

        // ---- small dividends can fool the checks; compare dividends of similar yield ----

        private void applyClusterConsensus() {
            List<Status> byPct = new ArrayList<>(statuses);
            byPct.sort(Comparator.comparingDouble(s -> s.pct));
            clusterDividends(byPct);
            Set<Integer> clusterIds = new LinkedHashSet<>();
            for (Status s : byPct) {
                clusterIds.add(s.cluster);
            }
            for (int cid : clusterIds) {
                clusterConsensus(statuses.stream().filter(s -> s.cluster == cid).toList());
            }
        }

        private void clusterConsensus(List<Status> cluster) {
            int cn = cluster.size();
            Set<Check> clusterCols = EnumSet.noneOf(Check.class);
            for (Check c : checks) {
                if (anyCheck(cluster, List.of(c))) {
                    clusterCols.add(c);
                }
            }
            List<Check> clusterChecks =
                    checks.stream().filter(clusterCols::contains).toList();
            for (Check c : clusterChecks) {
                int nFail = count(cluster, c);
                if (nFail == 0 || nFail == cn) {
                    continue;
                }
                double pctFail = (double) nFail / cn;
                if (c == Check.DIV_TOO_BIG) {
                    tooBigConsensus(cluster, clusterCols, pctFail);
                } else if (c == Check.DIV_TOO_SMALL) {
                    tooSmallConsensus(cluster, clusterCols, pctFail);
                }
            }
        }

        private void tooBigConsensus(List<Status> cluster, Set<Check> clusterCols, double pctFail) {
            Check c = Check.DIV_TOO_BIG;
            int cn = cluster.size();
            double trueThreshold = TOO_BIG_TRUE_SHARE;
            double falseThreshold = TOO_BIG_FALSE_SHARE;
            if (clusterCols.contains(Check.DIV_DATE_WRONG)
                    && cluster.stream().allMatch(s -> s.is(c) == s.is(Check.DIV_DATE_WRONG))) {
                return;
            }
            if (clusterCols.contains(Check.ADJ_EXCEEDS_PRICES)
                    && cluster.stream().allMatch(s -> !s.is(c) || s.is(Check.ADJ_EXCEEDS_PRICES))) {
                // too-big flags are believable where the adjustment also exceeds prices
                double pctExceeds = (double) count(cluster, Check.ADJ_EXCEEDS_PRICES) / cn;
                if (pctExceeds > EXCEEDS_PRICES_MAJORITY) {
                    for (Status s : cluster) {
                        if (s.is(Check.ADJ_EXCEEDS_PRICES)) {
                            s.set(c, true);
                        }
                    }
                }
                return;
            }
            if (clusterCols.contains(Check.DIV_EXCEEDS_ADJ)
                    && cluster.stream().allMatch(s -> s.is(Check.DIV_EXCEEDS_ADJ))) {
                boolean noVolatility = cluster.stream().filter(s -> s.is(c)).allMatch(s -> s.vol == 0);
                if (noVolatility) {
                    falseThreshold = TOO_BIG_NO_VOLATILITY_FALSE_SHARE;
                } else {
                    trueThreshold = TOO_BIG_ADJ_TOO_SMALL_TRUE_SHARE;
                }
            } else if (clusterCols.contains(Check.ADJ_EXCEEDS_PRICES)
                    && cluster.stream().allMatch(s -> s.is(c) == s.is(Check.ADJ_EXCEEDS_PRICES))) {
                trueThreshold = TOO_BIG_EXCEEDS_PRICES_TRUE_SHARE;
            } else {
                if (symbol.endsWith(".TA")) {
                    trueThreshold = TOO_BIG_TA_TRUE_SHARE;
                }
                falseThreshold = TOO_BIG_FALSE_SHARE;
            }
            if (pctFail >= trueThreshold) {
                for (Status s : cluster) {
                    s.set(c, true);
                    if (present.contains(Check.DIV_DATE_WRONG)) {
                        s.set(Check.DIV_DATE_WRONG, false);
                        s.trueDate = null;
                    }
                }
                if (present.contains(Check.DIV_DATE_WRONG)) {
                    clusterCols.clear();
                    clusterCols.addAll(present);
                }
            } else if (pctFail <= falseThreshold) {
                for (Status s : cluster) {
                    s.set(c, false);
                }
            }
        }

        private void tooSmallConsensus(List<Status> cluster, Set<Check> clusterCols, double pctFail) {
            double trueThreshold = TOO_SMALL_TRUE_SHARE;
            double falseThreshold = TOO_SMALL_FALSE_SHARE;
            if (!clusterCols.contains(Check.ADJ_EXCEEDS_DIV)) {
                // the adjustment confirms the dividends: too-small flags are likely false
                trueThreshold = TOO_SMALL_CONFIRMED_TRUE_SHARE;
                falseThreshold = TOO_SMALL_CONFIRMED_FALSE_SHARE;
            }
            if (pctFail >= trueThreshold) {
                for (Status s : cluster) {
                    s.set(Check.DIV_TOO_SMALL, true);
                }
            } else if (pctFail <= falseThreshold) {
                for (Status s : cluster) {
                    s.set(Check.DIV_TOO_SMALL, false);
                }
            }
        }

        // ---- combined verdicts ----

        /** A too-big dividend whose adjustment is also ~cd^2 too small. */
        private void resolveAdjTooSmall() {
            if (!present.contains(Check.DIV_TOO_BIG) || !present.contains(Check.DIV_EXCEEDS_ADJ)) {
                return;
            }
            boolean any = false;
            for (Status s : statuses) {
                boolean v = false;
                if (s.is(Check.DIV_TOO_BIG) && s.is(Check.DIV_EXCEEDS_ADJ)) {
                    double closeBefore = s.div / s.pct;
                    double implied = (1 - s.presentAdj) * closeBefore;
                    double ratio = s.div / implied;
                    v = Math.abs(ratio - (double) cd * cd) < cd;
                }
                s.set(Check.ADJ_TOO_SMALL, v);
                any |= v;
            }
            if (any) {
                checks.add(Check.ADJ_TOO_SMALL);
                present.add(Check.ADJ_TOO_SMALL);
            } else {
                statuses.forEach(s -> s.flags.remove(Check.ADJ_TOO_SMALL));
            }
        }

        /** Splits the combined too-big-and-pre-split verdict into its two checks. */
        private void mergeTooBigAndPreSplit() {
            if (!present.contains(Check.DIV_TOO_BIG_AND_PRE_SPLIT)) {
                return;
            }
            for (Check c : List.of(Check.DIV_TOO_BIG, Check.DIV_PRE_SPLIT)) {
                for (Status s : statuses) {
                    s.set(c, s.is(c) || s.is(Check.DIV_TOO_BIG_AND_PRE_SPLIT));
                }
                if (!present.contains(c)) {
                    present.add(c);
                    checks.add(c);
                }
            }
            statuses.forEach(s -> s.flags.remove(Check.DIV_TOO_BIG_AND_PRE_SPLIT));
            present.remove(Check.DIV_TOO_BIG_AND_PRE_SPLIT);
            checks.remove(Check.DIV_TOO_BIG_AND_PRE_SPLIT);
        }

        // ---- repair ----

        private void applyRepairs(List<Status> problems) {
            Map<String, List<LocalDate>> repairs = new TreeMap<>();
            Set<Integer> ids = new LinkedHashSet<>();
            for (Status s : problems) {
                ids.add(s.cluster);
            }
            for (int cid : ids) {
                for (Status row :
                        problems.stream().filter(s -> s.cluster == cid).toList()) {
                    Verdict v = verdict(row);
                    String k = switch (v.failed) {
                        case 1 -> repairOneFailure(row, v);
                        case 2 -> repairTwoFailures(row, v);
                        case 3 -> repairThreeFailures(row, v);
                        default -> null;
                    };
                    if (k != null) {
                        repairs.computeIfAbsent(k, x -> new ArrayList<>()).add(row.date.toLocalDate());
                    }
                }
            }
            repairs.forEach((k, dates) -> LOG.info("{}: repaired {}: {}", symbol, k, dates));
        }

        private Verdict verdict(Status row) {
            Verdict v = new Verdict();
            v.adjMissing = row.is(Check.ADJ_MISSING);
            v.divExceedsAdj = row.is(Check.DIV_EXCEEDS_ADJ);
            v.adjExceedsDiv = row.is(Check.ADJ_EXCEEDS_DIV);
            v.adjExceedsPrices = row.is(Check.ADJ_EXCEEDS_PRICES);
            v.divTooSmall = row.is(Check.DIV_TOO_SMALL);
            v.divTooBig = row.is(Check.DIV_TOO_BIG);
            v.divPreSplit = row.is(Check.DIV_PRE_SPLIT);
            v.divDateWrong = row.is(Check.DIV_DATE_WRONG);
            v.adjTooSmall = row.is(Check.ADJ_TOO_SMALL);
            for (Check c : checks) {
                v.failed += row.is(c) ? 1 : 0;
            }
            if (v.divTooBig && v.adjExceedsPrices && v.failed == 2) {
                // redundant: fixing the dividend fixes the adjustment
                v.adjExceedsPrices = false;
                v.failed--;
            }
            if (v.divDateWrong) {
                if (v.divTooBig) {
                    v.divTooBig = false;
                    v.failed--;
                }
                if (v.divExceedsAdj) {
                    v.divExceedsAdj = false;
                    v.failed--;
                }
            }
            if (v.divPreSplit && v.adjExceedsPrices) {
                v.adjExceedsPrices = false;
                v.failed--;
            }
            return v;
        }

        private @Nullable String repairOneFailure(Status row, Verdict v) {
            ZonedDateTime dt = row.date;
            if (v.divExceedsAdj || v.adjExceedsDiv) {
                scaleAdjBefore(dt, (1.0 - row.pct) / row.presentAdj);
                return v.divExceedsAdj ? "too-small div-adjust" : "too-big div-adjust";
            } else if (v.divTooSmall) {
                dividends[row.idx] = row.div * cd;
                double targetAdj = 1.0 - ((1.0 - row.presentAdj) * cd);
                scaleAdjBefore(dt, targetAdj / row.presentAdj);
                return "too-small div & div-adjust";
            } else if (v.divTooBig) {
                double correction = 1.0 / cd;
                dividends[row.idx] = row.div * correction;
                double targetAdj = 1.0 - row.pct * correction;
                scaleAdjBefore(dt, targetAdj / row.presentAdj);
                return "too-big div & div-adjust";
            } else if (v.adjMissing) {
                scaleAdjBefore(dt, 1.0 - row.pct);
                return "missing div-adjust";
            } else if (v.divDateWrong) {
                ZonedDateTime trueDate = row.trueDate;
                // roll back the present adjustment, apply the right one from the right date
                scaleAdjBeforeNoFlag(dt, 1.0 / row.presentAdj);
                double trueAdj = 1.0 - row.div / close[row.idx];
                if (trueDate != null) {
                    scaleAdjBeforeNoFlag(trueDate, trueAdj);
                    int ti = df2.indexOf(trueDate);
                    if (ti >= 0) {
                        dividends[ti] += row.div;
                    }
                }
                dividends[row.idx] = 0;
                flagBefore(dt);
                return "wrong ex-div date";
            } else if (v.adjExceedsPrices) {
                // probably a false positive, but no harm checking the adjustment
                double targetAdj = 1.0 - row.pct;
                if (Math.abs((targetAdj / row.presentAdj) - 1) > MIN_ADJ_CHANGE) {
                    scaleAdjBefore(dt, targetAdj / row.presentAdj);
                    return "div-adjust";
                }
                return null;
            } else if (v.divPreSplit) {
                double correction = 1.0 / splits[row.idx];
                dividends[row.idx] = row.div * correction;
                double targetAdj = 1.0 - row.pct * correction;
                scaleAdjBefore(dt, targetAdj / row.presentAdj);
                return "pre-split div & div-adjust";
            }
            return null;
        }

        private @Nullable String repairTwoFailures(Status row, Verdict v) {
            ZonedDateTime dt = row.date;
            if (v.divTooBig && v.adjMissing) {
                dividends[row.idx] /= cd;
                scaleAdjBefore(dt, 1.0 - row.pct / cd);
                return "too-big div and missing div-adjust";
            } else if (v.divTooSmall && v.adjMissing) {
                dividends[row.idx] *= cd;
                scaleAdjBefore(dt, 1.0 - row.pct * cd);
                return "too-small div and missing div-adjust";
            } else if (v.divTooBig && v.divExceedsAdj) {
                // the adjusted close is right, only the dividend is wrong
                dividends[row.idx] /= cd;
                return "div-too-big";
            } else if (v.divTooBig && v.adjExceedsPrices) {
                double targetAdj = 1.0 - row.pct / cd;
                dividends[row.idx] /= cd;
                scaleAdjBefore(dt, targetAdj / row.presentAdj);
                return "too-big div & div-adjust";
            } else if (v.divTooSmall && v.adjExceedsDiv) {
                dividends[row.idx] *= cd;
                return "too-small div";
            } else if (v.divPreSplit && v.divExceedsAdj) {
                double correction = 1.0 / splits[row.idx];
                dividends[row.idx] = row.div * correction;
                double targetDivPct = row.pct * correction;
                scaleAdjBefore(dt, (1.0 - targetDivPct) / row.presentAdj);
                return "pre-split & too-small div-adjust";
            }
            return null;
        }

        private @Nullable String repairThreeFailures(Status row, Verdict v) {
            ZonedDateTime dt = row.date;
            if (v.divTooBig && v.divExceedsAdj && v.divPreSplit) {
                double correction = (1.0 / cd) * (1.0 / splits[row.idx]);
                dividends[row.idx] = row.div * correction;
                double targetAdj = 1.0 - row.pct * correction;
                scaleAdjBefore(dt, targetAdj / row.presentAdj);
                return "too-big div & pre-split & div-adjust";
            } else if (v.divTooBig && v.divExceedsAdj && v.adjTooSmall) {
                double closeBefore = row.div / row.pct;
                double divTrue = row.div / cd;
                dividends[row.idx] = divTrue;
                double adjCorrect = 1.0 - divTrue / closeBefore;
                scaleAdjBefore(dt, adjCorrect / row.presentAdj);
                return "div-too-big and adj-too-small";
            }
            return null;
        }

        // ---- adjusted-close edits ----

        /** Sets the adjusted close before row {@code di} to {@code trueAdjust * close} if it differs much. */
        private void fixAdjustmentBefore(int di, double trueAdjust) {
            double presentAdj = df2.get(ADJ_CLOSE, di - 1) / df2.get(CLOSE, di - 1);
            if (!Stats.isClose(presentAdj, trueAdjust, PRICE_MATCH_RTOL)) {
                Instant dt = df2.time(di).toInstant();
                for (int i = 0; i < df2.size() && df2.time(i).toInstant().isBefore(dt); i++) {
                    df2.set(ADJ_CLOSE, i, trueAdjust * df2.get(CLOSE, i));
                    df2.repaired()[i] = true;
                }
            }
        }

        /** {@code df2.loc[:dt-1s, 'Adj Close'] *= m} on both tables, flagging the rows repaired. */
        private void scaleAdjBefore(ZonedDateTime dt, double m) {
            scaleAdjBeforeNoFlag(dt, m);
            flagBefore(dt);
        }

        private void scaleAdjBeforeNoFlag(ZonedDateTime dt, double m) {
            for (Frame f : new Frame[] {df2, df2Nan}) {
                for (int i = 0; i < f.size(); i++) {
                    if (f.time(i).toInstant().isBefore(dt.toInstant())) {
                        f.set(ADJ_CLOSE, i, f.get(ADJ_CLOSE, i) * m);
                    }
                }
            }
        }

        private void flagBefore(ZonedDateTime dt) {
            for (Frame f : new Frame[] {df2, df2Nan}) {
                for (int i = 0; i < f.size(); i++) {
                    if (f.time(i).toInstant().isBefore(dt.toInstant())) {
                        f.repaired()[i] = true;
                    }
                }
            }
        }

        private Frame finish() {
            return DividendRepair.finish(df2, intradayFrame, df2Nan, nanPrices);
        }
    }

    // ---- helpers ----

    /** Python's cluster_dividends: consecutive (by yield) dividends within 7x of the cluster mean. */
    private static void clusterDividends(List<Status> byPct) {
        int cluster = 0;
        double sum = 0;
        int count = 0;
        for (Status s : byPct) {
            if (count > 0 && s.pct / (sum / count) >= CLUSTER_YIELD_RATIO) {
                cluster++;
                sum = 0;
                count = 0;
            }
            s.cluster = cluster;
            sum += s.pct;
            count++;
        }
    }

    private static void markPhantom(Status s, List<Check> checks) {
        s.set(Check.PHANTOM, true);
        for (Check c : checks) {
            s.set(c, false);
        }
    }

    private static Duration distance(Status a, Status b) {
        return Duration.between(a.date, b.date).abs();
    }

    private static boolean anyCheck(List<Status> statuses, List<Check> checks) {
        for (Status s : statuses) {
            if (s.anyOf(checks)) {
                return true;
            }
        }
        return false;
    }

    private static int count(List<Status> statuses, Check c) {
        int k = 0;
        for (Status s : statuses) {
            k += s.is(c) ? 1 : 0;
        }
        return k;
    }

    /**
     * Carries daily repairs back to the intraday table (dividend amounts and adjustment steps),
     * restores the forward-filled prices and adds back the rows without a close.
     */
    private static Frame finish(
            Frame df2, @Nullable Frame intradayFrame, Frame df2Nan, Map<Instant, double[]> nanPrices) {
        Frame out = df2;
        if (intradayFrame != null) {
            out = intradayFrame;
            int n = df2.size();
            double[] dailyAdj = new double[n];
            for (int i = 0; i < n; i++) {
                dailyAdj[i] = df2.get(ADJ_CLOSE, i) / df2.get(CLOSE, i);
            }
            int ni = out.size();
            double[] intraAdj = new double[ni];
            for (int i = 0; i < ni; i++) {
                intraAdj[i] = out.get(ADJ_CLOSE, i) / out.get(CLOSE, i);
            }
            int[] divIndices = Frame.where(SuddenChange.nonZero(out.col(DIVIDENDS)));
            LocalDate firstDate = out.time(0).toLocalDate();
            for (int q = divIndices.length - 1; q >= 0; q--) {
                int di = divIndices[q];
                ZonedDateTime divDt = out.time(di);
                LocalDate divD = divDt.toLocalDate();
                if (divD.equals(firstDate)) {
                    out.set(DIVIDENDS, di, df2.get(DIVIDENDS, 0));
                    continue;
                }
                List<Integer> days = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    LocalDate d = df2.time(i).toLocalDate();
                    if (!d.isBefore(divD.minusDays(7)) && !d.isAfter(divD)) {
                        days.add(i);
                    }
                }
                if (days.size() < 2) {
                    continue;
                }
                int lastDay = days.getLast();
                double divCorrect = df2.get(DIVIDENDS, lastDay);
                out.set(DIVIDENDS, di, divCorrect);
                if (divCorrect != 0.0) {
                    double adjCorrect = dailyAdj[days.get(days.size() - 2)] / dailyAdj[lastDay];
                    double adjCurrent = intraAdj[di - 1] / intraAdj[di];
                    double correction = adjCorrect / adjCurrent;
                    for (int i = 0; i < ni && out.time(i).isBefore(divDt); i++) {
                        out.set(ADJ_CLOSE, i, out.get(ADJ_CLOSE, i) * correction);
                    }
                }
            }
        }
        for (int i = 0; i < out.size(); i++) {
            double[] saved = nanPrices.get(out.time(i).toInstant());
            if (saved != null) {
                for (int j = 0; j < OHLCA.length; j++) {
                    out.set(OHLCA[j], i, saved[j]);
                }
            }
        }
        return df2Nan.isEmpty() ? out : Frame.concat(out, df2Nan).sortedAscending();
    }
}
