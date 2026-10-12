package io.andreygrigoriev.yfin.internal.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import io.andreygrigoriev.yfin.exception.YFAuthException;
import io.andreygrigoriev.yfin.exception.YFRateLimitException;
import io.andreygrigoriev.yfin.spi.Cache;
import io.andreygrigoriev.yfin.spi.HttpRequest;
import io.andreygrigoriev.yfin.spi.HttpResponse;
import io.andreygrigoriev.yfin.spi.Transport;
import io.andreygrigoriev.yfin.testing.FakeTransport;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class YahooSessionTest {

    private static final Instant NOW = Instant.parse("2026-10-12T10:00:00Z");
    private static final String CRUMB_PATH = "/v1/test/getcrumb";
    private static final String QUOTE_PATH = "/v7/finance/quote";
    private static final URI QUOTE_URI = URI.create("https://query1.finance.yahoo.com/v7/finance/quote?symbols=AAPL");

    private final FakeTransport fake = FakeTransport.create();
    private final MemoryCache cache = new MemoryCache();

    /** Thread-safe in-memory cache. */
    private static final class MemoryCache implements Cache {
        final Map<String, String> entries = new ConcurrentHashMap<>();

        @Override
        public Optional<String> get(String namespace, String key) {
            return Optional.ofNullable(entries.get(namespace + "/" + key));
        }

        @Override
        public void put(String namespace, String key, String value) {
            entries.put(namespace + "/" + key, value);
        }

        @Override
        public void invalidate(String namespace, String key) {
            entries.remove(namespace + "/" + key);
        }

        Optional<String> session() {
            return get(YahooSession.CACHE_NAMESPACE, YahooSession.CACHE_KEY);
        }
    }

    private YahooSession session() {
        return session(fake);
    }

    private YahooSession session(Transport transport) {
        return new YahooSession(
                transport, null, cache, new RequestLimiter(8), RetryPolicy.of(0), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static HttpResponse withSetCookie(int status, String body, String... setCookies) {
        return new HttpResponse(
                status, Map.of("Set-Cookie", List.of(setCookies)), body.getBytes(StandardCharsets.UTF_8));
    }

    private void basicCookie() {
        fake.onGet("")
                .respond(withSetCookie(
                        404,
                        "<html>not found</html>",
                        "A3=d=AQABBcookie; Max-Age=31557600; Domain=.yahoo.com; Path=/"));
    }

    private void quoteOk() {
        fake.onGet(QUOTE_PATH).hasQuery("crumb").respondFixture("session/quote-ok.json");
    }

    private List<HttpRequest> requestsTo(String path) {
        return fake.requests().stream()
                .filter(r -> r.uri().getPath().equals(path))
                .toList();
    }

    private static String crumbOf(HttpRequest request) {
        return FakeTransport.parseQuery(request.uri().getRawQuery())
                .get("crumb")
                .getFirst();
    }

    private void cacheSession(String crumb, long cookieExpiresAt) {
        cache.put(YahooSession.CACHE_NAMESPACE, YahooSession.CACHE_KEY, """
                {"crumb":"%s","strategy":"BASIC","cookies":[{"name":"A3","value":"cached","domain":".yahoo.com",\
                "path":"/","secure":true,"httpOnly":true,"expiresAt":%d}]}""".formatted(crumb, cookieExpiresAt));
    }

    @Test
    void basicStrategyFetchesCookieAndCrumbThenSendsBoth() {
        basicCookie();
        fake.onGet(CRUMB_PATH).respond(200, "AbC/d.e1");
        quoteOk();

        HttpResponse response = session().execute(HttpRequest.get(QUOTE_URI));

        assertThat(response.status()).isEqualTo(200);
        assertThat(fake.requests())
                .extracting(r -> r.uri().getHost())
                .containsExactly("fc.yahoo.com", "query1.finance.yahoo.com", "query1.finance.yahoo.com");
        HttpRequest crumbRequest = requestsTo(CRUMB_PATH).getFirst();
        assertThat(crumbRequest.header("Cookie")).contains("A3=d=AQABBcookie");
        HttpRequest quote = requestsTo(QUOTE_PATH).getFirst();
        assertThat(crumbOf(quote)).isEqualTo("AbC/d.e1");
        assertThat(quote.uri().getRawQuery()).isEqualTo("symbols=AAPL&crumb=AbC%2Fd.e1");
        assertThat(quote.header("Cookie")).contains("A3=d=AQABBcookie");
    }

    @Test
    void persistsCrumbCookiesAndStrategy() throws Exception {
        basicCookie();
        fake.onGet(CRUMB_PATH).respond(200, "crumb1");
        var session = session();

        assertThat(session.crumb()).isEqualTo("crumb1");

        JsonNode stored = YahooJson.mapper().readTree(cache.session().orElseThrow());
        assertThat(stored.path("crumb").asText()).isEqualTo("crumb1");
        assertThat(stored.path("strategy").asText()).isEqualTo("BASIC");
        JsonNode cookie = stored.path("cookies").get(0);
        assertThat(cookie.path("name").asText()).isEqualTo("A3");
        assertThat(cookie.path("value").asText()).isEqualTo("d=AQABBcookie");
        assertThat(cookie.path("domain").asText()).isEqualTo(".yahoo.com");
        assertThat(cookie.path("expiresAt").asLong()).isEqualTo(NOW.getEpochSecond() + 31557600);
    }

    @Test
    void cachedCrumbIsReusedWithoutFetching() {
        cacheSession("cached1", NOW.getEpochSecond() + 3600);
        quoteOk();

        session().execute(HttpRequest.get(QUOTE_URI));

        HttpRequest quote = fake.singleRequest();
        assertThat(crumbOf(quote)).isEqualTo("cached1");
        assertThat(quote.header("Cookie")).contains("A3=cached");
    }

    @Test
    void sessionFromOneInstanceIsRestoredByTheNext() {
        basicCookie();
        fake.onGet(CRUMB_PATH).respond(200, "crumb1");
        quoteOk();
        session().crumb();
        fake.clearRequests();

        session().execute(HttpRequest.get(QUOTE_URI));

        assertThat(crumbOf(fake.singleRequest())).isEqualTo("crumb1");
    }

    @Test
    void expiredCachedCookiesForceNewFetch() {
        cacheSession("stale", NOW.getEpochSecond() - 1);
        basicCookie();
        fake.onGet(CRUMB_PATH).respond(200, "fresh");

        assertThat(session().crumb()).isEqualTo("fresh");
        assertThat(requestsTo(CRUMB_PATH)).hasSize(1);
    }

    @Test
    void corruptCacheEntryIsIgnored() {
        cache.put(YahooSession.CACHE_NAMESPACE, YahooSession.CACHE_KEY, "{not json");
        basicCookie();
        fake.onGet(CRUMB_PATH).respond(200, "fresh");

        assertThat(session().crumb()).isEqualTo("fresh");
    }

    @Test
    void invalidCrumbIsRefreshedOnceAndRequestRetried() {
        cacheSession("old", NOW.getEpochSecond() + 3600);
        basicCookie();
        fake.onGet(CRUMB_PATH).respond(200, "new");
        fake.onGet(QUOTE_PATH)
                .query("crumb", "old")
                .respondFixture(401, "session/invalid-crumb.json")
                .onGet(QUOTE_PATH)
                .query("crumb", "new")
                .respondFixture("session/quote-ok.json");

        HttpResponse response = session().execute(HttpRequest.get(QUOTE_URI));

        assertThat(response.status()).isEqualTo(200);
        assertThat(requestsTo(CRUMB_PATH)).hasSize(1);
        assertThat(requestsTo(QUOTE_PATH)).extracting(YahooSessionTest::crumbOf).containsExactly("old", "new");
        assertThat(cache.session()).hasValueSatisfying(v -> assertThat(v).contains("\"new\""));
    }

    @Test
    void invalidCrumbBodyWithoutStatus401AlsoTriggersRefresh() {
        cacheSession("old", NOW.getEpochSecond() + 3600);
        basicCookie();
        fake.onGet(CRUMB_PATH).respond(200, "new");
        fake.onGet(QUOTE_PATH)
                .query("crumb", "old")
                .respondFixture(400, "session/invalid-crumb.json")
                .onGet(QUOTE_PATH)
                .query("crumb", "new")
                .respondFixture("session/quote-ok.json");

        assertThat(session().execute(HttpRequest.get(QUOTE_URI)).status()).isEqualTo(200);
    }

    @Test
    void rejectedFreshCrumbFailsWithAuthException() {
        basicCookie();
        fake.onGet(CRUMB_PATH).respond(200, "crumb");
        fake.onGet(QUOTE_PATH).respondFixture(401, "session/invalid-crumb.json");

        assertThatThrownBy(() -> session().execute(HttpRequest.get(QUOTE_URI)))
                .isInstanceOf(YFAuthException.class)
                .hasMessageContaining("401");
        assertThat(requestsTo(QUOTE_PATH)).hasSize(2);
        assertThat(requestsTo(CRUMB_PATH)).hasSize(2);
        assertThat(cache.session()).isEmpty();
    }

    @Test
    void otherErrorStatusesAreReturnedAsIs() {
        basicCookie();
        fake.onGet(CRUMB_PATH).respond(200, "crumb");
        fake.onGet(QUOTE_PATH).respondFixture(404, "session/quote-summary-not-found.json");

        assertThat(session().execute(HttpRequest.get(QUOTE_URI)).status()).isEqualTo(404);
        assertThat(requestsTo(CRUMB_PATH)).hasSize(1);
    }

    @Test
    void fallsBackToConsentFlowAndRemembersIt() {
        basicCookie();
        fake.onGet(CRUMB_PATH)
                .thenRespond(200, "<html><body>blocked</body></html>")
                .thenRespond(200, "csrfCrumb")
                .register();
        fake.onGet("/consent").thenFixture("session/consent.html").register();
        fake.onPost("/v2/collectConsent").respond(200, "");
        fake.onGet("/copyConsent").respond(200, "");
        quoteOk();
        var session = session();

        session.execute(HttpRequest.get(QUOTE_URI));

        assertThat(session.strategy()).isEqualTo(YahooSession.Strategy.CSRF);
        assertThat(fake.requests())
                .extracting(r -> r.uri().getHost() + r.uri().getPath())
                .containsExactly(
                        "fc.yahoo.com",
                        "query1.finance.yahoo.com/v1/test/getcrumb",
                        "guce.yahoo.com/consent",
                        "consent.yahoo.com/v2/collectConsent",
                        "guce.yahoo.com/copyConsent",
                        "query2.finance.yahoo.com/v1/test/getcrumb",
                        "query1.finance.yahoo.com/v7/finance/quote");
        HttpRequest post = requestsTo("/v2/collectConsent").getFirst();
        assertThat(post.uri().getRawQuery()).isEqualTo("sessionId=3_cc-session_abc-123");
        assertThat(post.header("Content-Type")).contains("application/x-www-form-urlencoded");
        assertThat(FakeTransport.parseQuery(post.bodyAsString().orElseThrow()))
                .containsEntry("agree", List.of("agree", "agree"))
                .containsEntry("csrfToken", List.of("Xq7_ab9-CSRF"))
                .containsEntry("sessionId", List.of("3_cc-session_abc-123"))
                .containsEntry("consentUUID", List.of("default"))
                .containsEntry("originalDoneUrl", List.of("https://finance.yahoo.com/"))
                .containsEntry("namespace", List.of("yahoo"));
        assertThat(crumbOf(requestsTo(QUOTE_PATH).getFirst())).isEqualTo("csrfCrumb");
        assertThat(cache.session()).hasValueSatisfying(v -> assertThat(v).contains("\"CSRF\""));

        // next acquisition starts with the strategy that worked
        session.invalidate("csrfCrumb");
        fake.clearRequests();
        session.crumb();
        assertThat(fake.requests().getFirst().uri().getHost()).isEqualTo("guce.yahoo.com");
    }

    @Test
    void failsWithAuthExceptionWhenBothStrategiesFail() {
        basicCookie();
        fake.onGet(CRUMB_PATH).respond(403, "<html>forbidden</html>");
        fake.onGet("/consent").respondFixture("session/consent-no-token.html");
        var session = session();

        assertThatThrownBy(session::crumb)
                .isInstanceOf(YFAuthException.class)
                .hasMessageContaining("BASIC")
                .hasMessageContaining("CSRF");
        assertThat(cache.session()).isEmpty();
        assertThat(session.strategy()).isEqualTo(YahooSession.Strategy.BASIC);
    }

    @Test
    void transportFailureOfOneStrategyFallsBackToTheOther() {
        Transport transport = request -> {
            if (request.uri().getHost().equals("fc.yahoo.com")) {
                throw new io.andreygrigoriev.yfin.exception.YFTransportException("unknown host fc.yahoo.com");
            }
            return fake.send(request);
        };
        fake.onGet(CRUMB_PATH).respond(200, "csrfCrumb");
        fake.onGet("/consent").respondFixture("session/consent.html");
        fake.onPost("/v2/collectConsent").respond(200, "");
        fake.onGet("/copyConsent").respond(200, "");
        var session = session(transport);

        assertThat(session.crumb()).isEqualTo("csrfCrumb");
        assertThat(session.strategy()).isEqualTo(YahooSession.Strategy.CSRF);
    }

    @Test
    void rateLimitedCrumbFetchPropagates() {
        basicCookie();
        fake.onGet(CRUMB_PATH).respond(429, "Too Many Requests");

        assertThatThrownBy(() -> session().crumb()).isInstanceOf(YFRateLimitException.class);
    }

    @Test
    void concurrentCallsTriggerExactlyOneCrumbFetch() throws Exception {
        basicCookie();
        fake.onGet(CRUMB_PATH).respond(200, "shared");
        quoteOk();
        Transport slowCrumb = request -> {
            if (request.uri().getPath().equals(CRUMB_PATH)) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return fake.send(request);
        };
        var session = session(slowCrumb);
        var start = new CountDownLatch(1);
        var futures = new ArrayList<Future<HttpResponse>>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 50; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return session.execute(HttpRequest.get(QUOTE_URI));
                }));
            }
            start.countDown();
            for (Future<HttpResponse> f : futures) {
                assertThat(f.get().status()).isEqualTo(200);
            }
        }

        assertThat(requestsTo(CRUMB_PATH)).hasSize(1);
        assertThat(requestsTo("")).hasSize(1);
        assertThat(requestsTo(QUOTE_PATH))
                .hasSize(50)
                .allSatisfy(r -> assertThat(crumbOf(r)).isEqualTo("shared"));
    }

    @Test
    void concurrentCrumbRejectionsRefreshOnlyOnce() throws Exception {
        cacheSession("old", NOW.getEpochSecond() + 3600);
        basicCookie();
        fake.onGet(CRUMB_PATH).respond(200, "new");
        fake.onGet(QUOTE_PATH)
                .query("crumb", "old")
                .respondFixture(401, "session/invalid-crumb.json")
                .onGet(QUOTE_PATH)
                .query("crumb", "new")
                .respondFixture("session/quote-ok.json");
        var session = session();
        var start = new CountDownLatch(1);
        var futures = new ArrayList<Future<HttpResponse>>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 20; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return session.execute(HttpRequest.get(QUOTE_URI));
                }));
            }
            start.countDown();
            for (Future<HttpResponse> f : futures) {
                assertThat(f.get().status()).isEqualTo(200);
            }
        }

        assertThat(requestsTo(CRUMB_PATH)).hasSize(1);
    }

    @Test
    void createUsesJdkTransportCookieStore() {
        try (var jdk = JdkTransport.create()) {
            var session = YahooSession.create(jdk, cache, new RequestLimiter(1), RetryPolicy.of(0));
            assertThat(session.strategy()).isEqualTo(YahooSession.Strategy.BASIC);
        }
        cacheSession("cached1", NOW.getEpochSecond() + 3_000_000_000L);
        try (var jdk = JdkTransport.create()) {
            var session = YahooSession.create(jdk, cache, new RequestLimiter(1), RetryPolicy.of(0));
            assertThat(session.crumb()).isEqualTo("cached1");
            assertThat(jdk.cookieStore().get(URI.create("https://query1.finance.yahoo.com/")))
                    .extracting(c -> c.getName() + "=" + c.getValue())
                    .containsExactly("A3=cached");
        }
    }

    @Test
    void withCrumbAppendsParameter() {
        assertThat(YahooSession.withCrumb(HttpRequest.get(URI.create("https://h/p")), "a b")
                        .uri()
                        .toString())
                .isEqualTo("https://h/p?crumb=a+b");
        assertThat(YahooSession.withCrumb(HttpRequest.get(URI.create("https://h/p?x=1")), "c")
                        .uri()
                        .toString())
                .isEqualTo("https://h/p?x=1&crumb=c");
        assertThat(YahooSession.withCrumb(HttpRequest.get(URI.create("https://h/p?")), "c")
                        .uri()
                        .toString())
                .isEqualTo("https://h/p?crumb=c");
        var post = HttpRequest.postJson(URI.create("https://h/p"), "{}");
        var withCrumb = YahooSession.withCrumb(post, "c");
        assertThat(withCrumb.method()).isEqualTo("POST");
        assertThat(withCrumb.bodyAsString()).contains("{}");
        assertThat(withCrumb.header("Content-Type")).contains("application/json");
    }

    @Test
    void hiddenInputsParsesAttributesInAnyOrder() {
        assertThat(YahooSession.hiddenInputs(
                        "<INPUT name='a' value='1'><input value=\"2\" type=\"hidden\" name=\"b\"><input name=\"c\">"))
                .containsExactly(Map.entry("a", "1"), Map.entry("b", "2"));
    }

    @Test
    void plausibleCrumb() {
        assertThat(YahooSession.isPlausibleCrumb("AbC/d.e1")).isTrue();
        assertThat(YahooSession.isPlausibleCrumb("")).isFalse();
        assertThat(YahooSession.isPlausibleCrumb("<html>")).isFalse();
        assertThat(YahooSession.isPlausibleCrumb("Too Many Requests")).isFalse();
        assertThat(YahooSession.isPlausibleCrumb("{\"finance\":1}")).isFalse();
        assertThat(YahooSession.isPlausibleCrumb("x".repeat(65))).isFalse();
    }
}
