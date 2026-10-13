package io.andreygrigoriev.yfin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import io.andreygrigoriev.yfin.exception.YFDataException;
import io.andreygrigoriev.yfin.exception.YFSymbolNotFoundException;
import io.andreygrigoriev.yfin.internal.endpoint.quotesummary.InfoMapper;
import io.andreygrigoriev.yfin.internal.endpoint.quotesummary.QuoteSummaryEndpoint;
import io.andreygrigoriev.yfin.internal.endpoint.quotesummary.QuoteSummaryModule;
import io.andreygrigoriev.yfin.internal.http.YahooJson;
import io.andreygrigoriev.yfin.model.TickerInfo;
import io.andreygrigoriev.yfin.spi.Cache;
import io.andreygrigoriev.yfin.testing.FakeTransport;
import java.math.BigInteger;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TickerInfoTest {

    private static final String QUOTE_SUMMARY = "/v10/finance/quoteSummary/";
    private static final String QUOTE = "/v7/finance/quote";
    private static final String TIMESERIES = "/ws/fundamentals-timeseries/v1/finance/timeseries/";

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

    private TickerInfo info(String symbol, String fixture) {
        fake.onGet(QUOTE_SUMMARY + symbol).respondFixture("quoteSummary/" + fixture);
        fake.onGet(QUOTE).query("symbols", symbol).respondFixture("quote/" + fixture);
        servePeg(symbol);
        return yf.ticker(symbol).info();
    }

    /** Recorded trailingPegRatio responses: AAPL has values, SPY (like any non-equity) none. */
    private void servePeg(String symbol) {
        fake.onGet(TIMESERIES + symbol)
                .query("type", "trailingPegRatio")
                .respondFixture("timeseries/peg/" + (symbol.equals("AAPL") ? "aapl.json" : "spy.json"));
    }

    @Test
    void equity() {
        TickerInfo info = info("AAPL", "aapl.json");

        assertThat(info.symbol()).isEqualTo("AAPL");
        assertThat(info.quoteType()).isEqualTo("EQUITY");
        assertThat(info.longName()).isEqualTo("Apple Inc.");
        assertThat(info.sector()).isEqualTo("Technology");
        assertThat(info.industry()).isEqualTo("Consumer Electronics");
        assertThat(info.country()).isEqualTo("United States");
        assertThat(info.fullTimeEmployees()).isEqualTo(150_000L);
        assertThat(info.currency()).isEqualTo("USD");
        assertThat(info.financialCurrency()).isEqualTo("USD");
        assertThat(info.exchange()).isEqualTo("NMS");
        assertThat(info.exchangeTimezoneName()).isEqualTo("America/New_York");
        // the /v7 quote wins over summaryDetail, as in yfinance
        assertThat(info.marketCap()).isEqualTo(4_969_172_303_872L);
        assertThat(info.dividendYield()).isEqualTo(0.32);
        assertThat(info.trailingPE()).isEqualTo(39.047016);
        assertThat(info.forwardPE()).isEqualTo(35.513283);
        assertThat(info.beta()).isEqualTo(1.069);
        assertThat(info.sharesOutstanding()).isEqualTo(14_594_180_000L);
        assertThat(info.recommendationKey()).isEqualTo("buy");
        assertThat(info.currentPrice()).isEqualTo(340.478);
        assertThat(info.regularMarketPrice()).isEqualTo(340.49);
        assertThat(info.fiftyTwoWeekHigh()).isEqualTo(345.34);
        assertThat(info.exDividendDate()).isEqualTo(Instant.ofEpochSecond(1_786_320_000L));
        assertThat(info.totalRevenue()).isPositive();
        // fund-only fields are absent
        assertThat(info.totalAssets()).isNull();
        assertThat(info.fundFamily()).isNull();
    }

    @Test
    void equityRawMatchesYfinanceFlattening() {
        TickerInfo info = info("AAPL", "aapl.json");

        // same key count yfinance 1.7.0 produces for these responses
        assertThat(info.raw()).hasSize(183);
        // maxAge 1 (days) becomes seconds
        assertThat(info.raw()).containsEntry("maxAge", 86_400L).containsEntry("priceHint", 2L);
        // nested values stay nested, with {raw, fmt} unwrapped
        assertThat(info.raw().get("companyOfficers")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        var officer = (Map<String, Object>) ((List<?>) info.raw().get("companyOfficers")).getFirst();
        assertThat(officer).containsEntry("totalPay", 16_759_518L);
        assertThat(info.raw().get("corporateActions")).isEqualTo(List.of());
        assertThatThrownBy(() -> info.raw().put("x", 1)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void etf() {
        TickerInfo info = info("SPY", "spy.json");

        assertThat(info.quoteType()).isEqualTo("ETF");
        assertThat(info.exchange()).isEqualTo("PCX");
        assertThat(info.category()).isEqualTo("Large Blend");
        assertThat(info.fundFamily()).isEqualTo("State Street Investment Management");
        assertThat(info.totalAssets()).isEqualTo(809_216_114_688.0);
        assertThat(info.yield()).isEqualTo(0.0099);
        assertThat(info.trailingPE()).isEqualTo(25.1476);
        assertThat(info.sector()).isNull();
        assertThat(info.marketCap()).isNull();
        assertThat(info.raw()).hasSize(99);
    }

    @Test
    void mutualFund() {
        TickerInfo info = info("AGTHX", "agthx.json");

        assertThat(info.quoteType()).isEqualTo("MUTUALFUND");
        assertThat(info.longName()).isEqualTo("American Funds Growth Fd of Amer A");
        assertThat(info.exchange()).isEqualTo("NAS");
        assertThat(info.totalAssets()).isEqualTo(351_399_346_176.0);
        assertThat(info.sharesOutstanding()).isEqualTo(3_540_239_872L);
        assertThat(info.beta()).isNull();
        assertThat(info.raw()).hasSize(90);
    }

    @Test
    void crypto() {
        TickerInfo info = info("BTC-USD", "btc-usd.json");

        assertThat(info.quoteType()).isEqualTo("CRYPTOCURRENCY");
        assertThat(info.exchange()).isEqualTo("CCC");
        assertThat(info.marketCap()).isEqualTo(1_660_562_440_192L);
        assertThat(info.sharesOutstanding()).isNull();
        assertThat(info.trailingPE()).isNull();
        assertThat(info.financialCurrency()).isNull();
        assertThat(info.raw()).hasSize(92);
    }

    @Test
    void indexWithMissingModules() {
        // ^GSPC has no assetProfile and no financialData module: their fields are null
        TickerInfo info = info("^GSPC", "gspc.json");

        assertThat(info.symbol()).isEqualTo("^GSPC");
        assertThat(info.quoteType()).isEqualTo("INDEX");
        assertThat(info.longName()).isEqualTo("S&P 500");
        assertThat(info.regularMarketPrice()).isEqualTo(7810.14);
        assertThat(info.sector()).isNull();
        assertThat(info.longBusinessSummary()).isNull();
        assertThat(info.totalRevenue()).isNull();
        assertThat(info.recommendationKey()).isNull();
        assertThat(info.raw()).hasSize(72);
    }

    @Test
    void quoteMissingStillGivesQuoteSummaryFields() {
        fake.onGet(QUOTE_SUMMARY + "AAPL").respondFixture("quoteSummary/aapl.json");
        fake.onGet(QUOTE).query("symbols", "AAPL").respondFixture("quote/not-found.json");
        servePeg("AAPL");

        TickerInfo info = yf.ticker("AAPL").info();

        assertThat(info.sector()).isEqualTo("Technology");
        assertThat(info.marketCap()).isEqualTo(4_968_997_191_680L); // summaryDetail's
        assertThat(info.dividendYield()).isEqualTo(0.0032);
        assertThat(info.marketState()).isNull(); // only in the quote
    }

    @Test
    void noFundamentalsSkipsTheTrailingPegRequest() {
        // synthetic: Yahoo's "No fundamentals data found" answer, as for symbols without modules
        fake.onGet(QUOTE_SUMMARY + "AAPL").respond(404, """
                {"quoteSummary":{"result":null,"error":{"code":"Not Found",\
                "description":"No fundamentals data found for symbol: AAPL"}}}""");
        fake.onGet(QUOTE).query("symbols", "AAPL").respondFixture("quote/aapl.json");

        TickerInfo info = yf.ticker("AAPL").info();

        assertThat(info.marketState()).isNotNull(); // from the quote
        assertThat(info.trailingPegRatio()).isNull();
        assertThat(fake.requests()).noneMatch(r -> r.uri().getPath().startsWith(TIMESERIES));
    }

    @Test
    void trailingPegRatioFromTimeseries() {
        TickerInfo info = info("AAPL", "aapl.json");

        // the last point of the half-year series, added after every other key, as in yfinance
        assertThat(info.trailingPegRatio()).isEqualTo(2.706);
        assertThat(List.copyOf(info.raw().keySet()).getLast()).isEqualTo("trailingPegRatio");
        var request = fake.requests().stream()
                .filter(r -> r.uri().getPath().equals(TIMESERIES + "AAPL"))
                .findFirst()
                .orElseThrow();
        Map<String, List<String>> q = FakeTransport.parseQuery(request.uri().getRawQuery());
        assertThat(q.get("symbol")).containsExactly("AAPL");
        long period1 = Long.parseLong(q.get("period1").getFirst());
        long period2 = Long.parseLong(q.get("period2").getFirst());
        assertThat(period1 % 86_400).isZero();
        assertThat(period2 - period1).isEqualTo((182 + 1) * 86_400L);
    }

    @Test
    void noTrailingPegRatioIsAbsent() {
        TickerInfo info = info("SPY", "spy.json");

        assertThat(info.trailingPegRatio()).isNull();
        assertThat(info.raw()).doesNotContainKey("trailingPegRatio");
    }

    @Test
    void notFoundSymbol() {
        fake.onGet(QUOTE_SUMMARY + "NOSUCHSYMBOLXYZ").respondFixture(404, "quoteSummary/not-found.json");
        fake.onGet(QUOTE).query("symbols", "NOSUCHSYMBOLXYZ").respondFixture("quote/not-found.json");

        assertThatThrownBy(() -> yf.ticker("NOSUCHSYMBOLXYZ").info())
                .isInstanceOf(YFSymbolNotFoundException.class)
                .hasMessageContaining("Quote not found");
    }

    @Test
    void errorEnvelopeIsDataException() {
        fake.onGet(QUOTE_SUMMARY + "AAPL").respond(400, """
                        {"finance":{"result":null,"error":{"code":"Bad Request","description":"boom"}}}""");

        assertThatThrownBy(() -> yf.ticker("AAPL").info())
                .isInstanceOf(YFDataException.class)
                .isNotInstanceOf(YFSymbolNotFoundException.class)
                .hasMessageContaining("Bad Request");
    }

    @Test
    void mapperFlattensInOrderAndLaterKeysWin() throws Exception {
        var modules = new LinkedHashMap<QuoteSummaryModule, JsonNode>();
        modules.put(QuoteSummaryModule.SUMMARY_DETAIL, unwrapped("{\"maxAge\":1,\"currency\":\"USD\",\"a\":null}"));
        modules.put(QuoteSummaryModule.QUOTE_TYPE, unwrapped("{\"maxAge\":2,\"symbol\":\"WRONG\"}"));
        JsonNode quote =
                unwrapped("{\"currency\":\"EUR\",\"symbol\":\"X\",\"b\":{\"raw\":5,\"fmt\":\"5\"},\"c\":null}");

        TickerInfo info = InfoMapper.map("X", modules, quote);

        assertThat(info.raw())
                .containsExactly(
                        Map.entry("maxAge", 2L),
                        Map.entry("currency", "EUR"),
                        Map.entry("symbol", "X"),
                        Map.entry("b", 5L));
    }

    @Test
    void mapperWithNothing() {
        TickerInfo info = InfoMapper.map("X", Map.of(), null);

        assertThat(info.raw()).isEmpty();
        assertThat(info.longName()).isNull();
    }

    @Test
    void typedGetters() {
        var raw = new LinkedHashMap<String, Object>();
        raw.put("s", "text");
        raw.put("l", 7L);
        raw.put("d", 2.9);
        raw.put("big", new BigInteger("123456789012345678901234567890"));
        raw.put("nan", Double.NaN);
        TickerInfo info = new TickerInfo("X", raw);

        assertThat(info.getString("s")).isEqualTo("text");
        assertThat(info.getString("l")).isNull();
        assertThat(info.getDouble("l")).isEqualTo(7.0);
        assertThat(info.getDouble("s")).isNull();
        assertThat(info.getLong("d")).isEqualTo(2L);
        assertThat(info.getLong("big")).isNull();
        assertThat(info.getLong("nan")).isNull();
        assertThat(info.getLong("missing")).isNull();
        assertThat(info.getInstant("l")).isEqualTo(Instant.ofEpochSecond(7));
        // defensive copy
        raw.put("s", "changed");
        assertThat(info.getString("s")).isEqualTo("text");
    }

    private static JsonNode unwrapped(String json) throws Exception {
        return QuoteSummaryEndpoint.unwrap(YahooJson.mapper().readTree(json));
    }
}
