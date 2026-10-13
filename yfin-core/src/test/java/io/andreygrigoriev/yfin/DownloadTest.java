package io.andreygrigoriev.yfin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.andreygrigoriev.yfin.exception.YFDownloadException;
import io.andreygrigoriev.yfin.exception.YFSymbolNotFoundException;
import io.andreygrigoriev.yfin.exception.YFTransportException;
import io.andreygrigoriev.yfin.model.PriceHistory;
import io.andreygrigoriev.yfin.request.HistoryRequest;
import io.andreygrigoriev.yfin.request.Period;
import io.andreygrigoriev.yfin.spi.Cache;
import io.andreygrigoriev.yfin.spi.HttpRequest;
import io.andreygrigoriev.yfin.spi.HttpResponse;
import io.andreygrigoriev.yfin.spi.Transport;
import io.andreygrigoriev.yfin.testing.FakeTransport;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DownloadTest {

    private static final String CHART = "/v8/finance/chart/";
    private static final HistoryRequest MONTH = HistoryRequest.of(Period.ONE_MONTH);

    private final FakeTransport fake = FakeTransport.create();
    private YFinance yf;

    @BeforeEach
    void setUp() {
        fake.onGet("").respond(404, "not found"); // fc.yahoo.com sets the cookie
        fake.onGet("/v1/test/getcrumb").respond(200, "crumb1");
        yf = client(fake, 8);
    }

    @AfterEach
    void tearDown() {
        yf.close();
    }

    private static YFinance client(Transport transport, int maxConcurrent) {
        return YFinance.builder()
                .transport(transport)
                .cache(Cache.none())
                .maxRetries(0)
                .maxConcurrentRequests(maxConcurrent)
                .build();
    }

    private void ok(String symbol) {
        fake.onGet(CHART + symbol).query("range", "1mo").respondFixture("chart/aapl-1d-1mo.json");
    }

    private void notFound(String symbol) {
        fake.onGet(CHART + symbol).respondFixture(404, "chart/not-found.json");
    }

    private long chartRequests() {
        return fake.requests().stream()
                .filter(r -> r.uri().getPath().startsWith(CHART))
                .count();
    }

    @Test
    void allSucceedInInputOrder() {
        List<String> symbols = List.of("MSFT", "AAPL", "GOOG", "AMZN");
        symbols.forEach(this::ok);

        Map<String, PriceHistory> result = yf.download(List.of("msft", " aapl ", "GOOG", "amzn"), MONTH);

        assertThat(result).containsOnlyKeys(symbols);
        assertThat(result.keySet()).containsExactlyElementsOf(symbols);
        result.forEach((symbol, h) -> {
            assertThat(h.symbol()).isEqualTo(symbol);
            assertThat(h.bars()).hasSize(21);
        });
        assertThatThrownBy(() -> result.put("X", null)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void oneFailureThrowsWithPartialResults() {
        ok("AAPL");
        notFound("NOPE");
        ok("MSFT");

        YFDownloadException e = catchThrowableOfType(
                YFDownloadException.class, () -> yf.download(List.of("AAPL", "NOPE", "MSFT"), MONTH));

        assertThat(e.failures()).containsOnlyKeys("NOPE");
        assertThat(e.failures().get("NOPE"))
                .isInstanceOfSatisfying(
                        YFSymbolNotFoundException.class,
                        f -> assertThat(f.symbol()).isEqualTo("NOPE"));
        assertThat(e.partialResults().keySet()).containsExactly("AAPL", "MSFT");
        assertThat(e.partialResults().get("MSFT").bars()).hasSize(21);
        assertThat(e.getSuppressed()).containsExactly(e.failures().get("NOPE"));
        assertThat(e.getMessage()).contains("1 of 3").contains("NOPE");
        // strict but complete: every symbol was attempted
        assertThat(chartRequests()).isEqualTo(3);
    }

    @Test
    void allFail() {
        notFound("A");
        notFound("B");
        fake.onGet(CHART + "C").respond(500, "boom");

        YFDownloadException e =
                catchThrowableOfType(YFDownloadException.class, () -> yf.download(List.of("A", "B", "C"), MONTH));

        assertThat(e.failures().keySet()).containsExactly("A", "B", "C");
        assertThat(e.failures().get("C")).isInstanceOf(YFTransportException.class);
        assertThat(e.partialResults()).isEmpty();
        assertThat(e.getSuppressed()).hasSize(3);
    }

    @Test
    void duplicateSymbolsAreFetchedOnce() {
        ok("AAPL");
        ok("MSFT");

        Map<String, PriceHistory> result = yf.download(List.of("AAPL", "msft", "aapl", " AAPL", "MSFT"), MONTH);

        assertThat(result.keySet()).containsExactly("AAPL", "MSFT");
        assertThat(chartRequests()).isEqualTo(2);
    }

    @Test
    void emptyInputGivesEmptyMapWithoutRequests() {
        assertThat(yf.download(List.of(), MONTH)).isEmpty();
        assertThat(fake.requests()).isEmpty();
    }

    @Test
    void blankSymbolIsRejectedBeforeAnyRequest() {
        ok("AAPL");

        assertThatIllegalArgumentException().isThrownBy(() -> yf.download(List.of("AAPL", " "), MONTH));
        assertThat(fake.requests()).isEmpty();
    }

    @Test
    void downloadAfterCloseFails() {
        yf.close();

        assertThatThrownBy(() -> yf.download(List.of("AAPL"), MONTH)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> yf.tickers("AAPL")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void concurrencyBoundRespected() {
        List<String> symbols = List.of("S1", "S2", "S3", "S4", "S5", "S6", "S7", "S8");
        symbols.forEach(this::ok);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        Transport slow = (HttpRequest request) -> {
            boolean chart = request.uri().getPath().startsWith(CHART);
            if (!chart) {
                return fake.send(request);
            }
            int now = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(100);
                HttpResponse response = fake.send(request);
                return response;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new YFTransportException("interrupted", e);
            } finally {
                inFlight.decrementAndGet();
            }
        };

        try (YFinance bounded = client(slow, 3)) {
            Map<String, PriceHistory> result = bounded.download(symbols, MONTH);

            assertThat(result).hasSize(8);
        }
        assertThat(maxInFlight.get()).isEqualTo(3);
    }

    @Test
    void tickersGroup() {
        ok("AAPL");
        ok("MSFT");

        Tickers group = yf.tickers("msft", "AAPL", "Msft");

        assertThat(group.symbols()).containsExactly("MSFT", "AAPL");
        assertThat(group.tickers().keySet()).containsExactly("MSFT", "AAPL");
        assertThat(group.ticker(" aapl ").symbol()).isEqualTo("AAPL");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> group.ticker("GOOG"))
                .withMessageContaining("GOOG");
        assertThat(fake.requests()).isEmpty();

        Map<String, PriceHistory> history = group.history(MONTH);

        assertThat(history.keySet()).containsExactly("MSFT", "AAPL");
        assertThat(history.get("AAPL").bars()).hasSize(21);
        assertThat(chartRequests()).isEqualTo(2);
    }

    @Test
    void tickersNewsPerSymbol() {
        fake.onPost("/xhr/ncp").respondFixture("news/aapl-ncp-synthetic.json");

        var news = yf.tickers("AAPL", "MSFT").news();

        assertThat(news.keySet()).containsExactly("AAPL", "MSFT");
        assertThat(news.get("AAPL")).isNotEmpty();
        assertThat(fake.requests().stream()
                        .filter(r -> r.uri().getPath().equals("/xhr/ncp"))
                        .map(r -> r.bodyAsString().orElseThrow()))
                .hasSize(2)
                .anySatisfy(b -> assertThat(b).contains("\"AAPL\""))
                .anySatisfy(b -> assertThat(b).contains("\"MSFT\""));
    }

    @Test
    void tickersHistoryIsStrict() {
        ok("AAPL");
        notFound("NOPE");

        Tickers group = yf.tickers(List.of("AAPL", "NOPE"));

        YFDownloadException e = catchThrowableOfType(YFDownloadException.class, () -> group.history(Period.ONE_MONTH));
        assertThat(e.failures()).containsOnlyKeys("NOPE");
        assertThat(e.partialResults()).containsOnlyKeys("AAPL");
    }
}
