package io.andreygrigoriev.yfin.internal.endpoint.shares;

import com.fasterxml.jackson.databind.JsonNode;
import io.andreygrigoriev.yfin.exception.YFDataException;
import io.andreygrigoriev.yfin.internal.endpoint.Uris;
import io.andreygrigoriev.yfin.internal.http.YahooJson;
import io.andreygrigoriev.yfin.internal.http.YahooSession;
import io.andreygrigoriev.yfin.model.SharesCount;
import io.andreygrigoriev.yfin.spi.HttpRequest;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Full shares outstanding history (yfinance {@code get_shares_full}): the fundamentals timeseries
 * endpoint without a {@code type} parameter, whose result carries {@code shares_out} next to
 * {@code timestamp}.
 */
public final class SharesEndpoint {

    private static final Logger LOG = LoggerFactory.getLogger(SharesEndpoint.class);

    static final String BASE_URL = "https://query2.finance.yahoo.com/ws/fundamentals-timeseries/v1/finance/timeseries/";

    /** Default span when no start is given: 18 months, as in yfinance. */
    static final int DEFAULT_DAYS = 548;

    private final YahooSession session;
    private final Function<String, ZoneId> timezones;
    private final Supplier<Instant> clock;

    public SharesEndpoint(YahooSession session, Function<String, ZoneId> timezones) {
        this(session, timezones, Instant::now);
    }

    SharesEndpoint(YahooSession session, Function<String, ZoneId> timezones, Supplier<Instant> clock) {
        this.session = Objects.requireNonNull(session, "session");
        this.timezones = Objects.requireNonNull(timezones, "timezones");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Share counts of {@code symbol} reported from {@code start} (inclusive) to {@code end}
     * (exclusive), oldest first; empty when Yahoo has none.
     *
     * @param start first day, or {@code null} for 548 days before {@code end}
     * @param end day after the last one, or {@code null} for now
     * @throws IllegalArgumentException when {@code start} is not before {@code end}
     * @throws YFDataException for an error envelope or an unexpected payload
     */
    public List<SharesCount> sharesFull(String symbol, @Nullable LocalDate start, @Nullable LocalDate end) {
        Objects.requireNonNull(symbol, "symbol");
        ZoneId zone = timezones.apply(symbol);
        ZonedDateTime endTime =
                end != null ? end.atStartOfDay(zone) : clock.get().atZone(zone);
        ZonedDateTime startTime = start != null ? start.atStartOfDay(zone) : endTime.minusDays(DEFAULT_DAYS);
        if (!startTime.isBefore(endTime)) {
            throw new IllegalArgumentException("start must be before end: " + startTime + " >= " + endTime);
        }
        long period1 = startTime.toLocalDate().atStartOfDay(zone).toEpochSecond();
        long period2 = ceilDay(endTime).toEpochSecond();
        var params = new LinkedHashMap<String, String>();
        params.put("symbol", symbol);
        params.put("period1", Long.toString(period1));
        params.put("period2", Long.toString(period2));
        URI uri = Uris.uri(BASE_URL + Uris.encode(symbol), params);
        LOG.debug("{}: GET {}", symbol, uri);
        JsonNode root = YahooJson.read(session.execute(HttpRequest.get(uri)), symbol);
        return parse(root, zone);
    }

    private static ZonedDateTime ceilDay(ZonedDateTime time) {
        ZonedDateTime day = time.toLocalDate().atStartOfDay(time.getZone());
        return day.equals(time) ? day : time.toLocalDate().plusDays(1).atStartOfDay(time.getZone());
    }

    /** Pairs {@code timestamp} with {@code shares_out} of the first result, sorted by time. */
    static List<SharesCount> parse(JsonNode root, ZoneId zone) {
        JsonNode results = root.path("timeseries").path("result");
        if (results.isMissingNode() || results.isNull() || results.isEmpty()) {
            return List.of();
        }
        if (!results.isArray()) {
            throw new YFDataException("timeseries result is not a list", root.toString());
        }
        JsonNode first = results.get(0);
        JsonNode shares = first.path("shares_out");
        if (shares.isMissingNode() || shares.isNull()) {
            return List.of();
        }
        JsonNode timestamps = first.path("timestamp");
        if (!shares.isArray() || !timestamps.isArray() || shares.size() != timestamps.size()) {
            throw new YFDataException("shares_out does not match timestamp", root.toString());
        }
        var out = new ArrayList<SharesCount>(shares.size());
        for (int i = 0; i < shares.size(); i++) {
            JsonNode value = shares.get(i);
            JsonNode ts = timestamps.get(i);
            if (!value.isNumber() || !ts.isNumber()) {
                throw new YFDataException("non-numeric share count entry at " + i, root.toString());
            }
            // yfinance: naive UTC timestamps localized (not converted) to the exchange timezone
            LocalDateTime wallClock = LocalDateTime.ofEpochSecond(ts.longValue(), 0, ZoneOffset.UTC);
            out.add(new SharesCount(wallClock.atZone(zone), Math.round(value.doubleValue())));
        }
        out.sort(Comparator.comparing(SharesCount::time)); // stable: equal times keep Yahoo's order
        return List.copyOf(out);
    }
}
