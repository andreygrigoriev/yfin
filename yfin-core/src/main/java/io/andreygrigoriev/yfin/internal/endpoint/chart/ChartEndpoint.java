package io.andreygrigoriev.yfin.internal.endpoint.chart;

import com.fasterxml.jackson.databind.JsonNode;
import io.andreygrigoriev.yfin.exception.YFDataException;
import io.andreygrigoriev.yfin.internal.endpoint.Uris;
import io.andreygrigoriev.yfin.internal.http.YahooJson;
import io.andreygrigoriev.yfin.internal.http.YahooSession;
import io.andreygrigoriev.yfin.internal.repair.PriceRepair;
import io.andreygrigoriev.yfin.internal.repair.RepairContext;
import io.andreygrigoriev.yfin.model.HistoryMetadata;
import io.andreygrigoriev.yfin.model.PriceHistory;
import io.andreygrigoriev.yfin.request.HistoryRequest;
import io.andreygrigoriev.yfin.request.Interval;
import io.andreygrigoriev.yfin.request.Period;
import io.andreygrigoriev.yfin.spi.Cache;
import io.andreygrigoriev.yfin.spi.HttpRequest;
import java.net.URI;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.Temporal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Yahoo {@code /v8/finance/chart/{symbol}}: price history with actions, chart metadata and the
 * exchange timezone. Timezones are cached per symbol in the {@code tz} cache namespace; they are
 * needed to turn start/end dates into epoch seconds.
 */
public final class ChartEndpoint {

    private static final Logger LOG = LoggerFactory.getLogger(ChartEndpoint.class);

    static final String BASE_URL = "https://query2.finance.yahoo.com/v8/finance/chart/";
    static final String TZ_NAMESPACE = "tz";
    static final String EVENTS = "div,splits,capitalGains";

    /** Look-back used for {@code max}, by interval (seconds), as in yfinance. */
    private static final long MAX_1M = 691_200; // 8 days

    private static final long MAX_MINUTES = 5_184_000; // 60 days
    private static final long MAX_HOURLY = 63_072_000; // 730 days
    private static final long MAX_DAILY = 3_122_064_000L; // 99 years

    private final YahooSession session;
    private final Cache cache;
    private final Clock clock;
    private final boolean defaultRepair;

    public ChartEndpoint(YahooSession session, Cache cache) {
        this(session, cache, false);
    }

    /** @param defaultRepair whether requests that do not say so are price-repaired */
    public ChartEndpoint(YahooSession session, Cache cache, boolean defaultRepair) {
        this(session, cache, Clock.systemUTC(), defaultRepair);
    }

    ChartEndpoint(YahooSession session, Cache cache, Clock clock) {
        this(session, cache, clock, false);
    }

    ChartEndpoint(YahooSession session, Cache cache, Clock clock, boolean defaultRepair) {
        this.session = Objects.requireNonNull(session, "session");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.defaultRepair = defaultRepair;
    }

    /**
     * Price history of {@code symbol}.
     *
     * @throws io.andreygrigoriev.yfin.exception.YFSymbolNotFoundException when Yahoo does not know
     *     the symbol or it is delisted
     * @throws YFDataException when the response cannot be parsed
     */
    public PriceHistory history(String symbol, HistoryRequest request) {
        if (request.repair().orElse(defaultRepair)) {
            return repairedHistory(symbol, request, null);
        }
        ChartQuery query = resolve(symbol, request);
        ChartDto dto = fetch(symbol, params(query));
        rememberTimezone(symbol, dto.meta());
        return ChartMapper.map(dto, query);
    }

