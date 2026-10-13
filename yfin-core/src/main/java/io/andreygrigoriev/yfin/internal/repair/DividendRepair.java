package io.andreygrigoriev.yfin.internal.repair;

import static io.andreygrigoriev.yfin.internal.repair.Frame.Col.ADJ_CLOSE;
import static io.andreygrigoriev.yfin.internal.repair.Frame.Col.CAPITAL_GAINS;
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
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private static final double TOO_BIG_CHECK_THRESHOLD = 0.035;
    private static final Duration PHANTOM_PROXIMITY = Duration.ofDays(17);

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
        final Map<Check, Boolean> flags = new EnumMap<>(Check.class);

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
            return flags.getOrDefault(c, false);
        }

        void set(Check c, boolean v) {
            flags.put(c, v);
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

    private DividendRepair() {}

    static Frame fix(Frame input, Interval interval, boolean prepost, RepairContext ctx) {
        if (input.isEmpty() || Intervals.multiday(interval)) {
            return input;
        }
        boolean intraday = interval.isIntraday();
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
        int cd = ctx.currencyDivide();
        String symbol = ctx.symbol();

        Frame df = input.inZone(ctx.zone()).sortedAscending();
        boolean modified = false;
        boolean[] closeNaN = new boolean[df.size()];
        for (int i = 0; i < df.size(); i++) {
            closeNaN[i] = Double.isNaN(df.get(CLOSE, i));
        }
        Frame df2Nan = df.select(closeNaN);
        Frame df2 = df.select(SuddenChange.not(closeNaN));

        // prices can be missing on dividend rows: forward-fill, restore at the end
        Map<Instant, double[]> nanPrices = new HashMap<>();
        for (int i = 0; i < df2.size(); i++) {
            boolean anyNaN = false;
            for (Col c : OHLCA) {
                anyNaN |= Double.isNaN(df2.get(c, i));
            }
            if (anyNaN) {
                double[] saved = new double[OHLCA.length];
                for (int j = 0; j < OHLCA.length; j++) {
                    saved[j] = df2.get(OHLCA[j], i);
                }
                nanPrices.put(df2.time(i).toInstant(), saved);
            }
        }
        if (!nanPrices.isEmpty()) {
            for (Col c : OHLCA) {
                double[] a = df2.col(c);
                for (int i = 1; i < a.length; i++) {
                    if (Double.isNaN(a[i])) {
                        a[i] = a[i - 1];
                    }
                }
            }
        }

        Frame intradayFrame = null;
        if (intraday) {
            intradayFrame = df2.copy();
            LocalDate today = ctx.clock().instant().atZone(ctx.zone()).toLocalDate();
            df2 = Resample.resample(df2, Interval.ONE_DAY, ctx.zone(), false, today);
        }
        int n = df2.size();
        int[] divIndices = Frame.where(SuddenChange.nonZero(df2.col(DIVIDENDS)));
        if (divIndices.length == 0) {
            return df;
        }
        double[] close = df2.col(CLOSE);
        double[] adjClose = df2.col(ADJ_CLOSE);
        double[] low = df2.col(LOW);
        double[] high = df2.col(HIGH);
        double[] open = df2.col(OPEN);
        double[] volume = df2.col(VOLUME);
        double[] dividends = df2.col(DIVIDENDS);
        double[] splits = df2.col(SPLITS);

        // Yahoo's adjusted close can overflow to infinity (SSNLF)
        boolean anyInf = false;
        for (double v : adjClose) {
            anyInf |= Double.isInfinite(v);
        }
        if (anyInf) {
            boolean huge = true;
            while (huge) {
                huge = false;
                for (int i = 0; i < n; i++) {
                    if (!Double.isInfinite(adjClose[i]) && adjClose[i] > close[i] * 10) {
                        adjClose[i] *= 0.001;
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

        // the close before the dividend is sometimes already adjusted (below the low)
        List<LocalDate> fixedDates = new ArrayList<>();
        for (int q = divIndices.length - 1; q >= 0; q--) {
            int di = divIndices[q];
            if (di == 0) {
                continue;
            }
            double diff = low[di - 1] - close[di - 1];
            double div = dividends[di];
            if (diff > 0 && (diff / div - 1) < 0.01) {
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

        // is each dividend too big or too small for the price action?
        List<Status> statuses = new ArrayList<>();
        for (int q = divIndices.length - 1; q >= 0; q--) {
            int di = divIndices[q];
            ZonedDateTime dt = df2.time(di);
            double div = dividends[di];
            if (di == 0) {
                continue;
            }
            double divPct = div / close[di - 1];
            double drop;
            if (Stats.isClose(low[di], close[di - 1] * 100, 0.025)) {
                // price jumped ~100x on the ex-div day
                drop = close[di - 1] * 100 - low[di];
                divPct = div / (close[di - 1] * 100);
                fixAdjustmentBefore(df2, di, 1.0 - div / (close[di - 1] * 100));
            } else if (Stats.isClose(low[di], close[di - 1] * 0.01, 0.025)) {
                drop = close[di - 1] * 0.01 - low[di];
                divPct = div / (close[di - 1] * 0.01);
                fixAdjustmentBefore(df2, di, 1.0 - div / (close[di - 1] * 100));
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
            int start;
            int end;
            if (n - di < 4) {
                end = Math.min(n, di + 4);
                start = Math.max(0, end - 8);
            } else {
                start = Math.max(0, di - 4);
                end = Math.min(n, start + 8);
            }
            double typicalVolatility = Double.NaN;
            if (end - start >= 4) {
                double[] diffs = new double[end - 1 - start];
                for (int k = start; k < end - 1; k++) {
                    diffs[k - start] = Math.abs(close[k] - low[k + 1]);
                }
                typicalVolatility = Stats.mean(diffs);
            }

            String state = null;
            double bestDiff = Double.POSITIVE_INFINITY;
            List<Object[]> possibilities = new ArrayList<>();
            boolean allDropsZero = true;
            for (double d : drops) {
                allDropsZero &= d == 0.0;
            }
            if (allDropsZero && volume[di] == 0) {
                // no price action to analyse: crude heuristics
                int zeroVol = 0;
                for (double v : volume) {
                    zeroVol += v == 0.0 ? 1 : 0;
                }
                double pctZeroVol = (double) zeroVol / n;
                if (divPct * 100 < 0.1) {
                    possibilities.add(new Object[] {Check.DIV_TOO_SMALL, 0.0});
                } else if ((pctZeroVol > 0.75 && divPct > 0.25) || divPct > 1.0) {
                    possibilities.add(new Object[] {Check.DIV_TOO_BIG, 0.0});
                }
            } else {
                double split = splits[di];
                Double divPostSplit = null;
                if (split != 0.0) {
                    // Yahoo may not have applied a coincident split to the dividend
                    divPostSplit = div / split;
                    double d = divPostSplit > div ? drop - typicalVolatility : drop2Dmax;
                    if (d > 0) {
                        double diff = Math.abs(div - d);
                        double diffPostSplit = Math.abs(divPostSplit - d);
                        if (diffPostSplit * 2 <= diff) {
                            possibilities.add(new Object[] {Check.DIV_PRE_SPLIT, diffPostSplit});
                        }
                    }
                }
                if (divPct > TOO_BIG_CHECK_THRESHOLD) {
                    if (drop2Dmax <= 0.0) {
                        possibilities.add(new Object[] {Check.DIV_TOO_BIG, 0.0});
                    } else {
                        double diff = Math.abs(div - drop2Dmax);
                        double diffFx = Math.abs(div / cd - drop2Dmax);
                        if (divPostSplit == null) {
                            if (diffFx * 2 <= diff) {
                                possibilities.add(new Object[] {Check.DIV_TOO_BIG, diffFx});
                            }
                        } else {
                            double diffFxPostSplit = Math.abs(divPostSplit / cd - drop2Dmax);
                            if (diffFx < diffFxPostSplit) {
                                if (diffFx * 2 <= diff) {
                                    possibilities.add(new Object[] {Check.DIV_TOO_BIG, diffFx});
                                }
                            } else if (diffFxPostSplit * 2 <= diff) {
                                possibilities.add(new Object[] {Check.DIV_TOO_BIG_AND_PRE_SPLIT, diffFxPostSplit});
                            }
                        }
                    }
                }
                // too small: only the same-day change, to reduce false positives
                if (!Double.isNaN(typicalVolatility)) {
                    double dropWoVol = drop - typicalVolatility;
                    if (dropWoVol > 0 && intraday && prepost && (open[di] - close[di]) < 0.2 * dropWoVol) {
                        dropWoVol = 0; // price recovered by the end of the session
                    }
                    if (dropWoVol > 0) {
                        double diff = Math.abs(div - dropWoVol);
                        double diffFx = Math.abs(div * cd - dropWoVol);
                        if (divPostSplit == null) {
                            if (diffFx <= diff) {
                                possibilities.add(new Object[] {Check.DIV_TOO_SMALL, diffFx});
                            }
                        } else {
                            double diffFxPostSplit = Math.abs(divPostSplit * cd - dropWoVol);
                            if (diffFx < diffFxPostSplit) {
                                if (diffFx * 2 <= diff) {
                                    possibilities.add(new Object[] {Check.DIV_TOO_SMALL, diffFx});
                                }
                            } else if (diffFxPostSplit * 2 <= diff) {
                                possibilities.add(new Object[] {Check.DIV_TOO_SMALL_AND_PRE_SPLIT, diffFxPostSplit});
                            }
                        }
                    }
                }
            }
            Status s = new Status(dt, di, div, divPct, drop, drop2Dmax, typicalVolatility);
            for (Check c : List.of(
                    Check.DIV_TOO_BIG,
                    Check.DIV_TOO_SMALL,
                    Check.DIV_PRE_SPLIT,
                    Check.DIV_TOO_BIG_AND_PRE_SPLIT,
                    Check.DIV_TOO_SMALL_AND_PRE_SPLIT)) {
                s.set(c, false);
            }
            for (Object[] p : possibilities) {
                double d = (double) p[1];
                if (state == null || d < bestDiff) {
                    state = ((Check) p[0]).name();
                    bestDiff = d;
                }
            }
            if (state != null) {
                s.set(Check.valueOf(state), true);
            }
            statuses.add(s);
        }
        if (statuses.isEmpty() && !modified) {
            return df;
        }
        statuses.sort(Comparator.comparing(s -> s.date.toInstant()));

        List<Check> checks = new ArrayList<>(List.of(
                Check.DIV_TOO_BIG,
                Check.DIV_TOO_SMALL,
                Check.DIV_PRE_SPLIT,
                Check.DIV_TOO_BIG_AND_PRE_SPLIT,
                Check.DIV_TOO_SMALL_AND_PRE_SPLIT));

        // is the present adjustment too big, too small or missing?
        for (Status s : statuses) {
            int di = s.idx;
            double divPct = s.div / close[di - 1];
            double preAdj = adjClose[di - 1] / close[di - 1];
            double postAdj = adjClose[di] / close[di];
            boolean missing = postAdj == preAdj;
            double presentAdj = preAdj / postAdj;
            double impliedYield = 1.0 - presentAdj;
            boolean tooSmall = impliedYield < 0.1 * divPct;
            boolean exceeds = impliedYield > 10 * divPct;
            if (missing) {
                tooSmall = false;
            }
            s.presentAdj = presentAdj;
            s.set(Check.ADJ_MISSING, missing);
            s.set(Check.ADJ_EXCEEDS_DIV, exceeds);
            s.set(Check.DIV_EXCEEDS_ADJ, tooSmall);
        }
        checks.addAll(List.of(Check.ADJ_MISSING, Check.ADJ_EXCEEDS_DIV, Check.DIV_EXCEEDS_ADJ));

        // phantom dividends: an almost equal dividend very close by
        for (Status s : statuses) {
            s.set(Check.PHANTOM, false);
        }
        List<Integer> suspicious = new ArrayList<>();
        Set<Instant> suspiciousDates = new java.util.HashSet<>();
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
                Status phantom = null;
                if (distance(s, other).compareTo(PHANTOM_PROXIMITY) <= 0
                        && !other.is(Check.PHANTOM)
                        && divergence < 0.01) {
                    if (suspiciousDates.contains(other.date.toInstant())) {
                        // both anomalous: the smaller drop is the phantom
                        phantom = s.drop > 1.5 * other.drop ? other : s;
                    } else {
                        phantom = s;
                    }
                }
                if (phantom != null) {
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
                    && Math.abs(ratio - 1.0) < 0.08) {
                markPhantom(s.drop > 1.5 * last.drop ? last : s, checks);
            }
        }

        // remove phantoms early, but only if nothing else is wrong
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
                scaleAdjBefore(df2, df2Nan, s.date, 1.0 / s.presentAdj);
                dividends[s.idx] = 0;
                modified = true;
                statuses.remove(s);
            }
        }
        for (Status s : statuses) {
            s.flags.remove(Check.PHANTOM);
        }

        if (!anyCheck(statuses, checks) && statuses.size() > 1) {
            // a dividend ~0.01x of its neighbours is a 0.01x error
            for (int i = 0; i < statuses.size(); i++) {
                Double pre = i > 0 ? statuses.get(i - 1).pct / statuses.get(i).pct : null;
                Double post = i < statuses.size() - 1 ? statuses.get(i + 1).pct / statuses.get(i).pct : null;
                Double rPre = pre == null || pre == 0 ? post : pre;
                Double rPost = post == null || post == 0 ? rPre : post;
                if (rPre != null && rPost != null && Math.abs(rPre - cd) < 20 && Math.abs(rPost - cd) < 20) {
                    statuses.get(i).set(Check.DIV_TOO_SMALL, true);
                }
            }
        }

        if (!anyCheck(statuses, checks)) {
            if (!modified) {
                return df;
            }
            return finish(df2, intradayFrame, df2Nan, nanPrices);
        }

        // does the present adjustment contradict the price action?
        for (Status s : statuses) {
            int di = s.idx;
            ZonedDateTime dt = s.date;
            double div = s.div;
            double divPct = div / close[di - 1];
            // Yahoo may report the dividend early, so the drop comes later
            Instant lookaheadDate = dt.toInstant().plus(Duration.ofDays(35));
            int lookaheadIdx = 0;
            while (lookaheadIdx < n && df2.time(lookaheadIdx).toInstant().isBefore(lookaheadDate)) {
                lookaheadIdx++;
            }
            lookaheadIdx = Math.min(lookaheadIdx, n - 1);
            int lookbackIdx = Math.max(0, di - 14);
            // bad stock splits in the lookahead period: stop before them
            for (int k = di + 1; k <= lookaheadIdx; k++) {
                double chg = close[k] / close[k - 1] - 1;
                if (chg > 2 || chg < -0.9) {
                    lookaheadIdx = k - 1;
                    break;
                }
            }
            boolean exceedsPrices = false;
            boolean dateWrong = false;
            ZonedDateTime trueDate = null;
            if (lookaheadIdx > lookbackIdx && divPct > 0.05 && divPct < 1.0) {
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
                double adjDiv = div * adj[0];
                for (int k = 0; k < m; k++) {
                    if (!(adjDelta[k] > adjDiv * 0.6)) {
                        continue;
                    }
                    double adjDeltaDrop = adjDelta[k];
                    if (adjDeltaDrop > 1.001 * delta[k]) {
                        // adjusted price rose more than the unadjusted: dividend applied too early?
                        List<Integer> near = new ArrayList<>();
                        for (int q = 0; q < m; q++) {
                            if ((-1 * adjDelta[q]) / adjDeltaDrop >= 0.8) {
                                near.add(q);
                            }
                        }
                        double split = splits[di];
                        boolean preSplit = s.is(Check.DIV_PRE_SPLIT);
                        if ((split == 0.0 || !preSplit) && !near.isEmpty()) {
                            int reversal = near.getFirst();
                            if (near.size() > 1) {
                                double best = Double.POSITIVE_INFINITY;
                                for (int q : near) {
                                    ZonedDateTime dti = df2.time(lookbackIdx + q);
                                    double penalty = dti.isBefore(dt)
                                            ? Duration.between(dti, dt).toDays()
                                            : 0.1 * Duration.between(dt, dti).toDays();
                                    if (penalty < best) {
                                        best = penalty;
                                        reversal = q;
                                    }
                                }
                            }
                            dateWrong = true;
                            trueDate = df2.time(lookbackIdx + reversal);
                            break;
                        } else if (adjDeltaDrop > 0.39 * adjDiv) {
                            for (double a : adj) {
                                if (a < 1.0) {
                                    exceedsPrices = true;
                                    break;
                                }
                            }
                            break;
                        }
                    }
                }
            }
            if (exceedsPrices && s.is(Check.DIV_EXCEEDS_ADJ)) {
                exceedsPrices = false; // contradiction: probably low-liquidity price action
            }
            if (exceedsPrices) {
                double split = splits[di];
                if (split != 0.0) {
                    double divPostSplit = div / split;
                    double d = divPostSplit > div ? s.drop - s.vol : s.drop2Dmax;
                    if (d > 0 && Math.abs(divPostSplit - d) <= Math.abs(div - d) * 1.1) {
                        s.set(Check.DIV_PRE_SPLIT, true);
                    }
                }
            }
            s.set(Check.ADJ_EXCEEDS_PRICES, exceedsPrices);
            s.set(Check.DIV_DATE_WRONG, dateWrong);
            s.trueDate = trueDate;
            for (Status o : statuses) {
                if (o.is(Check.DIV_DATE_WRONG)) {
                    o.set(Check.DIV_TOO_BIG, false);
                }
            }
        }
        checks.addAll(List.of(Check.ADJ_EXCEEDS_PRICES, Check.DIV_DATE_WRONG));
        checks.removeIf(c -> !anyCheck(statuses, List.of(c)));
        Set<Check> present = EnumSet.noneOf(Check.class);
        present.addAll(checks);

        // small dividends can fool the checks; compare dividends of similar yield
        List<Status> byPct = new ArrayList<>(statuses);
        byPct.sort(Comparator.comparingDouble(s -> s.pct));
        clusterDividends(byPct);
        Set<Integer> clusterIds = new LinkedHashSet<>();
        for (Status s : byPct) {
            clusterIds.add(s.cluster);
        }
        for (int cid : clusterIds) {
            List<Status> cluster =
                    statuses.stream().filter(s -> s.cluster == cid).toList();
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
                    double trueThreshold = 1.0;
                    double falseThreshold = 0.5;
                    if (clusterCols.contains(Check.DIV_DATE_WRONG)
                            && cluster.stream().allMatch(s -> s.is(c) == s.is(Check.DIV_DATE_WRONG))) {
                        continue;
                    }
                    if (clusterCols.contains(Check.ADJ_EXCEEDS_PRICES)
                            && cluster.stream().allMatch(s -> !s.is(c) || s.is(Check.ADJ_EXCEEDS_PRICES))) {
                        // too-big flags are believable where the adjustment also exceeds prices
                        double pctExceeds = (double) count(cluster, Check.ADJ_EXCEEDS_PRICES) / cn;
                        if (pctExceeds > 0.5) {
                            for (Status s : cluster) {
                                if (s.is(Check.ADJ_EXCEEDS_PRICES)) {
                                    s.set(c, true);
                                }
                            }
                        }
                        continue;
                    }
                    if (clusterCols.contains(Check.DIV_EXCEEDS_ADJ)
                            && cluster.stream().allMatch(s -> s.is(Check.DIV_EXCEEDS_ADJ))) {
                        boolean noVolatility =
                                cluster.stream().filter(s -> s.is(c)).allMatch(s -> s.vol == 0);
                        if (noVolatility) {
                            falseThreshold = 2.0 / 3;
                        } else {
                            trueThreshold = 0.25;
                        }
                    } else if (clusterCols.contains(Check.ADJ_EXCEEDS_PRICES)
                            && cluster.stream().allMatch(s -> s.is(c) == s.is(Check.ADJ_EXCEEDS_PRICES))) {
                        trueThreshold = 0.5;
                    } else {
                        if (symbol.endsWith(".TA")) {
                            trueThreshold = 0.74; // currency mix-ups are common
                        }
                        falseThreshold = 0.5;
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
                            clusterCols = EnumSet.copyOf(present);
                        }
                        continue;
                    } else if (pctFail <= falseThreshold) {
                        for (Status s : cluster) {
                            s.set(c, false);
                        }
                        continue;
                    }
                }
                if (c == Check.DIV_TOO_SMALL) {
                    double trueThreshold = 1.0;
                    double falseThreshold = 0.11;
                    if (!clusterCols.contains(Check.ADJ_EXCEEDS_DIV)) {
                        // the adjustment confirms the dividends: too-small flags are likely false
                        trueThreshold = 6.0 / 11;
                        falseThreshold = 0.5;
                    }
                    if (pctFail >= trueThreshold) {
                        for (Status s : cluster) {
                            s.set(c, true);
                        }
                    } else if (pctFail <= falseThreshold) {
                        for (Status s : cluster) {
                            s.set(c, false);
                        }
                    }
                }
            }
        }

        if (present.contains(Check.DIV_TOO_BIG) && present.contains(Check.DIV_EXCEEDS_ADJ)) {
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
        if (present.contains(Check.DIV_TOO_BIG_AND_PRE_SPLIT)) {
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

        // keep the dividends with problems
        List<Status> problems = new ArrayList<>();
        for (Status s : statuses) {
            if (s.anyOf(checks)) {
                problems.add(s);
            }
        }
        if (problems.isEmpty()) {
            return finish(df2, intradayFrame, df2Nan, nanPrices);
        }

        Map<String, List<LocalDate>> repairs = new java.util.TreeMap<>();
        Set<Integer> ids = new LinkedHashSet<>();
        for (Status s : problems) {
            ids.add(s.cluster);
        }
        for (int cid : ids) {
            for (Status row : problems.stream().filter(s -> s.cluster == cid).toList()) {
                ZonedDateTime dt = row.date;
                boolean adjMissing = row.is(Check.ADJ_MISSING);
                boolean divExceedsAdj = row.is(Check.DIV_EXCEEDS_ADJ);
                boolean adjExceedsDiv = row.is(Check.ADJ_EXCEEDS_DIV);
                boolean adjExceedsPrices = row.is(Check.ADJ_EXCEEDS_PRICES);
                boolean divTooSmall = row.is(Check.DIV_TOO_SMALL);
                boolean divTooBig = row.is(Check.DIV_TOO_BIG);
                boolean divPreSplit = row.is(Check.DIV_PRE_SPLIT);
                boolean divDateWrong = row.is(Check.DIV_DATE_WRONG);
                boolean adjTooSmall = row.is(Check.ADJ_TOO_SMALL);
                int failed = 0;
                for (Check c : checks) {
                    failed += row.is(c) ? 1 : 0;
                }
                if (divTooBig && adjExceedsPrices && failed == 2) {
                    // redundant: fixing the dividend fixes the adjustment
                    adjExceedsPrices = false;
                    failed--;
                }
                if (divDateWrong) {
                    if (divTooBig) {
                        divTooBig = false;
                        failed--;
                    }
                    if (divExceedsAdj) {
                        divExceedsAdj = false;
                        failed--;
                    }
                }
                if (divPreSplit && adjExceedsPrices) {
                    adjExceedsPrices = false;
                    failed--;
                }

                String k = null;
                if (failed == 1) {
                    if (divExceedsAdj || adjExceedsDiv) {
                        k = divExceedsAdj ? "too-small div-adjust" : "too-big div-adjust";
                        scaleAdjBefore(df2, df2Nan, dt, (1.0 - row.pct) / row.presentAdj);
                    } else if (divTooSmall) {
                        k = "too-small div & div-adjust";
                        dividends[row.idx] = row.div * cd;
                        double targetAdj = 1.0 - ((1.0 - row.presentAdj) * cd);
                        scaleAdjBefore(df2, df2Nan, dt, targetAdj / row.presentAdj);
                    } else if (divTooBig) {
                        k = "too-big div & div-adjust";
                        double correction = 1.0 / cd;
                        dividends[row.idx] = row.div * correction;
                        double targetAdj = 1.0 - row.pct * correction;
                        scaleAdjBefore(df2, df2Nan, dt, targetAdj / row.presentAdj);
                    } else if (adjMissing) {
                        k = "missing div-adjust";
                        scaleAdjBefore(df2, df2Nan, dt, 1.0 - row.pct);
                    } else if (divDateWrong) {
                        k = "wrong ex-div date";
                        ZonedDateTime trueDate = row.trueDate;
                        // roll back the present adjustment, apply the right one from the right date
                        scaleAdjBeforeNoFlag(df2, df2Nan, dt, 1.0 / row.presentAdj);
                        double trueAdj = 1.0 - row.div / close[row.idx];
                        if (trueDate != null) {
                            scaleAdjBeforeNoFlag(df2, df2Nan, trueDate, trueAdj);
                            int ti = df2.indexOf(trueDate);
                            if (ti >= 0) {
                                dividends[ti] += row.div;
                            }
                        }
                        dividends[row.idx] = 0;
                        flagBefore(df2, df2Nan, dt);
                    } else if (adjExceedsPrices) {
                        // probably a false positive, but no harm checking the adjustment
                        double targetAdj = 1.0 - row.pct;
                        if (Math.abs((targetAdj / row.presentAdj) - 1) > 0.05) {
                            k = "div-adjust";
                            scaleAdjBefore(df2, df2Nan, dt, targetAdj / row.presentAdj);
                        }
                    } else if (divPreSplit) {
                        k = "pre-split div & div-adjust";
                        double correction = 1.0 / splits[row.idx];
                        dividends[row.idx] = row.div * correction;
                        double targetAdj = 1.0 - row.pct * correction;
                        scaleAdjBefore(df2, df2Nan, dt, targetAdj / row.presentAdj);
                    }
                } else if (failed == 2) {
                    if (divTooBig && adjMissing) {
                        k = "too-big div and missing div-adjust";
                        dividends[row.idx] /= cd;
                        scaleAdjBefore(df2, df2Nan, dt, 1.0 - row.pct / cd);
                    } else if (divTooSmall && adjMissing) {
                        k = "too-small div and missing div-adjust";
                        dividends[row.idx] *= cd;
                        scaleAdjBefore(df2, df2Nan, dt, 1.0 - row.pct * cd);
                    } else if (divTooBig && divExceedsAdj) {
                        // the adjusted close is right, only the dividend is wrong
                        k = "div-too-big";
                        dividends[row.idx] /= cd;
                    } else if (divTooBig && adjExceedsPrices) {
                        k = "too-big div & div-adjust";
                        double targetAdj = 1.0 - row.pct / cd;
                        dividends[row.idx] /= cd;
                        scaleAdjBefore(df2, df2Nan, dt, targetAdj / row.presentAdj);
                    } else if (divTooSmall && adjExceedsDiv) {
                        k = "too-small div";
                        dividends[row.idx] *= cd;
                    } else if (divPreSplit && divExceedsAdj) {
                        k = "pre-split & too-small div-adjust";
                        double correction = 1.0 / splits[row.idx];
                        dividends[row.idx] = row.div * correction;
                        double targetDivPct = row.pct * correction;
                        scaleAdjBefore(df2, df2Nan, dt, (1.0 - targetDivPct) / row.presentAdj);
                    }
                } else if (failed == 3) {
                    if (divTooBig && divExceedsAdj && divPreSplit) {
                        k = "too-big div & pre-split & div-adjust";
                        double correction = (1.0 / cd) * (1.0 / splits[row.idx]);
                        dividends[row.idx] = row.div * correction;
                        double targetAdj = 1.0 - row.pct * correction;
                        scaleAdjBefore(df2, df2Nan, dt, targetAdj / row.presentAdj);
                    } else if (divTooBig && divExceedsAdj && adjTooSmall) {
                        k = "div-too-big and adj-too-small";
                        double closeBefore = row.div / row.pct;
                        double divTrue = row.div / cd;
                        dividends[row.idx] = divTrue;
                        double adjCorrect = 1.0 - divTrue / closeBefore;
                        scaleAdjBefore(df2, df2Nan, dt, adjCorrect / row.presentAdj);
                    }
                }
                if (k != null) {
                    repairs.computeIfAbsent(k, x -> new ArrayList<>()).add(dt.toLocalDate());
                }
            }
        }
        repairs.forEach((k, dates) -> LOG.info("{}: repaired {}: {}", symbol, k, dates));
        return finish(df2, intradayFrame, df2Nan, nanPrices);
    }

    // ---- helpers ----

    /** Python's cluster_dividends: consecutive (by yield) dividends within 7x of the cluster mean. */
    private static void clusterDividends(List<Status> byPct) {
        int cluster = 0;
        double sum = 0;
        int count = 0;
        for (Status s : byPct) {
            if (count > 0 && s.pct / (sum / count) >= 7) {
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

    /** Sets the adjusted close before row {@code di} to {@code trueAdjust * close} if it differs much. */
    private static void fixAdjustmentBefore(Frame df2, int di, double trueAdjust) {
        double presentAdj = df2.get(ADJ_CLOSE, di - 1) / df2.get(CLOSE, di - 1);
        if (!Stats.isClose(presentAdj, trueAdjust, 0.025)) {
            Instant dt = df2.time(di).toInstant();
            for (int i = 0; i < df2.size() && df2.time(i).toInstant().isBefore(dt); i++) {
                df2.set(ADJ_CLOSE, i, trueAdjust * df2.get(CLOSE, i));
                df2.repaired()[i] = true;
            }
        }
    }

    /** {@code df2.loc[:dt-1s, 'Adj Close'] *= m} on both tables, flagging the rows repaired. */
    private static void scaleAdjBefore(Frame df2, Frame df2Nan, ZonedDateTime dt, double m) {
        scaleAdjBeforeNoFlag(df2, df2Nan, dt, m);
        flagBefore(df2, df2Nan, dt);
    }

    private static void scaleAdjBeforeNoFlag(Frame df2, Frame df2Nan, ZonedDateTime dt, double m) {
        for (Frame f : new Frame[] {df2, df2Nan}) {
            for (int i = 0; i < f.size(); i++) {
                if (f.time(i).toInstant().isBefore(dt.toInstant())) {
                    f.set(ADJ_CLOSE, i, f.get(ADJ_CLOSE, i) * m);
                }
            }
        }
    }

    private static void flagBefore(Frame df2, Frame df2Nan, ZonedDateTime dt) {
        for (Frame f : new Frame[] {df2, df2Nan}) {
            for (int i = 0; i < f.size(); i++) {
                if (f.time(i).toInstant().isBefore(dt.toInstant())) {
                    f.repaired()[i] = true;
                }
            }
        }
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
