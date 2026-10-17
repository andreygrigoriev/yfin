package io.github.andreygrigoriev.yfin.internal.endpoint.chart;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.andreygrigoriev.yfin.exception.YFDataException;
import io.github.andreygrigoriev.yfin.internal.http.YahooJson;
import io.github.andreygrigoriev.yfin.internal.repair.PriceRepair;
import io.github.andreygrigoriev.yfin.model.Bar;
import io.github.andreygrigoriev.yfin.model.CapitalGain;
import io.github.andreygrigoriev.yfin.model.Dividend;
import io.github.andreygrigoriev.yfin.model.HistoryMetadata;
import io.github.andreygrigoriev.yfin.model.PriceHistory;
import io.github.andreygrigoriev.yfin.model.Split;
import io.github.andreygrigoriev.yfin.request.HistoryRequest;
import io.github.andreygrigoriev.yfin.request.Interval;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Turns a {@link ChartDto} into a {@link PriceHistory}, following yfinance's {@code history()}
 * clean-up in its order: drop the bar Yahoo appends past {@code end}, aggregate 15m to 30m, move
 * timestamps into the exchange zone (midnight for daily and longer bars), drop unrequested pre/post
 * market bars, attach actions, merge the separate live bar, drop duplicates, adjust, drop empty
 * bars.
 */
final class ChartMapper {

    private static final Set<String> CAPITAL_GAINS_TYPES = Set.of("MUTUALFUND", "ETF");

    private ChartMapper() {}

    /** Whether instruments of {@code instrumentType} (mutual funds, ETFs) report capital gains. */
    static boolean hasCapitalGains(@Nullable String instrumentType) {
        return instrumentType != null && CAPITAL_GAINS_TYPES.contains(instrumentType);
    }

    /** Price repair hook, applied to the unadjusted bars before adjustment and dropping empty bars. */
    @FunctionalInterface
    interface Repairer {
        PriceRepair.Result repair(
                List<Bar> bars,
                List<Dividend> dividends,
                List<Split> splits,
                List<CapitalGain> capitalGains,
                boolean hasCapitalGains,
                HistoryMetadata metadata);
    }

    static PriceHistory map(ChartDto dto, ChartQuery query) {
        return map(dto, query, null);
    }

    static PriceHistory map(ChartDto dto, ChartQuery query, @Nullable Repairer repairer) {
        HistoryRequest request = query.request();
        Interval interval = query.interval();
        HistoryMetadata metadata = metadata(dto.meta(), query.symbol());
        ZoneId zone = metadata.exchangeZone();

        List<ChartDto.Row> rows = new ArrayList<>(dto.rows());
        Long period2 = query.period2();
        if (period2 != null && !rows.isEmpty() && rows.getLast().epoch() >= period2) {
            rows.removeLast(); // Yahoo often appends the latest price even past the requested end
        }
        if (interval != query.fetchInterval()) {
            rows = aggregate(rows, interval);
        }

        List<Bar> bars = new ArrayList<>(rows.size());
        for (ChartDto.Row r : rows) {
            bars.add(new Bar(
                    barTime(r.epoch(), interval, zone),
                    r.open(),
                    r.high(),
                    r.low(),
                    r.close(),
                    r.adjClose(),
                    r.volume()));
        }
        if (!request.prepost() && interval.isIntraday()) {
            bars = dropOutsideRegularHours(bars, dto.meta().path("tradingPeriods"), interval, zone);
        }

        List<Dividend> dividends = List.of();
        List<Split> splits = List.of();
        List<CapitalGain> capitalGains = List.of();
        String type = metadata.instrumentType();
        boolean hasCapitalGains = hasCapitalGains(type);
        if (request.actions() || repairer != null) {
            EventWindow window = new EventWindow(query, bars, zone);
            dividends = new ArrayList<>();
            for (ChartDto.Amount d : dto.dividends()) {
                if (window.contains(d.epoch())) {
                    dividends.add(new Dividend(eventTime(d.epoch(), interval, zone), d.amount()));
                }
            }
            splits = new ArrayList<>();
            for (ChartDto.SplitTerms s : dto.splits()) {
                if (window.contains(s.epoch())) {
                    splits.add(new Split(eventTime(s.epoch(), interval, zone), s.numerator(), s.denominator()));
                }
            }
            if (hasCapitalGains) {
                capitalGains = new ArrayList<>();
                for (ChartDto.Amount g : dto.capitalGains()) {
                    if (window.contains(g.epoch())) {
                        capitalGains.add(new CapitalGain(eventTime(g.epoch(), interval, zone), g.amount()));
                    }
                }
            }
        }

        bars = mergeLiveBar(bars, interval, request.prepost() && interval.isIntraday());
        bars = dropDuplicateTimes(bars);
        String currency = metadata.currency() == null ? "" : metadata.currency();
        if (repairer != null) {
            PriceRepair.Result repaired =
                    repairer.repair(bars, dividends, splits, capitalGains, hasCapitalGains, metadata);
            bars = new ArrayList<>(repaired.bars());
            dividends = repaired.dividends();
            currency = repaired.currency();
            if (!request.actions()) {
                dividends = List.of();
                splits = List.of();
                capitalGains = List.of();
            }
        }
        if (request.autoAdjust()) {
            bars = adjust(bars, true);
        } else if (request.backAdjust()) {
            bars = adjust(bars, false);
        }
        if (!request.keepNa()) {
            bars.removeIf(ChartMapper::isNaOrZero);
        }

        return new PriceHistory(
                query.symbol(), zone, interval, currency, bars, dividends, splits, capitalGains, metadata);
    }

