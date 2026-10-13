package io.andreygrigoriev.yfin.testing;

import io.andreygrigoriev.yfin.spi.HttpRequest;
import io.andreygrigoriev.yfin.spi.HttpResponse;
import io.andreygrigoriev.yfin.spi.Transport;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Test {@link Transport} that replays canned responses. Rules match on method, URI path, query
 * parameters and, when {@link RuleBuilder#host} is set, the host; they are tried in registration order; the first match wins. A request that
 * matches no rule fails loudly with an {@link AssertionError} listing the registered rules.
 *
 * <p>Fixtures are files under a fixture root, by default {@code src/test/resources/fixtures}
 * relative to the working directory (Maven runs tests from the module directory), so the class
 * works unchanged from any module that depends on the yfin-core test-jar.
 *
 * <pre>{@code
 * var transport = FakeTransport.create();
 * transport.on("GET", "/v8/finance/chart/AAPL").query("interval", "1d").respondFixture("chart/aapl-1d.json");
 * }</pre>
 */
public final class FakeTransport implements Transport {

    /** Default fixture root, relative to the module directory. */
    public static final Path DEFAULT_FIXTURE_ROOT = Path.of("src", "test", "resources", "fixtures");

    private final Path fixtureRoot;
    private final List<Rule> rules = new CopyOnWriteArrayList<>();
    private final List<HttpRequest> requests = new CopyOnWriteArrayList<>();

    private FakeTransport(Path fixtureRoot) {
        this.fixtureRoot = fixtureRoot;
    }

    /** Fake reading fixtures from {@link #DEFAULT_FIXTURE_ROOT}. */
    public static FakeTransport create() {
        return new FakeTransport(DEFAULT_FIXTURE_ROOT);
    }

    /** Fake reading fixtures from {@code fixtureRoot}. */
    public static FakeTransport withFixtureRoot(Path fixtureRoot) {
        return new FakeTransport(Objects.requireNonNull(fixtureRoot, "fixtureRoot"));
    }

    /** Starts a rule matching {@code method} and exact URI {@code path}. */
    public RuleBuilder on(String method, String path) {
        return new RuleBuilder(method, path);
    }

    /** Shorthand for {@code on("GET", path)}. */
    public RuleBuilder onGet(String path) {
        return on("GET", path);
    }

    /** Shorthand for {@code on("POST", path)}. */
    public RuleBuilder onPost(String path) {
        return on("POST", path);
    }

    @Override
    public HttpResponse send(HttpRequest request) {
        requests.add(request);
        Map<String, List<String>> query = parseQuery(request.uri().getRawQuery());
        for (Rule rule : rules) {
            if (rule.matches(request, query)) {
                return rule.next();
            }
        }
        var sb = new StringBuilder("FakeTransport: no rule matches ")
                .append(request.method())
                .append(' ')
                .append(request.uri());
        sb.append("\nregistered rules:");
        if (rules.isEmpty()) {
            sb.append(" none");
        }
        rules.forEach(r -> sb.append("\n  ").append(r.description));
        throw new AssertionError(sb.toString());
    }

    /** All requests received so far, in arrival order. */
    public List<HttpRequest> requests() {
        return List.copyOf(requests);
    }

    /** The only request received; fails if there was not exactly one. */
    public HttpRequest singleRequest() {
        List<HttpRequest> received = requests();
        if (received.size() != 1) {
            throw new AssertionError("expected exactly one request, got " + received.size() + ": " + received);
        }
        return received.getFirst();
    }

    /** Clears recorded requests; rules are kept. */
    public void clearRequests() {
        requests.clear();
    }

    /** Parses a raw query string into decoded parameters, preserving order. */
    public static Map<String, List<String>> parseQuery(String rawQuery) {
        var params = new LinkedHashMap<String, List<String>>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return params;
        }
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String name = decode(eq < 0 ? pair : pair.substring(0, eq));
            String value = eq < 0 ? "" : decode(pair.substring(eq + 1));
            params.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
        }
        return params;
    }

    private static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    private final class Rule {
        private final String description;
        private final String method;
        private final @Nullable String host;
        private final String path;
        private final Predicate<Map<String, List<String>>> queryPredicate;
        private final List<Supplier<HttpResponse>> responses;
        private int served;

        Rule(
                String description,
                String method,
                @Nullable String host,
                String path,
                Predicate<Map<String, List<String>>> queryPredicate,
                List<Supplier<HttpResponse>> responses) {
            this.description = description;
            this.method = method;
            this.host = host;
            this.path = path;
            this.queryPredicate = queryPredicate;
            this.responses = List.copyOf(responses);
        }

        boolean matches(HttpRequest request, Map<String, List<String>> query) {
            return request.method().equals(method)
                    && (host == null || host.equalsIgnoreCase(request.uri().getHost()))
                    && path.equals(request.uri().getPath())
                    && queryPredicate.test(query);
        }

        /** Serves responses in order; the last one repeats. */
        synchronized HttpResponse next() {
            Supplier<HttpResponse> response = responses.get(Math.min(served, responses.size() - 1));
            served++;
            return response.get();
        }
    }

    /** Builds one rule; terminal {@code respond*} methods register it. */
    public final class RuleBuilder {
        private final String method;
        private final String path;
        private @Nullable String host;
        private final StringBuilder description;
        private Predicate<Map<String, List<String>>> queryPredicate = q -> true;
        private final List<Supplier<HttpResponse>> responses = new ArrayList<>();

        private RuleBuilder(String method, String path) {
            this.method = method.toUpperCase(Locale.ROOT);
            this.path = Objects.requireNonNull(path, "path");
            this.description = new StringBuilder(this.method).append(' ').append(path);
        }

        /** Requires the request URI host to be {@code host} (case-insensitive). */
        public RuleBuilder host(String host) {
            this.host = Objects.requireNonNull(host, "host");
            description.append(" @").append(host);
            return this;
        }

        /** Requires query parameter {@code name} to have exactly the single value {@code value}. */
        public RuleBuilder query(String name, String value) {
            description.append(" ?").append(name).append('=').append(value);
            return queryMatching(q -> List.of(value).equals(q.get(name)));
        }

        /** Requires query parameter {@code name} to be present. */
        public RuleBuilder hasQuery(String name) {
            description.append(" ?").append(name);
            return queryMatching(q -> q.containsKey(name));
        }

        /** Requires an arbitrary predicate over the decoded query parameters. */
        public RuleBuilder queryMatching(Predicate<Map<String, List<String>>> predicate) {
            queryPredicate = queryPredicate.and(predicate);
            return this;
        }

        /** Next response: status 200 with the body of {@code fixture} (relative to the fixture root). */
        public RuleBuilder thenFixture(String fixture) {
            return thenFixture(200, fixture);
        }

        /** Next response: {@code status} with the body of {@code fixture}. Fails now if the file is missing. */
        public RuleBuilder thenFixture(int status, String fixture) {
            Path file = fixtureRoot.resolve(fixture);
            if (!Files.isRegularFile(file)) {
                throw new AssertionError("FakeTransport: fixture not found: " + file.toAbsolutePath());
            }
            responses.add(() -> {
                try {
                    return new HttpResponse(status, jsonHeaders(), Files.readAllBytes(file));
                } catch (IOException e) {
                    throw new UncheckedIOException("FakeTransport: cannot read fixture " + file, e);
                }
            });
            return this;
        }

        /** Next response: {@code status} with an inline UTF-8 body. */
        public RuleBuilder thenRespond(int status, String body) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            responses.add(() -> new HttpResponse(status, jsonHeaders(), bytes));
            return this;
        }

        /** Next response: exactly {@code response}. */
        public RuleBuilder thenRespond(HttpResponse response) {
            responses.add(() -> response);
            return this;
        }

        /** Registers the rule with the responses queued so far; the last one repeats. */
        public FakeTransport register() {
            if (responses.isEmpty()) {
                throw new IllegalStateException("rule " + description + " has no response");
            }
            rules.add(new Rule(description.toString(), method, host, path, queryPredicate, responses));
            return FakeTransport.this;
        }

        /** Shorthand for {@code thenFixture(fixture).register()}. */
        public FakeTransport respondFixture(String fixture) {
            return thenFixture(fixture).register();
        }

        /** Shorthand for {@code thenFixture(status, fixture).register()}. */
        public FakeTransport respondFixture(int status, String fixture) {
            return thenFixture(status, fixture).register();
        }

        /** Shorthand for {@code thenRespond(status, body).register()}. */
        public FakeTransport respond(int status, String body) {
            return thenRespond(status, body).register();
        }

        /** Shorthand for {@code thenRespond(response).register()}. */
        public FakeTransport respond(HttpResponse response) {
            return thenRespond(response).register();
        }

        private static Map<String, List<String>> jsonHeaders() {
            return Map.of("Content-Type", List.of("application/json;charset=utf-8"));
        }
    }
}
