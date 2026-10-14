package io.andreygrigoriev.yfin.it;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.andreygrigoriev.yfin.Screener;
import io.andreygrigoriev.yfin.YFinance;
import io.andreygrigoriev.yfin.internal.http.RequestLimiter;
import io.andreygrigoriev.yfin.internal.http.RetryPolicy;
import io.andreygrigoriev.yfin.internal.http.YahooJson;
import io.andreygrigoriev.yfin.internal.http.YahooSession;
import io.andreygrigoriev.yfin.spi.Cache;
import io.andreygrigoriev.yfin.spi.HttpRequest;
import io.andreygrigoriev.yfin.spi.Transport;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Posts the yfinance 1.7.0 {@code Calendars} request bodies directly, for recording calendar
 * fixtures before the public calendar API exists.
 *
 * <p>TODO(market calendars, Task 8): delete once the recorder cases call {@code
 * YFinance.*Calendar(...)}.
 */
final class RawCalendars {

    private static final URI URL =
            URI.create("https://query1.finance.yahoo.com/v1/finance/visualization?lang=en-US&region=US");

    /** A week with no calendar entries. */
    static final LocalDate PAST_START = LocalDate.of(1970, 1, 1);

    static final LocalDate PAST_END = LocalDate.of(1970, 1, 7);

    private static volatile @Nullable YahooSession session;

    private RawCalendars() {}

    /** Sends the raw requests through {@code transport}, with their own cookie/crumb session. */
    static void use(Transport transport) {
        session = YahooSession.create(transport, Cache.none(), new RequestLimiter(1), RetryPolicy.of(2));
    }

    static void earnings(YFinance yf, LocalDate start, LocalDate end, @Nullable Double marketCap) {
        ObjectNode query = op(
                "AND",
                op("EQ", "region", "us"),
                op("OR", op("EQ", "eventtype", "EAD"), op("EQ", "eventtype", "ERA")),
                op("GTE", "startdatetime", start.toString()),
                op("LTE", "startdatetime", end.toString()));
        ArrayNode operands = (ArrayNode) query.get("operands");
        if (marketCap != null) {
            ObjectNode cap = YahooJson.mapper().createObjectNode().put("operator", "GTE");
            cap.putArray("operands").add("intradaymarketcap").add(marketCap.doubleValue());
            operands.add(cap);
        }
        ObjectNode mostActive = op("OR");
        ArrayNode tickers = (ArrayNode) mostActive.get("operands");
        for (Map<String, Object> quote :
                yf.screen(Screener.predefined("most_actives").size(200)).quotes()) {
            double cap = quote.get("marketCap") instanceof Number n ? n.doubleValue() : 0;
            if (quote.get("symbol") instanceof String symbol
                    && !symbol.isEmpty()
                    && (marketCap == null || cap >= marketCap)) {
                tickers.add(op("EQ", "ticker", symbol));
            }
        }
        operands.add(mostActive);
        post(
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
                        "epssurprisepct"),
                query);
    }

    static void ipos(LocalDate start, LocalDate end) {
        String s = start.toString();
        String e = end.toString();
        post(
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
                        "dealtype"),
                op(
                        "OR",
                        op("GTELT", "startdatetime", s, e),
                        op("GTELT", "filingdate", s, e),
                        op("GTELT", "amendeddate", s, e)));
    }

    static void economicEvents(LocalDate start, LocalDate end) {
        post(
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
                        "originally_reported_actual"),
                window(start, end));
    }

    static void splits(LocalDate start, LocalDate end) {
        post(
                "splits",
                "startdatetime",
                List.of("ticker", "companyshortname", "startdatetime", "optionable", "old_share_worth", "share_worth"),
                window(start, end));
    }

    static void badOperator(LocalDate start) {
        // An unknown entityIdType gives HTTP 500; an unknown operator gives a finance.error envelope.
        post(
                "splits",
                "startdatetime",
                List.of("ticker", "startdatetime"),
                op("BOGUS", "startdatetime", start.toString()));
    }

    private static ObjectNode window(LocalDate start, LocalDate end) {
        return op("AND", op("GTE", "startdatetime", start.toString()), op("LTE", "startdatetime", end.toString()));
    }

    private static void post(String type, String sortField, List<String> fields, ObjectNode query) {
        ObjectNode body = YahooJson.mapper().createObjectNode();
        body.put("sortType", "DESC");
        body.put("entityIdType", type);
        body.put("sortField", sortField);
        ArrayNode include = body.putArray("includeFields");
        fields.forEach(include::add);
        body.put("size", 100);
        body.put("offset", 0);
        body.set("query", query);
        YahooSession s = Objects.requireNonNull(session, "RawCalendars.use was not called");
        s.execute(HttpRequest.postJson(URL, body.toString()));
    }

    private static ObjectNode op(String operator, ObjectNode... operands) {
        ObjectNode node = YahooJson.mapper().createObjectNode().put("operator", operator);
        ArrayNode list = node.putArray("operands");
        for (ObjectNode operand : operands) {
            list.add(operand);
        }
        return node;
    }

    private static ObjectNode op(String operator, String field, String... values) {
        ObjectNode node = YahooJson.mapper().createObjectNode().put("operator", operator);
        ArrayNode list = node.putArray("operands").add(field);
        for (String value : values) {
            list.add(value);
        }
        return node;
    }
}
