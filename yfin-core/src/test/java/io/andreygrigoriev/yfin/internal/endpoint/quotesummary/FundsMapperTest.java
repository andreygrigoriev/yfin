package io.andreygrigoriev.yfin.internal.endpoint.quotesummary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.andreygrigoriev.yfin.Ticker;
import io.andreygrigoriev.yfin.YFinance;
import io.andreygrigoriev.yfin.exception.YFSymbolNotFoundException;
import io.andreygrigoriev.yfin.internal.http.YahooJson;
import io.andreygrigoriev.yfin.model.FundHolding;
import io.andreygrigoriev.yfin.model.FundsData;
import io.andreygrigoriev.yfin.spi.Cache;
import io.andreygrigoriev.yfin.testing.FakeTransport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FundsMapperTest {

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

    private Ticker ticker(String symbol, int status, String fixture) {
        fake.onGet(PATH + symbol).respondFixture(status, fixture);
        return yf.ticker(symbol);
    }

    @Test
    void etf() {
        FundsData spy =
                ticker("SPY", 200, "quoteSummary/funds/spy.json").fundsData().orElseThrow();

        assertThat(spy.symbol()).isEqualTo("SPY");
        assertThat(spy.quoteType()).isEqualTo("ETF");
        assertThat(spy.categoryName()).isEqualTo("Large Blend");
        assertThat(spy.family()).isEqualTo("State Street Investment Management");
        assertThat(spy.legalType()).isEqualTo("Exchange Traded Fund");
        assertThat(spy.description()).isNotBlank();
        assertThat(spy.operations())
                .containsExactly(
                        Map.entry("annualReportExpenseRatio", new FundsData.Metric(0.000945, 0.0072176997)),
                        Map.entry("annualHoldingsTurnover", new FundsData.Metric(0.03, 0.9461)),
                        Map.entry("totalNetAssets", new FundsData.Metric(542460.75, 542460.75)));
        assertThat(spy.assetClasses())
                .containsEntry("cashPosition", 0.0025)
                .containsEntry("stockPosition", 0.9975)
                .hasSize(6);
        assertThat(spy.topHoldings()).hasSize(10);
        assertThat(spy.topHoldings().getFirst()).isEqualTo(new FundHolding("NVDA", "NVIDIA Corp", 0.0836162));
        assertThat(spy.equityHoldings().get("priceToEarnings")).isEqualTo(new FundsData.Metric(0.04132, null));
        assertThat(spy.equityHoldings().get("medianMarketCap")).isEqualTo(new FundsData.Metric(null, null));
        assertThat(spy.equityHoldings()).hasSize(6);
        // Yahoo sends bondHoldings as {}: every metric present, all null
        assertThat(spy.bondHoldings()).containsOnlyKeys("duration", "maturity", "creditQuality");
        assertThat(spy.bondHoldings().get("duration")).isEqualTo(new FundsData.Metric(null, null));
        assertThat(spy.bondRatings()).containsExactly(Map.entry("us_government", 0.0));
        assertThat(spy.sectorWeightings()).containsEntry("realestate", 0.017).hasSize(11);
        assertThat(FakeTransport.parseQuery(fake.requests().getLast().uri().getRawQuery()))
                .containsEntry("modules", List.of("summaryProfile,fundProfile,topHoldings,quoteType"));
        assertThatThrownBy(() -> spy.topHoldings().add(null)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void mutualFund() {
        FundsData agthx = ticker("AGTHX", 200, "quoteSummary/funds/agthx.json")
                .fundsData()
                .orElseThrow();

        assertThat(agthx.quoteType()).isEqualTo("MUTUALFUND");
        assertThat(agthx.family()).isEqualTo("Capital Group");
        // recorded: Yahoo sends categoryName and legalType as null for this fund
        assertThat(agthx.categoryName()).isNull();
        assertThat(agthx.legalType()).isNull();
        assertThat(agthx.operations().get("annualReportExpenseRatio").fund()).isEqualTo(0.0058999998);
        assertThat(agthx.operations().get("annualReportExpenseRatio").categoryAverage())
                .isNull();
        assertThat(agthx.assetClasses()).containsEntry("convertiblePosition", 0.0002);
        assertThat(agthx.equityHoldings().get("threeYearEarningsGrowth").fund()).isEqualTo(23.28);
    }

    @Test
    void notAFundIsEmpty() {
        // recorded: for an equity Yahoo returns quoteType and summaryProfile only
        assertThat(ticker("AAPL", 200, "quoteSummary/funds/aapl.json").fundsData())
                .isEmpty();
    }

    @Test
    void unknownSymbol() {
        assertThatThrownBy(() -> ticker("NOSUCHSYMBOLXYZ", 404, "quoteSummary/not-found.json")
                        .fundsData())
                .isInstanceOf(YFSymbolNotFoundException.class);
    }

    @Test
    void missingFields() throws Exception {
        var modules = Map.of(
                QuoteSummaryModule.TOP_HOLDINGS,
                YahooJson.mapper()
                        .readTree("{\"holdings\":[{\"holdingName\":\"X\"},1],\"sectorWeightings\":[{\"a\":\"no\"}]}"));

        FundsData data = FundsMapper.fundsData("X", modules).orElseThrow();

        assertThat(data.quoteType()).isNull();
        assertThat(data.description()).isNull();
        assertThat(data.family()).isNull();
        assertThat(data.assetClasses()).isEmpty();
        assertThat(data.topHoldings()).containsExactly(new FundHolding(null, "X", null));
        assertThat(data.operations().values()).containsOnly(new FundsData.Metric(null, null));
        assertThat(data.sectorWeightings()).isEmpty();
        assertThat(data.bondRatings()).isEmpty();
    }
}
