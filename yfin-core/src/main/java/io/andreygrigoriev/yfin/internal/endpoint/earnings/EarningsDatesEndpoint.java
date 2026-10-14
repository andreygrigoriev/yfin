package io.andreygrigoriev.yfin.internal.endpoint.earnings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.andreygrigoriev.yfin.exception.YFDataException;
import io.andreygrigoriev.yfin.internal.endpoint.visualization.VisualizationEndpoint;
import io.andreygrigoriev.yfin.internal.endpoint.visualization.VisualizationQuery;
import io.andreygrigoriev.yfin.internal.endpoint.visualization.VisualizationTable;
import io.andreygrigoriev.yfin.internal.http.YahooJson;
import io.andreygrigoriev.yfin.internal.http.YahooSession;
import io.andreygrigoriev.yfin.model.EarningsDate;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

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

    private final VisualizationEndpoint visualization;
    private final Function<String, ZoneId> timezones;

    /**
     * @param timezones exchange timezone of a symbol; only called when there are rows to convert
     */
    public EarningsDatesEndpoint(YahooSession session, Function<String, ZoneId> timezones) {
        this.visualization = new VisualizationEndpoint(Objects.requireNonNull(session, "session"));
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
        return parse(visualization.post(body(symbol, limit), symbol), symbol, timezones);
    }

    /** The request body: rows for {@code symbol}, earnings announcements only, newest first. */
    static ObjectNode body(String symbol, int limit) {
        VisualizationQuery q = VisualizationQuery.lowerCase();
        ObjectNode body = YahooJson.mapper().createObjectNode();
        body.put("sortType", "DESC");
        body.put("entityIdType", "sp_earnings");
        body.put("sortField", "startdatetime");
        ArrayNode fields = body.putArray("includeFields");
        FIELDS.forEach(fields::add);
        body.set("query", q.and(q.eq("ticker", symbol), q.or(q.eq("eventtype", "EAD"), q.eq("eventtype", "ERA"))));
        body.put("offset", 0);
        body.put("size", limit);
        return body;
    }

    /**
     * Maps the rows of the first document. Columns are looked up by id, not position. Rows without
     * a start time are skipped. A surprise without a reported EPS is dropped: Yahoo sends {@code 0}
     * there as a placeholder.
     */
    static List<EarningsDate> parse(JsonNode root, String symbol, Function<String, ZoneId> timezones) {
        return parse(VisualizationTable.of(root), symbol, timezones);
    }

    private static List<EarningsDate> parse(
            VisualizationTable table, String symbol, Function<String, ZoneId> timezones) {
        if (table.isEmpty()) {
            return List.of();
        }
        if (!table.hasColumn("startdatetime")) {
            throw new YFDataException("visualization response has no startdatetime column", table.payload());
        }
        ZoneId zone = timezones.apply(symbol);
        var out = new ArrayList<EarningsDate>(table.rows().size());
        for (JsonNode row : table.rows()) {
            @Nullable Instant time = table.instant(row, "startdatetime");
            if (time == null) {
                continue;
            }
            double reported = table.number(row, "epsactual");
            double surprise = Double.isNaN(reported) ? Double.NaN : table.number(row, "epssurprisepct");
            out.add(new EarningsDate(time.atZone(zone), table.number(row, "epsestimate"), reported, surprise));
        }
        return List.copyOf(out);
    }
}
