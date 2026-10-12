package io.andreygrigoriev.yfin.internal.endpoint.timeseries;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.andreygrigoriev.yfin.YFinance;
import io.andreygrigoriev.yfin.exception.YFDataException;
import io.andreygrigoriev.yfin.model.LineItem;
import io.andreygrigoriev.yfin.request.Frequency;
import io.andreygrigoriev.yfin.spi.Cache;
import io.andreygrigoriev.yfin.spi.HttpRequest;
import io.andreygrigoriev.yfin.testing.FakeTransport;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class TimeseriesEndpointTest {

    private static final String PATH = TimeseriesMapperTest.PATH;

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

    @Test
    void requestsEveryKeyInChunksWithPeriodBounds() {
        TimeseriesMapperTest.serve(
                fake, "AAPL", StatementKeys.BALANCE_SHEET, Frequency.ANNUAL, "aapl-annual-balance-sheet");

        yf.ticker("AAPL").balanceSheet(Frequency.ANNUAL);

        List<HttpRequest> requests = fake.requests().stream()
                .filter(r -> r.uri().getPath().equals(PATH + "AAPL"))
                .toList();
        assertThat(requests).hasSize(3);
        var types = new ArrayList<String>();
        for (HttpRequest r : requests) {
            assertThat(r.uri().getHost()).isEqualTo("query2.finance.yahoo.com");
            assertThat(r.uri().toString().length()).isLessThan(2500);
            Map<String, List<String>> q = FakeTransport.parseQuery(r.uri().getRawQuery());
            assertThat(q.get("symbol")).containsExactly("AAPL");
            assertThat(q.get("period1")).containsExactly("1483142400");
            assertThat(Long.parseLong(q.get("period2").getFirst()) % 86_400).isZero();
            List<String> chunk = List.of(q.get("type").getFirst().split(","));
            assertThat(chunk.size()).isLessThanOrEqualTo(TimeseriesEndpoint.CHUNK_KEYS);
            types.addAll(chunk);
        }
        assertThat(types)
                .isEqualTo(StatementKeys.BALANCE_SHEET.keys().stream()
                        .map(k -> "annual" + k)
                        .toList());
    }

    @Test
    void period2IsNextUtcMidnight() {
        assertThat(TimeseriesEndpoint.period2(Instant.parse("2026-10-12T15:30:00Z")))
                .isEqualTo(Instant.parse("2026-10-13T00:00:00Z").getEpochSecond());
        assertThat(TimeseriesEndpoint.period2(Instant.parse("2026-10-12T00:00:00Z")))
                .isEqualTo(Instant.parse("2026-10-12T00:00:00Z").getEpochSecond());
    }

    @Test
    void paramsUseClock() {
        var endpoint = new TimeseriesEndpoint(
                io.andreygrigoriev.yfin.internal.http.YahooSession.create(
                        fake,
                        Cache.none(),
                        new io.andreygrigoriev.yfin.internal.http.RequestLimiter(1),
                        io.andreygrigoriev.yfin.internal.http.RetryPolicy.of(0)),
                () -> Instant.parse("2026-01-01T12:00:00Z"));

        Map<String, String> params = endpoint.params("MSFT", "quarterly", List.of("TotalRevenue", "NetIncome"));

        assertThat(params)
                .containsExactly(
                        Map.entry("symbol", "MSFT"),
                        Map.entry("type", "quarterlyTotalRevenue,quarterlyNetIncome"),
                        Map.entry("period1", "1483142400"),
                        Map.entry(
                                "period2",
                                Long.toString(
                                        Instant.parse("2026-01-02T00:00:00Z").getEpochSecond())));
    }

    @Test
    void errorEnvelopeIsDataException() {
        fake.onGet(PATH + "AAPL")
                .respond(
                        400,
                        "{\"finance\":{\"result\":null,\"error\":{\"code\":\"Bad Request\","
                                + "\"description\":\"Parameter=period1 has an invalid type\"}}}");

        assertThatThrownBy(() -> yf.ticker("AAPL").incomeStatement(Frequency.ANNUAL))
                .isInstanceOf(YFDataException.class)
                .hasMessageContaining("Bad Request");
    }

    @Test
    void malformedPayloadIsDataException() {
        fake.onGet(PATH + "AAPL").respond(200, "{\"timeseries\":{\"result\":{}}}");
        assertThatThrownBy(() -> yf.ticker("AAPL").incomeStatement(Frequency.ANNUAL))
                .isInstanceOf(YFDataException.class)
                .hasMessageContaining("not a list");
    }

    @Test
    void missingEnvelopeIsDataException() {
        fake.onGet(PATH + "AAPL").respond(200, "{\"other\":1}");
        assertThatThrownBy(() -> yf.ticker("AAPL").cashFlow(Frequency.ANNUAL))
                .isInstanceOf(YFDataException.class)
                .hasMessageContaining("no timeseries object");
    }

    @ParameterizedTest
    @EnumSource(StatementKeys.class)
    void keyListsAreUniqueAndNonEmpty(StatementKeys statement) {
        assertThat(statement.keys()).isNotEmpty().doesNotHaveDuplicates().allMatch(k -> !k.isBlank());
    }

    @Test
    void keyListSizesMatchYfinance() {
        assertThat(StatementKeys.INCOME.keys()).hasSize(103);
        assertThat(StatementKeys.BALANCE_SHEET.keys()).hasSize(149);
        assertThat(StatementKeys.CASH_FLOW.keys()).hasSize(123);
        assertThat(StatementKeys.BALANCE_SHEET.supportsTrailing()).isFalse();
        assertThat(StatementKeys.INCOME.supportsTrailing()).isTrue();
        assertThat(StatementKeys.CASH_FLOW.supportsTrailing()).isTrue();
    }

    @Test
    void everyLineItemConstantIsAStatementKey() throws Exception {
        var all = new HashSet<String>();
        for (StatementKeys s : StatementKeys.values()) {
            all.addAll(s.keys());
        }
        int constants = 0;
        for (Field f : LineItem.class.getFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == LineItem.class) {
                assertThat(all).as(f.getName()).contains(((LineItem) f.get(null)).key());
                constants++;
            }
        }
        assertThat(constants).isGreaterThan(50);
    }
}
