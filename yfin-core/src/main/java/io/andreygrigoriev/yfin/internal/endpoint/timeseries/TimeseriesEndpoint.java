package io.andreygrigoriev.yfin.internal.endpoint.timeseries;

import com.fasterxml.jackson.databind.JsonNode;
import io.andreygrigoriev.yfin.exception.YFDataException;
import io.andreygrigoriev.yfin.internal.endpoint.Uris;
import io.andreygrigoriev.yfin.internal.http.YahooJson;
import io.andreygrigoriev.yfin.internal.http.YahooSession;
import io.andreygrigoriev.yfin.model.FinancialStatement;
import io.andreygrigoriev.yfin.model.ValuationMeasures;
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
import java.util.Optional;
import java.util.function.Supplier;
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

    /** The info complement yfinance fetches from this endpoint. */
    static final String TRAILING_PEG_RATIO = "trailingPegRatio";

    /** Look-back of the {@code trailingPegRatio} request: half a year, as in yfinance. */
    static final long PEG_LOOKBACK_DAYS = 365 / 2;

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

    /**
     * Valuation measures of {@code symbol} (yfinance {@code get_valuation_measures}): the {@code
     * frequency} series of every {@link ValuationMapper#MEASURES measure} plus the trailing series
     * behind {@link ValuationMeasures#current()}, in one request.
     *
     * @throws YFDataException for an error envelope or an unexpected payload
     */
    public ValuationMeasures valuationMeasures(String symbol, Frequency frequency) {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(frequency, "frequency");
        var types = new ArrayList<String>();
        for (String key : ValuationMapper.MEASURES) {
            types.add(frequency.yahooPrefix() + key);
            if (frequency != Frequency.TRAILING) {
                types.add(Frequency.TRAILING.yahooPrefix() + key);
            }
        }
        List<JsonNode> results = fetchTypes(symbol, types, PERIOD1);
        return ValuationMapper.map(symbol, frequency, results);
    }

    /**
     * The latest {@code trailingPegRatio} reported over the last half year, which yfinance adds to
     * {@code info}; empty when Yahoo has none (ETFs, indices, crypto, unprofitable companies).
     *
     * @throws YFDataException for an error envelope or an unexpected payload
     */
    public Optional<Double> trailingPegRatio(String symbol) {
        Objects.requireNonNull(symbol, "symbol");
        long period1 = clock.get()
                .truncatedTo(ChronoUnit.DAYS)
                .minus(PEG_LOOKBACK_DAYS, ChronoUnit.DAYS)
                .getEpochSecond();
        List<JsonNode> results = fetchTypes(symbol, List.of(TRAILING_PEG_RATIO), period1);
        return latestValue(results, TRAILING_PEG_RATIO);
    }

    /** The {@code raw} value of the last point of series {@code type}; empty when absent. */
    static Optional<Double> latestValue(List<JsonNode> results, String type) {
        for (JsonNode result : results) {
            JsonNode points = result.path(type);
            if (points.isArray() && !points.isEmpty()) {
                JsonNode raw =
                        points.get(points.size() - 1).path("reportedValue").path("raw");
                return raw.isNumber() ? Optional.of(raw.doubleValue()) : Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** The {@code result} elements of all chunk responses, in order. */
    List<JsonNode> fetch(String symbol, String prefix, List<String> keys) {
        return fetchTypes(symbol, keys.stream().map(k -> prefix + k).toList(), PERIOD1);
    }

    /** {@link #fetch} for fully named types (prefix included) from {@code period1}. */
    private List<JsonNode> fetchTypes(String symbol, List<String> types, long period1) {
        var results = new ArrayList<JsonNode>();
        for (int i = 0; i < types.size(); i += CHUNK_KEYS) {
            List<String> chunk = types.subList(i, Math.min(i + CHUNK_KEYS, types.size()));
            URI uri = Uris.uri(BASE_URL + Uris.encode(symbol), typeParams(symbol, chunk, period1));
            LOG.debug("{}: GET {}", symbol, uri);
            JsonNode root = YahooJson.read(session.execute(HttpRequest.get(uri)), symbol);
            results.addAll(parse(root));
        }
        return List.copyOf(results);
    }

    Map<String, String> params(String symbol, String prefix, List<String> keys) {
        return typeParams(symbol, keys.stream().map(k -> prefix + k).toList(), PERIOD1);
    }

    private Map<String, String> typeParams(String symbol, List<String> types, long period1) {
        var params = new LinkedHashMap<String, String>();
        params.put("symbol", symbol);
        params.put("type", String.join(",", types));
        params.put("period1", Long.toString(period1));
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
