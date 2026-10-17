package io.github.andreygrigoriev.yfin.internal.endpoint.isin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.andreygrigoriev.yfin.YFinance;
import io.github.andreygrigoriev.yfin.exception.YFDataException;
import io.github.andreygrigoriev.yfin.internal.cache.FileCache;
import io.github.andreygrigoriev.yfin.spi.Cache;
import io.github.andreygrigoriev.yfin.spi.HttpRequest;
import io.github.andreygrigoriev.yfin.spi.HttpResponse;
import io.github.andreygrigoriev.yfin.testing.FakeTransport;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IsinEndpointTest {

    private static final String SUGGEST = "/ajax/SearchController_Suggest";
    private static final String QUOTE = "/v7/finance/quote";

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
    void isinFromShortNameSearchAndCached() {
        fake.onGet(QUOTE).query("symbols", "AAPL").respondFixture("quote/aapl.json");
        fake.onGet(SUGGEST).respondFixture("isin/aapl.txt");

        assertThat(yf.ticker("AAPL").isin()).contains("US0378331005");

        HttpRequest suggest = suggestRequests().getFirst();
        assertThat(suggest.uri().getHost()).isEqualTo("markets.businessinsider.com");
        Map<String, List<String>> q = FakeTransport.parseQuery(suggest.uri().getRawQuery());
        assertThat(q).containsEntry("max_results", List.of("25")).containsEntry("query", List.of("Apple Inc."));
        assertThat(q).doesNotContainKey("crumb");
        assertThat(suggest.uri().getRawQuery()).contains("query=Apple%20Inc.");
        assertThat(cache.get("isin", "AAPL")).contains("US0378331005");
    }

    @Test
    void cacheHitSkipsRequests() {
        cache.put("isin", "AAPL", "US0378331005");

        assertThat(yf.ticker("AAPL").isin()).contains("US0378331005");
        assertThat(fake.requests()).isEmpty();
    }

    @Test
    void cachedNoneIsEmptyWithoutRequests() {
        cache.put("isin", "SAP.DE", IsinEndpoint.noneEntry(Instant.now()));

        assertThat(yf.ticker("SAP.DE").isin()).isEmpty();
        assertThat(fake.requests()).isEmpty();
    }

    @Test
    void expiredOrUntimedNoneIsLookedUpAgain() {
        fake.onGet(QUOTE).query("symbols", "AAPL").respondFixture("quote/aapl.json");
        fake.onGet(SUGGEST).respondFixture("isin/aapl.txt");

        cache.put("isin", "AAPL", IsinEndpoint.noneEntry(Instant.now().minus(IsinEndpoint.NONE_TTL.plusMinutes(1))));
        assertThat(yf.ticker("AAPL").isin()).contains("US0378331005");
        assertThat(suggestRequests()).hasSize(1);

        fake.clearRequests();
        cache.put("isin", "AAPL", "-"); // bare marker of an older version: no timestamp
        assertThat(yf.ticker("AAPL").isin()).contains("US0378331005");
        assertThat(suggestRequests()).hasSize(1);
    }

    @Test
    void yahooCookiesAreNotSentToBusinessInsiderNorPersistedFromIt() throws Exception {
        var transport = FakeTransport.create();
        transport
                .onGet("")
                .host("fc.yahoo.com")
                .respond(new HttpResponse(
                        404, Map.of("Set-Cookie", List.of("A3=yahoo-secret; Domain=.yahoo.com; Path=/")), new byte[0]));
        transport.onGet("/v1/test/getcrumb").respond(200, "crumb1");
        transport.onGet(QUOTE).query("symbols", "AAPL").respondFixture("quote/aapl.json");
        transport
                .onGet(SUGGEST)
                .respond(new HttpResponse(
                        200,
                        Map.of("Set-Cookie", List.of("bi=tracker; Path=/")),
                        java.nio.file.Files.readAllBytes(FakeTransport.DEFAULT_FIXTURE_ROOT.resolve("isin/aapl.txt"))));
        try (YFinance client = YFinance.builder()
                .transport(transport)
                .cache(cache)
                .maxRetries(0)
                .build()) {
            assertThat(client.ticker("AAPL").isin()).contains("US0378331005");
        }

        HttpRequest quote = transport.requests().stream()
                .filter(r -> r.uri().getPath().equals(QUOTE))
                .findFirst()
                .orElseThrow();
        assertThat(quote.headers().get("Cookie")).containsExactly("A3=yahoo-secret");
        HttpRequest suggest = transport.requests().stream()
                .filter(r -> r.uri().getPath().equals(SUGGEST))
                .findFirst()
                .orElseThrow();
        assertThat(suggest.headers()).doesNotContainKey("Cookie");
        assertThat(cache.get("cookie", "session"))
                .hasValueSatisfying(
                        session -> assertThat(session).contains("yahoo-secret").doesNotContain("tracker"));
    }

    @Test
    void noMatchIsEmptyAndCachedAsNone() {
        // SAP.DE's padded short name is not in the suggestions: yfinance gives "-"
        fake.onGet(QUOTE).query("symbols", "SAP.DE").respondFixture("quote/sap-de.json");
        fake.onGet(SUGGEST).respondFixture("isin/sap-de.txt");

        assertThat(yf.ticker("SAP.DE").isin()).isEmpty();
        assertThat(cache.get("isin", "SAP.DE"))
                .hasValueSatisfying(v -> assertThat(v).startsWith("-@"));

        fake.clearRequests();
        assertThat(yf.ticker("SAP.DE").isin()).isEmpty();
        assertThat(fake.requests()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"BTC-USD", "^GSPC", "EURUSD=X-"})
    void nonEquitySymbolsMakeNoRequest(String symbol) {
        assertThat(yf.ticker(symbol).isin()).isEmpty();
        assertThat(fake.requests()).isEmpty();
    }

    @Test
    void unknownSymbolWithoutQuoteIsEmptyAndNotCached() {
        fake.onGet(QUOTE).respondFixture("quote/not-found.json");

        assertThat(yf.ticker("ZZZZNOTREAL").isin()).isEmpty();
        assertThat(suggestRequests()).isEmpty();
        assertThat(cache.get("isin", "ZZZZNOTREAL")).isEmpty();
    }

    @Test
    void httpErrorThrows() {
        fake.onGet(QUOTE).query("symbols", "AAPL").respondFixture("quote/aapl.json");
        fake.onGet(SUGGEST).respond(403, "forbidden");

        assertThatThrownBy(() -> yf.ticker("AAPL").isin()).isInstanceOf(YFDataException.class);
        assertThat(cache.get("isin", "AAPL")).isEmpty();
    }

    @Test
    void parseFallsBackToFirstUntaggedEntryWhenNameMatches() throws Exception {
        String data = java.nio.file.Files.readString(FakeTransport.DEFAULT_FIXTURE_ROOT.resolve("isin/aapl.txt"));

        assertThat(IsinEndpoint.parse(data, "AAPL", "Apple Inc.")).isEqualTo("US0378331005");
        // ticker not listed but the name is: yfinance takes the first "|ISIN entry
        assertThat(IsinEndpoint.parse(data, "APC.DE", "Apple Inc.")).isEqualTo("CA0379741022");
        assertThat(IsinEndpoint.parse(data, "APC.DE", "Banana Corp")).isEqualTo("-");
    }

    @Test
    void parseEmptySuggestions() throws Exception {
        String data = java.nio.file.Files.readString(FakeTransport.DEFAULT_FIXTURE_ROOT.resolve("isin/unknown.txt"));

        assertThat(IsinEndpoint.parse(data, "ZZZZNOTREAL", "ZZZZNOTREAL")).isEqualTo("-");
    }

    private List<HttpRequest> suggestRequests() {
        return fake.requests().stream()
                .filter(r -> r.uri().getPath().equals(SUGGEST))
                .toList();
    }
}
