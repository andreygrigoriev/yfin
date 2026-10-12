package io.andreygrigoriev.yfin.internal.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.andreygrigoriev.yfin.exception.YFAuthException;
import io.andreygrigoriev.yfin.exception.YFTransportException;
import io.andreygrigoriev.yfin.spi.Cache;
import io.andreygrigoriev.yfin.spi.HttpRequest;
import io.andreygrigoriev.yfin.spi.HttpResponse;
import io.andreygrigoriev.yfin.spi.Transport;
import java.net.CookieManager;
import java.net.CookieStore;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Yahoo cookie + crumb session. Every API call goes through {@link #execute}, which appends the
 * crumb, runs the request through the {@link RetryPolicy} and the {@link RequestLimiter}, and
 * refreshes the crumb once when Yahoo rejects it.
 *
 * <p>Two strategies obtain a cookie and crumb, as in yfinance:
 *
 * <ul>
 *   <li>{@link Strategy#BASIC}: {@code GET https://fc.yahoo.com} sets the {@code A3} cookie, then
 *       {@code GET https://query1.finance.yahoo.com/v1/test/getcrumb}
 *   <li>{@link Strategy#CSRF}: the EU consent flow on {@code guce.yahoo.com} / {@code
 *       consent.yahoo.com}, then the crumb from {@code query2}
 * </ul>
 *
 * The strategy that last worked is tried first next time. Acquisition is single-flight: concurrent
 * callers wait for one fetch. Cookies, crumb and strategy are persisted in the {@code cookie} cache
 * namespace and restored on construction.
 *
 * <p>Cookies: when the transport manages cookies itself ({@link JdkTransport}), its store is used
 * and persisted. Otherwise the session keeps its own store, records {@code Set-Cookie} headers and
 * sends a {@code Cookie} header.
 */
public final class YahooSession {

    private static final Logger LOG = LoggerFactory.getLogger(YahooSession.class);

    /** Cache namespace and key of the persisted session. */
    static final String CACHE_NAMESPACE = "cookie";

    static final String CACHE_KEY = "session";

    static final URI BASIC_COOKIE_URI = URI.create("https://fc.yahoo.com");
    static final URI BASIC_CRUMB_URI = URI.create("https://query1.finance.yahoo.com/v1/test/getcrumb");
    static final URI CONSENT_URI = URI.create("https://guce.yahoo.com/consent");
    static final String COLLECT_CONSENT_URL = "https://consent.yahoo.com/v2/collectConsent?sessionId=";
    static final String COPY_CONSENT_URL = "https://guce.yahoo.com/copyConsent?sessionId=";
    static final URI CSRF_CRUMB_URI = URI.create("https://query2.finance.yahoo.com/v1/test/getcrumb");

    private static final Pattern INPUT_TAG = Pattern.compile("<input\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTRIBUTE =
            Pattern.compile("([a-zA-Z_:-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')", Pattern.CASE_INSENSITIVE);
    private static final int CRUMB_CHECK_LIMIT = 4096;

    /** How the cookie and crumb are obtained. */
    public enum Strategy {
        BASIC,
        CSRF;

        Strategy other() {
            return this == BASIC ? CSRF : BASIC;
        }
    }

    private final Transport transport;
    private final CookieStore cookies;
    private final boolean manageCookieHeader;
    private final Cache cache;
    private final RequestLimiter limiter;
    private final RetryPolicy retry;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();

    private volatile @Nullable String crumb;
    private volatile Strategy strategy = Strategy.BASIC;

    /**
     * @param transportCookies the transport's own cookie store, or {@code null} when the transport
     *     does not handle cookies and the session must send them itself
     */
    YahooSession(
            Transport transport,
            @Nullable CookieStore transportCookies,
            Cache cache,
            RequestLimiter limiter,
            RetryPolicy retry,
            Clock clock) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.limiter = Objects.requireNonNull(limiter, "limiter");
        this.retry = Objects.requireNonNull(retry, "retry");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.manageCookieHeader = transportCookies == null;
        this.cookies = transportCookies != null ? transportCookies : new CookieManager().getCookieStore();
        loadFromCache();
    }

    /** Session over {@code transport}, reusing its cookie store when it is a {@link JdkTransport}. */
    public static YahooSession create(Transport transport, Cache cache, RequestLimiter limiter, RetryPolicy retry) {
        CookieStore store = transport instanceof JdkTransport jdk ? jdk.cookieStore() : null;
        return new YahooSession(transport, store, cache, limiter, retry, Clock.systemUTC());
    }

    /** The strategy tried first on the next acquisition. */
    public Strategy strategy() {
        return strategy;
    }

    /**
     * Sends {@code request} with the crumb appended. A 401 or an {@code Invalid Crumb} answer drops
     * the session, fetches a new crumb and retries once.
     *
     * @return the response; non-2xx statuses other than crumb rejection are returned as is
     * @throws YFAuthException when no crumb can be obtained, or Yahoo rejects the fresh one too
     */
    public HttpResponse execute(HttpRequest request) {
        String used = crumb();
        HttpResponse response = send(withCrumb(request, used));
        if (!isCrumbRejected(response)) {
            return response;
        }
        LOG.debug("crumb rejected (HTTP {}), refreshing", response.status());
        invalidate(used);
        String fresh = crumb();
        response = send(withCrumb(request, fresh));
        if (isCrumbRejected(response)) {
            invalidate(fresh);
            throw new YFAuthException("Yahoo rejected a freshly fetched crumb: HTTP " + response.status());
        }
        return response;
    }

    /**
     * Sends {@code request} through the retry policy and the limiter without a crumb, for
     * non-Yahoo hosts that must not see the Yahoo session. Only cookies matching the request's
     * host are sent.
     */
    public HttpResponse executeWithoutCrumb(HttpRequest request) {
        return send(request);
    }

    /** The current crumb, fetching one (single-flight) when there is none. */
    public String crumb() {
        String current = crumb;
        if (current != null) {
            return current;
        }
        lock.lock();
        try {
            current = crumb;
            if (current == null) {
                current = acquire();
                crumb = current;
                persist();
            }
            return current;
        } finally {
            lock.unlock();
        }
    }

    /** Drops crumb and cookies if {@code stale} is still the current crumb. */
    void invalidate(String stale) {
        lock.lock();
        try {
            if (stale.equals(crumb)) {
                crumb = null;
                cookies.removeAll();
                cache.invalidate(CACHE_NAMESPACE, CACHE_KEY);
            }
        } finally {
            lock.unlock();
        }
    }

    // ---- acquisition ----

    private String acquire() {
        Strategy first = strategy;
        var failures = new ArrayList<String>();
        for (Strategy s : List.of(first, first.other())) {
            Optional<String> result;
            try {
                result = s == Strategy.BASIC ? basic() : csrf();
            } catch (YFTransportException e) {
                LOG.debug("{} strategy failed: {}", s, e.getMessage());
                failures.add(s + ": " + e.getMessage());
                continue;
            }
            if (result.isPresent()) {
                if (s != strategy) {
                    LOG.debug("switching session strategy to {}", s);
                }
                strategy = s;
                return result.get();
            }
            failures.add(s + ": no crumb");
            cookies.removeAll();
        }
        throw new YFAuthException("cannot obtain Yahoo cookie and crumb (" + String.join("; ", failures) + ")");
    }

    private Optional<String> basic() {
        send(HttpRequest.get(BASIC_COOKIE_URI)); // answers 404 but sets the A3 cookie
        return fetchCrumb(BASIC_CRUMB_URI);
    }

    private Optional<String> csrf() {
        HttpResponse page = send(HttpRequest.get(CONSENT_URI));
        Map<String, String> inputs = hiddenInputs(page.bodyAsString());
        String csrfToken = inputs.get("csrfToken");
        String sessionId = inputs.get("sessionId");
        if (csrfToken == null || sessionId == null) {
            LOG.debug("consent page has no csrfToken/sessionId");
            return Optional.empty();
        }
        var form = new LinkedHashMap<String, List<String>>();
        form.put("agree", List.of("agree", "agree"));
        form.put("consentUUID", List.of("default"));
        form.put("sessionId", List.of(sessionId));
        form.put("csrfToken", List.of(csrfToken));
        form.put("originalDoneUrl", List.of("https://finance.yahoo.com/"));
        form.put("namespace", List.of("yahoo"));
        String encodedId = encode(sessionId);
        send(formPost(URI.create(COLLECT_CONSENT_URL + encodedId), form));
        send(HttpRequest.get(URI.create(COPY_CONSENT_URL + encodedId)));
        return fetchCrumb(CSRF_CRUMB_URI);
    }

    private Optional<String> fetchCrumb(URI uri) {
        HttpResponse response = send(HttpRequest.get(uri));
        if (!response.isSuccess()) {
            LOG.debug("getcrumb answered HTTP {}", response.status());
            return Optional.empty();
        }
        String body = response.bodyAsString().strip();
        if (!isPlausibleCrumb(body)) {
            LOG.debug("getcrumb returned no usable crumb");
            return Optional.empty();
        }
        return Optional.of(body);
    }

    static boolean isPlausibleCrumb(String s) {
        if (s.isEmpty() || s.length() > 64) {
            return false;
        }
        return s.chars().noneMatch(c -> Character.isWhitespace(c) || c == '<' || c == '>' || c == '{' || c == '}');
    }

    /** {@code name -> value} of the {@code <input>} tags in {@code html}. */
    static Map<String, String> hiddenInputs(String html) {
        var result = new LinkedHashMap<String, String>();
        Matcher tag = INPUT_TAG.matcher(html);
        while (tag.find()) {
            String name = null;
            String value = null;
            Matcher attr = ATTRIBUTE.matcher(tag.group());
            while (attr.find()) {
                String v = attr.group(2) != null ? attr.group(2) : attr.group(3);
                switch (attr.group(1).toLowerCase(Locale.ROOT)) {
                    case "name" -> name = v;
                    case "value" -> value = v;
                    default -> {}
                }
            }
            if (name != null && value != null) {
                result.putIfAbsent(name, value);
            }
        }
        return result;
    }

    private static HttpRequest formPost(URI uri, Map<String, List<String>> form) {
        String body = form.entrySet().stream()
                .flatMap(e -> e.getValue().stream().map(v -> encode(e.getKey()) + "=" + encode(v)))
                .collect(Collectors.joining("&"));
        return new HttpRequest(
                "POST",
                uri,
                Map.of("Content-Type", List.of("application/x-www-form-urlencoded")),
                body.getBytes(StandardCharsets.UTF_8));
    }

    // ---- sending ----

    /** Through retry and limiter, with session cookie handling; no crumb. */
    private HttpResponse send(HttpRequest request) {
        HttpRequest withCookies = manageCookieHeader ? addCookieHeader(request) : request;
        HttpResponse response = retry.execute(() -> limiter.call(() -> transport.send(withCookies)));
        if (manageCookieHeader) {
            storeCookies(request.uri(), response);
        }
        return response;
    }

    private HttpRequest addCookieHeader(HttpRequest request) {
        List<HttpCookie> matching = cookies.get(request.uri());
        if (matching.isEmpty()) {
            return request;
        }
        String header =
                matching.stream().map(c -> c.getName() + "=" + c.getValue()).collect(Collectors.joining("; "));
        return request.withHeader("Cookie", header);
    }

    private void storeCookies(URI uri, HttpResponse response) {
        for (String header : response.headerValues("Set-Cookie")) {
            List<HttpCookie> parsed;
            try {
                parsed = HttpCookie.parse(header);
            } catch (IllegalArgumentException e) {
                LOG.debug("ignoring malformed Set-Cookie: {}", header);
                continue;
            }
            for (HttpCookie cookie : parsed) {
                // HttpCookie.parse guesses RFC 2965 for Max-Age, whose domain matching rejects
                // multi-label hosts (query1.finance.yahoo.com for .yahoo.com); Set-Cookie is Netscape style
                cookie.setVersion(0);
                if (cookie.getDomain() == null) {
                    cookie.setDomain(uri.getHost());
                }
                if (cookie.getPath() == null) {
                    cookie.setPath("/");
                }
                cookies.add(uri, cookie);
            }
        }
    }

    static HttpRequest withCrumb(HttpRequest request, String crumb) {
        String uri = request.uri().toString();
        String rawQuery = request.uri().getRawQuery();
        String separator;
        if (rawQuery == null) {
            separator = uri.endsWith("?") ? "" : "?";
        } else {
            separator = rawQuery.isEmpty() || uri.endsWith("&") ? "" : "&";
        }
        URI withCrumb = URI.create(uri + separator + "crumb=" + encode(crumb));
        return new HttpRequest(request.method(), withCrumb, request.headers(), request.body());
    }

    private static boolean isCrumbRejected(HttpResponse response) {
        if (response.status() == 401) {
            return true;
        }
        if (response.isSuccess()) {
            return false;
        }
        byte[] body = response.body();
        int len = Math.min(body.length, CRUMB_CHECK_LIMIT);
        return new String(body, 0, len, StandardCharsets.UTF_8).contains("Invalid Crumb");
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    // ---- persistence ----

    private void persist() {
        String current = crumb;
        if (current == null) {
            return;
        }
        ObjectNode root = YahooJson.mapper().createObjectNode();
        root.put("crumb", current);
        root.put("strategy", strategy.name());
        ArrayNode list = root.putArray("cookies");
        long now = clock.instant().getEpochSecond();
        for (HttpCookie c : cookies.getCookies()) {
            ObjectNode node = list.addObject();
            node.put("name", c.getName());
            node.put("value", c.getValue());
            node.put("domain", c.getDomain());
            node.put("path", c.getPath());
            node.put("secure", c.getSecure());
            node.put("httpOnly", c.isHttpOnly());
            node.put("expiresAt", c.getMaxAge() < 0 ? -1 : now + c.getMaxAge());
        }
        cache.put(CACHE_NAMESPACE, CACHE_KEY, root.toString());
    }

    private void loadFromCache() {
        Optional<String> stored = cache.get(CACHE_NAMESPACE, CACHE_KEY);
        if (stored.isEmpty()) {
            return;
        }
        try {
            JsonNode root = YahooJson.mapper().readTree(stored.get());
            String storedCrumb = root.path("crumb").asText("");
            if (!isPlausibleCrumb(storedCrumb)) {
                throw new IllegalArgumentException("no crumb");
            }
            long now = clock.instant().getEpochSecond();
            var restored = new ArrayList<HttpCookie>();
            JsonNode list = root.path("cookies");
            for (JsonNode node : list) {
                long expiresAt = node.path("expiresAt").asLong(-1);
                if (expiresAt >= 0 && expiresAt <= now) {
                    continue;
                }
                var cookie = new HttpCookie(
                        node.path("name").asText(), node.path("value").asText());
                cookie.setVersion(0); // Netscape-style domain matching, as for parsed Set-Cookie headers
                String domain = node.path("domain").asText("");
                if (domain.isEmpty()) {
                    continue;
                }
                cookie.setDomain(domain);
                cookie.setPath(node.path("path").asText("/"));
                cookie.setSecure(node.path("secure").asBoolean(false));
                cookie.setHttpOnly(node.path("httpOnly").asBoolean(false));
                cookie.setMaxAge(expiresAt < 0 ? -1 : expiresAt - now);
                restored.add(cookie);
            }
            if (!list.isEmpty() && restored.isEmpty()) {
                LOG.debug("cached session cookies expired");
                cache.invalidate(CACHE_NAMESPACE, CACHE_KEY);
                return;
            }
            for (HttpCookie cookie : restored) {
                String host =
                        cookie.getDomain().startsWith(".") ? cookie.getDomain().substring(1) : cookie.getDomain();
                cookies.add(URI.create("https://" + host + "/"), cookie);
            }
            strategy = Strategy.valueOf(root.path("strategy").asText(Strategy.BASIC.name()));
            crumb = storedCrumb;
            LOG.debug("restored Yahoo session from cache ({} cookies)", restored.size());
        } catch (Exception e) {
            LOG.warn("ignoring unreadable cached Yahoo session: {}", e.toString());
            cache.invalidate(CACHE_NAMESPACE, CACHE_KEY);
        }
    }
}
