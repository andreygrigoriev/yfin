package io.andreygrigoriev.yfin;

import io.andreygrigoriev.yfin.model.Bar;
import io.andreygrigoriev.yfin.model.HistoryMetadata;
import io.andreygrigoriev.yfin.model.PriceHistory;
import io.andreygrigoriev.yfin.model.TickerInfo;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;
import org.jspecify.annotations.Nullable;

/**
 * A small, fast subset of {@link TickerInfo}, computed the way yfinance's {@code fast_info} is:
 * mostly from one year of daily bars and their chart metadata, plus the {@code /v7/finance/quote}
 * for the share count.
 *
 * <p>Lazy: nothing is fetched until the first accessor that needs it, and each source is fetched at
 * most once per instance, so one instance gives a consistent snapshot. Thread-safe. Accessors
 * return {@code null} when the data to compute a value is missing.
 *
 * <p>The "year" window is the last year of daily bars up to today in the exchange zone. Averages,
 * year high/low and year change use full days only: today's bar is left out while the exchange
 * session it belongs to may still be running.
 */
public final class FastInfo {

    /** Where {@link FastInfo} gets its data: {@link Ticker}'s requests, or a test's stub. */
    interface Source {

        /** One year of daily bars, not adjusted, all-NaN rows kept. */
        PriceHistory yearDaily();

        /** Five days of hourly bars, not adjusted, with or without pre/post-market bars. */
        PriceHistory weekHourly(boolean prepost);

        /** The {@code /v7/finance/quote} fields of the symbol; empty when Yahoo returns none. */
        Map<String, Object> quote();

        /** The current time. */
        default Instant now() {
            return Instant.now();
        }
    }

    private static final Duration SESSION_GRACE = Duration.ofDays(1).plusMinutes(20);

    private final Supplier<PriceHistory> yearDaily;
    private final Supplier<PriceHistory> weekHourlyPrepost;
    private final Supplier<PriceHistory> weekHourlyRegular;
    private final Supplier<Map<String, Object>> quote;
    private final Supplier<Instant> now;

    FastInfo(Source source) {
        Objects.requireNonNull(source, "source");
        this.yearDaily = memoize(source::yearDaily);
        this.weekHourlyPrepost = memoize(() -> source.weekHourly(true));
        this.weekHourlyRegular = memoize(() -> source.weekHourly(false));
        this.quote = memoize(source::quote);
        this.now = memoize(source::now);
    }

    // ---- listing ----

    /** Trading currency. */
    public @Nullable String currency() {
        return metadata().currency();
    }

    /** Yahoo instrument type, e.g. {@code EQUITY}, {@code ETF}, {@code CRYPTOCURRENCY}. */
    public @Nullable String quoteType() {
        return metadata().instrumentType();
    }

    /** Exchange code, e.g. {@code NMS}. */
    public @Nullable String exchange() {
        return metadata().exchangeName();
    }

    /** The exchange's timezone. */
    public ZoneId timezone() {
        return metadata().exchangeZone();
    }

    // ---- size ----

    /** Shares outstanding, from the quote. */
    public @Nullable Long shares() {
        return quote.get().get("sharesOutstanding") instanceof Number n ? n.longValue() : null;
    }

    /** {@link #shares()} times {@link #lastPrice()}; the quote's market cap when shares are unknown. */
    public @Nullable Double marketCap() {
        Long shares = shares();
        if (shares == null) {
            return quote.get().get("marketCap") instanceof Number n ? n.doubleValue() : null;
        }
        Double last = lastPrice();
        return last == null ? null : shares * last;
    }

    // ---- latest session ----

    /** Close of the latest daily bar; the chart's regular-market price when there is none. */
    public @Nullable Double lastPrice() {
        List<Bar> bars = window(false);
        if (!bars.isEmpty() && !Double.isNaN(bars.getLast().close())) {
            return bars.getLast().close();
        }
        return metadata().regularMarketPrice();
    }