    // ---- metadata ----

    static HistoryMetadata metadata(JsonNode meta, String requestedSymbol) {
        String zoneName = YahooJson.text(meta.path("exchangeTimezoneName"));
        if (zoneName == null) {
            throw new YFDataException("chart meta has no exchangeTimezoneName", meta.toString());
        }
        ZoneId zone;
        try {
            zone = ZoneId.of(zoneName);
        } catch (DateTimeException e) {
            throw new YFDataException("unknown exchange timezone: " + zoneName, meta.toString(), e);
        }
        var validRanges = new ArrayList<String>();
        for (JsonNode r : meta.path("validRanges")) {
            if (r.isTextual()) {
                validRanges.add(r.asText());
            }
        }
        String symbol = YahooJson.text(meta.path("symbol"));
        Long gmtOffset = YahooJson.integral(meta.path("gmtoffset"));
        Long priceHint = YahooJson.integral(meta.path("priceHint"));
        return new HistoryMetadata(
                symbol == null ? requestedSymbol : symbol,
                YahooJson.text(meta.path("currency")),
                YahooJson.text(meta.path("exchangeName")),
                YahooJson.text(meta.path("fullExchangeName")),
                YahooJson.text(meta.path("instrumentType")),
                zone,
                YahooJson.text(meta.path("timezone")),
                gmtOffset,
                time(meta, "firstTradeDate", zone),
                time(meta, "regularMarketTime", zone),
                YahooJson.number(meta.path("regularMarketPrice")),
                YahooJson.number(meta.path("regularMarketDayHigh")),
                YahooJson.number(meta.path("regularMarketDayLow")),
                YahooJson.integral(meta.path("regularMarketVolume")),
                YahooJson.number(meta.path("previousClose")),
                YahooJson.number(meta.path("chartPreviousClose")),
                YahooJson.number(meta.path("fiftyTwoWeekHigh")),
                YahooJson.number(meta.path("fiftyTwoWeekLow")),
                YahooJson.text(meta.path("longName")),
                YahooJson.text(meta.path("shortName")),
                priceHint == null ? null : priceHint.intValue(),
                meta.path("hasPrePostMarketData").isBoolean()
                        ? meta.path("hasPrePostMarketData").booleanValue()
                        : null,
                YahooJson.text(meta.path("dataGranularity")),
                YahooJson.text(meta.path("range")),
                validRanges,
                YahooJson.plainMap(meta));
    }

    private static @Nullable ZonedDateTime time(JsonNode node, String field, ZoneId zone) {
        JsonNode v = node.path(field);
        return v.isIntegralNumber() ? Instant.ofEpochSecond(v.asLong()).atZone(zone) : null;
    }

    // ---- timestamps ----

