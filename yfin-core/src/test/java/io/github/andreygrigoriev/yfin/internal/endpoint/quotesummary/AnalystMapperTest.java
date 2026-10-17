package io.github.andreygrigoriev.yfin.internal.endpoint.quotesummary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.andreygrigoriev.yfin.Ticker;
import io.github.andreygrigoriev.yfin.YFinance;
import io.github.andreygrigoriev.yfin.exception.YFSymbolNotFoundException;
import io.github.andreygrigoriev.yfin.internal.http.YahooJson;
import io.github.andreygrigoriev.yfin.model.EarningsEstimate;
import io.github.andreygrigoriev.yfin.model.EarningsHistoryEntry;
import io.github.andreygrigoriev.yfin.model.EpsRevisions;
import io.github.andreygrigoriev.yfin.model.EpsTrend;
import io.github.andreygrigoriev.yfin.model.GrowthEstimate;
import io.github.andreygrigoriev.yfin.model.PriceTargets;
import io.github.andreygrigoriev.yfin.model.Recommendation;
import io.github.andreygrigoriev.yfin.model.RevenueEstimate;
import io.github.andreygrigoriev.yfin.model.UpgradeDowngrade;
import io.github.andreygrigoriev.yfin.spi.Cache;
import io.github.andreygrigoriev.yfin.testing.FakeTransport;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AnalystMapperTest {

    private static final String PATH = "/v10/finance/quoteSummary/";

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

    private Ticker ticker(String symbol, String fixture) {
        fake.onGet(PATH + symbol).respondFixture("quoteSummary/analysis/" + fixture);
        return yf.ticker(symbol);
    }

    private List<String> requestedModules() {
        return FakeTransport.parseQuery(fake.requests().getLast().uri().getRawQuery())
                .get("modules");
    }

    private static JsonNode module(String json) throws Exception {
        return QuoteSummaryEndpoint.unwrap(YahooJson.mapper().readTree(json));
    }

    // ---- equity with full data ----

    @Test
    void recommendations() {
        Ticker aapl = ticker("AAPL", "aapl.json");
        List<Recommendation> recs = aapl.recommendations();

        assertThat(recs)
                .containsExactly(
                        new Recommendation("0m", 6L, 19L, 13L, 3L, 3L),
                        new Recommendation("-1m", 6L, 19L, 13L, 3L, 3L),
                        new Recommendation("-2m", 6L, 19L, 14L, 3L, 2L),
                        new Recommendation("-3m", 6L, 22L, 14L, 2L, 2L));
        assertThat(requestedModules()).containsExactly("recommendationTrend");
        assertThat(aapl.recommendationsSummary()).isEqualTo(recs);
    }

    @Test
    void upgradesDowngrades() {
        List<UpgradeDowngrade> changes = ticker("AAPL", "aapl.json").upgradesDowngrades();

        // the fixture keeps the newest 20 of Yahoo's 971 entries
        assertThat(changes).hasSize(20);
        assertThat(changes.getFirst())
                .isEqualTo(new UpgradeDowngrade(
                        Instant.parse("2026-10-01T15:55:51Z"),
                        "Morgan Stanley",
                        "Overweight",
                        "Overweight",
                        "main",
                        "Lowers",
                        355.0,
                        360.0));
        assertThat(changes)
                .filteredOn(c -> "up".equals(c.action()))
                .singleElement()
                .satisfies(c -> {
                    assertThat(c.firm()).isEqualTo("Rothschild & Co");
                    assertThat(c.fromGrade()).isEqualTo("Neutral");
                    assertThat(c.toGrade()).isEqualTo("Buy");
                });
        assertThat(changes).isSortedAccordingTo((a, b) -> b.gradeDate().compareTo(a.gradeDate()));
        assertThat(requestedModules()).containsExactly("upgradeDowngradeHistory");
    }

    @Test
    void analystPriceTargets() {
        PriceTargets targets = ticker("AAPL", "aapl.json").analystPriceTargets().orElseThrow();

        assertThat(targets).isEqualTo(new PriceTargets(339.32, 405.0, 215.0, 328.09384, 340.0));
        assertThat(requestedModules()).containsExactly("financialData");
    }

    @Test
    void earningsEstimate() {
        List<EarningsEstimate> estimates = ticker("AAPL", "aapl.json").earningsEstimate();

        assertThat(estimates).extracting(EarningsEstimate::period).containsExactly("0q", "+1q", "0y", "+1y");
        assertThat(estimates.get(1))
                .isEqualTo(new EarningsEstimate(
                        "+1q", LocalDate.of(2026, 12, 31), 22L, 2.91006, 2.56, 3.42, 2.84, 0.0247, "USD"));
        assertThat(requestedModules()).containsExactly("earningsTrend");
    }

    @Test
    void revenueEstimate() {
        List<RevenueEstimate> estimates = ticker("AAPL", "aapl.json").revenueEstimate();

        assertThat(estimates).hasSize(4);
        assertThat(estimates.get(1))
                .isEqualTo(new RevenueEstimate(
                        "+1q",
                        LocalDate.of(2026, 12, 31),
                        19L,
                        154_618_048_490.0,
                        131_500_061_129.0,
                        170_414_000_000.0,
                        143_756_000_000.0,
                        0.0756,
                        "USD"));
    }

    @Test
    void epsTrend() {
        List<EpsTrend> trend = ticker("AAPL", "aapl.json").epsTrend();

        assertThat(trend).hasSize(4);
        assertThat(trend.get(1))
                .isEqualTo(new EpsTrend(
                        "+1q", LocalDate.of(2026, 12, 31), 2.91006, 2.90586, 2.9109, 2.90495, 2.9496, "USD"));
    }

    @Test
    void epsRevisions() {
        List<EpsRevisions> revisions = ticker("AAPL", "aapl.json").epsRevisions();

        assertThat(revisions).hasSize(4);
        // Yahoo's {"raw":0,"fmt":null} is still a value; "downLast90days":{} is ignored
        assertThat(revisions.get(1))
                .isEqualTo(new EpsRevisions("+1q", LocalDate.of(2026, 12, 31), 0L, 1L, 1L, 2L, "USD"));
        assertThat(revisions.getFirst())
                .isEqualTo(new EpsRevisions("0q", LocalDate.of(2026, 9, 30), 1L, 7L, 0L, 14L, "USD"));
    }

    @Test
    void earningsHistory() {
        List<EarningsHistoryEntry> history = ticker("AAPL", "aapl.json").earningsHistory();

        assertThat(history)
                .extracting(EarningsHistoryEntry::quarter)
                .containsExactly(
                        LocalDate.of(2025, 9, 30),
                        LocalDate.of(2025, 12, 31),
                        LocalDate.of(2026, 3, 31),
                        LocalDate.of(2026, 6, 30));
        assertThat(history.getLast())
                .isEqualTo(
                        new EarningsHistoryEntry(LocalDate.of(2026, 6, 30), "-1q", 1.89243, 2.02, 0.13, 0.0674, "USD"));
        assertThat(requestedModules()).containsExactly("earningsHistory");
    }

    @Test
    void growthEstimates() {
        List<GrowthEstimate> growth = ticker("AAPL", "aapl.json").growthEstimates();

        // industry and sector trends are empty; LTG comes from the index trend only
        assertThat(growth)
                .containsExactly(
                        new GrowthEstimate("0q", 0.0715, null, null, 0.24350001),
                        new GrowthEstimate("+1q", 0.0161, null, null, 0.2619),
                        new GrowthEstimate("0y", 0.1838, null, null, 0.3232),
                        new GrowthEstimate("+1y", 0.0856, null, null, 0.16459998),
                        new GrowthEstimate("LTG", null, null, null, 0.122));
        assertThat(fake.requests())
                .filteredOn(r -> r.uri().getPath().startsWith(PATH))
                .hasSize(1);
        assertThat(String.join(",", requestedModules()).split(","))
                .containsExactlyInAnyOrder("earningsTrend", "industryTrend", "sectorTrend", "indexTrend");
    }

    @Test
    void foreignEquityUsesItsCurrency() {
        Ticker sap = ticker("SAP.DE", "sap-de.json");

        assertThat(sap.earningsEstimate().getFirst())
                .isEqualTo(new EarningsEstimate(
                        "0q", LocalDate.of(2026, 9, 30), 11L, 1.8334, 1.70885, 2.12, 1.59, 0.1531, "EUR"));
        assertThat(sap.earningsHistory())
                .allSatisfy(e -> assertThat(e.currency()).isEqualTo("EUR"));
        assertThat(sap.recommendations().getFirst()).isEqualTo(new Recommendation("0m", 3L, 20L, 4L, 0L, 0L));
        // Yahoo sends no upgradeDowngradeHistory for SAP.DE
        assertThat(sap.upgradesDowngrades()).isEmpty();
    }

    // ---- missing modules ----

    @ParameterizedTest
    @CsvSource({"SPY, spy.json", "BTC-USD, btc-usd.json"})
    void symbolWithoutFundamentalsGivesEmptyResults(String symbol, String fixture) {
        // Yahoo answers 404 "No fundamentals data found for symbol" for ETFs and crypto pairs
        fake.onGet(PATH + symbol).respondFixture(404, "quoteSummary/analysis/" + fixture);
        Ticker t = yf.ticker(symbol);

        assertThat(t.recommendations()).isEmpty();
        assertThat(t.recommendationsSummary()).isEmpty();
        assertThat(t.upgradesDowngrades()).isEmpty();
        assertThat(t.analystPriceTargets()).isEmpty();
        assertThat(t.earningsEstimate()).isEmpty();
        assertThat(t.revenueEstimate()).isEmpty();
        assertThat(t.earningsHistory()).isEmpty();
        assertThat(t.epsTrend()).isEmpty();
        assertThat(t.epsRevisions()).isEmpty();
        assertThat(t.growthEstimates()).isEmpty();
    }

    @Test
    void unknownSymbolStillThrows() {
        fake.onGet(PATH + "NOSUCHSYMBOLXYZ").respondFixture(404, "quoteSummary/not-found.json");

        assertThatThrownBy(() -> yf.ticker("NOSUCHSYMBOLXYZ").recommendations())
                .isInstanceOf(YFSymbolNotFoundException.class);
    }

    // ---- mapper edge cases (synthetic) ----

    @Test
    void nullModulesGiveEmptyResults() {
        assertThat(AnalystMapper.recommendations(null)).isEmpty();
        assertThat(AnalystMapper.upgradesDowngrades(null)).isEmpty();
        assertThat(AnalystMapper.priceTargets(null)).isEmpty();
        assertThat(AnalystMapper.earningsEstimate(null)).isEmpty();
        assertThat(AnalystMapper.revenueEstimate(null)).isEmpty();
        assertThat(AnalystMapper.epsTrend(null)).isEmpty();
        assertThat(AnalystMapper.epsRevisions(null)).isEmpty();
        assertThat(AnalystMapper.earningsHistory(null)).isEmpty();
        assertThat(AnalystMapper.growthEstimates(Map.of())).isEmpty();
    }

    @Test
    void periodicTablesUseFirstFourTrendEntries() throws Exception {
        JsonNode trend = module("""
                {"trend":[
                  {"period":"0q","endDate":"bad","earningsEstimate":{"avg":1.0}},
                  {"period":"+1q"},
                  {"period":"0y","earningsEstimate":{}},
                  {"period":"+1y","earningsEstimate":{"avg":{"raw":4.0,"fmt":"4"}}},
                  {"period":"+5y","growth":0.1,"earningsEstimate":{"avg":5.0}}]}""");

        // entries without the sub-object (or an empty one) are skipped; +5y is past the first four
        assertThat(AnalystMapper.earningsEstimate(trend))
                .containsExactly(
                        new EarningsEstimate("0q", null, null, 1.0, null, null, null, null, null),
                        new EarningsEstimate("+1y", null, null, 4.0, null, null, null, null, null));
        // growth uses every entry; entries without any figure are dropped
        assertThat(AnalystMapper.growthEstimates(Map.of(QuoteSummaryModule.EARNINGS_TREND, trend)))
                .containsExactly(new GrowthEstimate("+5y", 0.1, null, null, null));
    }

    @Test
    void growthJoinsBenchmarksByPeriod() throws Exception {
        var modules = Map.of(
                QuoteSummaryModule.EARNINGS_TREND, module("{\"trend\":[{\"period\":\"0q\",\"growth\":0.1}]}"),
                QuoteSummaryModule.INDUSTRY_TREND,
                        module("{\"estimates\":[{\"period\":\"0q\",\"growth\":0.2},{\"growth\":9}]}"),
                QuoteSummaryModule.SECTOR_TREND, module("{\"estimates\":[{\"period\":\"+5y\",\"growth\":0.3}]}"),
                QuoteSummaryModule.INDEX_TREND, module("{\"estimates\":{}}"));

        assertThat(AnalystMapper.growthEstimates(modules))
                .containsExactly(
                        new GrowthEstimate("0q", 0.1, 0.2, null, null),
                        new GrowthEstimate("+5y", null, null, 0.3, null));
    }

    @Test
    void missingFieldsAreNull() throws Exception {
        assertThat(AnalystMapper.priceTargets(module("{\"maxAge\":1}")))
                .contains(new PriceTargets(null, null, null, null, null));
        assertThat(AnalystMapper.upgradesDowngrades(module("{\"history\":[{\"epochGradeDate\":\"x\"}]}")))
                .containsExactly(new UpgradeDowngrade(null, null, null, null, null, null, null, null));
        assertThat(AnalystMapper.recommendations(module("{\"trend\":{}}"))).isEmpty();
    }
}
