package io.github.andreygrigoriev.yfin.internal.endpoint.calendar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.andreygrigoriev.yfin.Screener;
import io.github.andreygrigoriev.yfin.exception.YFDataException;
import io.github.andreygrigoriev.yfin.internal.endpoint.screener.ScreenerEndpoint;
import io.github.andreygrigoriev.yfin.internal.endpoint.visualization.VisualizationEndpoint;
import io.github.andreygrigoriev.yfin.internal.endpoint.visualization.VisualizationQuery;
import io.github.andreygrigoriev.yfin.internal.endpoint.visualization.VisualizationTable;
import io.github.andreygrigoriev.yfin.internal.http.YahooJson;
import io.github.andreygrigoriev.yfin.internal.http.YahooSession;
import io.github.andreygrigoriev.yfin.model.EarningsEvent;
import io.github.andreygrigoriev.yfin.model.EconomicEvent;
import io.github.andreygrigoriev.yfin.model.IpoEvent;
import io.github.andreygrigoriev.yfin.model.ScreenerResult;
import io.github.andreygrigoriev.yfin.model.SplitEvent;
import io.github.andreygrigoriev.yfin.request.CalendarRequest;
import io.github.andreygrigoriev.yfin.request.EarningsCalendarRequest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Yahoo {@code POST /v1/finance/visualization} for the market-wide calendars, as yfinance 1.7.0
 * {@code Calendars}: the same entity types, sort fields, columns and upper-case query operators
 * ({@code PREDEFINED_CALENDARS}). Rows are mapped by column id.
 *
 * <p>yfinance turns {@code 0} into {@code NaN} in its {@code nan_cols} (EPS and surprise, IPO
 * prices and shares, economic values); this endpoint does the same.
 */
public final class CalendarEndpoint {

    private static final Logger LOG = LoggerFactory.getLogger(CalendarEndpoint.class);

    /**
     * Zone of the earnings times (the query is {@code region = us}) and of the IPO and split dates
     * (Yahoo sends New York midnight).
     */
    static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    /** Below this market-cap floor yfinance warns that the cutoff is probably a mistake. */
    static final double LOW_MARKET_CAP = 10_000_000;

    /** yfinance {@code screen(query="MOST_ACTIVES", count=200)}. */
    static final Screener MOST_ACTIVES = Screener.predefined("most_actives").size(200);

    /** yfinance {@code PREDEFINED_CALENDARS["sp_earnings"]}. */
    static final Spec EARNINGS = new Spec(
            "sp_earnings",
            "intradaymarketcap",
            List.of(
                    "ticker",
                    "companyshortname",
                    "intradaymarketcap",
                    "eventname",
                    "startdatetime",
                    "startdatetimetype",
                    "epsestimate",
                    "epsactual",
                    "epssurprisepct"));

    /** yfinance {@code PREDEFINED_CALENDARS["ipo_info"]}. */
    static final Spec IPO = new Spec(
            "ipo_info",
            "startdatetime",
            List.of(
                    "ticker",
                    "companyshortname",
                    "exchange_short_name",
                    "filingdate",
                    "startdatetime",
                    "amendeddate",
                    "pricefrom",
                    "priceto",
                    "offerprice",
                    "currencyname",
                    "shares",
                    "dealtype"));

    /** yfinance {@code PREDEFINED_CALENDARS["economic_event"]}. */
    static final Spec ECONOMIC = new Spec(
            "economic_event",
            "startdatetime",
            List.of(
                    "econ_release",
                    "country_code",
                    "startdatetime",
                    "period",
                    "after_release_actual",
                    "consensus_estimate",
                    "prior_release_actual",
                    "originally_reported_actual"));

    /** yfinance {@code PREDEFINED_CALENDARS["splits"]}. */
    static final Spec SPLITS = new Spec(
            "splits",
            "startdatetime",
            List.of("ticker", "companyshortname", "startdatetime", "optionable", "old_share_worth", "share_worth"));

    private static final VisualizationQuery Q = VisualizationQuery.upperCase();

    private final VisualizationEndpoint visualization;
    private final ScreenerEndpoint screener;

    public CalendarEndpoint(YahooSession session) {
        Objects.requireNonNull(session, "session");
        this.visualization = new VisualizationEndpoint(session);
        this.screener = new ScreenerEndpoint(session);
    }

