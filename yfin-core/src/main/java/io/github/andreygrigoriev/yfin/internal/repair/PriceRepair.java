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
import io.github.andreygrigoriev.yfin.model.Bar;
import io.github.andreygrigoriev.yfin.model.CapitalGain;
import io.github.andreygrigoriev.yfin.model.Dividend;
import io.github.andreygrigoriev.yfin.model.Split;
import io.github.andreygrigoriev.yfin.request.Interval;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * yfinance's price repair pipeline (the {@code repair=True} branch of {@code PriceHistory.history}),
 * in its order: make the currency unit consistent, bad dividends and dividend adjustments, missing
 * prices of the latest bar, currency unit mix-ups and bad stock splits (not for FX and futures,
 * which need volume), zero or missing prices, capital gains counted twice, then revert the currency
 * scaling. Runs on unadjusted bars before auto-adjustment and before empty bars are dropped.
 */
public final class PriceRepair {

    private static final Logger LOG = LoggerFactory.getLogger(PriceRepair.class);

    /** Subunit currencies and their main unit: pence, South African cents, Israeli agorot. */
    private static final Map<String, String> SUBUNITS = Map.of("GBp", "GBP", "ZAc", "ZAR", "ILA", "ILS");

    private static final double SUBUNIT_FACTOR = 0.01;

    /** Bars and actions repaired together. */
    public record Result(List<Bar> bars, List<Dividend> dividends, String currency) {}

    private PriceRepair() {}

    /**
     * Repairs {@code bars} (sorted, unadjusted, duplicates removed) with the actions that fall on
     * them. Splits and capital gains are read but never changed; dividends may be rescaled,
     * removed or moved.
     */
    public static Result repair(
            List<Bar> bars,
            List<Dividend> dividends,
            List<Split> splits,
            List<CapitalGain> capitalGains,
            boolean hasCapitalGains,
            Interval interval,
            boolean prepost,
            RepairContext ctx) {
        Objects.requireNonNull(ctx, "ctx");
        if (bars.isEmpty()) {
            return new Result(bars, dividends, ctx.currency());
        }
        Mapped mapped = toFrame(bars, dividends, splits, capitalGains, hasCapitalGains, interval);
        Frame repaired = repair(mapped.frame, interval, prepost, ctx);
        return new Result(toBars(repaired), toDividends(repaired, mapped), ctx.currency());
    }

    /** The pipeline on a table. */
    static Frame repair(Frame input, Interval interval, boolean prepost, RepairContext ctx) {
        LOG.debug("{}: checking {} OHLC for repairs", ctx.symbol(), interval);
        Frame df = input.inZone(ctx.zone()).sortedAscending();
        String originalCurrency = ctx.currency();
        boolean scaled = standardiseCurrency(df, ctx);
        double[] vol = df.col(VOLUME);
        for (int i = 0; i < vol.length; i++) {
            if (Double.isNaN(vol[i])) {
                vol[i] = 0;
            }
        }
        df = DividendRepair.fix(df, interval, prepost, ctx);
        if (!df.isEmpty()) {
            // the latest bar must be repaired before 100x and split repair
            int n = df.size();
            Frame last = Zeroes.fix(df.slice(n - 1, n), interval, prepost, ctx);
            df = Frame.concat(df.slice(0, n - 1), last);
        }
        if (!ctx.symbol().contains("=")) {
            // not for FX and futures: these repairs need volume
            df = UnitMixups.fix(df, interval, prepost, ctx);
            df = SplitRepair.fix(df, interval, ctx);
        }
        // 100x and split errors must be repaired before price reconstruction
        df = Zeroes.fix(df, interval, prepost, ctx);
        df = CapitalGainsRepair.fix(df, ctx);
        if (scaled) {
            // back to the quotation currency
            for (Col c : Frame.PRICES) {
                double[] a = df.col(c);
                for (int i = 0; i < a.length; i++) {
                    a[i] /= SUBUNIT_FACTOR;
                }
            }
            double[] d = df.col(DIVIDENDS);
            for (int i = 0; i < d.length; i++) {
                d[i] /= SUBUNIT_FACTOR;
            }
            ctx.currency(originalCurrency);
            ctx.currencyRepaired(false);
        }
        return df.sortedAscending();
    }