    /** Bar start in {@code zone}; daily and longer bars become midnight of their exchange date. */
    static ZonedDateTime barTime(long epoch, Interval interval, ZoneId zone) {
        ZonedDateTime t = Instant.ofEpochSecond(epoch).atZone(zone);
        if (interval.isIntraday()) {
            return t;
        }
        if ((interval == Interval.ONE_DAY || interval == Interval.ONE_WEEK)
                && t.getMinute() == 0
                && (t.getHour() == 22 || t.getHour() == 23)) {
            // Yahoo sometimes reports midnight a few hours early around DST changes (e.g. Brazil)
            t = t.plusHours(24 - t.getHour());
        }
        return t.toLocalDate().atStartOfDay(zone);
    }

    private static ZonedDateTime eventTime(long epoch, Interval interval, ZoneId zone) {
        ZonedDateTime t = Instant.ofEpochSecond(epoch).atZone(zone);
        return interval.isIntraday() ? t : t.toLocalDate().atStartOfDay(zone);
    }

    // ---- 30m aggregation ----

    private static List<ChartDto.Row> aggregate(List<ChartDto.Row> rows, Interval interval) {
        long size = interval.nominalDuration().toSeconds();
        var result = new ArrayList<ChartDto.Row>();
        int i = 0;
        while (i < rows.size()) {
            long bucket = Math.floorDiv(rows.get(i).epoch(), size) * size;
            double open = Double.NaN;
            double high = Double.NaN;
            double low = Double.NaN;
            double close = Double.NaN;
            double adjClose = Double.NaN;
            long volume = 0;
            for (; i < rows.size() && Math.floorDiv(rows.get(i).epoch(), size) * size == bucket; i++) {
                ChartDto.Row r = rows.get(i);
                if (Double.isNaN(open)) {
                    open = r.open();
                }
                high = nanMax(high, r.high());
                low = nanMin(low, r.low());
                if (!Double.isNaN(r.close())) {
                    close = r.close();
                }
                if (!Double.isNaN(r.adjClose())) {
                    adjClose = r.adjClose();
                }
                volume += r.volume();
            }
            result.add(new ChartDto.Row(bucket, open, high, low, close, adjClose, volume));
        }
        return result;
    }

    // ---- pre/post market ----

    /** Drops bars Yahoo returned outside regular hours although pre/post market was not requested. */
    private static List<Bar> dropOutsideRegularHours(
            List<Bar> bars, JsonNode tradingPeriods, Interval interval, ZoneId zone) {
        JsonNode regular = tradingPeriods.isObject() ? tradingPeriods.path("regular") : tradingPeriods;
        if (!regular.isArray()) {
            return bars;
        }
        Map<LocalDate, long[]> sessions = new HashMap<>();
        for (JsonNode day : regular) {
            for (JsonNode p : day.isArray() ? day : List.of(day)) {
                JsonNode start = p.path("start");
                JsonNode end = p.path("end");
                if (start.isIntegralNumber() && end.isIntegralNumber()) {
                    LocalDate date =
                            Instant.ofEpochSecond(start.asLong()).atZone(zone).toLocalDate();
                    sessions.putIfAbsent(date, new long[] {start.asLong(), end.asLong()});
                }
            }
        }
        long length = interval.nominalDuration().toSeconds();
        var kept = new ArrayList<Bar>(bars.size());
        for (Bar b : bars) {
            long[] session = sessions.get(b.time().toLocalDate());
            long t = b.time().toEpochSecond();
            if (session != null && (t >= session[1] || t + length <= session[0])) {
                continue;
            }
            kept.add(b);
        }
        return kept;
    }

    // ---- actions window ----

    /** yfinance keeps actions from the first bar's day (when a start was given) up to {@code end}. */
    private static final class EventWindow {
        private final long from;
        private final long until;

        EventWindow(ChartQuery query, List<Bar> bars, ZoneId zone) {
            long f = Long.MIN_VALUE;
            if (query.period1() != null && !bars.isEmpty()) {
                f = bars.getFirst()
                        .time()
                        .withZoneSameInstant(zone)
                        .truncatedTo(ChronoUnit.DAYS)
                        .toEpochSecond();
            }
            this.from = f;
            this.until = query.period2() == null ? Long.MAX_VALUE : query.period2();
        }

        boolean contains(long epoch) {
            return epoch >= from && epoch < until;
        }
    }

