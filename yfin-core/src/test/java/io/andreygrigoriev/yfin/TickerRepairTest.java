package io.andreygrigoriev.yfin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.andreygrigoriev.yfin.internal.cache.FileCache;
import io.andreygrigoriev.yfin.model.Bar;
import io.andreygrigoriev.yfin.model.PriceHistory;
import io.andreygrigoriev.yfin.request.HistoryRequest;
import io.andreygrigoriev.yfin.request.Interval;
import io.andreygrigoriev.yfin.request.Period;
import io.andreygrigoriev.yfin.spi.HttpRequest;
import io.andreygrigoriev.yfin.testing.FakeTransport;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Price repair through {@link Ticker#history(HistoryRequest)}. */
class TickerRepairTest {

    private static final String CHART = "/v8/finance/chart/";

    @TempDir
    Path cacheDir;

    private final FakeTransport fake = FakeTransport.create();
    private FileCache cache;

    @BeforeEach
    void setUp() {
        fake.onGet("").respond(404, "not found");
        fake.onGet("/v1/test/getcrumb").respond(200, "crumb1");
        cache = new FileCache(cacheDir);
        cache.put("tz", "AAPL", "America/New_York");
    }

    private YFinance client(boolean repairByDefault) {
        return YFinance.builder()
                .transport(fake)
                .cache(cache)
                .maxRetries(0)
                .repair(repairByDefault)
                .build();
    }

    private List<HttpRequest> chartRequests() {
        return fake.requests().stream()
                .filter(r -> r.uri().getPath().startsWith(CHART))
                .toList();
    }

    @AfterEach
    void tearDown() {
        fake.clearRequests();
    }

    @Test
    void repairDisabledLeavesDataUntouched() {
        fake.onGet(CHART + "AAPL").query("range", "1y").respondFixture("chart/aapl-1d-1y.json");
        try (YFinance plain = client(false)) {
            PriceHistory byDefault = plain.ticker("AAPL").history(Period.ONE_YEAR);
            PriceHistory explicit = plain.ticker("AAPL")
                    .history(HistoryRequest.builder()
                            .period(Period.ONE_YEAR)
                            .repair(false)
                            .build());

            assertThat(explicit).isEqualTo(byDefault);
            assertThat(byDefault.bars()).noneMatch(Bar::repaired);
            assertThat(chartRequests()).hasSize(2);
        }
    }

    @Test
    void cleanDataUnchangedByRepair() {
        fake.onGet(CHART + "AAPL").query("range", "1y").respondFixture("chart/aapl-1d-1y.json");
        // finer-grained data for reconstruction is not available
        fake.onGet(CHART + "AAPL").hasQuery("period1").respondFixture(404, "chart/not-found.json");
        try (YFinance plain = client(false);
                YFinance repairing = client(true)) {
            PriceHistory raw = plain.ticker("AAPL").history(Period.ONE_YEAR);
            PriceHistory repaired = repairing.ticker("AAPL").history(Period.ONE_YEAR);

            assertThat(repaired.bars()).hasSameSizeAs(raw.bars());
            for (int i = 0; i < raw.bars().size(); i++) {
                Bar a = raw.bars().get(i);
                Bar b = repaired.bars().get(i);
                assertThat(b.time()).isEqualTo(a.time());
                assertThat(b.close()).isCloseTo(a.close(), org.assertj.core.data.Percentage.withPercentage(1e-9));
                assertThat(b.open()).isCloseTo(a.open(), org.assertj.core.data.Percentage.withPercentage(1e-9));
                assertThat(b.volume()).isEqualTo(a.volume());
            }
            assertThat(repaired.dividends()).isEqualTo(raw.dividends());
            assertThat(repaired.currency()).isEqualTo("USD");
        }
    }

    @Test
    void weeklyRepairResamplesDailyBars() {
        fake.onGet(CHART + "AAPL").query("interval", "1d").respondFixture("chart/aapl-1d-1y.json");
        fake.onGet(CHART + "AAPL").hasQuery("period1").respondFixture(404, "chart/not-found.json");
        try (YFinance repairing = client(true)) {
            PriceHistory weekly =
                    repairing.ticker("AAPL").history(HistoryRequest.of(Period.ONE_YEAR, Interval.ONE_WEEK));

            assertThat(weekly.interval()).isEqualTo(Interval.ONE_WEEK);
            assertThat(weekly.bars()).hasSizeBetween(50, 55);
            assertThat(weekly.bars()).allMatch(b -> b.time().getDayOfWeek() == DayOfWeek.MONDAY);
            HttpRequest first = chartRequests().getFirst();
            assertThat(FakeTransport.parseQuery(first.uri().getRawQuery()))
                    .containsEntry("interval", List.of("1d"))
                    .containsKey("period1")
                    .doesNotContainKey("range");
        }
    }

    @Test
    void fiveDayIntervalRejectedWithRepair() {
        try (YFinance repairing = client(true)) {
            assertThatThrownBy(() ->
                            repairing.ticker("AAPL").history(HistoryRequest.of(Period.ONE_YEAR, Interval.FIVE_DAYS)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("5d");
        }
    }
}