    /**
     * US earnings announcements in the window, latest first.
     *
     * <p>With {@link EarningsCalendarRequest#filterMostActive()} on the first page, the {@code
     * most_actives} screen (200 symbols, those under the market-cap floor dropped) restricts the
     * result first, as yfinance does. If no symbol survives, the result is empty and no calendar
     * request is made; yfinance would send an empty {@code OR}.
     *
     * @throws YFDataException for an error envelope or an unexpected payload, also from the screen
     */
    public List<EarningsEvent> earnings(EarningsCalendarRequest request) {
        OptionalDouble marketCap = request.marketCap();
        if (marketCap.isPresent() && marketCap.getAsDouble() < LOW_MARKET_CAP) {
            LOG.warn("market cap {} is very low, did you mean to set it higher?", marketCap.getAsDouble());
        }
        List<String> mostActive = List.of();
        if (request.filterMostActive() && request.offset() == 0) {
            mostActive = mostActiveSymbols(screener.screen(MOST_ACTIVES), marketCap);
            if (mostActive.isEmpty()) {
                LOG.debug("no most-active symbol above the market cap: empty earnings calendar");
                return List.of();
            }
        }
        return parseEarnings(visualization.post(earningsBody(request, mostActive), null));
    }

    /**
     * IPOs whose first trading, filing or amendment date falls in the window.
     *
     * @throws YFDataException for an error envelope or an unexpected payload
     */
    public List<IpoEvent> ipos(CalendarRequest request) {
        return parseIpos(visualization.post(ipoBody(request), null));
    }

    /**
     * Economic releases in the window.
     *
     * @throws YFDataException for an error envelope or an unexpected payload
     */
    public List<EconomicEvent> economicEvents(CalendarRequest request) {
        return parseEconomicEvents(visualization.post(economicBody(request), null));
    }

    /**
     * Stock splits taking effect in the window.
     *
     * @throws YFDataException for an error envelope or an unexpected payload
     */
    public List<SplitEvent> splits(CalendarRequest request) {
        return parseSplits(visualization.post(splitsBody(request), null));
    }

    /** The yfinance body; {@code mostActive} becomes an {@code OR} of tickers when not empty. */
    static ObjectNode earningsBody(EarningsCalendarRequest request, List<String> mostActive) {
        var operands = new ArrayList<ObjectNode>();
        operands.add(Q.eq("region", "us"));
        operands.add(Q.or(Q.eq("eventtype", "EAD"), Q.eq("eventtype", "ERA")));
        operands.add(Q.gte("startdatetime", request.start().toString()));
        operands.add(Q.lte("startdatetime", request.end().toString()));
        request.marketCap().ifPresent(cap -> operands.add(Q.gte("intradaymarketcap", cap)));
        if (!mostActive.isEmpty()) {
            operands.add(Q.or(mostActive.stream().map(s -> Q.eq("ticker", s)).toList()));
        }
        return body(EARNINGS, request.limit(), request.offset(), Q.and(operands));
    }

    /**
     * The screen's symbols in order; with a floor, those whose {@code marketCap} is below it or
     * missing (yfinance reads it as {@code 0}).
     */
    static List<String> mostActiveSymbols(ScreenerResult result, OptionalDouble marketCap) {
        var out = new ArrayList<String>();
        for (Map<String, Object> quote : result.quotes()) {
            double cap = quote.get("marketCap") instanceof Number n ? n.doubleValue() : 0;
            if (quote.get("symbol") instanceof String symbol
                    && !symbol.isEmpty()
                    && (marketCap.isEmpty() || cap >= marketCap.getAsDouble())) {
                out.add(symbol);
            }
        }
        return List.copyOf(out);
    }

    static ObjectNode ipoBody(CalendarRequest request) {
        String start = request.start().toString();
        String end = request.end().toString();
        ObjectNode query = Q.or(
                Q.gtelt("startdatetime", start, end),
                Q.gtelt("filingdate", start, end),
                Q.gtelt("amendeddate", start, end));
        return body(IPO, request.limit(), request.offset(), query);
    }

    static ObjectNode economicBody(CalendarRequest request) {
        return body(ECONOMIC, request.limit(), request.offset(), dateRange(request));
    }

    static ObjectNode splitsBody(CalendarRequest request) {
        return body(SPLITS, request.limit(), request.offset(), dateRange(request));
    }

    private static ObjectNode dateRange(CalendarRequest request) {
        return Q.and(
                Q.gte("startdatetime", request.start().toString()),
                Q.lte("startdatetime", request.end().toString()));
    }

    /** The yfinance {@code _get_data} body; dates go on the wire as {@code yyyy-MM-dd}. */
    static ObjectNode body(Spec spec, int limit, int offset, ObjectNode query) {
        ObjectNode body = YahooJson.mapper().createObjectNode();
        body.put("sortType", "DESC");
        body.put("entityIdType", spec.entityIdType());
        body.put("sortField", spec.sortField());
        ArrayNode fields = body.putArray("includeFields");
        spec.fields().forEach(fields::add);
        body.put("size", Math.min(limit, CalendarRequest.MAX_LIMIT));
        body.put("offset", offset);
        body.set("query", query);
        return body;
    }