    // ---- live bar ----

    /**
     * When the market is open Yahoo often returns the current, partial interval as a separate last
     * bar inside the previous one. For daily bars the earlier duplicate is dropped; otherwise the
     * last bar is merged into the one before.
     */
    static List<Bar> mergeLiveBar(List<Bar> bars, Interval interval, boolean prepost) {
        var result = new ArrayList<>(bars);
        if (result.size() < 2) {
            return result;
        }
        Bar last = result.getLast();
        Bar prev = result.get(result.size() - 2);
        if (interval == Interval.ONE_DAY) {
            if (last.time().toLocalDate().equals(prev.time().toLocalDate())) {
                result.remove(result.size() - 2);
            }
            return result;
        }
        if (!sameInterval(prev.time(), last.time(), interval) || prev.time().equals(last.time())) {
            return result;
        }
        if (prepost && last.time().getSecond() == 0) {
            return result; // most likely the start of post-market, not a live bar
        }
        double open = Double.isNaN(prev.open()) ? last.open() : prev.open();
        double high = Double.isNaN(last.high()) ? prev.high() : nanMax(last.high(), prev.high());
        double low = Double.isNaN(last.low()) ? prev.low() : nanMin(last.low(), prev.low());
        Bar merged =
                new Bar(prev.time(), open, high, low, last.close(), last.adjClose(), prev.volume() + last.volume());
        result.removeLast();
        result.set(result.size() - 1, merged);
        return result;
    }

    static boolean sameInterval(ZonedDateTime first, ZonedDateTime second, Interval interval) {
        return switch (interval) {
            case ONE_DAY -> first.toLocalDate().equals(second.toLocalDate());
            case ONE_WEEK -> Duration.between(first, second).toDays() < 7;
            case FIVE_DAYS -> Duration.between(first, second).compareTo(Duration.ofDays(5)) < 0;
            case ONE_MONTH -> first.getYear() == second.getYear() && first.getMonth() == second.getMonth();
            case THREE_MONTHS -> {
                int shift = first.getMonthValue() % 3 - 1;
                int q1 = Math.floorDiv(first.getMonthValue() - shift - 1, 3) + 1;
                int q2 = Math.floorDiv(second.getMonthValue() - shift - 1, 3) + 1;
                yield q2 - q1 + 4 * (second.getYear() - first.getYear()) == 0;
            }
            default -> Duration.between(first, second).compareTo(interval.nominalDuration()) < 0;
        };
    }

    // ---- clean-up ----

    private static List<Bar> dropDuplicateTimes(List<Bar> bars) {
        var seen = new HashSet<Instant>();
        var result = new ArrayList<Bar>(bars.size());
        for (Bar b : bars) {
            if (seen.add(b.time().toInstant())) {
                result.add(b);
            }
        }
        return result;
    }

    /**
     * Scales open, high and low by {@code adjClose / close}. {@code auto}: close becomes the adjusted
     * close (yfinance {@code auto_adjust}); otherwise close stays raw ({@code back_adjust}).
     */
    private static List<Bar> adjust(List<Bar> bars, boolean auto) {
        var result = new ArrayList<Bar>(bars.size());
        for (Bar b : bars) {
            double ratio = b.adjClose() / b.close();
            result.add(new Bar(
                    b.time(),
                    b.open() * ratio,
                    b.high() * ratio,
                    b.low() * ratio,
                    auto ? b.adjClose() : b.close(),
                    b.adjClose(),
                    b.volume(),
                    b.repaired()));
        }
        return result;
    }

    private static boolean isNaOrZero(Bar b) {
        return naOrZero(b.open())
                && naOrZero(b.high())
                && naOrZero(b.low())
                && naOrZero(b.close())
                && naOrZero(b.adjClose())
                && b.volume() == 0;
    }

    private static boolean naOrZero(double v) {
        return Double.isNaN(v) || v == 0;
    }

    private static double nanMax(double a, double b) {
        return Double.isNaN(a) ? b : Double.isNaN(b) ? a : Math.max(a, b);
    }

    private static double nanMin(double a, double b) {
        return Double.isNaN(a) ? b : Double.isNaN(b) ? a : Math.min(a, b);
    }
}
