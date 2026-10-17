package io.github.andreygrigoriev.yfin.internal.endpoint.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.andreygrigoriev.yfin.Lookup;
import io.github.andreygrigoriev.yfin.YFinance;
import io.github.andreygrigoriev.yfin.exception.YFDataException;
import io.github.andreygrigoriev.yfin.model.LookupResult;
import io.github.andreygrigoriev.yfin.request.LookupType;
import io.github.andreygrigoriev.yfin.spi.Cache;
import io.github.andreygrigoriev.yfin.spi.HttpRequest;
import io.github.andreygrigoriev.yfin.testing.FakeTransport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class LookupEndpointTest {

    private static final String LOOKUP = "/v1/finance/lookup";

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
    void allTypes() {
        fake.onGet(LOOKUP).respondFixture("lookup/aapl-all.json");

        List<LookupResult> results = yf.lookup(Lookup.of("aapl").count(5));

        assertThat(results).hasSize(5);
        assertThat(results).extracting(LookupResult::symbol).startsWith("AAPL", "AAPU", "APLY");
        LookupResult aapl = results.getFirst();
        assertThat(aapl.shortName()).isEqualTo("Apple Inc.");
        assertThat(aapl.quoteType()).isEqualTo("equity");
        assertThat(aapl.exchange()).isEqualTo("NMS");
        assertThat(aapl.regularMarketPrice()).isEqualTo(339.9800109863281);
        assertThat(aapl.regularMarketChange()).isEqualTo(3.339996337890625);
        assertThat(aapl.regularMarketPercentChange()).isEqualTo(0.9921566843986511);
        assertThat(aapl.rank()).isEqualTo(176832L);
        assertThat(aapl.raw()).containsEntry("industryName", "Technology");
        assertThat(results.get(1).raw()).doesNotContainKey("industryName");

        Map<String, List<String>> q =
                FakeTransport.parseQuery(lookupRequest().uri().getRawQuery());
        assertThat(q)
                .containsEntry("query", List.of("aapl"))
                .containsEntry("type", List.of("all"))
                .containsEntry("start", List.of("0"))
                .containsEntry("count", List.of("5"))
                .containsEntry("formatted", List.of("false"))
                .containsEntry("fetchPricingData", List.of("true"))
                .containsEntry("lang", List.of("en-US"))
                .containsEntry("region", List.of("US"));
        assertThat(lookupRequest().uri().getHost()).isEqualTo("query1.finance.yahoo.com");
    }

    @Test
    void etfFilter() {
        fake.onGet(LOOKUP).query("type", "etf").respondFixture("lookup/gold-etf.json");

        List<LookupResult> results =
                yf.lookup(Lookup.of("gold").type(LookupType.ETF).count(3));

        assertThat(results).hasSize(3);
        assertThat(results.getFirst().symbol()).isEqualTo("GLD");
        assertThat(results.getFirst().shortName()).isEqualTo("SPDR Gold Shares");
        assertThat(results).allSatisfy(r -> assertThat(r.quoteType()).isEqualTo("etf"));
    }

    @Test
    void cryptocurrencyFilter() {
        fake.onGet(LOOKUP).query("type", "cryptocurrency").respondFixture("lookup/btc-crypto.json");

        List<LookupResult> results =
                yf.lookup(Lookup.of("btc").type(LookupType.CRYPTOCURRENCY).count(3));

        assertThat(results.getFirst().symbol()).isEqualTo("BTC-USD");
        assertThat(results.getFirst().exchange()).isEqualTo("CCC");
    }

    @Test
    void defaultsAreAllAnd25() {
        fake.onGet(LOOKUP).respondFixture("lookup/empty.json");

        yf.lookup(" aapl ");

        Map<String, List<String>> q =
                FakeTransport.parseQuery(lookupRequest().uri().getRawQuery());
        assertThat(q)
                .containsEntry("query", List.of("aapl"))
                .containsEntry("type", List.of("all"))
                .containsEntry("count", List.of("25"));
    }

    @ParameterizedTest
    @CsvSource({
        "ALL, all",
        "STOCK, equity",
        "MUTUAL_FUND, mutualfund",
        "ETF, etf",
        "INDEX, index",
        "FUTURE, future",
        "CURRENCY, currency",
        "CRYPTOCURRENCY, cryptocurrency"
    })
    void typesMapToYahooTypes(LookupType type, String yahooType) {
        assertThat(type.yahooType()).isEqualTo(yahooType);
        fake.onGet(LOOKUP).respondFixture("lookup/empty.json");

        yf.lookup(Lookup.of("x").type(type));

        assertThat(FakeTransport.parseQuery(lookupRequest().uri().getRawQuery()))
                .containsEntry("type", List.of(yahooType));
    }

    @Test
    void emptyResults() {
        fake.onGet(LOOKUP).respondFixture("lookup/empty.json");

        assertThat(yf.lookup("zzzzqqqxnotreal")).isEmpty();
    }

    @Test
    void missingResultIsEmpty() {
        fake.onGet(LOOKUP).respond(200, "{\"finance\":{\"result\":[],\"error\":null}}");

        assertThat(yf.lookup("aapl")).isEmpty();
    }

    @Test
    void documentsWithoutSymbolAreDropped() {
        fake.onGet(LOOKUP)
                .respond(
                        200, "{\"finance\":{\"result\":[{\"documents\":[{\"shortName\":\"x\"},{\"symbol\":\"A\"}]}]}}");

        assertThat(yf.lookup("a")).extracting(LookupResult::symbol).containsExactly("A");
    }

    @Test
    void errorPayloadThrows() {
        fake.onGet(LOOKUP).respondFixture(400, "lookup/bad-type.json");

        assertThatThrownBy(() -> yf.lookup("aapl"))
                .isInstanceOf(YFDataException.class)
                .hasMessageContaining("Bad Request")
                .hasMessageContaining("Valid types");
    }

    @Test
    void outagePageThrows() {
        fake.onGet(LOOKUP).respond(200, "<html>Will be right back</html>");

        assertThatThrownBy(() -> yf.lookup("aapl")).isInstanceOf(YFDataException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  "})
    void blankQueryIsRejected(String query) {
        assertThatThrownBy(() -> yf.lookup(query)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Lookup.of(query)).isInstanceOf(IllegalArgumentException.class);
        assertThat(fake.requests()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void nonPositiveCountIsRejected(int count) {
        assertThatThrownBy(() -> Lookup.of("aapl").count(count)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void optionsAreImmutableValues() {
        Lookup base = Lookup.of("gold");
        Lookup etf = base.type(LookupType.ETF);

        assertThat(base.type()).isEqualTo(LookupType.ALL);
        assertThat(base.count()).isEqualTo(Lookup.DEFAULT_COUNT);
        assertThat(etf).isNotEqualTo(base).isEqualTo(Lookup.of("gold").type(LookupType.ETF));
        assertThat(etf.hashCode())
                .isEqualTo(Lookup.of("gold").type(LookupType.ETF).hashCode());
    }

    private HttpRequest lookupRequest() {
        return fake.requests().stream()
                .filter(r -> r.uri().getPath().equals(LOOKUP))
                .reduce((a, b) -> b)
                .orElseThrow();
    }
}
