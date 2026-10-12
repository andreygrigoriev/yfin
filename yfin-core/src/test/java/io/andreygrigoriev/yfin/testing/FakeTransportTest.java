package io.andreygrigoriev.yfin.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.andreygrigoriev.yfin.spi.HttpRequest;
import io.andreygrigoriev.yfin.spi.HttpResponse;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FakeTransportTest {

    private static final String BASE = "https://query1.finance.yahoo.com";

    private static HttpRequest get(String pathAndQuery) {
        return HttpRequest.get(URI.create(BASE + pathAndQuery));
    }

    @Test
    void servesFixtureForMatchingPathAndQuery() {
        var transport = FakeTransport.create()
                .onGet("/v8/finance/chart/AAPL")
                .query("interval", "1d")
                .respondFixture("testing/chart-ok.json");

        HttpResponse response = transport.send(get("/v8/finance/chart/AAPL?range=1mo&interval=1d"));

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.bodyAsString()).contains("\"symbol\":\"AAPL\"");
        assertThat(response.header("content-type")).hasValue("application/json;charset=utf-8");
    }

    @Test
    void servesInlineBodyAndStatus() {
        var transport = FakeTransport.create().onGet("/v1/test/getcrumb").respond(429, "Too Many Requests");

        HttpResponse response = transport.send(get("/v1/test/getcrumb"));

        assertThat(response.status()).isEqualTo(429);
        assertThat(response.bodyAsString()).isEqualTo("Too Many Requests");
    }

    @Test
    void servesFixtureWithNonSuccessStatus() {
        var transport =
                FakeTransport.create().onGet("/v8/finance/chart/NOPE").respondFixture(404, "testing/chart-error.json");

        HttpResponse response = transport.send(get("/v8/finance/chart/NOPE"));

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.bodyAsString()).contains("No data found");
    }

    @Test
    void firstMatchingRuleWins() {
        var transport = FakeTransport.create()
                .onGet("/q")
                .query("symbols", "AAPL")
                .respond(200, "specific")
                .onGet("/q")
                .respond(200, "fallback");

        assertThat(transport.send(get("/q?symbols=AAPL")).bodyAsString()).isEqualTo("specific");
        assertThat(transport.send(get("/q?symbols=MSFT")).bodyAsString()).isEqualTo("fallback");
        assertThat(transport.send(get("/q")).bodyAsString()).isEqualTo("fallback");
    }

    @Test
    void methodMustMatch() {
        var transport = FakeTransport.create().onPost("/v1/finance/screener").respond(200, "{}");

        assertThatThrownBy(() -> transport.send(get("/v1/finance/screener")))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("no rule matches GET");
        assertThat(transport
                        .send(HttpRequest.postJson(URI.create(BASE + "/v1/finance/screener"), "{}"))
                        .bodyAsString())
                .isEqualTo("{}");
    }

    @Test
    void queryValuesAreDecodedBeforeMatching() {
        var transport = FakeTransport.create()
                .onGet("/v10/finance/quoteSummary/AAPL")
                .query("modules", "price,summaryDetail")
                .hasQuery("crumb")
                .respond(200, "ok");

        assertThat(transport
                        .send(get("/v10/finance/quoteSummary/AAPL?modules=price%2CsummaryDetail&crumb=a%2Fb"))
                        .bodyAsString())
                .isEqualTo("ok");
        assertThatThrownBy(() -> transport.send(get("/v10/finance/quoteSummary/AAPL?modules=price%2CsummaryDetail")))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void customQueryPredicate() {
        var transport = FakeTransport.create()
                .onGet("/v7/finance/quote")
                .queryMatching(q -> q.getOrDefault("symbols", List.of()).contains("AAPL,MSFT"))
                .respond(200, "both");

        assertThat(transport.send(get("/v7/finance/quote?symbols=AAPL%2CMSFT")).bodyAsString())
                .isEqualTo("both");
    }

    @Test
    void sequencedResponsesRepeatTheLast() {
        var transport = FakeTransport.create()
                .onGet("/chart")
                .thenRespond(429, "slow down")
                .thenRespond(500, "oops")
                .thenRespond(200, "ok")
                .register();

        assertThat(List.of(1, 2, 3, 4).stream()
                        .map(i -> transport.send(get("/chart")).status()))
                .containsExactly(429, 500, 200, 200);
    }

    @Test
    void unmatchedRequestFailsLoudlyListingRules() {
        var transport = FakeTransport.create().onGet("/v8/finance/chart/AAPL").respond(200, "{}");

        assertThatThrownBy(() -> transport.send(get("/v8/finance/chart/MSFT?interval=1d")))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("no rule matches GET " + BASE + "/v8/finance/chart/MSFT?interval=1d")
                .hasMessageContaining("GET /v8/finance/chart/AAPL");
    }

    @Test
    void unmatchedRequestWithNoRules() {
        assertThatThrownBy(() -> FakeTransport.create().send(get("/x")))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("registered rules: none");
    }

    @Test
    void missingFixtureFailsAtRegistration() {
        var builder = FakeTransport.create().onGet("/x");

        assertThatThrownBy(() -> builder.respondFixture("testing/does-not-exist.json"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("fixture not found")
                .hasMessageContaining("does-not-exist.json");
    }

    @Test
    void ruleWithoutResponseIsRejected() {
        assertThatThrownBy(() -> FakeTransport.create().onGet("/x").register())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void customFixtureRoot(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("custom.json"), "{\"custom\":true}");
        var transport = FakeTransport.withFixtureRoot(dir).onGet("/c").respondFixture("custom.json");

        assertThat(transport.send(get("/c")).bodyAsString()).isEqualTo("{\"custom\":true}");
    }

    @Test
    void recordsReceivedRequestsInOrder() {
        var transport =
                FakeTransport.create().onGet("/a").respond(200, "a").onGet("/b").respond(200, "b");
        HttpRequest first = get("/a?x=1").withHeader("Cookie", "A3=abc");
        HttpRequest second = get("/b");

        transport.send(first);
        transport.send(second);

        assertThat(transport.requests()).containsExactly(first, second);
        assertThat(transport.requests().getFirst().header("cookie")).hasValue("A3=abc");
        transport.clearRequests();
        assertThat(transport.requests()).isEmpty();
    }

    @Test
    void recordsUnmatchedRequestsToo() {
        var transport = FakeTransport.create();
        HttpRequest request = get("/missing");

        assertThatThrownBy(() -> transport.send(request)).isInstanceOf(AssertionError.class);
        assertThat(transport.singleRequest()).isEqualTo(request);
    }

    @Test
    void singleRequestFailsOnZeroRequests() {
        assertThatThrownBy(() -> FakeTransport.create().singleRequest())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("got 0");
    }

    @Test
    void parseQueryHandlesEdgeCases() {
        assertThat(FakeTransport.parseQuery(null)).isEmpty();
        assertThat(FakeTransport.parseQuery("")).isEmpty();
        assertThat(FakeTransport.parseQuery("a=1&a=2&flag&&b=x%20y+z"))
                .isEqualTo(Map.of("a", List.of("1", "2"), "flag", List.of(""), "b", List.of("x y z")));
    }
}
