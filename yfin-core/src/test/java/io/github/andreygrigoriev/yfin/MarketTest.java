package io.github.andreygrigoriev.yfin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.andreygrigoriev.yfin.exception.YFDataException;
import io.github.andreygrigoriev.yfin.model.MarketStatus;
import io.github.andreygrigoriev.yfin.model.MarketSummaryItem;
import io.github.andreygrigoriev.yfin.request.MarketRegion;
import io.github.andreygrigoriev.yfin.spi.Cache;
import io.github.andreygrigoriev.yfin.spi.HttpRequest;
import io.github.andreygrigoriev.yfin.testing.FakeTransport;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MarketTest {

    private static final String SUMMARY = "/v6/finance/quote/marketSummary";
    private static final String TIME = "/v6/finance/markettime";
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

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
    void usMarket() {
        fake.onGet(SUMMARY)
                .host("query1.finance.yahoo.com")
                .query("market", "US")
                .respondFixture("market/summary-us.json");
        fake.onGet(TIME)
                .host("query1.finance.yahoo.com")
                .query("market", "US")
                .respondFixture("market/markettime-us.json");

        Market m = yf.market("us");

        assertThat(m.region()).isEqualTo(MarketRegion.US);
        MarketStatus s = m.status().orElseThrow();
        assertThat(s.id()).isEqualTo("us");
        assertThat(s.name()).isEqualTo("U.S. markets");
        assertThat(s.status()).isEqualTo("open");
        assertThat(s.isOpen()).isTrue();
        assertThat(s.message()).isEqualTo("U.S. markets close in 17 minutes");
        assertThat(s.zone()).isEqualTo(NEW_YORK);
        assertThat(s.zoneShortName()).isEqualTo("EDT");
        assertThat(s.open().toInstant()).isEqualTo(Instant.parse("2026-10-12T13:30:00Z"));
        assertThat(s.open().getZone()).isEqualTo(NEW_YORK);
        assertThat(s.open().getHour()).isEqualTo(9);
        assertThat(s.close().toInstant()).isEqualTo(Instant.parse("2026-10-12T20:00:00Z"));
        assertThat(s.raw()).containsEntry("yfit_market_id", "us_market").doesNotContainKey("time");

        List<MarketSummaryItem> summary = m.summary();
        assertThat(summary)
                .extracting(MarketSummaryItem::symbol)
                .containsExactly("^GSPC", "^DJI", "^IXIC", "^RUT", "^TNX", "^VIX", "GC=F");
        MarketSummaryItem dow = summary.get(1);
        assertThat(dow.exchange()).isEqualTo("DJI");
        assertThat(dow.shortName()).isEqualTo("Dow 30");
        assertThat(dow.quoteType()).isEqualTo("INDEX");
        assertThat(dow.price()).isEqualTo(51831.64);
        assertThat(dow.change()).isEqualTo(176.6914);
        assertThat(dow.changePercent()).isEqualTo(0.3420589);
        assertThat(dow.previousClose()).isEqualTo(51654.95);
        assertThat(dow.time()).isEqualTo(Instant.ofEpochSecond(1791834216L));
        assertThat(dow.marketState()).isEqualTo("REGULAR");
        assertThat(dow.raw()).containsEntry("fullExchangeName", "DJI");

        // fetched together, once, with yfinance's parameters
        m.status();
        m.summary();
        HttpRequest summaryRequest = single(SUMMARY);
        assertThat(FakeTransport.parseQuery(summaryRequest.uri().getRawQuery()))
                .containsEntry(
                        "fields",
                        List.of("shortName,regularMarketPrice,regularMarketChange,regularMarketChangePercent"))
                .containsEntry("formatted", List.of("false"))
                .containsEntry("lang", List.of("en-US"));
        assertThat(FakeTransport.parseQuery(single(TIME).uri().getRawQuery()))
                .containsEntry("formatted", List.of("true"))
                .containsEntry("key", List.of("finance"))
                .containsEntry("market", List.of("US"));
    }

    @Test
    void nonUsMarketHasNoStatusAndKeepsSharedExchanges() {
        fake.onGet(SUMMARY).query("market", "EUROPE").respondFixture("market/summary-europe.json");
        // Yahoo ignores the market parameter and answers for the U.S.
        fake.onGet(TIME).query("market", "EUROPE").respondFixture("market/markettime-us.json");

        Market m = yf.market(MarketRegion.EUROPE);

        assertThat(m.status()).isEmpty();
        assertThat(m.summary())
                .extracting(MarketSummaryItem::symbol)
                .containsExactly("^FTSE", "^FCHI", "^GDAXI", "^N100", "EURUSD=X", "GBP=X");
        assertThat(m.summary()).extracting(MarketSummaryItem::exchange).containsSubsequence("PAR", "PAR");
        assertThat(m.toString()).isEqualTo("Market[EUROPE]");
    }

    @Test
    void emptySummary() {
        fake.onGet(SUMMARY).respondFixture("market/summary-empty.json");
        fake.onGet(TIME).respondFixture("market/markettime-us.json");

        assertThat(yf.market(MarketRegion.US).summary()).isEmpty();
    }

    @Test
    void missingMarketTime() {
        fake.onGet(SUMMARY).respondFixture("market/summary-us.json");
        fake.onGet(TIME).respondFixture("market/markettime-empty.json");

        assertThatThrownBy(() -> yf.market(MarketRegion.US).status())
                .isInstanceOf(YFDataException.class)
                .hasMessageContaining("no market time");
    }

    @Test
    void errorPayload() {
        fake.onGet(SUMMARY).respondFixture(400, "market/summary-bad-request.json");
        fake.onGet(TIME).respondFixture("market/markettime-us.json");

        assertThatThrownBy(() -> yf.market(MarketRegion.GB).summary())
                .isInstanceOf(YFDataException.class)
                .hasMessageContaining("invalid broad market region");
    }

    @Test
    void zoneFallsBackToGmtOffset() {
        fake.onGet(SUMMARY).respondFixture("market/summary-empty.json");
        fake.onGet(TIME).respond(200, """
                        {"finance":{"marketTimes":[{"marketTime":[{"id":"us","status":"closed",
                        "open":"2026-10-13T13:30:00Z","close":"2026-10-13T20:00:00Z",
                        "timezone":[{"gmtoffset":"-14400","short":"EDT"}]}]}]}}
                        """);

        MarketStatus s = yf.market(MarketRegion.US).status().orElseThrow();

        assertThat(s.zone()).isEqualTo(ZoneOffset.ofHours(-4));
        assertThat(s.isOpen()).isFalse();
        assertThat(s.open().getHour()).isEqualTo(9);
    }

    @Test
    void invalidMarketTime() {
        fake.onGet(SUMMARY).respondFixture("market/summary-empty.json");
        fake.onGet(TIME).respond(200, """
                        {"finance":{"marketTimes":[{"marketTime":[{"id":"us","open":"soon",
                        "close":"2026-10-13T20:00:00Z","timezone":[{"$text":"America/New_York"}]}]}]}}
                        """);

        assertThatThrownBy(() -> yf.market(MarketRegion.US).status())
                .isInstanceOf(YFDataException.class)
                .hasMessageContaining("invalid market time: soon");
    }

    @Test
    void regionValidation() {
        assertThat(MarketRegion.of(" cryptocurrencies ")).isEqualTo(MarketRegion.CRYPTOCURRENCIES);
        assertThatThrownBy(() -> yf.market("MARS"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown market MARS")
                .hasMessageContaining("EUROPE");
        yf.close();
        assertThatThrownBy(() -> yf.market(MarketRegion.US)).isInstanceOf(IllegalStateException.class);
    }

    private HttpRequest single(String path) {
        List<HttpRequest> matching = fake.requests().stream()
                .filter(r -> r.uri().getPath().equals(path))
                .toList();
        assertThat(matching).hasSize(1);
        return matching.getFirst();
    }
}
