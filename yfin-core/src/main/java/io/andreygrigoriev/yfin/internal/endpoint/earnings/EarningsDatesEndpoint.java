package io.andreygrigoriev.yfin.internal.endpoint.earnings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.andreygrigoriev.yfin.exception.YFDataException;
import io.andreygrigoriev.yfin.internal.endpoint.Uris;
import io.andreygrigoriev.yfin.internal.http.YahooJson;
import io.andreygrigoriev.yfin.internal.http.YahooSession;
import io.andreygrigoriev.yfin.model.EarningsDate;
import io.andreygrigoriev.yfin.spi.HttpRequest;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Yahoo {@code POST /v1/finance/visualization} with entity type {@code sp_earnings}: the past and
 * upcoming earnings announcements of one symbol, newest first.
 *
 * <p>yfinance 1.7.0 scrapes the {@code finance.yahoo.com/calendar/earnings} HTML page instead,
 * because the {@code earnings} entity type it used before stopped being updated in 2025. The
 * {@code sp_earnings} entity type (also used by yfinance's {@code Calendars}) is current and
 * returns JSON.
 */
public final class EarningsDatesEndpoint {

    private static final Logger LOG = LoggerFactory.getLogger(EarningsDatesEndpoint.class);

    static final String URL = "https://query1.finance.yahoo.com/v1/finance/visualization";

    /** Most rows Yahoo returns per request. */
    public static final int MAX_LIMIT = 100;

    /** Requested columns; only the start time and the EPS columns are mapped. */
    static final List<String> FIELDS = List.of(
            "ticker",
            "companyshortname",
            "eventname",
            "startdatetime",
            "startdatetimetype",
            "dateisestimate",
            "epsestimate",
            "epsactual",
            "epssurprisepct",
            "timeZoneShortName",
            "gmtOffsetMilliSeconds");

    private final YahooSession session;
    private final Function<String, ZoneId> timezones;

    /**
     * @param timezones exchange timezone of a symbol; only called when there are rows to convert
     */
    public EarningsDatesEndpoint(YahooSession session, Function<String, ZoneId> timezones) {
        this.session = Objects.requireNonNull(session, "session");
        this.timezones = Objects.requireNonNull(timezones, "timezones");
    }

    /**
     * Up to {@code limit} earnings dates of {@code symbol}, newest (upcoming) first; empty when
     * Yahoo has none, e.g. for an ETF or an unknown symbol.
     *
     * @throws IllegalArgumentException when {@code limit} is not in {@code 1..100}
     * @throws YFDataException for an error envelope or an unexpected payload
     */
    public List<EarningsDate> earningsDates(String symbol, int limit) {
        Objects.requireNonNull(symbol, "symbol");
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be in 1.." + MAX_LIMIT + ": " + limit);
        }
        var params = new LinkedHashMap<String, String>();
        params.put("lang", "en-US");
        params.put("region", "US");
        HttpRequest request = HttpRequest.postJson(Uris.uri(URL, params), body(symbol, limit));
        LOG.debug("{}: POST {}", symbol, request.uri());
        JsonNode root = YahooJson.read(session.execute(request), symbol);
        return parse(root, symbol, timezones);
    }

    /** The request body: rows for {@code symbol}, earnings announcements only, newest first. */
    static String body(String symbol, int limit) {
        ObjectNode body = YahooJson.mapper().createObjectNode();
        body.put("sortType", "DESC");
        body.put("entityIdType", "sp_earnings");
        body.put("sortField", "startdatetime");
        ArrayNode fields = body.putArray("includeFields");
        FIELDS.forEach(fields::add);
        ObjectNode query = body.putObject("query");
        query.put("operator", "and");
        ArrayNode operands = query.putArray("operands");
        operands.add(eq("ticker", symbol));
        ObjectNode eventTypes = operands.addObject();
        eventTypes.put("operator", "or");
        eventTypes.putArray("operands").add(eq("eventtype", "EAD")).add(eq("eventtype", "ERA"));
        body.put("offset", 0);
        body.put("size", limit);
        return body.toString();
    }

    private static ObjectNode eq(String field, String value) {
        ObjectNode node = YahooJson.mapper().createObjectNode();
        node.put("operator", "eq");
        node.putArray("operands").add(field).add(value);
        return node;
    }

    /**
     * Maps the rows of the first document. Columns are looked up by id, not position. Rows without
     * a start time are skipped. A surprise without a reported EPS is dropped: Yahoo sends {@code 0}
     * there as a placeholder.
     */
    static List<EarningsDate> parse(JsonNode root, String symbol, Function<String, ZoneId> timezones) {
        JsonNode results = root.path("finance").path("result");
        if (!results.isArray()) {
            throw new YFDataException("visualization response has no result list", root.toString());
        }
        JsonNode document = results.path(0).path("documents").path(0);
        JsonNode rows = document.path("rows");
        if (rows.isMissingNode() || rows.isNull() || rows.isEmpty()) {
            return List.of();
        }
        if (!rows.isArray()) {
            throw new YFDataException("visualization rows are not a list", root.toString());
        }
        Map<String, Integer> columns = new HashMap<>();
        JsonNode columnNodes = document.path("columns");
        for (int i = 0; i < columnNodes.size(); i++) {
            columns.putIfAbsent(columnNodes.get(i).path("id").asText(), i);
        }
        if (!columns.containsKey("startdatetime")) {
            throw new YFDataException("visualization response has no startdatetime column", root.toString());
        }
        ZoneId zone = timezones.apply(symbol);
        var out = new ArrayList<EarningsDate>(rows.size());
        for (JsonNode row : rows) {
            @Nullable String start = text(row, columns.get("startdatetime"));
            if (start == null) {
                continue;
            }
            Instant time;
            try {
                time = Instant.parse(start);
            } catch (DateTimeException e) {
                throw new YFDataException("invalid earnings date: " + start, root.toString());
            }
            double reported = number(row, columns.get("epsactual"));
            double surprise = Double.isNaN(reported) ? Double.NaN : number(row, columns.get("epssurprisepct"));
            out.add(new EarningsDate(time.atZone(zone), number(row, columns.get("epsestimate")), reported, surprise));
        }
        return List.copyOf(out);
    }

    private static @Nullable String text(JsonNode row, @Nullable Integer column) {
        JsonNode value = column == null ? null : row.get(column);
        return value == null || !value.isTextual() || value.asText().isEmpty() ? null : value.asText();
    }

    private static double number(JsonNode row, @Nullable Integer column) {
        JsonNode value = column == null ? null : row.get(column);
        return value != null && value.isNumber() ? value.doubleValue() : Double.NaN;
    }
}
