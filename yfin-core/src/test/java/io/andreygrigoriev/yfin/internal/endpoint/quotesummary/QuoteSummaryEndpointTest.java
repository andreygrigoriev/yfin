package io.andreygrigoriev.yfin.internal.endpoint.quotesummary;

import static io.andreygrigoriev.yfin.internal.endpoint.quotesummary.QuoteSummaryModule.ASSET_PROFILE;
import static io.andreygrigoriev.yfin.internal.endpoint.quotesummary.QuoteSummaryModule.DEFAULT_KEY_STATISTICS;
import static io.andreygrigoriev.yfin.internal.endpoint.quotesummary.QuoteSummaryModule.FINANCIAL_DATA;
import static io.andreygrigoriev.yfin.internal.endpoint.quotesummary.QuoteSummaryModule.QUOTE_TYPE;
import static io.andreygrigoriev.yfin.internal.endpoint.quotesummary.QuoteSummaryModule.SUMMARY_DETAIL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import io.andreygrigoriev.yfin.exception.YFDataException;
import io.andreygrigoriev.yfin.exception.YFSymbolNotFoundException;
import io.andreygrigoriev.yfin.internal.http.RequestLimiter;
import io.andreygrigoriev.yfin.internal.http.RetryPolicy;
import io.andreygrigoriev.yfin.internal.http.YahooJson;
import io.andreygrigoriev.yfin.internal.http.YahooSession;
import io.andreygrigoriev.yfin.spi.Cache;
import io.andreygrigoriev.yfin.spi.HttpRequest;
import io.andreygrigoriev.yfin.spi.HttpResponse;
import io.andreygrigoriev.yfin.testing.FakeTransport;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QuoteSummaryEndpointTest {

    private static final String PATH = "/v10/finance/quoteSummary/";

    private final FakeTransport fake = FakeTransport.create();
    private QuoteSummaryEndpoint endpoint;

    @BeforeEach
    void setUp() {
        fake.onGet("").respond(404, "not found");
        fake.onGet("/v1/test/getcrumb").respond(200, "crumb1");
        YahooSession session = YahooSession.create(fake, Cache.none(), new RequestLimiter(1), RetryPolicy.of(0));
        endpoint = new QuoteSummaryEndpoint(session);
    }

    private static JsonNode json(String text) throws Exception {
        return YahooJson.mapper().readTree(text);
    }

    @Test
    void fetchesRequestedModulesWithYfinanceParameters() {
        fake.onGet(PATH + "AAPL").respondFixture("quoteSummary/aapl.json");

        Map<QuoteSummaryModule, JsonNode> modules = endpoint.fetch("AAPL", InfoMapper.INFO_MODULES);

        assertThat(modules)
                .containsOnlyKeys(FINANCIAL_DATA, QUOTE_TYPE, DEFAULT_KEY_STATISTICS, ASSET_PROFILE, SUMMARY_DETAIL);
        // response order is kept
        assertThat(modules.keySet()).first().isEqualTo(ASSET_PROFILE);
        assertThat(modules.get(ASSET_PROFILE).path("sector").asText()).isEqualTo("Technology");

        HttpRequest request = fake.requests().getLast();
        assertThat(request.uri().getHost()).isEqualTo("query2.finance.yahoo.com");
        assertThat(FakeTransport.parseQuery(request.uri().getRawQuery()))
                .containsEntry(
                        "modules", List.of("summaryDetail,assetProfile,quoteType,defaultKeyStatistics,financialData"))
                .containsEntry("formatted", List.of("false"))
                .containsEntry("corsDomain", List.of("finance.yahoo.com"))
                .containsEntry("symbol", List.of("AAPL"))
                .containsEntry("lang", List.of("en-US"))
                .containsEntry("region", List.of("US"))
                .containsEntry("crumb", List.of("crumb1"));
    }

    @Test
    void unwrapsRawFmtValueObjectsInFetchedModules() {
        fake.onGet(PATH + "AAPL").respondFixture("quoteSummary/aapl.json");

        JsonNode officer = endpoint.fetch("AAPL", Set.of(ASSET_PROFILE))
                .get(ASSET_PROFILE)
                .path("companyOfficers")
                .path(0);

        assertThat(officer.path("totalPay").isNumber()).isTrue();
        assertThat(officer.path("totalPay").longValue()).isEqualTo(16_759_518L);
        assertThat(officer.path("exercisedValue").longValue()).isZero();
    }

    @Test
    void onlyRequestedModulesAreReturned() {
        fake.onGet(PATH + "AAPL").respondFixture("quoteSummary/aapl.json");

        assertThat(endpoint.fetch("AAPL", Set.of(QUOTE_TYPE))).containsOnlyKeys(QUOTE_TYPE);
    }

    @Test
    void missingModulesAreAbsent() {
        fake.onGet(PATH + "^GSPC").respondFixture("quoteSummary/gspc.json");

        Map<QuoteSummaryModule, JsonNode> modules = endpoint.fetch("^GSPC", InfoMapper.INFO_MODULES);

        assertThat(modules).containsOnlyKeys(SUMMARY_DETAIL, DEFAULT_KEY_STATISTICS, QUOTE_TYPE);
        assertThat(fake.requests().getLast().uri().getRawPath()).isEqualTo(PATH + "%5EGSPC");
    }

    @Test
    void notFoundSymbol() {
        fake.onGet(PATH + "NOSUCHSYMBOLXYZ").respondFixture(404, "quoteSummary/not-found.json");

        assertThatThrownBy(() -> endpoint.fetch("NOSUCHSYMBOLXYZ", InfoMapper.INFO_MODULES))
                .isInstanceOf(YFSymbolNotFoundException.class)
                .hasMessageContaining("NOSUCHSYMBOLXYZ");
    }

    @Test
    void noFundamentalsDataIsEmptyNotAnError() {
        fake.onGet(PATH + "SPY").respondFixture(404, "quoteSummary/holders/spy.json");

        assertThat(endpoint.fetch("SPY", Set.of(QuoteSummaryModule.MAJOR_HOLDERS_BREAKDOWN)))
                .isEmpty();
    }

    @Test
    void isNoFundamentalsOnlyMatchesThat404() {
        String noData = """
                {"quoteSummary":{"result":null,"error":{"code":"Not Found","description":"No fundamentals data found for symbol: SPY"}}}""";
        assertThat(QuoteSummaryEndpoint.isNoFundamentals(HttpResponse.of(404, noData)))
                .isTrue();
        assertThat(QuoteSummaryEndpoint.isNoFundamentals(HttpResponse.of(200, noData)))
                .isFalse();
        assertThat(QuoteSummaryEndpoint.isNoFundamentals(HttpResponse.of(404, """
                        {"quoteSummary":{"result":null,"error":{"code":"Not Found","description":"Quote not found for symbol: X"}}}""")))
                .isFalse();
        assertThat(QuoteSummaryEndpoint.isNoFundamentals(HttpResponse.of(404, "not json {")))
                .isFalse();
    }

    @Test
    void otherErrorEnvelopeIsDataException() {
        fake.onGet(PATH + "AAPL").respond(400, """
                        {"quoteSummary":{"result":null,"error":{"code":"Bad Request","description":"Unsupported modules"}}}""");

        assertThatThrownBy(() -> endpoint.fetch("AAPL", Set.of(QUOTE_TYPE)))
                .isInstanceOf(YFDataException.class)
                .isNotInstanceOf(YFSymbolNotFoundException.class)
                .hasMessageContaining("Bad Request");
    }

    @Test
    void emptyModuleSetIsRejected() {
        assertThatThrownBy(() -> endpoint.fetch("AAPL", Set.of())).isInstanceOf(IllegalArgumentException.class);
        assertThat(fake.requests()).isEmpty();
    }

    @Test
    void parseHandlesEmptyAndMalformedResults() throws Exception {
        assertThat(QuoteSummaryEndpoint.parse(
                        json("{\"quoteSummary\":{\"result\":[],\"error\":null}}"), Set.of(QUOTE_TYPE)))
                .isEmpty();
        assertThat(QuoteSummaryEndpoint.parse(json("{\"quoteSummary\":{\"result\":null}}"), Set.of(QUOTE_TYPE)))
                .isEmpty();
        assertThatThrownBy(() -> QuoteSummaryEndpoint.parse(json("{\"chart\":{}}"), Set.of(QUOTE_TYPE)))
                .isInstanceOf(YFDataException.class);
        assertThatThrownBy(() ->
                        QuoteSummaryEndpoint.parse(json("{\"quoteSummary\":{\"result\":[1]}}"), Set.of(QUOTE_TYPE)))
                .isInstanceOf(YFDataException.class);
    }

    @Test
    void unwrap() throws Exception {
        JsonNode node = QuoteSummaryEndpoint.unwrap(json("""
                {"a":{"raw":1.5,"fmt":"1.50"},"b":{"raw":7,"longFmt":"7"},"c":{},"d":"x\\u00a0y",
                 "e":[{"raw":2,"fmt":"2"},{"f":{"raw":3,"fmt":null}}],"g":{"raw":"no fmt"},"h":null}"""));

        assertThat(node.path("a").doubleValue()).isEqualTo(1.5);
        assertThat(node.path("b").longValue()).isEqualTo(7);
        assertThat(node.path("c").isNull()).isTrue();
        assertThat(node.path("d").asText()).isEqualTo("x y");
        assertThat(node.path("e").path(0).intValue()).isEqualTo(2);
        assertThat(node.path("e").path(1).path("f").intValue()).isEqualTo(3);
        // an object with only "raw" is not a Yahoo value object
        assertThat(node.path("g").path("raw").asText()).isEqualTo("no fmt");
        assertThat(node.path("h").isNull()).isTrue();
    }

    @Test
    void moduleNames() {
        assertThat(QuoteSummaryModule.values()).hasSize(34);
        assertThat(QuoteSummaryModule.fromYahooName("defaultKeyStatistics")).contains(DEFAULT_KEY_STATISTICS);
        assertThat(QuoteSummaryModule.fromYahooName("nope")).isEmpty();
    }
}
