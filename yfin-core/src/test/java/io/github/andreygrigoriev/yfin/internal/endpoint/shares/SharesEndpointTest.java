package io.github.andreygrigoriev.yfin.internal.endpoint.shares;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.andreygrigoriev.yfin.YFinance;
import io.github.andreygrigoriev.yfin.exception.YFDataException;
import io.github.andreygrigoriev.yfin.internal.cache.FileCache;
import io.github.andreygrigoriev.yfin.internal.http.RequestLimiter;
import io.github.andreygrigoriev.yfin.internal.http.RetryPolicy;
import io.github.andreygrigoriev.yfin.internal.http.YahooJson;
import io.github.andreygrigoriev.yfin.internal.http.YahooSession;
import io.github.andreygrigoriev.yfin.model.SharesCount;
import io.github.andreygrigoriev.yfin.spi.Cache;
import io.github.andreygrigoriev.yfin.spi.HttpRequest;
import io.github.andreygrigoriev.yfin.testing.FakeTransport;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SharesEndpointTest {

    private static final String PATH = "/ws/fundamentals-timeseries/v1/finance/timeseries/";
    private static final ZoneId NY = ZoneId.of("America/New_York");

    @TempDir
    Path cacheDir;

    private final FakeTransport fake = FakeTransport.create();
    private Cache cache;
    private YFinance yf;

    @BeforeEach
    void setUp() {
        fake.onGet("").respond(404, "not found");
        fake.onGet("/v1/test/getcrumb").respond(200, "crumb1");
        cache = new FileCache(cacheDir);
        cache.put("tz", "AAPL", "America/New_York");
        cache.put("tz", "BTC-USD", "UTC");
        cache.put("tz", "ZZZZNOTREAL", "America/New_York");
        yf = YFinance.builder().transport(fake).cache(cache).maxRetries(0).build();
    }

    @AfterEach
    void tearDown() {
        yf.close();
    }

    @Test
    void sharesSortedOldestFirstInExchangeTimezone() {
        fake.onGet(PATH + "AAPL").respondFixture("shares/aapl.json");

        List<SharesCount> shares = yf.ticker("AAPL").sharesFull(LocalDate.of(2025, 1, 1), LocalDate.of(2026, 10, 13));

        assertThat(shares).hasSize(80);
        assertThat(shares).isSortedAccordingTo((a, b) -> a.time().compareTo(b.time()));
        // 1735948800 = 2025-01-04T00:00Z, localized (not converted) like yfinance; duplicates kept in order
        assertThat(shares.get(0))
                .isEqualTo(new SharesCount(ZonedDateTime.of(2025, 1, 4, 0, 0, 0, 0, NY), 15115799552L));
        assertThat(shares.get(1))
                .isEqualTo(new SharesCount(ZonedDateTime.of(2025, 1, 4, 0, 0, 0, 0, NY), 15146300416L));
        assertThat(shares.getLast().shares()).isEqualTo(14594180000L);
        assertThat(shares.getLast().time().getZone()).isEqualTo(NY);
    }

    @Test
    void requestHasSymbolAndPeriodsWithoutType() {
        fake.onGet(PATH + "AAPL").respondFixture("shares/aapl.json");

        yf.ticker("AAPL").sharesFull(LocalDate.of(2025, 1, 1), LocalDate.of(2026, 10, 13));

        HttpRequest request = fake.requests().stream()
                .filter(r -> r.uri().getPath().startsWith(PATH))
                .findFirst()
                .orElseThrow();
        assertThat(request.uri().getHost()).isEqualTo("query2.finance.yahoo.com");
        Map<String, List<String>> q = FakeTransport.parseQuery(request.uri().getRawQuery());
        assertThat(q).containsEntry("symbol", List.of("AAPL")).doesNotContainKey("type");
        assertThat(q.get("period1"))
                .containsExactly(Long.toString(
                        ZonedDateTime.of(2025, 1, 1, 0, 0, 0, 0, NY).toEpochSecond()));
        assertThat(q.get("period2"))
                .containsExactly(Long.toString(
                        ZonedDateTime.of(2026, 10, 13, 0, 0, 0, 0, NY).toEpochSecond()));
        assertThat(fake.requests()).noneMatch(r -> r.uri().getPath().startsWith("/v8/finance/chart/"));
    }

    @Test
    void defaultsSpan548DaysEndingTomorrowMidnight() {
        fake.onGet(PATH + "AAPL").respondFixture("shares/aapl.json");
        YahooSession session = sessionOf();
        Instant now = ZonedDateTime.of(2026, 10, 12, 15, 30, 0, 0, NY).toInstant();

        new SharesEndpoint(session, s -> NY, Clock.fixed(now, ZoneOffset.UTC)).sharesFull("AAPL", null, null);

        Map<String, List<String>> q =
                FakeTransport.parseQuery(fake.requests().getLast().uri().getRawQuery());
        LocalDate start = LocalDate.of(2026, 10, 12).minusDays(548);
        assertThat(q.get("period1"))
                .containsExactly(Long.toString(start.atStartOfDay(NY).toEpochSecond()));
        assertThat(q.get("period2"))
                .containsExactly(Long.toString(
                        ZonedDateTime.of(2026, 10, 13, 0, 0, 0, 0, NY).toEpochSecond()));
    }

    @Test
    void noSharesDataGivesEmptyList() {
        fake.onGet(PATH + "BTC-USD").respondFixture("shares/btc-usd.json");
        fake.onGet(PATH + "ZZZZNOTREAL").respondFixture("shares/unknown.json");

        assertThat(yf.ticker("BTC-USD").sharesFull()).isEmpty();
        assertThat(yf.ticker("ZZZZNOTREAL").sharesFull()).isEmpty();
    }

    @Test
    void emptyResultGivesEmptyList() {
        JsonNode root = readJson("{\"timeseries\":{\"result\":[],\"error\":null}}");

        assertThat(SharesEndpoint.parse(root, NY)).isEmpty();
    }

    @Test
    void startNotBeforeEndRejectedWithoutRequest() {
        LocalDate day = LocalDate.of(2026, 1, 5);

        assertThatThrownBy(() -> yf.ticker("AAPL").sharesFull(day, day)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> yf.ticker("AAPL").sharesFull(day.plusDays(1), day))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(fake.requests()).noneMatch(r -> r.uri().getPath().startsWith(PATH));
    }

    @Test
    void badRequestEnvelopeThrows() {
        fake.onGet(PATH + "AAPL")
                .respond(
                        400,
                        "{\"finance\":{\"result\":null,\"error\":{\"code\":\"Bad Request\",\"description\":\"x\"}}}");

        assertThatThrownBy(() -> yf.ticker("AAPL").sharesFull())
                .isInstanceOf(YFDataException.class)
                .hasMessageContaining("Bad Request");
    }

    @Test
    void mismatchedArraysThrow() {
        JsonNode root = readJson("{\"timeseries\":{\"result\":[{\"timestamp\":[1,2],\"shares_out\":[5]}]}}");

        assertThatThrownBy(() -> SharesEndpoint.parse(root, NY)).isInstanceOf(YFDataException.class);
    }

    private YahooSession sessionOf() {
        return YahooSession.create(fake, Cache.none(), new RequestLimiter(1), RetryPolicy.of(0));
    }

    private static JsonNode readJson(String json) {
        try {
            return YahooJson.mapper().readTree(json);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
