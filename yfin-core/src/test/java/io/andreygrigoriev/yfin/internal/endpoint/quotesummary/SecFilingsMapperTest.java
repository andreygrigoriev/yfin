package io.andreygrigoriev.yfin.internal.endpoint.quotesummary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.andreygrigoriev.yfin.YFinance;
import io.andreygrigoriev.yfin.exception.YFSymbolNotFoundException;
import io.andreygrigoriev.yfin.internal.http.YahooJson;
import io.andreygrigoriev.yfin.model.SecFiling;
import io.andreygrigoriev.yfin.spi.Cache;
import io.andreygrigoriev.yfin.testing.FakeTransport;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SecFilingsMapperTest {

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

    private List<SecFiling> filings(String symbol, int status, String fixture) {
        fake.onGet(PATH + symbol).respondFixture(status, fixture);
        return yf.ticker(symbol).secFilings();
    }

    @Test
    void equity() {
        List<SecFiling> filings = filings("AAPL", 200, "quoteSummary/secfilings/aapl.json");

        assertThat(filings).hasSize(80);
        SecFiling first = filings.getFirst();
        assertThat(first.date()).isEqualTo(LocalDate.parse("2026-09-01"));
        assertThat(first.time()).isEqualTo(Instant.ofEpochSecond(1_788_220_800L));
        assertThat(first.type()).isEqualTo("8-K/A");
        assertThat(first.title()).isEqualTo("Corporate Changes & Voting Matters");
        assertThat(first.edgarUrl()).startsWith("https://finance.yahoo.com/sec-filing/AAPL/");
        assertThat(first.exhibits()).containsOnlyKeys("8-K/A");
        SecFiling tenQ = filings.stream()
                .filter(f -> f.date() != null && f.date().equals(LocalDate.parse("2026-07-31")))
                .filter(f -> "10-Q".equals(f.type()))
                .findFirst()
                .orElseThrow();
        assertThat(tenQ.exhibits().keySet()).containsExactly("EX-31.1", "EX-31.2", "EX-32.1", "10-Q");
        assertThat(filings).isSortedAccordingTo((a, b) -> b.date().compareTo(a.date()));
        assertThat(FakeTransport.parseQuery(fake.requests().getLast().uri().getRawQuery()))
                .containsEntry("modules", List.of("secFilings"));
    }

    @Test
    void etfHasNone() {
        // recorded: 404 "No fundamentals data found"
        assertThat(filings("SPY", 404, "quoteSummary/secfilings/spy.json")).isEmpty();
    }

    @Test
    void unknownSymbol() {
        assertThatThrownBy(() -> filings("NOSUCHSYMBOLXYZ", 404, "quoteSummary/not-found.json"))
                .isInstanceOf(YFSymbolNotFoundException.class);
    }

    @Test
    void missingFields() throws Exception {
        assertThat(SecFilingsMapper.filings(null)).isEmpty();
        List<SecFiling> filings = SecFilingsMapper.filings(YahooJson.mapper()
                .readTree("{\"filings\":[{\"date\":\"bad\",\"exhibits\":[{\"type\":\"EX-1\"},"
                        + "{\"type\":\"EX-2\",\"url\":\"u\"}]}]}"));

        assertThat(filings).hasSize(1);
        SecFiling f = filings.getFirst();
        assertThat(f.date()).isNull();
        assertThat(f.time()).isNull();
        assertThat(f.type()).isNull();
        assertThat(f.exhibits()).containsExactly(java.util.Map.entry("EX-2", "u"));
        assertThatThrownBy(() -> f.exhibits().put("x", "y")).isInstanceOf(UnsupportedOperationException.class);
    }
}
