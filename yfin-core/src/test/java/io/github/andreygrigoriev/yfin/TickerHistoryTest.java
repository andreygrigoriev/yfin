package io.github.andreygrigoriev.yfin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.andreygrigoriev.yfin.exception.YFDataException;
import io.github.andreygrigoriev.yfin.exception.YFRateLimitException;
import io.github.andreygrigoriev.yfin.exception.YFSymbolNotFoundException;
import io.github.andreygrigoriev.yfin.internal.cache.FileCache;
import io.github.andreygrigoriev.yfin.model.Actions;
import io.github.andreygrigoriev.yfin.model.HistoryMetadata;
import io.github.andreygrigoriev.yfin.model.PriceHistory;
import io.github.andreygrigoriev.yfin.request.HistoryRequest;
import io.github.andreygrigoriev.yfin.request.Interval;
import io.github.andreygrigoriev.yfin.request.Period;
import io.github.andreygrigoriev.yfin.spi.Cache;
import io.github.andreygrigoriev.yfin.spi.HttpRequest;
import io.github.andreygrigoriev.yfin.testing.FakeTransport;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TickerHistoryTest {

    private static final String CHART = "/v8/finance/chart/";

    @TempDir
    Path cacheDir;

    private final FakeTransport fake = FakeTransport.create();
    private Cache cache;
    private YFinance yf;

    @BeforeEach
    void setUp() {
        fake.onGet("").respond(404, "not found"); // fc.yahoo.com sets the cookie
        fake.onGet("/v1/test/getcrumb").respond(200, "crumb1");
        cache = new FileCache(cacheDir);
        yf = YFinance.builder().transport(fake).cache(cache).maxRetries(0).build();
    }

    @AfterEach
    void tearDown() {
        yf.close();
    }

    private List<HttpRequest> chartRequests() {
        return fake.requests().stream()
                .filter(r -> r.uri().getPath().startsWith(CHART))
                .toList();
    }

    private static Map<String, List<String>> query(HttpRequest r) {
        return FakeTransport.parseQuery(r.uri().getRawQuery());
    }

    @Test
    void historyByPeriod() {
        fake.onGet(CHART + "AAPL").query("range", "1mo").respondFixture("chart/aapl-1d-1mo.json");

        PriceHistory h = yf.ticker("aapl").history(Period.ONE_MONTH);

        assertThat(h.symbol()).isEqualTo("AAPL");
        assertThat(h.bars()).hasSize(21);
        HttpRequest request = chartRequests().getFirst();
        assertThat(request.uri().getHost()).isEqualTo("query2.finance.yahoo.com");
        assertThat(query(request))
                .containsEntry("range", List.of("1mo"))
                .containsEntry("interval", List.of("1d"))
                .containsEntry("includePrePost", List.of("false"))
                .containsEntry("events", List.of("div,splits,capitalGains"))
                .containsEntry("crumb", List.of("crumb1"))
                .doesNotContainKeys("period1", "period2");
        // the timezone seen in the response is cached for later date conversions
        assertThat(cache.get("tz", "AAPL")).contains("America/New_York");
    }

    @Test
    void defaultHistoryIsOneMonthDaily() {
        fake.onGet(CHART + "AAPL").query("range", "1mo").respondFixture("chart/aapl-1d-1mo.json");

        assertThat(yf.ticker("AAPL").history().bars()).hasSize(21);
    }

    @Test
    void startEndDatesUseExchangeTimezone() {
        fake.onGet(CHART + "AAPL").query("range", "1d").respondFixture("chart/aapl-tz.json");
        fake.onGet(CHART + "AAPL").hasQuery("period1").respondFixture("chart/aapl-split-dividend-2020.json");
        var request = HistoryRequest.builder()
                .start(LocalDate.of(2020, 8, 1))
                .end(LocalDate.of(2020, 9, 15))
                .build();

        PriceHistory h = yf.ticker("AAPL").history(request);

        assertThat(h.splits()).hasSize(1);
        assertThat(h.dividends()).hasSize(1);
        List<HttpRequest> requests = chartRequests();
        assertThat(requests).hasSize(2);
        assertThat(query(requests.get(0))).containsEntry("range", List.of("1d"));
        // midnight New York (EDT, UTC-4)
        assertThat(query(requests.get(1)))
                .containsEntry("period1", List.of("1596254400"))
                .containsEntry("period2", List.of("1600142400"))
                .doesNotContainKey("range");
        assertThat(cache.get("tz", "AAPL")).contains("America/New_York");

        // the second call takes the timezone from the cache
        fake.clearRequests();
        yf.ticker("AAPL").history(request);
        assertThat(chartRequests()).hasSize(1);
    }

    @Test
    void cachedTimezoneSkipsLookup() {
        cache.put("tz", "AAPL", "America/New_York");
        fake.onGet(CHART + "AAPL").hasQuery("period1").respondFixture("chart/aapl-split-dividend-2020.json");

        yf.ticker("AAPL")
                .history(HistoryRequest.builder()
                        .start(LocalDate.of(2020, 8, 1))
                        .end(LocalDate.of(2020, 9, 15))
                        .build());

        assertThat(chartRequests())
                .singleElement()
                .satisfies(r -> assertThat(query(r)).containsEntry("period1", List.of("1596254400")));
    }

    @Test
    void invalidCachedTimezoneRefetched() {
        cache.put("tz", "AAPL", "Not/AZone");
        fake.onGet(CHART + "AAPL").query("range", "1d").respondFixture("chart/aapl-tz.json");
        fake.onGet(CHART + "AAPL").hasQuery("period1").respondFixture("chart/aapl-split-dividend-2020.json");

        yf.ticker("AAPL")
                .history(HistoryRequest.builder()
                        .start(LocalDate.of(2020, 8, 1))
                        .end(LocalDate.of(2020, 9, 15))
                        .build());

        assertThat(chartRequests()).hasSize(2);
        assertThat(cache.get("tz", "AAPL")).contains("America/New_York");
    }

    @Test
    void periodAnchoredAtStartComputesEnd() {
        cache.put("tz", "AAPL", "America/New_York");
        fake.onGet(CHART + "AAPL").hasQuery("period1").respondFixture("chart/aapl-split-dividend-2020.json");

        yf.ticker("AAPL")
                .history(HistoryRequest.builder()
                        .period(Period.ONE_MONTH)
                        .start(LocalDate.of(2020, 8, 1))
                        .build());

        assertThat(query(chartRequests().getFirst()))
                .containsEntry("period1", List.of("1596254400"))
                // 2020-09-01 00:00 New York
                .containsEntry("period2", List.of("1598932800"));
    }

    @Test
    void maxIntradayUsesYahooLookback() {
        fake.onGet(CHART + "AAPL").hasQuery("period1").respondFixture("chart/azo-1m-nulls.json");

        yf.ticker("AAPL")
                .history(HistoryRequest.builder()
                        .period(Period.MAX)
                        .interval(Interval.ONE_MINUTE)
                        .build());

        Map<String, List<String>> q = query(chartRequests().getFirst());
        long p1 = Long.parseLong(q.get("period1").getFirst());
        long p2 = Long.parseLong(q.get("period2").getFirst());
        assertThat(p2 - p1).isEqualTo(8 * 86_400 - 5);
        assertThat(q).containsEntry("interval", List.of("1m"));
    }

    @Test
    void thirtyMinutesFetchedAsFifteen() {
        fake.onGet(CHART + "AAPL").query("interval", "15m").respondFixture("chart/aapl-15m-prepost.json");

        PriceHistory h = yf.ticker("AAPL")
                .history(HistoryRequest.builder()
                        .period(Period.ONE_DAY)
                        .interval(Interval.THIRTY_MINUTES)
                        .prepost(true)
                        .build());

        assertThat(h.interval()).isEqualTo(Interval.THIRTY_MINUTES);
        assertThat(query(chartRequests().getFirst())).containsEntry("includePrePost", List.of("true"));
    }

    @Test
    void symbolIsPathEncoded() {
        fake.onGet(CHART + "^GSPC").respondFixture("chart/btc-usd-1d-5d.json");

        yf.ticker("^gspc").history(Period.FIVE_DAYS);

        assertThat(chartRequests().getFirst().uri().getRawPath()).isEqualTo(CHART + "%5EGSPC");
    }

    @Test
    void actionsFromMaxDailyHistory() {
        fake.onGet(CHART + "AAPL").hasQuery("period1").respondFixture("chart/aapl-split-dividend-2020.json");
        Ticker aapl = yf.ticker("AAPL");

        assertThat(aapl.splits())
                .singleElement()
                .satisfies(s -> assertThat(s.ratio()).isEqualTo(4.0));
        assertThat(aapl.dividends())
                .singleElement()
                .satisfies(d -> assertThat(d.amount()).isEqualTo(0.205));
        assertThat(aapl.capitalGains()).isEmpty();
        Actions actions = aapl.actions();
        assertThat(actions.dividends()).hasSize(1);
        assertThat(actions.splits()).hasSize(1);
        assertThat(query(chartRequests().getFirst()))
                .containsEntry("interval", List.of("1d"))
                .containsKeys("period1", "period2");
    }

    @Test
    void capitalGainsOfEtf() {
        fake.onGet(CHART + "SCHD").respondFixture("chart/schd-1wk-capital-gains.json");

        assertThat(yf.ticker("SCHD").capitalGains())
                .singleElement()
                .satisfies(g -> assertThat(g.amount()).isEqualTo(0.1234));
    }

    @Test
    void historyMetadataFromShortDailyRequest() {
        fake.onGet(CHART + "BTC-USD").query("range", "5d").respondFixture("chart/btc-usd-1d-5d.json");

        HistoryMetadata m = yf.ticker("BTC-USD").historyMetadata();

        assertThat(m.symbol()).isEqualTo("BTC-USD");
        assertThat(m.exchangeZone()).isEqualTo(ZoneId.of("UTC"));
        assertThat(m.instrumentType()).isEqualTo("CRYPTOCURRENCY");
        assertThat(query(chartRequests().getFirst())).containsEntry("interval", List.of("1d"));
    }

    @Test
    void rateLimitedThroughTicker() {
        fake.onGet(CHART + "AAPL").respond(429, "Too Many Requests");

        // end to end: Ticker -> session -> retry policy (no retries configured) -> typed exception
        assertThatThrownBy(() -> yf.ticker("AAPL").history(Period.ONE_MONTH)).isInstanceOf(YFRateLimitException.class);
        assertThat(chartRequests()).hasSize(1);
    }

    @Test
    void delistedSymbolNotFound() {
        fake.onGet(CHART + "NOSUCHSYMBOLXYZ").respondFixture(404, "chart/not-found.json");
        Ticker ticker = yf.ticker("NOSUCHSYMBOLXYZ");

        assertThatThrownBy(() -> ticker.history(Period.ONE_MONTH))
                .isInstanceOfSatisfying(
                        YFSymbolNotFoundException.class,
                        e -> assertThat(e.symbol()).isEqualTo("NOSUCHSYMBOLXYZ"))
                .hasMessageContaining("delisted");
        // the timezone lookup for a date range fails the same way
        assertThatThrownBy(() -> ticker.history(
                        HistoryRequest.builder().start(LocalDate.of(2020, 1, 1)).build()))
                .isInstanceOf(YFSymbolNotFoundException.class);
        assertThatThrownBy(ticker::dividends).isInstanceOf(YFSymbolNotFoundException.class);
        assertThat(cache.get("tz", "NOSUCHSYMBOLXYZ")).isEmpty();
    }

    @Test
    void invalidIntervalRangeCombinationRejected() {
        assertThatThrownBy(() -> HistoryRequest.builder()
                        .period(Period.ONE_YEAR)
                        .interval(Interval.ONE_MINUTE)
                        .build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HistoryRequest.builder()
                        .start(LocalDate.of(2020, 1, 1))
                        .interval(Interval.FIVE_MINUTES)
                        .build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(fake.requests()).isEmpty();
    }

    @Test
    void malformedPayloadIsDataException() {
        fake.onGet(CHART + "AAPL")
                .thenRespond(200, "<html>Will be right back</html>")
                .thenRespond(200, "{\"chart\":{\"result\":[],\"error\":null}}")
                .thenRespond(
                        200,
                        "{\"chart\":{\"result\":[{\"meta\":{\"exchangeTimezoneName\":\"UTC\"},\"timestamp\":[1,2],"
                                + "\"indicators\":{\"quote\":[{\"close\":[1.0]}]}}],\"error\":null}}")
                .register();
        Ticker aapl = yf.ticker("AAPL");

        assertThatThrownBy(aapl::history).isInstanceOf(YFDataException.class).hasMessageContaining("invalid JSON");
        assertThatThrownBy(aapl::history).isInstanceOf(YFDataException.class).hasMessageContaining("no result");
        assertThatThrownBy(aapl::history).isInstanceOf(YFDataException.class).hasMessageContaining("malformed");
    }

    @Test
    void closedClientRejectsCalls() {
        Ticker aapl = yf.ticker("AAPL");
        yf.close();

        assertThatThrownBy(aapl::history).isInstanceOf(IllegalStateException.class);
    }
}
