package io.andreygrigoriev.yfin.it;

import io.andreygrigoriev.yfin.Screener;
import io.andreygrigoriev.yfin.YFinance;
import io.andreygrigoriev.yfin.internal.http.JdkTransport;
import io.andreygrigoriev.yfin.request.CalendarRequest;
import io.andreygrigoriev.yfin.request.EarningsCalendarRequest;
import io.andreygrigoriev.yfin.request.Frequency;
import io.andreygrigoriev.yfin.request.HistoryRequest;
import io.andreygrigoriev.yfin.request.Interval;
import io.andreygrigoriev.yfin.request.Period;
import io.andreygrigoriev.yfin.spi.Cache;
import io.andreygrigoriev.yfin.spi.HttpRequest;
import io.andreygrigoriev.yfin.spi.HttpResponse;
import io.andreygrigoriev.yfin.spi.Transport;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.CookieStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * Recording {@link Transport} decorator that saves Yahoo responses as test fixtures under {@code
 * <root>/<endpoint>/<case>.json} ({@code .html} or {@code .txt} for non-JSON bodies), the layout {@code FakeTransport} in the yfin-core test-jar
 * replays.
 *
 * <p>Only the response body is saved, never request or response headers, so cookies stay out of
 * fixtures; every crumb seen on a {@code getcrumb} response is replaced by {@value #CRUMB_PLACEHOLDER}
 * in the saved bodies. Session traffic (cookie and crumb bootstrap, consent pages) is passed
 * through without being recorded, and so are transient 429 and 5xx responses that the client
 * retries.
 *
 * <p>Command line: {@code FixtureRecorder [--out DIR] (--list | all | CASE...)}; run without
 * arguments to list the cases. See {@link #CASES}.
 */
public final class FixtureRecorder implements Transport {

    /** Default output directory, relative to the repository root. */
    public static final Path DEFAULT_ROOT = Path.of("yfin-core", "src", "test", "resources", "fixtures");

    /** Text written in place of a crumb in saved bodies. */
    public static final String CRUMB_PLACEHOLDER = "REDACTED_CRUMB";

    /** Start of a calendar week with no entries, for the {@code *-empty} calendar cases. */
    private static final LocalDate PAST = LocalDate.of(1970, 1, 1);

    /** Built-in cases the CLI can record: a normal, an empty and an error case per endpoint. */
    public static final List<FixtureCase> CASES = List.of(
            FixtureCase.of(
                    "chart",
                    "aapl-1d-1mo",
                    "AAPL daily bars, 1 month",
                    yf -> yf.ticker("AAPL").history(HistoryRequest.of(Period.ONE_MONTH, Interval.ONE_DAY))),
            FixtureCase.of(
                    "chart",
                    "btc-usd-1d-5d",
                    "BTC-USD daily bars, 5 days (24h market)",
                    yf -> yf.ticker("BTC-USD").history(HistoryRequest.of(Period.FIVE_DAYS, Interval.ONE_DAY))),
            FixtureCase.of(
                    "chart",
                    "not-found",
                    "unknown symbol (error payload)",
                    yf -> yf.ticker("NOSUCHSYMBOLXYZ").history(Period.ONE_MONTH)),
            new FixtureCase(
                    "aapl",
                    "AAPL quoteSummary + quote (info)",
                    Map.of("quoteSummary", "quoteSummary", "quote", "quote"),
                    yf -> yf.ticker("AAPL").info()),
            FixtureCase.of(
                    "quote",
                    "not-found",
                    "unknown symbol quote (empty result)",
                    yf -> yf.ticker("NOSUCHSYMBOLXYZ").fastInfo().shares()),
            FixtureCase.of(
                    "timeseries",
                    "aapl-annual-financials",
                    "AAPL annual income statement",
                    yf -> yf.ticker("AAPL").incomeStatement(Frequency.ANNUAL)),
            FixtureCase.of(
                    "options",
                    "aapl",
                    "AAPL expirations + nearest chain",
                    yf -> yf.ticker("AAPL").optionChain()),
            FixtureCase.of(
                    "options",
                    "unknown",
                    "unknown symbol options",
                    yf -> yf.ticker("NOSUCHSYMBOLXYZ").optionExpirations()),
            FixtureCase.of("search", "apple", "search 'apple'", yf -> yf.search("apple")),
            FixtureCase.of("search", "empty", "search with no hits", yf -> yf.search("zzqqxxjjvvkk")),
            FixtureCase.of("lookup", "aapl-all", "lookup 'AAPL'", yf -> yf.lookup("AAPL")),
            FixtureCase.of(
                    "screener", "predefined-day-gainers", "predefined day_gainers", yf -> yf.screen("day_gainers")),
            FixtureCase.of(
                    "sector",
                    "technology",
                    "technology sector",
                    yf -> yf.sector("technology").data()),
            FixtureCase.of(
                    "industry",
                    "semiconductors",
                    "semiconductors industry",
                    yf -> yf.industry("semiconductors").data()),
            new FixtureCase(
                    "summary-us",
                    "US market summary",
                    Map.of("marketSummary", "market"),
                    yf -> yf.market("US").summary()),
            new FixtureCase(
                    "markettime-us",
                    "US market time",
                    Map.of("marketTime", "market"),
                    yf -> yf.market("US").status()),
            FixtureCase.of(
                    "news", "aapl", "AAPL news (ncp)", yf -> yf.ticker("AAPL").news()),
            new FixtureCase(
                    "aapl",
                    "AAPL earnings dates",
                    Map.of("visualization", "earnings"),
                    yf -> yf.ticker("AAPL").earningsDates()),
            new FixtureCase(
                    "most-actives",
                    "200 most active stocks (earnings calendar filter)",
                    Map.of("screener", "calendar"),
                    yf -> yf.screen(Screener.predefined("most_actives").size(200))),
            calendarCase(
                    "earnings-week",
                    "earnings calendar, next 7 days, most-active filter",
                    yf -> yf.earningsCalendar(EarningsCalendarRequest.nextWeek().limit(CalendarRequest.MAX_LIMIT))),
            calendarCase(
                    "earnings-week-mcap",
                    "earnings calendar, next 7 days, market cap >= 1e9, most-active filter",
                    yf -> yf.earningsCalendar(
                            EarningsCalendarRequest.nextWeek().marketCap(1e9).limit(CalendarRequest.MAX_LIMIT))),
            calendarCase(
                    "earnings-empty",
                    "earnings calendar, a week in 1970",
                    yf -> yf.earningsCalendar(EarningsCalendarRequest.from(PAST).limit(CalendarRequest.MAX_LIMIT))),
            calendarCase(
                    "ipo-week",
                    "IPO calendar, next 7 days",
                    yf -> yf.ipoCalendar(CalendarRequest.nextWeek().limit(CalendarRequest.MAX_LIMIT))),
            calendarCase(
                    "ipo-empty",
                    "IPO calendar, a week in 1970",
                    yf -> yf.ipoCalendar(CalendarRequest.from(PAST).limit(CalendarRequest.MAX_LIMIT))),
            calendarCase(
                    "economic-week",
                    "economic events calendar, next 7 days",
                    yf -> yf.economicEventsCalendar(CalendarRequest.nextWeek().limit(CalendarRequest.MAX_LIMIT))),
            calendarCase(
                    "economic-empty",
                    "economic events calendar, a week in 1970",
                    yf -> yf.economicEventsCalendar(CalendarRequest.from(PAST).limit(CalendarRequest.MAX_LIMIT))),
            calendarCase(
                    "splits-week",
                    "splits calendar, next 7 days",
                    yf -> yf.splitsCalendar(CalendarRequest.nextWeek().limit(CalendarRequest.MAX_LIMIT))),
            calendarCase(
                    "splits-empty",
                    "splits calendar, a week in 1970",
                    yf -> yf.splitsCalendar(CalendarRequest.from(PAST).limit(CalendarRequest.MAX_LIMIT))),
            new FixtureCase(
                    "aapl",
                    "AAPL full shares history",
                    Map.of("timeseries", "shares"),
                    yf -> yf.ticker("AAPL").sharesFull()),
            FixtureCase.of(
                    "isin", "aapl", "AAPL ISIN lookup", yf -> yf.ticker("AAPL").isin()));

    private final Transport delegate;
    private final Path root;
    private final Set<String> crumbs = ConcurrentHashMap.newKeySet();
    private volatile @Nullable Recording current;

    /** Recorder writing below {@code root} and sending through {@code delegate}. */
    public FixtureRecorder(Transport delegate, Path root) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.root = Objects.requireNonNull(root, "root");
    }

    @Override
    public @Nullable CookieStore cookieStore() {
        return delegate.cookieStore();
    }

    @Override
    public HttpResponse send(HttpRequest request) {
        HttpResponse response = delegate.send(request);
        if (isCrumbRequest(request) && response.isSuccess()) {
            String crumb = response.bodyAsString().strip();
            if (!crumb.isEmpty()) {
                crumbs.add(crumb);
            }
            return response;
        }
        Recording recording = current;
        String endpoint = endpointOf(request);
        if (recording != null && endpoint != null && !isTransient(response.status())) {
            recording.add(endpoint, response);
        }
        return response;
    }

    /**
     * Runs {@code fixtureCase} against {@code client} (which must send through this recorder) and
     * writes the recorded responses. An exception from the action is expected for error cases: it
     * is reported on {@code log} and the responses are still written.
     *
     * @return the files written, in recording order
     */
    public synchronized List<Path> record(FixtureCase fixtureCase, YFinance client, PrintStream log) {
        var recording = new Recording(fixtureCase);
        current = recording;
        try {
            fixtureCase.action().accept(client);
        } catch (RuntimeException e) {
            log.println("  " + fixtureCase.name() + ": action threw "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            current = null;
        }
        return recording.write();
    }

    /**
     * Calendar case recording only the visualization response; the most-active screener request of
     * the earnings calendar is not saved (see the {@code most-actives} case).
     */
    private static FixtureCase calendarCase(String name, String description, Consumer<YFinance> action) {
        return new FixtureCase(name, description, Map.of("visualization", "calendar"), action);
    }

    /** Endpoint directory for {@code request}, or {@code null} for session and unknown traffic. */
    static @Nullable String endpointOf(HttpRequest request) {
        String host = Objects.requireNonNullElse(request.uri().getHost(), "").toLowerCase(Locale.ROOT);
        String path = Objects.requireNonNullElse(request.uri().getPath(), "");
        if (host.endsWith("businessinsider.com")) {
            return "isin";
        }
        if (!host.endsWith("finance.yahoo.com")) {
            return null; // fc.yahoo.com, guce/consent.yahoo.com: session bootstrap
        }
        if (path.startsWith("/v8/finance/chart/")) {
            return "chart";
        }
        if (path.startsWith("/v10/finance/quoteSummary/")) {
            return "quoteSummary";
        }
        if (path.equals("/v7/finance/quote")) {
            return "quote";
        }
        if (path.startsWith("/v6/finance/quote/marketSummary")) {
            return "marketSummary";
        }
        if (path.startsWith("/v6/finance/markettime")) {
            return "marketTime";
        }
        if (path.startsWith("/ws/fundamentals-timeseries/")) {
            return "timeseries";
        }
        if (path.startsWith("/v7/finance/options/")) {
            return "options";
        }
        if (path.equals("/v1/finance/search")) {
            return "search";
        }
        if (path.equals("/v1/finance/lookup")) {
            return "lookup";
        }
        if (path.startsWith("/v1/finance/screener")) {
            return "screener";
        }
        if (path.equals("/v1/finance/visualization")) {
            return "visualization";
        }
        if (path.startsWith("/v1/finance/sectors/")) {
            return "sector";
        }
        if (path.startsWith("/v1/finance/industries/")) {
            return "industry";
        }
        if (path.equals("/xhr/ncp")) {
            return "news";
        }
        return null;
    }

    private static boolean isCrumbRequest(HttpRequest request) {
        return Objects.requireNonNullElse(request.uri().getPath(), "").endsWith("/v1/test/getcrumb");
    }

    private static boolean isTransient(int status) {
        return status == 429 || status >= 500;
    }

    private String redact(String body) {
        String result = body;
        for (String crumb : crumbs) {
            result = result.replace(crumb, CRUMB_PLACEHOLDER);
        }
        return result;
    }

    /** Responses captured while one case runs, grouped by output directory. */
    private final class Recording {
        private final FixtureCase fixtureCase;
        private final Map<String, List<HttpResponse>> byDir = new LinkedHashMap<>();

        Recording(FixtureCase fixtureCase) {
            this.fixtureCase = fixtureCase;
        }

        synchronized void add(String endpoint, HttpResponse response) {
            String dir = fixtureCase.endpoints().get(endpoint);
            if (dir != null) {
                byDir.computeIfAbsent(dir, k -> new ArrayList<>()).add(response);
            }
        }

        synchronized List<Path> write() {
            var written = new ArrayList<Path>();
            byDir.forEach((dir, responses) -> {
                for (int i = 0; i < responses.size(); i++) {
                    HttpResponse response = responses.get(i);
                    String suffix = responses.size() == 1 ? "" : "-" + (i + 1);
                    String body = redact(response.bodyAsString());
                    Path file = root.resolve(dir).resolve(fixtureCase.name() + suffix + extension(body));
                    writeAtomically(file, body);
                    written.add(file);
                }
            });
            return written;
        }
    }

    /** {@code .json}, {@code .html} or {@code .txt} by content (Yahoo mislabels content types). */
    static String extension(String body) {
        String start = body.stripLeading();
        if (start.isEmpty() || start.startsWith("{") || start.startsWith("[")) {
            return ".json";
        }
        return start.startsWith("<") ? ".html" : ".txt";
    }

    private static void writeAtomically(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = Files.createTempFile(file.getParent(), ".fixture", ".tmp");
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write fixture " + file, e);
        }
    }

    /**
     * One recordable scenario.
     *
     * @param name file name stem, e.g. {@code aapl-1d-1mo}
     * @param description shown by {@code --list}
     * @param endpoints endpoints to record (see {@link #endpointOf}) mapped to their output
     *     directory; responses from other endpoints, e.g. timezone lookups, are not saved
     * @param action calls the client
     */
    public record FixtureCase(
            String name, String description, Map<String, String> endpoints, Consumer<YFinance> action) {

        public FixtureCase {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(description, "description");
            endpoints = Map.copyOf(endpoints);
            Objects.requireNonNull(action, "action");
        }

        /** Case recording a single endpoint into the directory of the same name. */
        public static FixtureCase of(String endpoint, String name, String description, Consumer<YFinance> action) {
            return new FixtureCase(name, description, Map.of(endpoint, endpoint), action);
        }

        /** {@code <dir>/<name>} identifier used on the command line. */
        public String id() {
            return String.join("+", endpoints.values().stream().sorted().toList()) + "/" + name;
        }
    }

    /**
     * CLI entry point; paths are relative to the working directory, so run it from the repository
     * root. Exits with status 2 on usage errors.
     */
    public static void main(String[] args) {
        int status = run(args, System.out, System.err);
        if (status != 0) {
            System.exit(status);
        }
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        Path root = DEFAULT_ROOT;
        var selected = new ArrayList<String>();
        boolean list = args.length == 0;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--out" -> {
                    if (i + 1 >= args.length) {
                        err.println("--out needs a directory");
                        return 2;
                    }
                    root = Path.of(args[++i]);
                }
                case "--list" -> list = true;
                default -> selected.add(args[i]);
            }
        }
        if (list) {
            out.println("usage: FixtureRecorder [--out DIR] (--list | all | CASE...)");
            out.println("cases:");
            CASES.forEach(c -> out.printf("  %-45s %s%n", c.id(), c.description()));
            return 0;
        }
        List<FixtureCase> cases;
        try {
            cases = select(selected);
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            return 2;
        }
        try (JdkTransport jdk = JdkTransport.create()) {
            var recorder = new FixtureRecorder(jdk, root);
            try (YFinance client = YFinance.builder()
                    .transport(recorder)
                    .cache(Cache.none())
                    .maxConcurrentRequests(1)
                    .build()) {
                for (FixtureCase c : cases) {
                    out.println(c.id());
                    recorder.record(c, client, out).forEach(p -> out.println("  wrote " + p));
                }
            }
        }
        return 0;
    }

    static List<FixtureCase> select(List<String> ids) {
        if (ids.contains("all")) {
            return CASES;
        }
        var result = new ArrayList<FixtureCase>();
        for (String id : ids) {
            FixtureCase match = CASES.stream()
                    .filter(c -> c.id().equals(id))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("unknown case: " + id + " (see --list)"));
            result.add(match);
        }
        return result;
    }
}