    /**
     * Last close of the previous trading day, pre/post-market included (from hourly bars); the
     * quote's regular-market previous close when that is not available.
     */
    public @Nullable Double previousClose() {
        List<Double> closes = dailyLastCloses(weekHourlyPrepost.get());
        if (closes.size() >= 2) {
            return closes.get(closes.size() - 2);
        }
        return quoteDouble("regularMarketPreviousClose");
    }

    /** Regular-session close of the previous trading day. */
    public @Nullable Double regularMarketPreviousClose() {
        List<Bar> bars = window(false);
        if (bars.size() == 1) {
            List<Double> closes = dailyLastCloses(weekHourlyRegular.get());
            if (closes.size() >= 2) {
                return closes.get(closes.size() - 2);
            }
        } else if (bars.size() >= 2) {
            double close = bars.get(bars.size() - 2).close();
            return Double.isNaN(close) ? null : close;
        }
        return quoteDouble("regularMarketPreviousClose");
    }

    public @Nullable Double open() {
        return lastBarValue(Bar::open);
    }

    public @Nullable Double dayHigh() {
        return lastBarValue(Bar::high);
    }

    public @Nullable Double dayLow() {
        return lastBarValue(Bar::low);
    }

    /** Volume of the latest daily bar. */
    public @Nullable Long lastVolume() {
        List<Bar> bars = window(false);
        return bars.isEmpty() ? null : bars.getLast().volume();
    }

    // ---- year window, full days ----

    /** Mean close of the last 50 full days. */
    public @Nullable Double fiftyDayAverage() {
        return mean(tail(window(true), 50), Bar::close);
    }

    /** Mean close of the last 200 full days. */
    public @Nullable Double twoHundredDayAverage() {
        return mean(tail(window(true), 200), Bar::close);
    }

    /** Mean volume of the last 10 full days, truncated. */
    public @Nullable Long tenDayAverageVolume() {
        return truncate(mean(tail(window(true), 10), b -> (double) b.volume()));
    }

    /** Mean volume over the three months up to the last full day, truncated. */
    public @Nullable Long threeMonthAverageVolume() {
        List<Bar> bars = window(true);
        if (bars.isEmpty()) {
            return null;
        }
        ZonedDateTime last = bars.getLast().time();
        ZonedDateTime first = last.minusMonths(3).plusDays(1);
        return truncate(mean(filter(bars, b -> !b.time().isBefore(first)), b -> (double) b.volume()));
    }

    /** Highest high of the year (today included only when there are no full days). */
    public @Nullable Double yearHigh() {
        return extreme(Bar::high, true);
    }

    /** Lowest low of the year (today included only when there are no full days). */
    public @Nullable Double yearLow() {
        return extreme(Bar::low, false);
    }

    /** Relative change from the first to the last full-day close of the year, e.g. {@code 0.12}. */
    public @Nullable Double yearChange() {
        List<Bar> bars = window(true);
        if (bars.size() < 2) {
            return null;
        }
        double first = bars.getFirst().close();
        double change = (bars.getLast().close() - first) / first;
        return Double.isNaN(change) ? null : change;
    }

    /** All values by yfinance's camel-case key; computing it fetches every source. */
    public Map<String, @Nullable Object> toMap() {
        var map = new LinkedHashMap<String, @Nullable Object>();
        map.put("currency", currency());
        map.put("dayHigh", dayHigh());
        map.put("dayLow", dayLow());
        map.put("exchange", exchange());
        map.put("fiftyDayAverage", fiftyDayAverage());
        map.put("lastPrice", lastPrice());
        map.put("lastVolume", lastVolume());
        map.put("marketCap", marketCap());
        map.put("open", open());
        map.put("previousClose", previousClose());
        map.put("quoteType", quoteType());
        map.put("regularMarketPreviousClose", regularMarketPreviousClose());
        map.put("shares", shares());
        map.put("tenDayAverageVolume", tenDayAverageVolume());
        map.put("threeMonthAverageVolume", threeMonthAverageVolume());
        map.put("timezone", timezone().getId());
        map.put("twoHundredDayAverage", twoHundredDayAverage());
        map.put("yearChange", yearChange());
        map.put("yearHigh", yearHigh());
        map.put("yearLow", yearLow());
        return map;
    }