    /**
     * History with price repair. Multi-day intervals are fetched as daily bars, repaired and
     * resampled, because Yahoo's multi-day adjustment is broken (as in yfinance).
     *
     * @param reconstructStart for nested reconstruction requests, the interval the outermost
     *     reconstruction started from
     */
    private PriceHistory repairedHistory(String symbol, HistoryRequest request, @Nullable Interval reconstructStart) {
        Interval target = request.interval();
        if (target == Interval.FIVE_DAYS) {
            throw new IllegalArgumentException("Yahoo's 5d interval is not supported with price repair");
        }
        boolean multiday =
                target == Interval.ONE_WEEK || target == Interval.ONE_MONTH || target == Interval.THREE_MONTHS;
        HistoryRequest effective = request;
        boolean ytd = request.period().orElse(null) == Period.YTD;
        if (multiday) {
            effective = dailyRequest(symbol, request);
        }
        ChartQuery query = resolve(symbol, effective);
        ChartDto dto = fetch(symbol, params(query));
        rememberTimezone(symbol, dto.meta());
        HistoryMetadata meta = ChartMapper.metadata(dto.meta(), symbol);
        String currency = meta.currency() == null ? "" : meta.currency();
        ZoneId zone = meta.exchangeZone();
        RepairContext.FinerHistory finer = (sub, start, end, prepost, outer) -> {
            HistoryRequest fine = HistoryRequest.builder()
                    .start(start)
                    .end(end)
                    .interval(sub)
                    .prepost(prepost)
                    .autoAdjust(false)
                    .actions(true)
                    .repair(true)
                    .build();
            PriceHistory h = repairedHistory(symbol, fine, outer);
            String type = h.metadata().instrumentType();
            boolean funds = "MUTUALFUND".equals(type) || "ETF".equals(type);
            return PriceRepair.frameOf(h.bars(), h.dividends(), h.splits(), h.capitalGains(), funds, sub);
        };
        RepairContext ctx =
                new RepairContext(symbol, zone, currency, meta.regularMarketPrice(), clock, finer, reconstructStart);
        PriceHistory h = ChartMapper.map(
                dto,
                query,
                (bars, dividends, splits, capitalGains, funds, metadata) -> PriceRepair.repair(
                        bars,
                        dividends,
                        splits,
                        capitalGains,
                        funds,
                        query.interval(),
                        query.request().prepost(),
                        ctx));
        if (!multiday) {
            return h;
        }
        return new PriceHistory(
                h.symbol(),
                h.exchangeZone(),
                target,
                h.currency(),
                PriceRepair.resample(h.bars(), target, ytd, ctx),
                h.dividends(),
                h.splits(),
                h.capitalGains(),
                h.metadata());
    }

    /** The daily request behind a repaired multi-day request; a period becomes a start date as in yfinance. */
    private HistoryRequest dailyRequest(String symbol, HistoryRequest request) {
        HistoryRequest.Builder b = HistoryRequest.builder()
                .interval(Interval.ONE_DAY)
                .prepost(request.prepost())
                .autoAdjust(request.autoAdjust())
                .backAdjust(request.backAdjust())
                .actions(request.actions())
                .repair(true)
                .keepNa(request.keepNa());
        Period period = request.period().orElse(null);
        Temporal start = request.start().orElse(null);
        Temporal end = request.end().orElse(null);
        if (start == null && end == null && period != null && period != Period.MAX) {
            LocalDate today = clock.instant().atZone(timezone(symbol)).toLocalDate();
            if (period == Period.YTD) {
                b.start(today.withDayOfYear(1));
            } else {
                b.start(today.minus(period.length().orElseThrow()).minusDays(4));
            }
            return b.build();
        }
        if (period != null) {
            b.period(period);
        }
        if (start instanceof LocalDate d) {
            b.start(d);
        } else if (start instanceof Instant i) {
            b.start(i);
        }
        if (end instanceof LocalDate d) {
            b.end(d);
        } else if (end instanceof Instant i) {
            b.end(i);
        }
        return b.build();
    }

    /** Chart metadata from a short daily request (5 days). */
    public HistoryMetadata metadata(String symbol) {
        var params = new LinkedHashMap<String, String>();
        params.put("range", Period.FIVE_DAYS.code());
        params.put("interval", Interval.ONE_DAY.code());
        ChartDto dto = fetch(symbol, params);
        rememberTimezone(symbol, dto.meta());
        return ChartMapper.metadata(dto.meta(), symbol);
    }

