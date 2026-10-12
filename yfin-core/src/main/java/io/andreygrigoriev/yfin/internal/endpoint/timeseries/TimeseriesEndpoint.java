package io.andreygrigoriev.yfin.internal.endpoint.timeseries;

import com.fasterxml.jackson.databind.JsonNode;
import io.andreygrigoriev.yfin.exception.YFDataException;
import io.andreygrigoriev.yfin.internal.endpoint.Uris;
import io.andreygrigoriev.yfin.internal.http.YahooJson;
import io.andreygrigoriev.yfin.internal.http.YahooSession;
import io.andreygrigoriev.yfin.model.FinancialStatement;
import io.andreygrigoriev.yfin.request.Frequency;
import io.andreygrigoriev.yfin.spi.HttpRequest;
import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Yahoo {@code /ws/fundamentals-timeseries/v1/finance/timeseries/{symbol}}: financial statement
 * line items as time series. The types (frequency prefix + line item key) are requested in chunks
 * of {@value #CHUNK_KEYS}, which keeps every URL under about 2 KB, like yfinance's chunked mode.
 */
public final class TimeseriesEndpoint {

    private static final Logger LOG = LoggerFactory.getLogger(TimeseriesEndpoint.class);

    static final String BASE_URL = "https://query2.finance.yahoo.com/ws/fundamentals-timeseries/v1/finance/timeseries/";

    /** Line item keys per request. */
    static final int CHUNK_KEYS = 60;

    /**
     * {@code period1}: 2016-12-31T00:00Z, as in yfinance. Yahoo returns at most about four years or
     * five quarters regardless.
     */
    static final long PERIOD1 = 1_483_142_400L;

    private final YahooSession session;
    private final Supplier<Instant> clock;

    public TimeseriesEndpoint(YahooSession session) {
        this(session, Instant::now);
    }

    TimeseriesEndpoint(YahooSession session, Supplier<Instant> clock) {
        this.session = Objects.requireNonNull(session, "session");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * The {@code statement} of {@code symbol} at {@code frequency}; empty when Yahoo has no
     * fundamentals for the symbol.
     *
     * @throws IllegalArgumentException for {@link Frequency#TRAILING} on a statement without
     *     trailing data (the balance sheet)
     * @throws YFDataException for an error envelope or an unexpected payload
     */
    public FinancialStatement statement(String symbol, StatementKeys statement, Frequency frequency) {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(statement, "statement");
        Objects.requireNonNull(frequency, "frequency");
        if (frequency == Frequency.TRAILING && !statement.supportsTrailing()) {
            throw new IllegalArgumentException("trailing frequency is not available for the " + statement.label());
        }
        List<JsonNode> results = fetch(symbol, frequency.yahooPrefix(), statement.keys());
        return TimeseriesMapper.map(symbol, frequency, statement.keys(), results);
    }

    /** The {@code result} elements of all chunk responses, in order. */
    List<JsonNode> fetch(String symbol, String prefix, List<String> keys) {
        var results = new ArrayList<JsonNode>();
        for (int i = 0; i < keys.size(); i += CHUNK_KEYS) {
            List<String> chunk = keys.subList(i, Math.min(i + CHUNK_KEYS, keys.size()));
            URI uri = Uris.uri(BASE_URL + Uris.encode(symbol), params(symbol, prefix, chunk));
            LOG.debug("{}: GET {}", symbol, uri);
            JsonNode root = YahooJson.read(session.execute(HttpRequest.get(uri)), symbol);
            results.addAll(parse(root));
        }
        return List.copyOf(results);
    }

    Map<String, String> params(String symbol, String prefix, List<String> keys) {
        var params = new LinkedHashMap<String, String>();
        params.put("symbol", symbol);
        params.put("type", keys.stream().map(k -> prefix + k).collect(Collectors.joining(",")));
        params.put("period1", Long.toString(PERIOD1));
        params.put("period2", Long.toString(period2(clock.get())));
        return params;
    }

    /** {@code now} rounded up to the next UTC midnight, in epoch seconds. */
    static long period2(Instant now) {
        Instant day = now.truncatedTo(ChronoUnit.DAYS);
        return (day.equals(now) ? day : day.plus(1, ChronoUnit.DAYS)).getEpochSecond();
    }

    /** The {@code result} list; empty when Yahoo returns none. */
    static List<JsonNode> parse(JsonNode root) {
        JsonNode envelope = root.path("timeseries");
        if (!envelope.isObject()) {
            throw new YFDataException("timeseries response has no timeseries object", root.toString());
        }
        JsonNode results = envelope.path("result");
        if (results.isNull() || results.isMissingNode()) {
            return List.of();
        }
        if (!results.isArray()) {
            throw new YFDataException("timeseries result is not a list", root.toString());
        }
        var out = new ArrayList<JsonNode>(results.size());
        results.forEach(out::add);
        return out;
    }
}
