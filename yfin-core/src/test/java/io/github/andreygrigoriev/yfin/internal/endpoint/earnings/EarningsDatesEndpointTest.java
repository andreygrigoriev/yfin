package io.github.andreygrigoriev.yfin.internal.endpoint.earnings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.andreygrigoriev.yfin.YFinance;
import io.github.andreygrigoriev.yfin.exception.YFDataException;
import io.github.andreygrigoriev.yfin.internal.cache.FileCache;
import io.github.andreygrigoriev.yfin.internal.http.YahooJson;
import io.github.andreygrigoriev.yfin.model.EarningsDate;
import io.github.andreygrigoriev.yfin.spi.Cache;
import io.github.andreygrigoriev.yfin.spi.HttpRequest;
import io.github.andreygrigoriev.yfin.testing.FakeTransport;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EarningsDatesEndpointTest {

    private static final String PATH = "/v1/finance/visualization";
    private static final String CHART = "/v8/finance/chart/";

    @TempDir
    Path cacheDir;

    private final FakeTransport fake = FakeTransport.create();
    private Cache cache;
    private YFinance yf;

    @BeforeEach
    void setUp() {
        fake.onGet("").respond(404, "not found");
        fake.onGet("/v1/test/getcrumb").respond(200, "crumb1");
        cache = new FileCache(cacheDir);
        yf = YFinance.builder().transport(fake).cache(cache).maxRetries(0).build();
    }

    @AfterEach
    void tearDown() {
        yf.close();
    }

    @Test
    void pastAndFutureDatesInExchangeTimezone() {
        fake.onPost(PATH).respondFixture("earnings/aapl.json");
        fake.onGet(CHART + "AAPL").query("range", "1d").respondFixture("chart/aapl-tz.json");

        List<EarningsDate> dates = yf.ticker("AAPL").earningsDates();

        assertThat(dates).hasSize(12);
        ZoneId ny = ZoneId.of("America/New_York");
        // upcoming: estimate only
        EarningsDate next = dates.getFirst();
        assertThat(next.date()).isEqualTo(ZonedDateTime.of(2026, 11, 2, 15, 0, 0, 0, ny));
        assertThat(next.epsEstimate()).isEqualTo(1.98);
        assertThat(next.reportedEps()).isNaN();
        assertThat(next.surprisePercent()).isNaN();
        assertThat(next.isReported()).isFalse();
        // latest reported
        EarningsDate last = dates.get(1);
        assertThat(last.date()).isEqualTo(ZonedDateTime.of(2026, 7, 30, 16, 30, 28, 0, ny));
        assertThat(last.epsEstimate()).isEqualTo(1.89);
        assertThat(last.reportedEps()).isEqualTo(2.02);
        assertThat(last.surprisePercent()).isEqualTo(6.74);
        assertThat(last.isReported()).isTrue();
        // newest first
        assertThat(dates.getLast().date()).isEqualTo(ZonedDateTime.of(2024, 2, 1, 16, 30, 0, 0, ny));
        assertThat(dates).isSortedAccordingTo((a, b) -> b.date().compareTo(a.date()));
        assertThat(cache.get("tz", "AAPL")).contains("America/New_York");
    }

    @Test
    void requestBodyAndParams() {
        fake.onPost(PATH).respondFixture("earnings/aapl.json");
        cache.put("tz", "AAPL", "America/New_York");

        yf.ticker("AAPL").earningsDates(25);

        HttpRequest request = visualizationRequests().getFirst();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.uri().getHost()).isEqualTo("query1.finance.yahoo.com");
        assertThat(request.header("Content-Type")).contains("application/json");
        Map<String, List<String>> q = FakeTransport.parseQuery(request.uri().getRawQuery());
        assertThat(q).containsEntry("lang", List.of("en-US")).containsEntry("region", List.of("US"));
        JsonNode body = readJson(request.bodyAsString().orElseThrow());
        assertThat(body.path("entityIdType").asText()).isEqualTo("sp_earnings");
        assertThat(body.path("sortField").asText()).isEqualTo("startdatetime");
        assertThat(body.path("sortType").asText()).isEqualTo("DESC");
        assertThat(body.path("size").asInt()).isEqualTo(25);
        assertThat(body.path("offset").asInt()).isZero();
        assertThat(body.path("includeFields").toString())
                .contains("\"startdatetime\"", "\"epsestimate\"", "\"epsactual\"", "\"epssurprisepct\"");
        JsonNode query = body.path("query");
        assertThat(query.path("operator").asText()).isEqualTo("and");
        assertThat(query.path("operands").get(0).toString())
                .isEqualTo("{\"operator\":\"eq\",\"operands\":[\"ticker\",\"AAPL\"]}");
        assertThat(query.path("operands").get(1).toString()).contains("\"EAD\"", "\"ERA\"");
        // timezone from the cache: no chart request
        assertThat(chartRequests()).isEmpty();
    }

    @Test
    void bodyWireFormatIsStable() {
        assertThat(EarningsDatesEndpoint.body("AAPL", 25).toString())
                .isEqualTo("{\"sortType\":\"DESC\",\"entityIdType\":\"sp_earnings\",\"sortField\":\"startdatetime\","
                        + "\"includeFields\":[\"ticker\",\"companyshortname\",\"eventname\",\"startdatetime\","
                        + "\"startdatetimetype\",\"dateisestimate\",\"epsestimate\",\"epsactual\",\"epssurprisepct\","
                        + "\"timeZoneShortName\",\"gmtOffsetMilliSeconds\"],"
                        + "\"query\":{\"operator\":\"and\",\"operands\":["
                        + "{\"operator\":\"eq\",\"operands\":[\"ticker\",\"AAPL\"]},"
                        + "{\"operator\":\"or\",\"operands\":[{\"operator\":\"eq\",\"operands\":[\"eventtype\",\"EAD\"]},"
                        + "{\"operator\":\"eq\",\"operands\":[\"eventtype\",\"ERA\"]}]}]},"
                        + "\"offset\":0,\"size\":25}");
    }

    @Test
    void missingEstimatesAreNaN() {
        fake.onPost(PATH).respondFixture("earnings/cba-ax.json");
        cache.put("tz", "CBA.AX", "Australia/Sydney");

        List<EarningsDate> dates = yf.ticker("CBA.AX").earningsDates();

        assertThat(dates).hasSize(2);
        assertThat(dates.getFirst().date())
                .isEqualTo(ZonedDateTime.of(2027, 2, 9, 16, 12, 0, 0, ZoneId.of("Australia/Sydney")));
        assertThat(dates).allSatisfy(d -> {
            assertThat(d.epsEstimate()).isNaN();
            assertThat(d.reportedEps()).isNaN();
            assertThat(d.surprisePercent()).isNaN();
        });
    }

    @Test
    void placeholderSurpriseWithoutReportedEpsIsNaN() {
        fake.onPost(PATH).respondFixture("earnings/sap-de.json");
        cache.put("tz", "SAP.DE", "Europe/Berlin");

        List<EarningsDate> dates = yf.ticker("SAP.DE").earningsDates(8);

        assertThat(dates).hasSize(8);
        EarningsDate next = dates.getFirst();
        assertThat(next.date()).isEqualTo(ZonedDateTime.of(2026, 10, 21, 17, 30, 0, 0, ZoneId.of("Europe/Berlin")));
        assertThat(next.epsEstimate()).isEqualTo(1.83);
        assertThat(next.surprisePercent()).isNaN();
        assertThat(dates.get(1).surprisePercent()).isEqualTo(-9.29);
    }

    @Test
    void genuineZerosAreKept() {
        fake.onPost(PATH).respondFixture("earnings/gme.json");
        cache.put("tz", "GME", "America/New_York");

        List<EarningsDate> dates = yf.ticker("GME").earningsDates();

        // reported 0.27 against 0.27: a real 0% surprise
        assertThat(dates.get(1).reportedEps()).isEqualTo(0.27);
        assertThat(dates.get(1).surprisePercent()).isZero();
        // reported EPS of exactly 0
        assertThat(dates.getLast().reportedEps()).isZero();
        assertThat(dates.getLast().surprisePercent()).isEqualTo(100.0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SPY", "ZZZZNOTREAL"})
    void noRowsGiveEmptyListWithoutTimezoneLookup(String symbol) {
        String fixture = symbol.equals("SPY") ? "earnings/spy.json" : "earnings/unknown.json";
        fake.onPost(PATH).respondFixture(fixture);

        assertThat(yf.ticker(symbol).earningsDates()).isEmpty();
        assertThat(chartRequests()).isEmpty();
    }

    @Test
    void errorEnvelopeThrows() {
        fake.onPost(PATH).respondFixture("earnings/bad-entity.json");

        assertThatThrownBy(() -> yf.ticker("AAPL").earningsDates())
                .isInstanceOf(YFDataException.class)
                .hasMessageContaining("Internal Server Error");
    }

    @Test
    void missingResultListThrows() {
        fake.onPost(PATH).respond(200, "{\"finance\":{\"result\":null,\"error\":null}}");

        assertThatThrownBy(() -> yf.ticker("AAPL").earningsDates())
                .isInstanceOf(YFDataException.class)
                .hasMessageContaining("no result list");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 101})
    void limitOutOfRangeRejectedWithoutRequest(int limit) {
        assertThatThrownBy(() -> yf.ticker("AAPL").earningsDates(limit))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1..100");
        assertThat(visualizationRequests()).isEmpty();
    }

    @Test
    void parseLooksUpColumnsByIdAndSkipsRowsWithoutDate() {
        JsonNode root = readJson("""
                {"finance":{"result":[{"documents":[{
                  "columns":[{"id":"epsactual"},{"id":"startdatetime"},{"id":"epsestimate"}],
                  "rows":[[1.5,"2026-01-29T21:30:00.000Z",1.4],[1.0,null,1.0],[null,"2025-10-30T20:30:00.000Z",null]]
                }]}],"error":null}}
                """);

        List<EarningsDate> dates = EarningsDatesEndpoint.parse(root, "X", s -> ZoneId.of("UTC"));

        assertThat(dates).hasSize(2);
        assertThat(dates.getFirst().reportedEps()).isEqualTo(1.5);
        assertThat(dates.getFirst().epsEstimate()).isEqualTo(1.4);
        // no surprise column at all
        assertThat(dates.getFirst().surprisePercent()).isNaN();
        assertThat(dates.get(1).epsEstimate()).isNaN();
    }

    @Test
    void parseRejectsInvalidDate() {
        JsonNode root = readJson("""
                {"finance":{"result":[{"documents":[{"columns":[{"id":"startdatetime"}],"rows":[["soon"]]}]}]}}
                """);

        assertThatThrownBy(() -> EarningsDatesEndpoint.parse(root, "X", s -> ZoneId.of("UTC")))
                .isInstanceOf(YFDataException.class)
                .hasMessageContaining("soon");
    }

    @Test
    void parseRejectsMissingDateColumn() {
        JsonNode root = readJson("""
                {"finance":{"result":[{"documents":[{"columns":[{"id":"epsactual"}],"rows":[[1.0]]}]}]}}
                """);

        assertThatThrownBy(() -> EarningsDatesEndpoint.parse(root, "X", s -> ZoneId.of("UTC")))
                .isInstanceOf(YFDataException.class)
                .hasMessageContaining("startdatetime");
    }

    private List<HttpRequest> visualizationRequests() {
        return fake.requests().stream()
                .filter(r -> r.uri().getPath().equals(PATH))
                .toList();
    }

    private List<HttpRequest> chartRequests() {
        return fake.requests().stream()
                .filter(r -> r.uri().getPath().startsWith(CHART))
                .toList();
    }

    private static JsonNode readJson(String json) {
        try {
            return YahooJson.mapper().readTree(json);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
