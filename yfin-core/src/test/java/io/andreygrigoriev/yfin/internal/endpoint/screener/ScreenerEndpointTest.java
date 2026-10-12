package io.andreygrigoriev.yfin.internal.endpoint.screener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import io.andreygrigoriev.yfin.Screener;
import io.andreygrigoriev.yfin.YFinance;
import io.andreygrigoriev.yfin.exception.YFDataException;
import io.andreygrigoriev.yfin.internal.http.YahooJson;
import io.andreygrigoriev.yfin.model.ScreenerResult;
import io.andreygrigoriev.yfin.request.EquityQuery;
import io.andreygrigoriev.yfin.request.EtfQuery;
import io.andreygrigoriev.yfin.request.FundQuery;
import io.andreygrigoriev.yfin.request.SortField;
import io.andreygrigoriev.yfin.spi.Cache;
import io.andreygrigoriev.yfin.spi.HttpRequest;
import io.andreygrigoriev.yfin.testing.FakeTransport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ScreenerEndpointTest {

    private static final String SCREENER = "/v1/finance/screener";
    private static final String PREDEFINED = "/v1/finance/screener/predefined/saved";

    private final FakeTransport fake = FakeTransport.create();
    private YFinance yf;

    @BeforeEach
    void setUp() {
        fake.onGet("").respond(404, "not found");
        fake.onGet("/v1/test/getcrumb").respond(200, "crumb1");
        yf = YFinance.builder()
                .transport(fake)
                .cache(Cache.none())
                .maxRetries(0)
                .build();
    }

    @AfterEach
    void tearDown() {
        yf.close();
    }

    @Test
    void customEquityScreen() throws Exception {
        fake.onPost(SCREENER).respondFixture("screener/custom-equity.json");

        ScreenerResult r = yf.screen(
                Screener.of(EquityQuery.and(EquityQuery.gt("percentchange", 3), EquityQuery.eq("region", "us")))
                        .size(5)
                        .sort(SortField.ascending("percentchange")));

        assertThat(r.id()).isNull();
        assertThat(r.title()).isNull();
        assertThat(r.start()).isZero();
        assertThat(r.count()).isEqualTo(5);
        assertThat(r.total()).isEqualTo(1633L);
        assertThat(r.symbols()).containsExactly("CWPE", "SBKFF", "BBLS", "AHT-PD", "PLMR");
        Map<String, Object> first = r.quotes().getFirst();
        assertThat(first)
                .containsEntry("symbol", "CWPE")
                .containsEntry("shortName", "CW Petroleum Corp")
                .containsEntry("regularMarketPrice", 0.0103);
        assertThat(r.raw()).containsKeys("start", "count", "total", "quotes", "useRecords");

        HttpRequest request = screenerRequest();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.uri().getHost()).isEqualTo("query1.finance.yahoo.com");
        assertThat(request.headers().get("Content-Type")).containsExactly("application/json");
        assertThat(FakeTransport.parseQuery(request.uri().getRawQuery()))
                .containsEntry("corsDomain", List.of("finance.yahoo.com"))
                .containsEntry("formatted", List.of("false"))
                .containsEntry("lang", List.of("en-US"))
                .containsEntry("region", List.of("US"))
                .containsEntry("crumb", List.of("crumb1"));
        assertThat(request.bodyAsString())
                .contains("{\"offset\":0,\"count\":5,\"size\":5,"
                        + "\"sortField\":\"percentchange\",\"userId\":\"\",\"userIdType\":\"guid\",\"sortType\":\"ASC\","
                        + "\"quoteType\":\"EQUITY\",\"query\":{\"operator\":\"AND\",\"operands\":["
                        + "{\"operator\":\"GT\",\"operands\":[\"percentchange\",3]},"
                        + "{\"operator\":\"EQ\",\"operands\":[\"region\",\"us\"]}]}}");
    }

    @Test
    void customDefaults() {
        fake.onPost(SCREENER).respondFixture("screener/custom-empty.json");

        ScreenerResult r = yf.screen(
                EquityQuery.and(EquityQuery.eq("region", "us"), EquityQuery.gt("intradayprice", 1_000_000_000_000L)));

        assertThat(r.quotes()).isEmpty();
        assertThat(r.symbols()).isEmpty();
        assertThat(r.count()).isZero();
        assertThat(r.total()).isZero();
        JsonNode body = body(screenerRequest());
        assertThat(body.path("offset").asInt()).isZero();
        assertThat(body.path("size").asInt()).isEqualTo(25);
        assertThat(body.path("sortField").asText()).isEqualTo("ticker");
        assertThat(body.path("sortType").asText()).isEqualTo("DESC");
    }

    @Test
    void customFundScreen() {
        fake.onPost(SCREENER).respondFixture("screener/custom-fund.json");

        ScreenerResult r = yf.screen(Screener.of(FundQuery.and(
                        FundQuery.eq("categoryname", "Large Growth"),
                        FundQuery.isIn("performanceratingoverall", 4, 5),
                        FundQuery.eq("exchange", "NAS")))
                .size(3)
                .sort(SortField.descending("fundnetassets")));

        assertThat(r.symbols()).containsExactly("VIGAX", "VIGIX", "VIGRX");
        assertThat(r.total()).isEqualTo(229L);
        JsonNode body = body(screenerRequest());
        assertThat(body.path("quoteType").asText()).isEqualTo("MUTUALFUND");
        assertThat(body.path("query").path("operands").get(1).toString())
                .isEqualTo("{\"operator\":\"OR\",\"operands\":["
                        + "{\"operator\":\"EQ\",\"operands\":[\"performanceratingoverall\",4]},"
                        + "{\"operator\":\"EQ\",\"operands\":[\"performanceratingoverall\",5]}]}");
    }

    @Test
    void customEtfScreen() {
        fake.onPost(SCREENER).respondFixture("screener/custom-etf.json");

        ScreenerResult r = yf.screen(
                Screener.of(EtfQuery.and(EtfQuery.eq("region", "us"), EtfQuery.eq("categoryname", "Technology")))
                        .size(3)
                        .sort(SortField.ascending("annualreportnetexpenseratio")));

        assertThat(r.symbols()).containsExactly("GGRW", "GGTL", "XLK");
        assertThat(body(screenerRequest()).path("quoteType").asText()).isEqualTo("ETF");
    }

    @Test
    void predefinedScreen() {
        fake.onGet(PREDEFINED).respondFixture("screener/predefined-day-gainers.json");

        ScreenerResult r = yf.screen("day_gainers");

        assertThat(r.id()).isEqualTo("ec5bebb9-b7b2-4474-9e5c-3e258b61cbe6");
        assertThat(r.title()).isEqualTo("Day Gainers");
        assertThat(r.description()).isNotBlank();
        assertThat(r.count()).isEqualTo(25);
        assertThat(r.total()).isEqualTo(44L);
        assertThat(r.quotes()).hasSize(25);
        assertThat(r.symbols()).startsWith("FIGS", "FICO", "CCC");
        assertThat(r.raw()).containsKeys("canonicalName", "criteriaMeta", "predefinedScr");

        HttpRequest request = fake.requests().getLast();
        assertThat(request.method()).isEqualTo("GET");
        assertThat(FakeTransport.parseQuery(request.uri().getRawQuery()))
                .containsEntry("scrIds", List.of("day_gainers"))
                .containsEntry("corsDomain", List.of("finance.yahoo.com"))
                .containsEntry("formatted", List.of("false"))
                .doesNotContainKeys("count", "offset", "sortField");
    }

    @Test
    void predefinedWithSizeAndSort() {
        fake.onGet(PREDEFINED).respondFixture("screener/predefined-most-actives.json");

        ScreenerResult r =
                yf.screen(Screener.predefined(" most_actives ").size(5).sort(SortField.descending("dayvolume")));

        assertThat(r.title()).isEqualTo("Most Actives");
        assertThat(r.symbols()).containsExactly("ONDS", "SPCX", "NOK", "INTC", "NVDA");
        assertThat(r.total()).isEqualTo(180L);
        assertThat(FakeTransport.parseQuery(fake.requests().getLast().uri().getRawQuery()))
                .containsEntry("scrIds", List.of("most_actives"))
                .containsEntry("count", List.of("5"))
                .containsEntry("sortField", List.of("dayvolume"))
                .containsEntry("sortType", List.of("DESC"));
    }

    @Test
    void predefinedWithOffsetRunsEquivalentQuery() {
        fake.onPost(SCREENER).respondFixture("screener/predefined-offset.json");

        ScreenerResult r =
                yf.screen(Screener.predefined("day_gainers").offset(5).size(3));

        assertThat(r.start()).isEqualTo(5);
        assertThat(r.symbols()).containsExactly("SNAP", "ECO", "CHA");
        JsonNode body = body(screenerRequest());
        assertThat(body.path("offset").asInt()).isEqualTo(5);
        assertThat(body.path("size").asInt()).isEqualTo(3);
        assertThat(body.path("sortField").asText()).isEqualTo("percentchange");
        assertThat(body.path("sortType").asText()).isEqualTo("DESC");
        assertThat(body.path("quoteType").asText()).isEqualTo("EQUITY");
        assertThat(body.path("query").toString())
                .isEqualTo(YahooJson.mapper()
                        .valueToTree(
                                Screener.predefinedQuery("day_gainers").query().toMap())
                        .toString());
    }

    @Test
    void predefinedOffsetUnknownName() {
        assertThatThrownBy(() -> yf.screen(Screener.predefined("no_such_screen").offset(0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown predefined screen: no_such_screen");
    }

    @Test
    void predefinedUnknownName() {
        fake.onGet(PREDEFINED).respondFixture(404, "screener/predefined-unknown.json");

        assertThatThrownBy(() -> yf.screen("no_such_screen_xyz"))
                .isInstanceOf(YFDataException.class)
                .hasMessageContaining("No screener records found");
    }

    @Test
    void errorPayload() {
        fake.onPost(SCREENER).respondFixture(400, "screener/custom-bad-request.json");

        assertThatThrownBy(() -> yf.screen(EquityQuery.eq("region", "us")))
                .isInstanceOf(YFDataException.class)
                .hasMessageContaining("internal-error")
                .hasMessageContaining("Unsupported operator");
    }

    @Test
    void missingResult() {
        fake.onPost(SCREENER).respond(200, "{\"finance\":{\"result\":[],\"error\":null}}");

        assertThatThrownBy(() -> yf.screen(EquityQuery.eq("region", "us")))
                .isInstanceOf(YFDataException.class)
                .hasMessageContaining("screener result is missing");
    }

    @Test
    void outage() {
        fake.onPost(SCREENER).respond(200, "<html>Will be right back</html>");

        assertThatThrownBy(() -> yf.screen(EquityQuery.eq("region", "us")))
                .isInstanceOf(YFDataException.class)
                .hasMessageContaining("down");
    }

    private HttpRequest screenerRequest() {
        return fake.requests().stream()
                .filter(r -> r.uri().getPath().startsWith(SCREENER))
                .reduce((a, b) -> b)
                .orElseThrow();
    }

    private static JsonNode body(HttpRequest request) {
        try {
            return YahooJson.mapper().readTree(request.bodyAsString().orElseThrow());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
