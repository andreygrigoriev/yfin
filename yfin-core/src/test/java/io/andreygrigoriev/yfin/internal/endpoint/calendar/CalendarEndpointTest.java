package io.andreygrigoriev.yfin.internal.endpoint.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import io.andreygrigoriev.yfin.exception.YFDataException;
import io.andreygrigoriev.yfin.internal.http.RequestLimiter;
import io.andreygrigoriev.yfin.internal.http.RetryPolicy;
import io.andreygrigoriev.yfin.internal.http.YahooJson;
import io.andreygrigoriev.yfin.internal.http.YahooSession;
import io.andreygrigoriev.yfin.model.EconomicEvent;
import io.andreygrigoriev.yfin.model.IpoEvent;
import io.andreygrigoriev.yfin.model.SplitEvent;
import io.andreygrigoriev.yfin.request.CalendarRequest;
import io.andreygrigoriev.yfin.spi.Cache;
import io.andreygrigoriev.yfin.spi.HttpRequest;
import io.andreygrigoriev.yfin.testing.FakeTransport;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CalendarEndpointTest {

    private static final String PATH = "/v1/finance/visualization";
    private static final CalendarRequest WEEK =
            CalendarRequest.between(LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 21));

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
