package io.github.andreygrigoriev.yfin.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.andreygrigoriev.yfin.YFinance;
import io.github.andreygrigoriev.yfin.internal.http.YahooJson;
import io.github.andreygrigoriev.yfin.it.FixtureRecorder.FixtureCase;
import io.github.andreygrigoriev.yfin.request.Period;
import io.github.andreygrigoriev.yfin.spi.Cache;
import io.github.andreygrigoriev.yfin.spi.HttpRequest;
import io.github.andreygrigoriev.yfin.spi.HttpResponse;
import io.github.andreygrigoriev.yfin.testing.FakeTransport;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FixtureRecorderTest {

    private static final Path CORE_FIXTURES = Path.of("..", "yfin-core", "src", "test", "resources", "fixtures");

    @TempDir
    Path root;

    private final FakeTransport fake = FakeTransport.withFixtureRoot(CORE_FIXTURES);
    private final ByteArrayOutputStream logBytes = new ByteArrayOutputStream();
    private final PrintStream log = new PrintStream(logBytes, true, StandardCharsets.UTF_8);
    private FixtureRecorder recorder;
    private YFinance yf;

    @BeforeEach
    void setUp() {
        fake.onGet("").respond(404, "not found");
        fake.onGet("/v1/test/getcrumb").respond(200, "crumb-secret-1");
        recorder = new FixtureRecorder(fake, root);
        yf = YFinance.builder()
                .transport(recorder)
                .cache(Cache.none())
                .maxRetries(0)
                .build();
    }

    @AfterEach
    void tearDown() {
        yf.close();
    }

    @Test
    void writesEndpointResponseBodyOnly() throws IOException {
        fake.onGet("/v8/finance/chart/AAPL").respondFixture("testing/chart-ok.json");

        List<Path> written = recorder.record(
                FixtureCase.of(
                        "chart", "aapl-1mo", "test", c -> c.ticker("AAPL").history(Period.ONE_MONTH)),
                yf,
                log);

        Path expected = root.resolve("chart").resolve("aapl-1mo.json");
        assertThat(written).containsExactly(expected);
        assertThat(Files.readString(expected))
                .isEqualTo(Files.readString(CORE_FIXTURES.resolve("testing/chart-ok.json")));
        try (Stream<Path> files = Files.walk(root)) {
            assertThat(files.filter(Files::isRegularFile)).containsExactly(expected);
        }
    }

    @Test
    void sessionAndUnlistedEndpointsAreNotRecorded() {
        fake.onGet("/v8/finance/chart/AAPL").respondFixture("testing/chart-ok.json");

        List<Path> written = recorder.record(
                FixtureCase.of("quote", "aapl", "test", c -> c.ticker("AAPL").history(Period.ONE_MONTH)), yf, log);

        assertThat(written).isEmpty();
        assertThat(root).isEmptyDirectory();
    }

    @Test
    void severalResponsesGetNumberedSuffixes() {
        fake.onGet("/v8/finance/chart/AAPL").respond(200, "{\"n\":1}");

        List<Path> written = recorder.record(
                FixtureCase.of("chart", "twice", "test", c -> {
                    recorder.send(
                            HttpRequest.get(URI.create("https://query2.finance.yahoo.com/v8/finance/chart/AAPL")));
                    recorder.send(
                            HttpRequest.get(URI.create("https://query2.finance.yahoo.com/v8/finance/chart/AAPL")));
                }),
                yf,
                log);

        assertThat(written).containsExactly(root.resolve("chart/twice-1.json"), root.resolve("chart/twice-2.json"));
    }

    @Test
    void crumbIsRedactedFromBodies() throws IOException {
        fake.onGet("/v7/finance/quote").respond(200, "{\"echo\":\"crumb-secret-1\"}");

        recorder.record(
                FixtureCase.of("quote", "echo", "test", c -> {
                    recorder.send(HttpRequest.get(URI.create("https://query1.finance.yahoo.com/v1/test/getcrumb")));
                    recorder.send(HttpRequest.get(URI.create("https://query1.finance.yahoo.com/v7/finance/quote")));
                }),
                yf,
                log);

        assertThat(Files.readString(root.resolve("quote/echo.json")))
                .isEqualTo("{\"echo\":\"" + FixtureRecorder.CRUMB_PLACEHOLDER + "\"}")
                .doesNotContain("crumb-secret-1");
    }

    @Test
    void errorCaseIsWrittenEvenWhenTheActionThrows() throws IOException {
        fake.onGet("/v8/finance/chart/NOPE")
                .respond(
                        404,
                        "{\"chart\":{\"result\":null,\"error\":{\"code\":\"Not Found\","
                                + "\"description\":\"No data found, symbol may be delisted\"}}}");

        List<Path> written = recorder.record(
                FixtureCase.of(
                        "chart", "not-found", "test", c -> c.ticker("NOPE").history(Period.ONE_MONTH)),
                yf,
                log);

        assertThat(written).containsExactly(root.resolve("chart/not-found.json"));
        assertThat(Files.readString(written.getFirst())).contains("Not Found");
        assertThat(logBytes.toString(StandardCharsets.UTF_8)).contains("not-found: action threw");
    }

    @Test
    void transientResponsesAreSkippedAndHtmlGetsItsExtension() {
        fake.onGet("/v1/finance/sectors/tech").respond(429, "Too Many Requests");
        fake.onGet("/v1/finance/sectors/other")
                .respond(new HttpResponse(
                        404,
                        Map.of("Content-Type", List.of("text/html; charset=utf-8")),
                        "<html></html>".getBytes(StandardCharsets.UTF_8)));

        List<Path> written = recorder.record(
                FixtureCase.of("sector", "unknown", "test", c -> {
                    recorder.send(
                            HttpRequest.get(URI.create("https://query1.finance.yahoo.com/v1/finance/sectors/tech")));
                    recorder.send(
                            HttpRequest.get(URI.create("https://query1.finance.yahoo.com/v1/finance/sectors/other")));
                }),
                yf,
                log);

        assertThat(written).containsExactly(root.resolve("sector/unknown.html"));
    }

    @Test
    void extensionFollowsContent() {
        assertThat(FixtureRecorder.extension(" {\"a\":1}")).isEqualTo(".json");
        assertThat(FixtureRecorder.extension("[]")).isEqualTo(".json");
        assertThat(FixtureRecorder.extension("")).isEqualTo(".json");
        assertThat(FixtureRecorder.extension("<!doctype html>")).isEqualTo(".html");
        assertThat(FixtureRecorder.extension("mmSuggestDeliver(0, ...)")).isEqualTo(".txt");
    }

    @Test
    void endpointMapsToOverriddenDirectory() {
        fake.onGet("/ws/fundamentals-timeseries/v1/finance/timeseries/AAPL").respond(200, "{}");

        List<Path> written = recorder.record(
                new FixtureCase(
                        "aapl",
                        "test",
                        Map.of("timeseries", "shares"),
                        c -> recorder.send(
                                HttpRequest.get(
                                        URI.create(
                                                "https://query2.finance.yahoo.com/ws/fundamentals-timeseries/v1/finance/timeseries/AAPL")))),
                yf,
                log);

        assertThat(written).containsExactly(root.resolve("shares/aapl.json"));
    }

    @Test
    void responsesOutsideARecordingAreNotSaved() {
        fake.onGet("/v7/finance/quote").respond(200, "{}");

        recorder.send(HttpRequest.get(URI.create("https://query1.finance.yahoo.com/v7/finance/quote")));

        assertThat(root).isEmptyDirectory();
    }

    @Test
    void classifiesYahooEndpoints() {
        assertThat(endpoint("https://query2.finance.yahoo.com/v8/finance/chart/AAPL"))
                .isEqualTo("chart");
        assertThat(endpoint("https://query2.finance.yahoo.com/v10/finance/quoteSummary/AAPL"))
                .isEqualTo("quoteSummary");
        assertThat(endpoint("https://query1.finance.yahoo.com/v7/finance/quote"))
                .isEqualTo("quote");
        assertThat(endpoint("https://query1.finance.yahoo.com/v6/finance/quote/marketSummary"))
                .isEqualTo("marketSummary");
        assertThat(endpoint("https://query1.finance.yahoo.com/v6/finance/markettime"))
                .isEqualTo("marketTime");
        assertThat(endpoint("https://query2.finance.yahoo.com/ws/fundamentals-timeseries/v1/finance/timeseries/AAPL"))
                .isEqualTo("timeseries");
        assertThat(endpoint("https://query2.finance.yahoo.com/v7/finance/options/AAPL"))
                .isEqualTo("options");
        assertThat(endpoint("https://query2.finance.yahoo.com/v1/finance/search"))
                .isEqualTo("search");
        assertThat(endpoint("https://query1.finance.yahoo.com/v1/finance/lookup"))
                .isEqualTo("lookup");
        assertThat(endpoint("https://query1.finance.yahoo.com/v1/finance/screener"))
                .isEqualTo("screener");
        assertThat(endpoint("https://query1.finance.yahoo.com/v1/finance/screener/predefined/saved"))
                .isEqualTo("screener");
        assertThat(endpoint("https://query1.finance.yahoo.com/v1/finance/visualization"))
                .isEqualTo("visualization");
        assertThat(endpoint("https://query1.finance.yahoo.com/v1/finance/sectors/technology"))
                .isEqualTo("sector");
        assertThat(endpoint("https://query1.finance.yahoo.com/v1/finance/industries/semiconductors"))
                .isEqualTo("industry");
        assertThat(endpoint("https://finance.yahoo.com/xhr/ncp")).isEqualTo("news");
        assertThat(endpoint("https://markets.businessinsider.com/ajax/SearchController_Suggest?query=AAPL"))
                .isEqualTo("isin");

        assertThat(endpoint("https://fc.yahoo.com")).isNull();
        assertThat(endpoint("https://query1.finance.yahoo.com/v1/test/getcrumb"))
                .isNull();
        assertThat(endpoint("https://guce.yahoo.com/consent")).isNull();
        assertThat(endpoint("https://finance.yahoo.com/")).isNull();
    }

    @Test
    void cliListsCases() {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();

        int status = FixtureRecorder.run(new String[] {"--list"}, printer(out), printer(err));

        assertThat(status).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("usage:").contains("chart/aapl-1d-1mo");
    }

    @Test
    void cliRejectsUnknownCasesAndMissingOutDir() {
        var err = new ByteArrayOutputStream();

        assertThat(FixtureRecorder.run(new String[] {"chart/nope"}, printer(new ByteArrayOutputStream()), printer(err)))
                .isEqualTo(2);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("unknown case: chart/nope");
        assertThat(FixtureRecorder.run(new String[] {"--out"}, printer(new ByteArrayOutputStream()), printer(err)))
                .isEqualTo(2);
    }

    @Test
    void caseIdsAreUniqueAndSelectable() {
        var ids = new HashSet<String>();
        FixtureRecorder.CASES.forEach(
                c -> assertThat(ids.add(c.id())).as(c.id()).isTrue());

        assertThat(FixtureRecorder.select(List.of("all"))).isEqualTo(FixtureRecorder.CASES);
        assertThat(FixtureRecorder.select(List.of("shares/aapl")))
                .singleElement()
                .satisfies(c -> assertThat(c.endpoints()).containsExactly(Map.entry("timeseries", "shares")));
    }

    @Test
    void visualizationCasesKeepTheirOutputDirectories() {
        assertThat(FixtureRecorder.select(List.of("earnings/aapl")))
                .singleElement()
                .satisfies(c -> assertThat(c.endpoints()).containsExactly(Map.entry("visualization", "earnings")));
        assertThat(FixtureRecorder.select(List.of(
                        "calendar/earnings-week",
                        "calendar/earnings-week-mcap",
                        "calendar/earnings-empty",
                        "calendar/ipo-week",
                        "calendar/ipo-empty",
                        "calendar/economic-week",
                        "calendar/economic-empty",
                        "calendar/splits-week",
                        "calendar/splits-empty")))
                .allSatisfy(c -> assertThat(c.endpoints()).containsExactly(Map.entry("visualization", "calendar")));
        assertThat(FixtureRecorder.select(List.of("calendar/most-actives")))
                .singleElement()
                .satisfies(c -> assertThat(c.endpoints()).containsExactly(Map.entry("screener", "calendar")));
    }

    @Test
    void calendarCaseCallsThePublicApiAndRecordsIntoCalendar() throws IOException {
        fake.onPost("/v1/finance/visualization").respondFixture("calendar/splits-week.json");

        List<Path> written = recorder.record(
                FixtureRecorder.select(List.of("calendar/splits-week")).getFirst(), yf, log);

        assertThat(written).containsExactly(root.resolve("calendar/splits-week.json"));
        assertThat(logBytes.toString(StandardCharsets.UTF_8)).isEmpty();
        HttpRequest post = fake.requests().stream()
                .filter(r -> r.method().equals("POST"))
                .findFirst()
                .orElseThrow();
        JsonNode body = YahooJson.mapper().readTree(post.bodyAsString().orElseThrow());
        assertThat(body.path("entityIdType").asText()).isEqualTo("splits");
        assertThat(body.path("size").asInt()).isEqualTo(100);
        assertThat(body.path("query").path("operator").asText()).isEqualTo("AND");
    }

    @Test
    void earningsCalendarCaseRecordsOnlyTheVisualizationResponse() throws IOException {
        fake.onGet("/v1/finance/screener/predefined/saved").respondFixture("calendar/most-actives.json");
        fake.onPost("/v1/finance/visualization").respondFixture("calendar/earnings-week-mcap.json");

        List<Path> written = recorder.record(
                FixtureRecorder.select(List.of("calendar/earnings-week-mcap")).getFirst(), yf, log);

        assertThat(written).containsExactly(root.resolve("calendar/earnings-week-mcap.json"));
        assertThat(logBytes.toString(StandardCharsets.UTF_8)).isEmpty();
        HttpRequest post = fake.requests().stream()
                .filter(r -> r.uri().getPath().equals("/v1/finance/visualization"))
                .findFirst()
                .orElseThrow();
        JsonNode operands = YahooJson.mapper()
                .readTree(post.bodyAsString().orElseThrow())
                .path("query")
                .path("operands");
        assertThat(operands.path(4).path("operands").path(1).asDouble()).isEqualTo(1e9);
        assertThat(operands.path(5).path("operands").size()).isPositive();
    }

    @Test
    void emptyCalendarCaseUsesAWeekIn1970() throws IOException {
        fake.onPost("/v1/finance/visualization").respondFixture("calendar/ipo-empty.json");

        recorder.record(FixtureRecorder.select(List.of("calendar/ipo-empty")).getFirst(), yf, log);

        HttpRequest post = fake.requests().stream()
                .filter(r -> r.method().equals("POST"))
                .findFirst()
                .orElseThrow();
        JsonNode window = YahooJson.mapper()
                .readTree(post.bodyAsString().orElseThrow())
                .path("query")
                .path("operands")
                .path(0)
                .path("operands");
        assertThat(window.path(1).asText()).isEqualTo("1970-01-01");
        assertThat(window.path(2).asText()).isEqualTo("1970-01-08");
    }

    private static String endpoint(String uri) {
        return FixtureRecorder.endpointOf(HttpRequest.get(URI.create(uri)));
    }

    private static PrintStream printer(ByteArrayOutputStream bytes) {
        return new PrintStream(bytes, true, StandardCharsets.UTF_8);
    }
}