    /**
     * yfinance's {@code _standardise_currency}: prices in a subunit (e.g. GBp) are converted to the
     * main unit so that dividends and prices compare; dividends that are absurdly high against the
     * converted prices are converted too. Prices already in the main unit (the latest close agrees
     * with the metadata's regular market price ~100x) are left alone.
     *
     * @return whether prices were scaled
     */
    static boolean standardiseCurrency(Frame df, RepairContext ctx) {
        String currency = ctx.currency();
        String main = SUBUNITS.get(currency);
        if (main == null) {
            return false;
        }
        int lastActive = -1;
        for (int i = 0; i < df.size(); i++) {
            if (df.get(VOLUME, i) > 0) {
                lastActive = i;
            }
        }
        if (lastActive < 0) {
            return false;
        }
        boolean inSubunits = true;
        Instant recent = ctx.clock().instant().minus(Duration.ofDays(30));
        Double marketPrice = ctx.regularMarketPrice();
        if (df.time(lastActive).toInstant().isAfter(recent) && marketPrice != null) {
            double ratio = marketPrice / df.get(CLOSE, lastActive);
            if (Math.abs(ratio * SUBUNIT_FACTOR - 1) < 0.1) {
                inSubunits = false;
            }
        }
        if (inSubunits) {
            for (Col c : Frame.PRICES) {
                double[] a = df.col(c);
                for (int i = 0; i < a.length; i++) {
                    a[i] *= SUBUNIT_FACTOR;
                }
            }
        }
        ctx.currency(main);
        ctx.currencyRepaired(true);

        // a dividend yield above 100% on the converted prices means the dividend was in the subunit
        double[] close = df.col(CLOSE);
        double[] div = df.col(DIVIDENDS);
        double prev = close.length > 0 ? close[0] : Double.NaN;
        double ffilled = Double.NaN;
        double sum = 0;
        int count = 0;
        for (int i = 0; i < df.size(); i++) {
            double before = i == 0 ? close[0] : prev;
            if (div[i] != 0.0) {
                sum += div[i] / before;
                count++;
            }
            if (!Double.isNaN(close[i])) {
                ffilled = close[i];
            }
            prev = ffilled;
        }
        if (count > 0 && sum / count > 1) {
            for (int i = 0; i < div.length; i++) {
                div[i] *= SUBUNIT_FACTOR;
            }
        }
        return inSubunits;
    }

    // ---- conversion between bars and the table ----

    /** A table with the original event times of the dividends it holds. */
    record Mapped(Frame frame, Map<Integer, ZonedDateTime> dividendTimes, List<Dividend> unmapped) {}

    /**
     * Puts the actions onto the bars they fall in, as yfinance's {@code safe_merge_dfs} does:
     * intraday by date (first bar of the day), otherwise the bar whose interval contains the event.
     * Actions outside the bars stay as they are.
     */
    static Mapped toFrame(
            List<Bar> bars,
            List<Dividend> dividends,
            List<Split> splits,
            List<CapitalGain> capitalGains,
            boolean hasCapitalGains,
            Interval interval) {
        int n = bars.size();
        ZonedDateTime[] times = new ZonedDateTime[n];
        for (int i = 0; i < n; i++) {
            times[i] = bars.get(i).time();
        }
        Frame f = new Frame(times, hasCapitalGains);
        for (int i = 0; i < n; i++) {
            Bar b = bars.get(i);
            f.set(OPEN, i, b.open());
            f.set(HIGH, i, b.high());
            f.set(LOW, i, b.low());
            f.set(CLOSE, i, b.close());
            f.set(ADJ_CLOSE, i, b.adjClose());
            f.set(VOLUME, i, b.volume());
            f.repaired()[i] = b.repaired();
        }
        Map<Integer, ZonedDateTime> dividendTimes = new HashMap<>();
        List<Dividend> unmapped = new ArrayList<>();
        for (Dividend d : dividends) {
            int row = rowOf(times, d.time(), interval);
            if (row < 0) {
                unmapped.add(d);
            } else {
                f.set(DIVIDENDS, row, f.get(DIVIDENDS, row) + d.amount());
                dividendTimes.putIfAbsent(row, d.time());
            }
        }
        for (Split s : splits) {
            int row = rowOf(times, s.time(), interval);
            if (row >= 0) {
                double prev = f.get(SPLITS, row);
                f.set(SPLITS, row, (prev == 0.0 ? 1.0 : prev) * s.ratio());
            }
        }
        for (CapitalGain g : capitalGains) {
            int row = rowOf(times, g.time(), interval);
            if (row >= 0) {
                f.set(CAPITAL_GAINS, row, f.get(CAPITAL_GAINS, row) + g.amount());
            }
        }
        return new Mapped(f, dividendTimes, unmapped);
    }

