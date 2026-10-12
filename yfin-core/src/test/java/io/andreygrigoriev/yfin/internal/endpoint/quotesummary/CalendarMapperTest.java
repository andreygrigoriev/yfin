package io.andreygrigoriev.yfin.internal.endpoint.quotesummary;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.andreygrigoriev.yfin.Ticker;
import io.andreygrigoriev.yfin.YFinance;
import io.andreygrigoriev.yfin.internal.http.YahooJson;
import io.andreygrigoriev.yfin.model.Calendar;
import io.andreygrigoriev.yfin.spi.Cache;
import io.andreygrigoriev.yfin.testing.FakeTransport;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CalendarMapperTest {

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

    private static JsonNode module(String json) throws Exception {
        return QuoteSummaryEndpoint.unwrap(YahooJson.mapper().readTree(json));
    }

    @Test
    void equityCalendar() {
        Calendar calendar = ticker("AAPL", 200, "aapl.json").calendar().orElseThrow();

        assertThat(calendar)
                .isEqualTo(new Calendar(
                        List.of(LocalDate.of(2026, 11, 2)),
                        false,
                        2.07,
                        1.93,
                        1.9822,
                        117_219_700_000.0,
                        112_248_100_000.0,
                        113_617_923_250.0,
                        LocalDate.of(2026, 8, 10),
                        LocalDate.of(2026, 8, 13)));
        assertThat(FakeTransport.parseQuery(fake.requests().getLast().uri().getRawQuery()))
                .containsEntry("modules", List.of("calendarEvents"));
    }

    @Test
    void calendarWithoutDividendDate() {
        Calendar calendar = ticker("SAP.DE", 200, "sap-de.json").calendar().orElseThrow();

        assertThat(calendar.earningsDates()).containsExactly(LocalDate.of(2026, 10, 21));
        assertThat(calendar.exDividendDate()).isEqualTo(LocalDate.of(2026, 5, 6));
        assertThat(calendar.dividendDate()).isNull();
        assertThat(calendar.revenueAverage()).isEqualTo(10_094_598_350.0);
    }

    @ParameterizedTest
    @CsvSource({"SPY, spy.json", "BTC-USD, btc-usd.json"})
    void symbolWithoutFundamentalsHasNoCalendar(String symbol, String fixture) {
        assertThat(ticker(symbol, 404, fixture).calendar()).isEmpty();
    }

    @Test
    void missingModuleAndFields() throws Exception {
        assertThat(CalendarMapper.calendar(null)).isEmpty();
        assertThat(CalendarMapper.calendar(module("{\"maxAge\":1}")))
                .contains(new Calendar(List.of(), null, null, null, null, null, null, null, null, null));
        // a two-date window; non-numeric dates are skipped
        assertThat(
                        CalendarMapper.calendar(
                                module(
                                        "{\"earnings\":{\"earningsDate\":[1793649600,\"x\",1794254400],\"isEarningsDateEstimate\":true}}")))
                .hasValueSatisfying(c -> {
                    assertThat(c.earningsDates()).containsExactly(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 9));
                    assertThat(c.earningsDateEstimate()).isTrue();
                });
    }
}
