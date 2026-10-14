package io.andreygrigoriev.yfin.internal.endpoint.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.andreygrigoriev.yfin.exception.YFDataException;
import io.andreygrigoriev.yfin.internal.http.RequestLimiter;
import io.andreygrigoriev.yfin.internal.http.RetryPolicy;
import io.andreygrigoriev.yfin.internal.http.YahooJson;
import io.andreygrigoriev.yfin.internal.http.YahooSession;
import io.andreygrigoriev.yfin.model.EarningsEvent;
import io.andreygrigoriev.yfin.model.EconomicEvent;
import io.andreygrigoriev.yfin.model.IpoEvent;
import io.andreygrigoriev.yfin.model.ScreenerResult;
import io.andreygrigoriev.yfin.model.SplitEvent;
import io.andreygrigoriev.yfin.request.CalendarRequest;
import io.andreygrigoriev.yfin.request.EarningsCalendarRequest;
import io.andreygrigoriev.yfin.spi.Cache;
import io.andreygrigoriev.yfin.spi.HttpRequest;
import io.andreygrigoriev.yfin.testing.FakeTransport;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CalendarEndpointTest {

    private static final String PATH = "/v1/finance/visualization";
    private static final String SCREENER = "/v1/finance/screener/predefined/saved";
    private static final CalendarRequest WEEK =
            CalendarRequest.between(LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 21));

    private static final EarningsCalendarRequest EARNINGS_WEEK =
            EarningsCalendarRequest.between(LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 21));

    private final FakeTransport fake = FakeTransport.create();
    private CalendarEndpoint endpoint;

    @BeforeEach
    void setUp() {
        fake.onGet("").respond(404, "not found");
        fake.onGet("/v1/test/getcrumb").respond(200, "crumb1");
        YahooSession session = YahooSession.create(fake, Cache.none(), new RequestLimiter(1), RetryPolicy.of(0));
        endpoint = new CalendarEndpoint(session);
    }

    // The fixtures echo the posted body as "rawCriteria"; they were recorded with the yfinance bodies.
    @ParameterizedTest
    @ValueSource(strings = {"ipo", "economic", "splits"})
    void bodyMatchesYfinance(String type) {
        fake.onPost(PATH).respondFixture("calendar/" + type + "-week.json");

        call(type).apply(WEEK.limit(100));

        HttpRequest request = fake.requests().getLast();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.uri().getHost()).isEqualTo("query1.finance.yahoo.com");
        assertThat(request.uri().getPath()).isEqualTo(PATH);
        assertThat(FakeTransport.parseQuery(request.uri().getRawQuery()))
                .containsEntry("lang", List.of("en-US"))
                .containsEntry("region", List.of("US"));
        JsonNode sent = readJson(request.bodyAsString().orElseThrow());
        JsonNode recorded = readJson(fixture("calendar/" + type + "-week.json")
                .path("finance")
                .path("result")
                .path(0)
                .path("rawCriteria")
                .asText());
        assertThat(sent).isEqualTo(recorded);
    }

    @Test
    void bodyCarriesLimitAndOffset() {
        JsonNode body = CalendarEndpoint.splitsBody(WEEK.limit(5).offset(10));

        assertThat(body.path("size").asInt()).isEqualTo(5);
        assertThat(body.path("offset").asInt()).isEqualTo(10);
        assertThat(CalendarEndpoint.ipoBody(WEEK).path("size").asInt()).isEqualTo(CalendarRequest.DEFAULT_LIMIT);
    }

    @Test
    void ipos() {
        fake.onPost(PATH).respondFixture("calendar/ipo-week.json");

        List<IpoEvent> ipos = endpoint.ipos(WEEK);

        assertThat(ipos).hasSize(16);
        assertThat(ipos.getFirst())
                .isEqualTo(new IpoEvent(
                        "AVUC",
                        "American Century ETF Trust",
                        null,
                        null,
                        LocalDate.of(2026, 10, 15),
                        null,
                        Double.NaN,
                        Double.NaN,
                        Double.NaN,
                        null,
                        Double.NaN,
                        "Expected"));
        IpoEvent priced = ipos.get(8);
        assertThat(priced.symbol()).isEqualTo("MGOL");
        assertThat(priced.exchange()).isEqualTo("Nasdaq");
        assertThat(priced.priceFrom()).isEqualTo(5.0);
        assertThat(priced.priceTo()).isEqualTo(5.0);
        assertThat(priced.offerPrice()).isNaN();
        assertThat(priced.currency()).isEqualTo("USD");
    }

    @Test
    void economicEvents() {
        fake.onPost(PATH).respondFixture("calendar/economic-week.json");

        List<EconomicEvent> events = endpoint.economicEvents(WEEK);

        assertThat(events).hasSize(100);
        EconomicEvent first = events.getFirst();
        assertThat(first.event()).isEqualTo("CPI YY*");
        assertThat(first.region()).isEqualTo("SA");
        assertThat(first.time()).isEqualTo(Instant.parse("2026-10-21T00:00:00Z"));
        assertThat(first.period()).isEqualTo("Sep");
        // not released yet: no actual, no estimate; the prior value is a numeric string
        assertThat(first.actual()).isNaN();
        assertThat(first.expected()).isNaN();
        assertThat(first.last()).isEqualTo(1.8);
        assertThat(first.revised()).isNaN();
    }

    @Test
    void splits() {
        fake.onPost(PATH).respondFixture("calendar/splits-week.json");

        List<SplitEvent> splits = endpoint.splits(WEEK);

        assertThat(splits).hasSize(79);
        assertThat(splits.getFirst())
                .isEqualTo(new SplitEvent("117670.KQ", "Alphachips Inc", LocalDate.of(2026, 10, 21), false, 1, 2));
        assertThat(splits.getFirst().ratio()).isEqualTo(2.0);
        // a zero new share worth is kept (not one of yfinance's nan_cols)
        SplitEvent zero = splits.get(57);
        assertThat(zero.symbol()).isEqualTo("033340.KQ");
        assertThat(zero.newShareWorth()).isZero();
        assertThat(zero.ratio()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ipo", "economic", "splits"})
    void emptyWindowGivesEmptyList(String type) {
        fake.onPost(PATH).respondFixture("calendar/" + type + "-empty.json");

        assertThat(call(type).apply(WEEK)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ipo", "economic", "splits"})
    void errorEnvelopeThrows(String type) {
        fake.onPost(PATH).respondFixture("calendar/error.json");

        assertThatThrownBy(() -> call(type).apply(WEEK))
                .isInstanceOf(YFDataException.class)
                .hasMessageContaining("Unsupported operator");
    }

    // Synthetic: rows 0 and 8 of ipo-week.json with the columns (and cells) in reverse order.
    @Test
    void reorderedColumnsMapById() {
        fake.onPost(PATH).respondFixture("calendar/ipo-reordered-synthetic.json");
        List<IpoEvent> week = CalendarEndpoint.parseIpos(fixture("calendar/ipo-week.json"));

        List<IpoEvent> ipos = endpoint.ipos(WEEK);

        assertThat(ipos).containsExactly(week.get(0), week.get(8));
    }

    @Test
    void rowsWithoutKeyAreSkipped() {
        JsonNode ipo = readJson("""
                {"finance":{"result":[{"documents":[{
                  "columns":[{"id":"ticker"},{"id":"startdatetime"},{"id":"pricefrom"},{"id":"shares"}],
                  "rows":[[null,"2026-10-15T04:00:00.000Z",1,1],["",null,1,1],["A",null,0,"1000"]]
                }]}]}}
                """);
        JsonNode econ = readJson("""
                {"finance":{"result":[{"documents":[{
                  "columns":[{"id":"econ_release"},{"id":"startdatetime"},{"id":"after_release_actual"},
                             {"id":"consensus_estimate"},{"id":"prior_release_actual"}],
                  "rows":[[null,"2026-10-21T00:00:00.000Z","1","1","1"],["CPI",null,"1","1","1"],
                          ["GDP","2026-10-21T00:00:00.000Z","0","0.0","n/a"]]
                }]}]}}
                """);
        JsonNode splits = readJson("""
                {"finance":{"result":[{"documents":[{
                  "columns":[{"id":"ticker"},{"id":"startdatetime"}],
                  "rows":[[null,"2026-10-21T04:00:00.000Z"],["X",null],["Y","2026-10-21T04:00:00.000Z"]]
                }]}]}}
                """);

        List<IpoEvent> ipos = CalendarEndpoint.parseIpos(ipo);
        assertThat(ipos).singleElement().satisfies(e -> {
            assertThat(e.symbol()).isEqualTo("A");
            assertThat(e.date()).isNull();
            // zero placeholder price; numeric string share count
            assertThat(e.priceFrom()).isNaN();
            assertThat(e.shares()).isEqualTo(1000.0);
        });
        assertThat(CalendarEndpoint.parseEconomicEvents(econ)).singleElement().satisfies(e -> {
            assertThat(e.event()).isEqualTo("GDP");
            assertThat(e.actual()).isNaN();
            assertThat(e.expected()).isNaN();
            assertThat(e.last()).isNaN();
            assertThat(e.region()).isNull();
        });
        assertThat(CalendarEndpoint.parseSplits(splits)).singleElement().satisfies(e -> {
            assertThat(e.symbol()).isEqualTo("Y");
            assertThat(e.optionable()).isNull();
            assertThat(e.ratio()).isNaN();
        });
    }

    @Test
    void invalidDateThrows() {
        JsonNode root = readJson("""
                {"finance":{"result":[{"documents":[{"columns":[{"id":"ticker"},{"id":"startdatetime"}],
                  "rows":[["X","next week"]]}]}]}}
                """);

        assertThatThrownBy(() -> CalendarEndpoint.parseSplits(root))
                .isInstanceOf(YFDataException.class)
                .hasMessageContaining("next week");
    }

    // earnings-week*.json were posted with the symbols of most-actives.json (same snapshot, 132 quotes,
    // all above 1e9), so the body built from that screen equals the recorded one
    @ParameterizedTest
    @ValueSource(strings = {"earnings-week", "earnings-week-mcap"})
    void earningsBodyMatchesYfinance(String name) {
        fake.onGet(SCREENER).respondFixture("calendar/most-actives.json");
        fake.onPost(PATH).respondFixture("calendar/" + name + ".json");
        EarningsCalendarRequest request = EARNINGS_WEEK.limit(100);

        endpoint.earnings(name.endsWith("mcap") ? request.marketCap(1e9) : request);

        HttpRequest screen = requestsTo(SCREENER).getFirst();
        assertThat(FakeTransport.parseQuery(screen.uri().getRawQuery()))
                .containsEntry("scrIds", List.of("most_actives"))
                .containsEntry("count", List.of("200"));
        HttpRequest request0 = requestsTo(PATH).getFirst();
        assertThat(FakeTransport.parseQuery(request0.uri().getRawQuery()))
                .containsEntry("lang", List.of("en-US"))
                .containsEntry("region", List.of("US"));
        assertThat(readJson(request0.bodyAsString().orElseThrow())).isEqualTo(rawCriteria(name));
    }

    @Test
    void earningsBodyWithoutFilters() {
        fake.onPost(PATH).respondFixture("calendar/earnings-week.json");

        endpoint.earnings(EARNINGS_WEEK.filterMostActive(false));

        assertThat(requestsTo(SCREENER)).isEmpty();
        JsonNode sent = readJson(requestsTo(PATH).getFirst().bodyAsString().orElseThrow());
        JsonNode expected = rawCriteria("earnings-week").deepCopy();
        ((ObjectNode) expected).put("size", CalendarRequest.DEFAULT_LIMIT);
        ((ArrayNode) expected.path("query").path("operands")).remove(4);
        assertThat(sent).isEqualTo(expected);
        assertThat(sent.path("query").path("operands")).hasSize(4);
    }

    @Test
    void earningsBodyWithCapOnly() {
        JsonNode body = CalendarEndpoint.earningsBody(EARNINGS_WEEK.marketCap(5e9), List.of());

        JsonNode operands = body.path("query").path("operands");
        assertThat(operands).hasSize(5);
        assertThat(operands.get(4).toString())
                .isEqualTo("{\"operator\":\"GTE\",\"operands\":[\"intradaymarketcap\",5.0E9]}");
    }

    @Test
    void mostActiveFilteredByCap() {
        fake.onGet(SCREENER).respondFixture("calendar/most-actives.json");
        fake.onPost(PATH).respondFixture("calendar/earnings-week-mcap.json");

        endpoint.earnings(EARNINGS_WEEK.marketCap(1e11));

        JsonNode operands = readJson(requestsTo(PATH).getFirst().bodyAsString().orElseThrow())
                .path("query")
                .path("operands");
        assertThat(operands).hasSize(6);
        assertThat(operands.get(4).path("operands").get(1).asDouble()).isEqualTo(1e11);
        JsonNode tickers = operands.get(5);
        assertThat(tickers.path("operator").asText()).isEqualTo("OR");
        // 32 of the 132 most-active quotes have a market cap of at least 100B
        assertThat(tickers.path("operands")).hasSize(32);
        assertThat(tickers.path("operands").get(0).toString())
                .isEqualTo("{\"operator\":\"EQ\",\"operands\":[\"ticker\",\"BAC\"]}");
        assertThat(tickers.path("operands").get(1).path("operands").get(1).asText())
                .isEqualTo("NVDA");
    }

    @Test
    void mostActiveSymbolsSkipsQuotesWithoutSymbolOrCap() {
        var result = new ScreenerResult(
                "most_actives",
                null,
                null,
                0,
                4,
                4L,
                List.of(
                        Map.of("symbol", "A", "marketCap", 2e9),
                        Map.of("symbol", "B"),
                        Map.of("symbol", "", "marketCap", 5e9),
                        Map.of("marketCap", 5e9),
                        Map.of("symbol", "C", "marketCap", 500_000_000L)),
                Map.of());

        assertThat(CalendarEndpoint.mostActiveSymbols(result, OptionalDouble.empty()))
                .containsExactly("A", "B", "C");
        assertThat(CalendarEndpoint.mostActiveSymbols(result, OptionalDouble.of(1e9)))
                .containsExactly("A");
    }

    @Test
    void mostActiveSkippedWithOffset() {
        fake.onPost(PATH).respondFixture("calendar/earnings-week.json");

        endpoint.earnings(EARNINGS_WEEK.offset(12));

        assertThat(requestsTo(SCREENER)).isEmpty();
        JsonNode sent = readJson(requestsTo(PATH).getFirst().bodyAsString().orElseThrow());
        assertThat(sent.path("offset").asInt()).isEqualTo(12);
        assertThat(sent.path("query").path("operands")).hasSize(4);
    }

    @Test
    void noMostActiveSurvivorGivesEmptyWithoutCalendarRequest() {
        fake.onGet(SCREENER).respondFixture("calendar/most-actives.json");

        List<EarningsEvent> events = endpoint.earnings(EARNINGS_WEEK.marketCap(1e15));

        assertThat(events).isEmpty();
        // besides the cookie and crumb bootstrap, only the screen
        assertThat(fake.requests())
                .filteredOn(r -> r.uri().getPath().startsWith("/v1/finance/"))
                .singleElement()
                .satisfies(r -> assertThat(r.uri().getPath()).isEqualTo(SCREENER));
    }

    @Test
    void screenerErrorPropagates() {
        fake.onGet(SCREENER).respondFixture(404, "screener/predefined-unknown.json");

        assertThatThrownBy(() -> endpoint.earnings(EARNINGS_WEEK))
                .isInstanceOf(YFDataException.class)
                .hasMessageContaining("No screener records found");
        assertThat(requestsTo(PATH)).isEmpty();
    }

    @Test
    void earnings() {
        fake.onGet(SCREENER).respondFixture("calendar/most-actives.json");
        fake.onPost(PATH).respondFixture("calendar/earnings-week.json");

        List<EarningsEvent> events = endpoint.earnings(EARNINGS_WEEK);

        // latest first; KEY and AMC share a start time and keep Yahoo's order, as do TSM and ERIC
        assertThat(events)
                .extracting(EarningsEvent::symbol)
                .containsExactly("NFLX", "KEY", "AMC", "AGNC", "CLF", "TSM", "ERIC", "BAC");
        assertThat(events.getFirst())
                .isEqualTo(new EarningsEvent(
                        "NFLX",
                        "Netflix, Inc.",
                        290792885113.615,
                        "Q3 2026 Earnings Announcement",
                        ZonedDateTime.of(2026, 10, 20, 16, 0, 0, 0, CalendarEndpoint.NEW_YORK),
                        "AMC",
                        0.82,
                        Double.NaN,
                        Double.NaN));
        EarningsEvent bac = events.getLast();
        assertThat(bac.eventName()).isNull();
        assertThat(bac.timing()).isEqualTo("TAS");
        assertThat(bac.startTime()).isEqualTo(ZonedDateTime.of(2026, 10, 14, 6, 44, 44, 0, CalendarEndpoint.NEW_YORK));
        assertThat(bac.isReported()).isTrue();
        assertThat(bac.reportedEps()).isEqualTo(0.91);
        assertThat(bac.surprisePercent()).isEqualTo(-17.93);
        // not reported yet: the 0.0 surprise placeholder becomes NaN
        EarningsEvent tsm = events.get(5);
        assertThat(tsm.isReported()).isFalse();
        assertThat(tsm.epsEstimate()).isEqualTo(4.52);
        assertThat(tsm.surprisePercent()).isNaN();
    }

    @Test
    void earningsEmptyWindow() {
        fake.onPost(PATH).respondFixture("calendar/earnings-empty.json");

        assertThat(endpoint.earnings(EARNINGS_WEEK.filterMostActive(false))).isEmpty();
    }

    @Test
    void earningsErrorEnvelopeThrows() {
        fake.onPost(PATH).respondFixture("calendar/error.json");

        assertThatThrownBy(() -> endpoint.earnings(EARNINGS_WEEK.filterMostActive(false)))
                .isInstanceOf(YFDataException.class)
                .hasMessageContaining("Unsupported operator");
    }

    @Test
    void earningsRowsWithoutKeyAreSkipped() {
        JsonNode root = readJson("""
                {"finance":{"result":[{"documents":[{
                  "columns":[{"id":"ticker"},{"id":"startdatetime"},{"id":"epsestimate"}],
                  "rows":[[null,"2026-10-15T12:30:00.000Z",1],["X",null,1],["Y","2026-10-15T12:30:00.000Z",0]]
                }]}]}}
                """);

        assertThat(CalendarEndpoint.parseEarnings(root)).singleElement().satisfies(e -> {
            assertThat(e.symbol()).isEqualTo("Y");
            assertThat(e.companyName()).isNull();
            assertThat(e.marketCap()).isNaN();
            assertThat(e.epsEstimate()).isNaN();
            assertThat(e.timing()).isNull();
        });
    }

    private List<HttpRequest> requestsTo(String path) {
        return fake.requests().stream()
                .filter(r -> r.uri().getPath().equals(path))
                .toList();
    }

    private static JsonNode rawCriteria(String name) {
        return readJson(fixture("calendar/" + name + ".json")
                .path("finance")
                .path("result")
                .path(0)
                .path("rawCriteria")
                .asText());
    }

    private Function<CalendarRequest, List<?>> call(String type) {
        return switch (type) {
            case "ipo" -> endpoint::ipos;
            case "economic" -> endpoint::economicEvents;
            case "splits" -> endpoint::splits;
            default -> throw new IllegalArgumentException(type);
        };
    }

    private static JsonNode fixture(String name) {
        try (InputStream in = CalendarEndpointTest.class.getResourceAsStream("/fixtures/" + name)) {
            return YahooJson.mapper().readTree(in);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static JsonNode readJson(String json) {
        try {
            return YahooJson.mapper().readTree(json);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