    @Override
    public String toString() {
        return "FastInfo[lazy]";
    }

    // ---- helpers ----

    private HistoryMetadata metadata() {
        return yearDaily.get().metadata();
    }

    /**
     * Daily bars from one year before tomorrow up to today, in the exchange zone; with {@code
     * fullDaysOnly}, today is left out while its session may still be running (until 20 minutes
     * after the latest bar's day ends).
     */
    private List<Bar> window(boolean fullDaysOnly) {
        PriceHistory history = yearDaily.get();
        Instant t = now.get();
        LocalDate today = t.atZone(history.exchangeZone()).toLocalDate();
        LocalDate from = today.plusDays(1).minusYears(1);
        LocalDate to0 = today;
        List<Bar> bars = filter(history.bars(), b -> inRange(b, from, to0));
        if (fullDaysOnly
                && !bars.isEmpty()
                && t.isBefore(bars.getLast().time().toInstant().plus(SESSION_GRACE))) {
            LocalDate to1 = today.minusDays(1);
            bars = filter(bars, b -> inRange(b, from, to1));
        }
        return bars;
    }

    private static boolean inRange(Bar bar, LocalDate from, LocalDate to) {
        LocalDate d = bar.time().toLocalDate();
        return !d.isBefore(from) && !d.isAfter(to);
    }

    /** Last non-NaN close of each day, oldest first. */
    private static List<Double> dailyLastCloses(PriceHistory history) {
        var byDay = new LinkedHashMap<LocalDate, Double>();
        for (Bar bar : history.bars()) {
            if (!Double.isNaN(bar.close())) {
                byDay.put(bar.time().toLocalDate(), bar.close());
            }
        }
        return new ArrayList<>(byDay.values());
    }

    private @Nullable Double lastBarValue(ToDoubleFunction<Bar> field) {
        List<Bar> bars = window(false);
        if (bars.isEmpty()) {
            return null;
        }
        double v = field.applyAsDouble(bars.getLast());
        return Double.isNaN(v) ? null : v;
    }

    private @Nullable Double extreme(ToDoubleFunction<Bar> field, boolean max) {
        List<Bar> bars = window(true);
        if (bars.isEmpty()) {
            bars = window(false);
        }
        Double result = null;
        for (Bar bar : bars) {
            double v = field.applyAsDouble(bar);
            if (!Double.isNaN(v) && (result == null || (max ? v > result : v < result))) {
                result = v;
            }
        }
        return result;
    }

    private @Nullable Double quoteDouble(String key) {
        return quote.get().get(key) instanceof Number n ? n.doubleValue() : null;
    }

    private static List<Bar> tail(List<Bar> bars, int n) {
        return bars.subList(Math.max(0, bars.size() - n), bars.size());
    }

    private static List<Bar> filter(List<Bar> bars, Predicate<Bar> keep) {
        return bars.stream().filter(keep).toList();
    }

    /** Mean ignoring NaN, like pandas; {@code null} when there is nothing to average. */
    private static @Nullable Double mean(List<Bar> bars, ToDoubleFunction<Bar> field) {
        double sum = 0;
        int n = 0;
        for (Bar bar : bars) {
            double v = field.applyAsDouble(bar);
            if (!Double.isNaN(v)) {
                sum += v;
                n++;
            }
        }
        return n == 0 ? null : sum / n;
    }

    private static @Nullable Long truncate(@Nullable Double v) {
        return v == null ? null : v.longValue();
    }

    private static <T> Supplier<T> memoize(Supplier<T> supplier) {
        return new Supplier<>() {
            private @Nullable T value;

            @Override
            public synchronized T get() {
                T v = value;
                if (v == null) {
                    v = Objects.requireNonNull(supplier.get(), "source returned null");
                    value = v;
                }
                return v;
            }
        };
    }
}
