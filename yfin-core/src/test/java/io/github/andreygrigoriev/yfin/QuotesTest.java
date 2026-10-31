package io.github.andreygrigoriev.yfin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.andreygrigoriev.yfin.exception.YFDataException;
import io.github.andreygrigoriev.yfin.model.Quote;
import io.github.andreygrigoriev.yfin.spi.Cache;
import io.github.andreygrigoriev.yfin.spi.HttpRequest;
import io.github.andreygrigoriev.yfin.testing.FakeTransport;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QuotesTest {

    private static final String PATH = "/v7/finance/quote";

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

    private static List<String> symbols(int from, int to) {
        return IntStream.range(from, to).mapToObj(i -> "S" + i).toList();
    }

    private static String body(List<String> symbols) {
        String results = symbols.stream()
                .map(s -> "{\"symbol\":\"" + s + "\",\"regularMarketPrice\":1.5}")
                .collect(Collectors.joining(","));
        return "{\"quoteResponse\":{\"result\":[" + results + "],\"error\":null}}";
    }

    private void serve(List<String> batch) {
        fake.onGet(PATH).query("symbols", String.join(",", batch)).respond(200, body(batch));
    }

    private long quoteRequests() {
        return fake.requests().stream()
                .filter(r -> r.uri().getPath().equals(PATH))
                .count();
    }

    @Test
    void emptyInputMakesNoRequest() {
        assertThat(yf.quotes(List.of())).isEmpty();
        assertThat(quoteRequests()).isZero();
    }

    @Test
    void oneSymbolIsOneRequest() {
        serve(List.of("S0"));

        assertThat(yf.quotes(List.of("S0"))).containsOnlyKeys("S0");
        assertThat(quoteRequests()).isEqualTo(1);
    }

    @Test
    void exactlyOneBatchOf200() {
        serve(symbols(0, 200));

        assertThat(yf.quotes(symbols(0, 200))).hasSize(200);
        assertThat(quoteRequests()).isEqualTo(1);
    }

    @Test
    void symbol201SpillsIntoSecondRequest() {
        serve(symbols(0, 200));
        serve(symbols(200, 201));

        var quotes = yf.quotes(symbols(0, 201));

        assertThat(quotes).hasSize(201);
        assertThat(quoteRequests()).isEqualTo(2);
        assertThat(new ArrayList<>(quotes.keySet())).isEqualTo(symbols(0, 201));
    }

    @Test
    void symbolsAreNormalizedAndDeduplicated() {
        fake.onGet(PATH).query("symbols", "AAPL,MSFT").respond(200, body(List.of("AAPL", "MSFT")));

        assertThat(yf.quotes(List.of(" aapl ", "MSFT", "AAPL"))).containsOnlyKeys("AAPL", "MSFT");
        assertThat(quoteRequests()).isEqualTo(1);
    }

    @Test
    void unknownSymbolsAreOmitted() {
        fake.onGet(PATH).query("symbols", "AAPL,NOSUCH").respond(200, body(List.of("AAPL")));

        assertThat(yf.quotes(List.of("AAPL", "NOSUCH"))).containsOnlyKeys("AAPL");
    }

    @Test
    void mapsTheQuoteFields() {
        fake.onGet(PATH).query("symbols", "AAPL").respondFixture("quote/aapl.json");

        Quote q = yf.quotes(List.of("AAPL")).get("AAPL");

        assertThat(q.symbol()).isEqualTo("AAPL");
        assertThat(q.shortName()).isEqualTo("Apple Inc.");
        assertThat(q.quoteType()).isEqualTo("EQUITY");
        assertThat(q.currency()).isEqualTo("USD");
        assertThat(q.marketState()).isEqualTo("REGULAR");
        assertThat(q.price()).isEqualTo(340.49);
        assertThat(q.changePercent()).isEqualTo(1.1436477, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(q.previousClose()).isEqualTo(336.64);
        assertThat(q.volume()).isEqualTo(9_000_684L);
        assertThat(q.averageDailyVolume3Month()).isEqualTo(45_268_204L);
        assertThat(q.marketCap()).isEqualTo(4_969_172_303_872.0);
        assertThat(q.fiftyDayAverage()).isEqualTo(322.3886, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(q.twoHundredDayAverage()).isEqualTo(290.74344, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(q.fiftyTwoWeekHigh()).isEqualTo(345.34);
        assertThat(q.fiftyTwoWeekLow()).isEqualTo(243.42);
        assertThat(q.dayHigh()).isEqualTo(342.63);
        assertThat(q.dayLow()).isEqualTo(337.6);
        assertThat(q.time()).isEqualTo(Instant.ofEpochSecond(1_791_818_511L));
        assertThat(q.raw()).containsEntry("exchange", "NMS");
    }

    @Test
    void missingFieldsAreNull() {
        fake.onGet(PATH)
                .query("symbols", "X")
                .respond(200, body(List.of("X")).replace("\"regularMarketPrice\":1.5", "\"a\":1"));

        Quote q = yf.quotes(List.of("X")).get("X");

        assertThat(q.price()).isNull();
        assertThat(q.marketCap()).isNull();
        assertThat(q.volume()).isNull();
        assertThat(q.time()).isNull();
        assertThat(q.marketState()).isNull();
    }

    @Test
    void failedBatchFailsTheCall() {
        serve(symbols(0, 200));
        fake.onGet(PATH).query("symbols", "S200").respond(200, "{\"quoteResponse\":{\"result\":5}}");

        assertThatThrownBy(() -> yf.quotes(symbols(0, 201))).isInstanceOf(YFDataException.class);
    }

    @Test
    void blankSymbolIsRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> yf.quotes(List.of("AAPL", " ")));
        assertThat(quoteRequests()).isZero();
    }

    @Test
    void closedClientThrows() {
        yf.close();

        assertThatThrownBy(() -> yf.quotes(List.of("AAPL"))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void requestCarriesCrumbAndParameters() {
        serve(List.of("S0"));
        yf.quotes(List.of("S0"));

        HttpRequest request = fake.requests().getLast();
        assertThat(FakeTransport.parseQuery(request.uri().getRawQuery()))
                .containsEntry("crumb", List.of("crumb1"))
                .containsEntry("formatted", List.of("false"));
    }
}