    /** Rows without a symbol or start time are skipped; latest first, ties in Yahoo's order. */
    static List<EarningsEvent> parseEarnings(JsonNode root) {
        return parseEarnings(VisualizationTable.of(root));
    }

    private static List<EarningsEvent> parseEarnings(VisualizationTable t) {
        var out = new ArrayList<EarningsEvent>(t.rows().size());
        for (JsonNode row : t.rows()) {
            @Nullable String symbol = t.text(row, "ticker");
            @Nullable Instant start = t.instant(row, "startdatetime");
            if (symbol == null || start == null) {
                continue;
            }
            out.add(new EarningsEvent(
                    symbol,
                    t.text(row, "companyshortname"),
                    t.number(row, "intradaymarketcap"),
                    t.text(row, "eventname"),
                    start.atZone(NEW_YORK),
                    t.text(row, "startdatetimetype"),
                    t.zeroAsNaN(row, "epsestimate"),
                    t.zeroAsNaN(row, "epsactual"),
                    t.zeroAsNaN(row, "epssurprisepct")));
        }
        // yfinance sort_values; List.sort is stable, so ties keep Yahoo's order
        out.sort(Comparator.comparing(EarningsEvent::startTime).reversed());
        return List.copyOf(out);
    }

    /** Rows without a symbol are skipped. */
    static List<IpoEvent> parseIpos(JsonNode root) {
        return parseIpos(VisualizationTable.of(root));
    }

    private static List<IpoEvent> parseIpos(VisualizationTable t) {
        var out = new ArrayList<IpoEvent>(t.rows().size());
        for (JsonNode row : t.rows()) {
            @Nullable String symbol = t.text(row, "ticker");
            if (symbol == null) {
                continue;
            }
            out.add(new IpoEvent(
                    symbol,
                    t.text(row, "companyshortname"),
                    t.text(row, "exchange_short_name"),
                    t.localDate(row, "filingdate", NEW_YORK),
                    t.localDate(row, "startdatetime", NEW_YORK),
                    t.localDate(row, "amendeddate", NEW_YORK),
                    t.zeroAsNaN(row, "pricefrom"),
                    t.zeroAsNaN(row, "priceto"),
                    t.zeroAsNaN(row, "offerprice"),
                    t.text(row, "currencyname"),
                    t.zeroAsNaN(row, "shares"),
                    t.text(row, "dealtype")));
        }
        return List.copyOf(out);
    }

    /** Rows without a release name or time are skipped. */
    static List<EconomicEvent> parseEconomicEvents(JsonNode root) {
        return parseEconomicEvents(VisualizationTable.of(root));
    }

    private static List<EconomicEvent> parseEconomicEvents(VisualizationTable t) {
        var out = new ArrayList<EconomicEvent>(t.rows().size());
        for (JsonNode row : t.rows()) {
            @Nullable String event = t.text(row, "econ_release");
            @Nullable Instant time = t.instant(row, "startdatetime");
            if (event == null || time == null) {
                continue;
            }
            out.add(new EconomicEvent(
                    event,
                    t.text(row, "country_code"),
                    time,
                    t.text(row, "period"),
                    t.zeroAsNaN(row, "after_release_actual"),
                    t.zeroAsNaN(row, "consensus_estimate"),
                    t.zeroAsNaN(row, "prior_release_actual"),
                    t.zeroAsNaN(row, "originally_reported_actual")));
        }
        return List.copyOf(out);
    }

    /** Rows without a symbol or date are skipped. */
    static List<SplitEvent> parseSplits(JsonNode root) {
        return parseSplits(VisualizationTable.of(root));
    }

    private static List<SplitEvent> parseSplits(VisualizationTable t) {
        var out = new ArrayList<SplitEvent>(t.rows().size());
        for (JsonNode row : t.rows()) {
            @Nullable String symbol = t.text(row, "ticker");
            @Nullable LocalDate date = t.localDate(row, "startdatetime", NEW_YORK);
            if (symbol == null || date == null) {
                continue;
            }
            out.add(new SplitEvent(
                    symbol,
                    t.text(row, "companyshortname"),
                    date,
                    t.bool(row, "optionable"),
                    t.number(row, "old_share_worth"),
                    t.number(row, "share_worth")));
        }
        return List.copyOf(out);
    }

    /** One yfinance {@code PREDEFINED_CALENDARS} entry. */
    record Spec(String entityIdType, String sortField, List<String> fields) {}
}
