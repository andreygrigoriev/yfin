package io.andreygrigoriev.yfin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.andreygrigoriev.yfin.model.Bar;
import io.andreygrigoriev.yfin.model.FastInfo;
import io.andreygrigoriev.yfin.model.HistoryMetadata;
import io.andreygrigoriev.yfin.model.PriceHistory;
import io.andreygrigoriev.yfin.request.HistoryRequest;
import io.andreygrigoriev.yfin.request.Interval;
import io.andreygrigoriev.yfin.request.Period;
import io.andreygrigoriev.yfin.spi.Cache;
import io.andreygrigoriev.yfin.spi.HttpRequest;
import io.andreygrigoriev.yfin.testing.FakeTransport;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Expected values were computed with yfinance's {@code FastInfo} logic over the same recorded
 * responses, at the recording time.
 */
class FastInfoTest {

    /** When the fixtures were recorded: Monday 2026-10-12 11:21 New York, market open. */
    private static final Instant RECORDED = Instant.ofEpochSecond(1_791_818_515L);

    private static final String CHART = "/v8/finance/chart/";

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

    private void chart(String symbol, String range, String interval, String fixture) {
        fake.onGet(CHART + symbol)
                .query("range", range)
                .query("interval", interval)
                .respondFixture("chart/" + fixture);
    }

    private void quote(String symbol, String fixture) {
        fake.onGet("/v7/finance/quote").query("symbols", symbol).respondFixture("quote/" + fixture);
    }

    private List<HttpRequest> dataRequests() {
        return fake.requests().stream()
                .filter(r -> !r.uri().getPath().equals("/v1/test/getcrumb")
                        && !r.uri().getPath().isEmpty())
                .toList();
    }

    @Test
    void equityDuringTheSession() {
        chart("AAPL", "1y", "1d", "aapl-1d-1y.json");
        chart("AAPL", "5d", "1h", "aapl-1h-5d-prepost.json");
        quote("AAPL", "aapl.json");

        FastInfo fi = yf.ticker("AAPL").fastInfo(() -> RECORDED);

        assertThat(fi.currency()).isEqualTo("USD");
        assertThat(fi.quoteType()).isEqualTo("EQUITY");
        assertThat(fi.exchange()).isEqualTo("NMS");
        assertThat(fi.timezone()).isEqualTo(ZoneId.of("America/New_York"));
        // latest (today's, partial) bar
        assertThat(fi.lastPrice()).isEqualTo(340.5008850097656);
        assertThat(fi.open()).isEqualTo(338.1600036621094);
        assertThat(fi.dayHigh()).isEqualTo(342.6300048828125);
        assertThat(fi.dayLow()).isEqualTo(337.6000061035156);
        assertThat(fi.lastVolume()).isEqualTo(9_001_077L);
        assertThat(fi.regularMarketPreviousClose()).isEqualTo(336.6400146484375);
        // last hourly close of Friday 2026-10-09, post-market included
        assertThat(fi.previousClose()).isEqualTo(336.64);
        // full days only: today is left out while the session runs
        assertThat(fi.fiftyDayAverage()).isCloseTo(322.38860107421874, within(1e-9));
        assertThat(fi.twoHundredDayAverage()).isCloseTo(290.74344970703123, within(1e-9));
        assertThat(fi.tenDayAverageVolume()).isEqualTo(36_305_750L);
        assertThat(fi.threeMonthAverageVolume()).isEqualTo(45_096_883L);
        assertThat(fi.yearHigh()).isEqualTo(345.3399963378906);
        assertThat(fi.yearLow()).isEqualTo(243.4199981689453);
        assertThat(fi.yearChange()).isCloseTo(0.35928292687795665, within(1e-12));
    }

    @Test
    void sharesAndMarketCapComeFromTheQuote() {
        chart("AAPL", "1y", "1d", "aapl-1d-1y.json");
        quote("AAPL", "aapl.json");

        FastInfo fi = yf.ticker("AAPL").fastInfo();

        assertThat(fi.shares()).isEqualTo(14_594_180_000L);
        assertThat(fi.marketCap()).isEqualTo(14_594_180_000L * fi.lastPrice());
    }

    @Test
    void cryptoWithoutSharesUsesTheQuotedMarketCap() {
        chart("BTC-USD", "1y", "1d", "btc-usd-1d-1y.json");
        quote("BTC-USD", "btc-usd.json");

        FastInfo fi = yf.ticker("BTC-USD").fastInfo(() -> RECORDED);

        assertThat(fi.timezone()).isEqualTo(ZoneId.of("UTC"));
        assertThat(fi.quoteType()).isEqualTo("CRYPTOCURRENCY");
        assertThat(fi.lastPrice()).isEqualTo(82550.3984375);
        assertThat(fi.regularMarketPreviousClose()).isEqualTo(83679.46875);
        assertThat(fi.fiftyDayAverage()).isCloseTo(80942.0040625, within(1e-6));
        assertThat(fi.tenDayAverageVolume()).isEqualTo(26_412_483_267L);
        assertThat(fi.threeMonthAverageVolume()).isEqualTo(27_870_832_702L);
        assertThat(fi.yearHigh()).isEqualTo(116273.3125);
        assertThat(fi.yearChange()).isCloseTo(-0.2740636236675261, within(1e-12));
        assertThat(fi.shares()).isNull();
        assertThat(fi.marketCap()).isEqualTo(1_660_562_440_192.0);
    }

