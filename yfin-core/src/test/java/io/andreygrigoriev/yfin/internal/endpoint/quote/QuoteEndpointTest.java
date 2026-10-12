package io.andreygrigoriev.yfin.internal.endpoint.quote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import io.andreygrigoriev.yfin.exception.YFDataException;
import io.andreygrigoriev.yfin.internal.http.RequestLimiter;
import io.andreygrigoriev.yfin.internal.http.RetryPolicy;
import io.andreygrigoriev.yfin.internal.http.YahooJson;
import io.andreygrigoriev.yfin.internal.http.YahooSession;
import io.andreygrigoriev.yfin.spi.Cache;
import io.andreygrigoriev.yfin.spi.HttpRequest;
import io.andreygrigoriev.yfin.testing.FakeTransport;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QuoteEndpointTest {

    private static final String PATH = "/v7/finance/quote";

    private final FakeTransport fake = FakeTransport.create();
    private QuoteEndpoint endpoint;

    @BeforeEach
    void setUp() {
        fake.onGet("").respond(404, "not found");
        fake.onGet("/v1/test/getcrumb").respond(200, "crumb1");
        YahooSession session = YahooSession.create(fake, Cache.none(), new RequestLimiter(1), RetryPolicy.of(0));
        endpoint = new QuoteEndpoint(session);
    }

    @Test
    void quoteWithYfinanceParameters() {
        fake.onGet(PATH).query("symbols", "AAPL").respondFixture("quote/aapl.json");

        JsonNode quote = endpoint.quote("AAPL").orElseThrow();

        assertThat(quote.path("sharesOutstanding").longValue()).isEqualTo(14_594_180_000L);
        assertThat(quote.path("exchange").asText()).isEqualTo("NMS");
        HttpRequest request = fake.requests().getLast();
        assertThat(request.uri().getHost()).isEqualTo("query1.finance.yahoo.com");
        assertThat(FakeTransport.parseQuery(request.uri().getRawQuery()))
                .containsEntry("formatted", List.of("false"))
                .containsEntry("lang", List.of("en-US"))
                .containsEntry("region", List.of("US"))
                .containsEntry("crumb", List.of("crumb1"));
    }

    @Test
    void unknownSymbolIsEmpty() {
        fake.onGet(PATH).query("symbols", "NOSUCHSYMBOLXYZ").respondFixture("quote/not-found.json");

        assertThat(endpoint.quote("NOSUCHSYMBOLXYZ")).isEmpty();
    }

    @Test
    void severalSymbolsInOneRequest() {
        fake.onGet(PATH).query("symbols", "AAPL,SPY").respond(200, """
                        {"quoteResponse":{"result":[{"symbol":"AAPL","regularMarketPrice":1},
                         {"symbol":"SPY","regularMarketPrice":2},{"noSymbol":true}],"error":null}}""");

        var quotes = endpoint.quotes(List.of("AAPL", "SPY"));

        assertThat(quotes).containsOnlyKeys("AAPL", "SPY");
        assertThat(quotes.get("SPY").path("regularMarketPrice").intValue()).isEqualTo(2);
    }

    @Test
    void errors() throws Exception {
        assertThatThrownBy(() -> endpoint.quotes(List.of())).isInstanceOf(IllegalArgumentException.class);
        fake.onGet(PATH).query("symbols", "AAPL").respond(400, """
                        {"finance":{"result":null,"error":{"code":"Bad Request","description":"Missing symbols"}}}""");
        assertThatThrownBy(() -> endpoint.quote("AAPL"))
                .isInstanceOf(YFDataException.class)
                .hasMessageContaining("Bad Request");
        assertThatThrownBy(() -> QuoteEndpoint.parse(YahooJson.mapper().readTree("{}")))
                .isInstanceOf(YFDataException.class);
        assertThatThrownBy(() -> QuoteEndpoint.parse(YahooJson.mapper().readTree("{\"quoteResponse\":{\"result\":1}}")))
                .isInstanceOf(YFDataException.class);
        assertThat(QuoteEndpoint.parse(YahooJson.mapper().readTree("{\"quoteResponse\":{\"result\":null}}")))
                .isEmpty();
    }
}
