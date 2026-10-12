package io.andreygrigoriev.yfin.internal.endpoint.quotesummary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.andreygrigoriev.yfin.Ticker;
import io.andreygrigoriev.yfin.YFinance;
import io.andreygrigoriev.yfin.internal.http.YahooJson;
import io.andreygrigoriev.yfin.model.Sustainability;
import io.andreygrigoriev.yfin.spi.Cache;
import io.andreygrigoriev.yfin.testing.FakeTransport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SustainabilityMapperTest {

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
        fake.onGet(PATH + symbol).respondFixture(status, "quoteSummary/analysis/" + fixture);
        return yf.ticker(symbol);
    }

    @Test
    void esgScores() {
        // synthetic: Yahoo currently sends no esgScores for any symbol probed
        Sustainability esg =
                ticker("MSFT", 200, "msft-esg-synthetic.json").sustainability().orElseThrow();

        assertThat(esg.totalEsg()).isEqualTo(15.21);
        assertThat(esg.environmentScore()).isEqualTo(1.67);
        assertThat(esg.socialScore()).isEqualTo(7.79);
        assertThat(esg.governanceScore()).isEqualTo(5.75);
        assertThat(esg.percentile()).isEqualTo(12.38);
        assertThat(esg.highestControversy()).isEqualTo(3.0);
        assertThat(esg.esgPerformance()).isEqualTo("AVG_PERF");
        assertThat(esg.peerGroup()).isEqualTo("Software & Services");
        assertThat(esg.peerCount()).isEqualTo(256L);
        assertThat(esg.ratingYear()).isEqualTo(2024L);
        assertThat(esg.ratingMonth()).isEqualTo(9L);
        assertThat(esg.relatedControversy()).containsExactly("Business Ethics Incidents", "Anticompetitive Practices");
        assertThat(esg.raw())
                .containsEntry("militaryContract", true)
                .containsEntry("tobacco", false)
                .containsEntry("totalEsg", 15.21)
                .containsEntry("peerEsgScorePerformance", Map.of("min", 8.31, "avg", 19.04, "max", 36.61))
                .doesNotContainKey("environmentPercentile");
        assertThat(FakeTransport.parseQuery(fake.requests().getLast().uri().getRawQuery()))
                .containsEntry("modules", List.of("esgScores"));
    }

    @ParameterizedTest
    @CsvSource({"AAPL, aapl-esg.json", "SPY, spy.json", "BTC-USD, btc-usd.json"})
    void noEsgScores(String symbol, String fixture) {
        // recorded: Yahoo answers 404 "No fundamentals data found" when esgScores is requested alone
        assertThat(ticker(symbol, 404, fixture).sustainability()).isEmpty();
    }

    @Test
    void missingModuleAndFields() throws Exception {
        assertThat(SustainabilityMapper.sustainability(null)).isEmpty();
        Sustainability empty = SustainabilityMapper.sustainability(
                        YahooJson.mapper().readTree("{\"maxAge\":1,\"relatedControversy\":[1,\"A\"]}"))
                .orElseThrow();

        assertThat(empty.totalEsg()).isNull();
        assertThat(empty.peerGroup()).isNull();
        assertThat(empty.relatedControversy()).containsExactly("A");
        assertThat(empty.raw()).containsEntry("maxAge", 1L);
        assertThatThrownBy(() -> empty.raw().put("x", 1)).isInstanceOf(UnsupportedOperationException.class);
    }
}