    @Test
    void afterTheSessionTodayCountsAsAFullDay() {
        chart("AAPL", "1y", "1d", "aapl-1d-1y.json");
        Ticker aapl = yf.ticker("AAPL");
        // Tuesday 2026-10-13 11:21 New York: Monday's bar is complete, and no newer bar exists
        FastInfo fi = aapl.fastInfo(() -> RECORDED.plus(Duration.ofDays(1)));

        List<Bar> bars = aapl.history(HistoryRequest.builder()
                        .period(Period.ONE_YEAR)
                        .autoAdjust(false)
                        .keepNa(true)
                        .build())
                .bars();
        // window is 2025-10-14 .. 2026-10-13: the first bar (2025-10-13) drops out
        List<Bar> window = bars.subList(1, bars.size());
        double expected50 = window.subList(window.size() - 50, window.size()).stream()
                .mapToDouble(Bar::close)
                .average()
                .orElseThrow();
        assertThat(fi.fiftyDayAverage()).isCloseTo(expected50, within(1e-9));
        double first = window.getFirst().close();
        assertThat(fi.yearChange()).isCloseTo((window.getLast().close() - first) / first, within(1e-12));
    }

    @Test
    void emptyHistoryFallsBackToMetadataAndQuote() {
        chart("AAPL", "1y", "1d", "aapl-weekend-empty.json");
        chart("AAPL", "5d", "1h", "aapl-weekend-empty.json");
        quote("AAPL", "aapl.json");

        FastInfo fi = yf.ticker("AAPL").fastInfo();

        assertThat(fi.lastPrice()).isEqualTo(340.93); // chart metadata regularMarketPrice
        assertThat(fi.previousClose()).isEqualTo(336.64); // quote regularMarketPreviousClose
        assertThat(fi.regularMarketPreviousClose()).isEqualTo(336.64);
        assertThat(fi.open()).isNull();
        assertThat(fi.dayHigh()).isNull();
        assertThat(fi.dayLow()).isNull();
        assertThat(fi.lastVolume()).isNull();
        assertThat(fi.fiftyDayAverage()).isNull();
        assertThat(fi.twoHundredDayAverage()).isNull();
        assertThat(fi.tenDayAverageVolume()).isNull();
        assertThat(fi.threeMonthAverageVolume()).isNull();
        assertThat(fi.yearHigh()).isNull();
        assertThat(fi.yearLow()).isNull();
        assertThat(fi.yearChange()).isNull();
        assertThat(fi.currency()).isEqualTo("USD");
    }

    @Test
    void lazyAndFetchedOncePerInstance() {
        chart("AAPL", "1y", "1d", "aapl-1d-1y.json");
        quote("AAPL", "aapl.json");

        FastInfo fi = yf.ticker("AAPL").fastInfo();
        assertThat(dataRequests()).isEmpty();

        fi.currency();
        fi.lastPrice();
        fi.fiftyDayAverage();
        assertThat(dataRequests()).hasSize(1);
        assertThat(dataRequests().getFirst().uri().getPath()).isEqualTo(CHART + "AAPL");

        fi.shares();
        fi.marketCap();
        assertThat(dataRequests()).hasSize(2);
        assertThat(dataRequests().getLast().uri().getPath()).isEqualTo("/v7/finance/quote");
    }

    @Test
    void toMapHasYfinanceKeys() {
        var calls = new AtomicInteger();
        FastInfo fi = new FastInfo(new FastInfo.Source() {
            @Override
            public PriceHistory yearDaily() {
                calls.incrementAndGet();
                return emptyHistory();
            }

            @Override
            public PriceHistory weekHourly(boolean prepost) {
                calls.incrementAndGet();
                return emptyHistory();
            }

            @Override
            public Map<String, Object> quote() {
                calls.incrementAndGet();
                return Map.of("sharesOutstanding", 10L);
            }
        });

        Map<String, Object> map = fi.toMap();

        assertThat(map.keySet())
                .containsExactly(
                        "currency",
                        "dayHigh",
                        "dayLow",
                        "exchange",
                        "fiftyDayAverage",
                        "lastPrice",
                        "lastVolume",
                        "marketCap",
                        "open",
                        "previousClose",
                        "quoteType",
                        "regularMarketPreviousClose",
                        "shares",
                        "tenDayAverageVolume",
                        "threeMonthAverageVolume",
                        "timezone",
                        "twoHundredDayAverage",
                        "yearChange",
                        "yearHigh",
                        "yearLow");
        assertThat(map)
                .containsEntry("shares", 10L)
                .containsEntry("lastPrice", 1.5)
                .containsEntry("marketCap", 15.0);
        // year daily, hourly with and without prepost, quote: each at most once
        assertThat(calls).hasValue(3);
    }

    private static PriceHistory emptyHistory() {
        ZoneId zone = ZoneId.of("America/New_York");
        var md = new HistoryMetadata(
                "X", "USD", "NMS", null, "EQUITY", zone, null, null, null, null, 1.5, null, null, null, null, null,
                null, null, null, null, null, null, null, null, List.of(), Map.of());
        return new PriceHistory("X", zone, Interval.ONE_DAY, "USD", List.of(), List.of(), List.of(), List.of(), md);
    }
}