    private static int rowOf(ZonedDateTime[] times, ZonedDateTime t, Interval interval) {
        int n = times.length;
        if (n == 0) {
            return -1;
        }
        if (interval.isIntraday()) {
            LocalDate d = t.withZoneSameInstant(times[0].getZone()).toLocalDate();
            for (int i = 0; i < n; i++) {
                if (!times[i].toLocalDate().isBefore(d)) {
                    return times[i].toLocalDate().equals(d) || i > 0 ? i : -1;
                }
            }
            return -1;
        }
        Instant e = t.toInstant();
        if (e.isBefore(times[0].toInstant())
                || !e.isBefore(Intervals.plus(times[n - 1], interval, 1).toInstant())) {
            return -1;
        }
        int row = 0;
        for (int i = 0; i < n; i++) {
            if (!times[i].toInstant().isAfter(e)) {
                row = i;
            }
        }
        return row;
    }

    static List<Bar> toBars(Frame f) {
        var bars = new ArrayList<Bar>(f.size());
        for (int i = 0; i < f.size(); i++) {
            double v = f.get(VOLUME, i);
            bars.add(new Bar(
                    f.time(i),
                    f.get(OPEN, i),
                    f.get(HIGH, i),
                    f.get(LOW, i),
                    f.get(CLOSE, i),
                    f.get(ADJ_CLOSE, i),
                    Double.isNaN(v) ? 0 : Math.round(v),
                    f.repaired()[i]));
        }
        return bars;
    }

    private static List<Dividend> toDividends(Frame f, Mapped mapped) {
        Map<Instant, ZonedDateTime> original = new HashMap<>();
        mapped.dividendTimes()
                .forEach((row, t) -> original.put(mapped.frame().time(row).toInstant(), t));
        var result = new ArrayList<>(mapped.unmapped());
        for (int i = 0; i < f.size(); i++) {
            double amount = f.get(DIVIDENDS, i);
            if (amount != 0.0 && !Double.isNaN(amount)) {
                ZonedDateTime t = original.getOrDefault(f.time(i).toInstant(), f.time(i));
                result.add(new Dividend(t, amount));
            }
        }
        result.sort((a, b) -> a.time().compareTo(b.time()));
        return result;
    }

    /** Bars, dividends, splits and capital gains of a history as a table (for nested reconstruction). */
    public static Frame frameOf(
            List<Bar> bars,
            List<Dividend> dividends,
            List<Split> splits,
            List<CapitalGain> capitalGains,
            boolean hasCapitalGains,
            Interval interval) {
        return toFrame(bars, dividends, splits, capitalGains, hasCapitalGains, interval)
                .frame();
    }

    /**
     * Aggregates bars into {@code target} bins (yfinance's {@code _resample}), for multi-day
     * intervals repaired as daily data.
     */
    public static List<Bar> resample(List<Bar> bars, Interval target, boolean ytd, RepairContext ctx) {
        if (bars.isEmpty()) {
            return bars;
        }
        Frame f = toFrame(bars, List.of(), List.of(), List.of(), false, Interval.ONE_DAY)
                .frame();
        LocalDate today = ctx.clock().instant().atZone(ctx.zone()).toLocalDate();
        return toBars(Resample.resample(f, target, ctx.zone(), ytd, today));
    }
}
