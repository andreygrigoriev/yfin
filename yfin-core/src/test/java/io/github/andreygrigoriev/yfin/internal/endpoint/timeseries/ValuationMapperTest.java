package io.github.andreygrigoriev.yfin.internal.endpoint.timeseries;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.andreygrigoriev.yfin.YFinance;
import io.github.andreygrigoriev.yfin.exception.YFDataException;
import io.github.andreygrigoriev.yfin.internal.http.YahooJson;
import io.github.andreygrigoriev.yfin.model.ValuationMeasures;
import io.github.andreygrigoriev.yfin.request.Frequency;
import io.github.andreygrigoriev.yfin.spi.Cache;
import io.github.andreygrigoriev.yfin.spi.HttpRequest;
import io.github.andreygrigoriev.yfin.testing.FakeTransport;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ValuationMapperTest {

    private static final String PATH = TimeseriesMapperTest.PATH;

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

    private ValuationMeasures valuation(String symbol, Frequency frequency, String fixture) {
        fake.onGet(PATH + symbol).respondFixture("timeseries/valuation/" + fixture);
        return yf.ticker(symbol).valuationMeasures(frequency);
    }

    private static LocalDate d(String iso) {
        return LocalDate.parse(iso);
    }

    @Test
    void quarterly() {
        ValuationMeasures v = valuation("AAPL", Frequency.QUARTERLY, "aapl-quarterly.json");

        assertThat(v.symbol()).isEqualTo("AAPL");
        assertThat(v.frequency()).isEqualTo(Frequency.QUARTERLY);
        assertThat(v.isEmpty()).isFalse();
        assertThat(v.periods())
                .containsExactly(d("2026-06-30"), d("2026-03-31"), d("2025-12-31"), d("2025-09-30"), d("2025-06-30"));
        assertThat(v.rows().keySet()).containsExactlyElementsOf(ValuationMeasures.MEASURES);
        assertThat(v.get("MarketCap").firstKey()).isEqualTo(d("2026-06-30"));
        assertThat(v.get("MarketCap")).containsEntry(d("2026-06-30"), 4_249_933_332_160.0);
        assertThat(v.get("MarketCap")).containsEntry(d("2025-06-30"), 3_048_153_652_740.0);
        assertThat(v.get("PeRatio")).containsEntry(d("2026-06-30"), 35.031477);
        assertThat(v.get("EnterprisesValueEBITDARatio")).containsEntry(d("2025-06-30"), 22.3081);
        // current = newest trailing point of each measure
        assertThat(v.current("MarketCap")).isEqualTo(4_918_530_543_600.0);
        assertThat(v.current("PegRatio")).isEqualTo(2.706);
        assertThat(v.current()).hasSize(9);
        assertThat(v.get("nope")).isEmpty();
        assertThat(v.current("nope")).isNaN();

        // one request with every measure, period prefix then trailing (yfinance order)
        List<HttpRequest> requests = fake.requests().stream()
                .filter(r -> r.uri().getPath().equals(PATH + "AAPL"))
                .toList();
        assertThat(requests).hasSize(1);
        Map<String, List<String>> q =
                FakeTransport.parseQuery(requests.getFirst().uri().getRawQuery());
        assertThat(q.get("period1")).containsExactly("1483142400");
        assertThat(q.get("type").getFirst().split(","))
                .hasSize(18)
                .startsWith("quarterlyMarketCap", "trailingMarketCap", "quarterlyEnterpriseValue");
    }

    @Test
    void annual() {
        ValuationMeasures v = valuation("AAPL", Frequency.ANNUAL, "aapl-annual.json");

        assertThat(v.periods()).containsExactly(d("2025-09-30"), d("2024-09-30"), d("2023-09-30"), d("2022-09-30"));
        assertThat(v.get("PeRatio")).containsEntry(d("2025-09-30"), 38.638847);
        assertThat(v.get("PegRatio")).containsEntry(d("2022-09-30"), 2.4734);
        assertThat(v.current("MarketCap")).isEqualTo(4_918_530_543_600.0);
    }

    @Test
    void trailingRowsHaveNaNForMissingDays() {
        ValuationMeasures v = valuation("AAPL", Frequency.TRAILING, "aapl-trailing.json");

        assertThat(v.periods()).hasSize(11).startsWith(d("2026-09-23"));
        assertThat(v.periods().getLast()).isEqualTo(d("2024-06-10"));
        assertThat(v.get("MarketCap")).containsEntry(d("2026-09-23"), 4_918_530_543_600.0);
        // recorded: Yahoo has no P/E point for the newest day
        assertThat(v.get("PeRatio").get(d("2026-09-23"))).isNaN();
        assertThat(v.get("PeRatio")).hasSize(11);
        assertThat(v.get("PegRatio")).containsEntry(d("2024-06-10"), 2.0439);
        String types = FakeTransport.parseQuery(fake.requests().getLast().uri().getRawQuery())
                .get("type")
                .getFirst();
        assertThat(types.split(",")).hasSize(9).allMatch(t -> t.startsWith("trailing"));
    }

    @Test
    void etfIsEmpty() {
        // recorded: Yahoo answers with meta-only results
        ValuationMeasures v = valuation("SPY", Frequency.QUARTERLY, "spy-quarterly.json");

        assertThat(v.isEmpty()).isTrue();
        assertThat(v.periods()).isEmpty();
        assertThat(v.rows()).isEmpty();
        assertThat(v.current()).isEmpty();
    }

    @Test
    void errorEnvelopeIsDataException() {
        // recorded: Yahoo's answer to a malformed period1
        fake.onGet(PATH + "AAPL").respondFixture(400, "timeseries/error-bad-request.json");

        assertThatThrownBy(() -> yf.ticker("AAPL").valuationMeasures())
                .isInstanceOf(YFDataException.class)
                .hasMessageContaining("Bad Request");
    }

    @Test
    void malformedPointsAreSkipped() throws Exception {
        var result = YahooJson.mapper().readTree("""
                {"meta":{},"quarterlyMarketCap":[null,{"asOfDate":"bad","reportedValue":{"raw":1}},
                 {"asOfDate":"2026-03-31","reportedValue":{}},
                 {"asOfDate":"2026-06-30","reportedValue":{"raw":5}}],
                 "quarterlyUnknown":[{"asOfDate":"2026-06-30","reportedValue":{"raw":7}}],
                 "annualMarketCap":[{"asOfDate":"2025-12-31","reportedValue":{"raw":9}}]}""");

        ValuationMeasures v = ValuationMapper.map("X", Frequency.QUARTERLY, List.of(result));

        assertThat(v.periods()).containsExactly(d("2026-06-30"));
        assertThat(v.get("MarketCap")).containsExactly(Map.entry(d("2026-06-30"), 5.0));
        assertThat(v.get("PeRatio").get(d("2026-06-30"))).isNaN();
        assertThat(v.current()).isEmpty();
        assertThatThrownBy(() -> v.rows().put("x", null)).isInstanceOf(UnsupportedOperationException.class);
    }
}