    /** The exchange timezone of {@code symbol}, from the cache or a one-day chart request. */
    public ZoneId timezone(String symbol) {
        Optional<String> cached = cache.get(TZ_NAMESPACE, symbol);
        if (cached.isPresent()) {
            try {
                return ZoneId.of(cached.get());
            } catch (DateTimeException e) {
                LOG.debug("dropping invalid cached timezone {} for {}", cached.get(), symbol);
                cache.invalidate(TZ_NAMESPACE, symbol);
            }
        }
        var params = new LinkedHashMap<String, String>();
        params.put("range", Period.ONE_DAY.code());
        params.put("interval", Interval.ONE_DAY.code());
        ChartDto dto = fetch(symbol, params);
        ZoneId zone = ChartMapper.metadata(dto.meta(), symbol).exchangeZone();
        cache.put(TZ_NAMESPACE, symbol, zone.getId());
        return zone;
    }

    // ---- request resolution ----

    /** Resolves the request window to {@code range} or {@code period1}/{@code period2}, as yfinance does. */
    ChartQuery resolve(String symbol, HistoryRequest request) {
        Period period = request.period().orElse(null);
        Temporal start = request.start().orElse(null);
        Temporal end = request.end().orElse(null);
        long now = clock.instant().getEpochSecond();
        if (period == Period.MAX) {
            long lookback = switch (request.interval()) {
                case ONE_MINUTE -> MAX_1M;
                case TWO_MINUTES, FIVE_MINUTES, FIFTEEN_MINUTES, THIRTY_MINUTES, NINETY_MINUTES -> MAX_MINUTES;
                case SIXTY_MINUTES, ONE_HOUR -> MAX_HOURLY;
                default -> MAX_DAILY;
            };
            return new ChartQuery(symbol, request, now - lookback + 5, now, null);
        }
        if (start == null && end == null) {
            return new ChartQuery(
                    symbol, request, null, null, Objects.requireNonNull(period).code());
        }
        ZoneId zone = timezone(symbol);
        Long p1 = start == null ? null : epoch(start, zone);
        Long p2 = end == null ? null : epoch(end, zone);
        java.time.Period length = period == null
                ? java.time.Period.ofMonths(1)
                : period.length().orElseThrow(() -> new IllegalArgumentException(period + " needs no start/end"));
        if (p1 == null) {
            p1 = Instant.ofEpochSecond(Objects.requireNonNull(p2))
                    .atZone(zone)
                    .minus(length)
                    .toEpochSecond();
        } else if (p2 == null) {
            p2 = period == null
                    ? now
                    : Instant.ofEpochSecond(p1).atZone(zone).plus(length).toEpochSecond();
        }
        return new ChartQuery(symbol, request, p1, p2, null);
    }

    private static long epoch(Temporal t, ZoneId zone) {
        return t instanceof LocalDate d ? d.atStartOfDay(zone).toEpochSecond() : ((Instant) t).getEpochSecond();
    }

    static Map<String, String> params(ChartQuery query) {
        var params = new LinkedHashMap<String, String>();
        if (query.range() != null) {
            params.put("range", query.range());
        } else {
            params.put("period1", String.valueOf(query.period1()));
            params.put("period2", String.valueOf(query.period2()));
        }
        params.put("interval", query.fetchInterval().code());
        params.put("includePrePost", String.valueOf(query.request().prepost()));
        params.put("events", EVENTS);
        return params;
    }

    // ---- transport ----

    private ChartDto fetch(String symbol, Map<String, String> params) {
        URI uri = uri(symbol, params);
        LOG.debug("{}: GET {}", symbol, uri);
        JsonNode root = YahooJson.read(session.execute(HttpRequest.get(uri)), symbol);
        return ChartDto.parse(root);
    }

    static URI uri(String symbol, Map<String, String> params) {
        return Uris.uri(BASE_URL + Uris.encode(symbol), params);
    }

    private void rememberTimezone(String symbol, JsonNode meta) {
        @Nullable String zone = meta.path("exchangeTimezoneName").asText(null);
        if (zone == null || zone.isEmpty()) {
            return;
        }
        if (!cache.get(TZ_NAMESPACE, symbol).map(zone::equals).orElse(false)) {
            try {
                ZoneId.of(zone);
            } catch (DateTimeException e) {
                return;
            }
            cache.put(TZ_NAMESPACE, symbol, zone);
        }
    }
}
