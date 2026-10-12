package io.andreygrigoriev.yfin.internal.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.andreygrigoriev.yfin.exception.YFDataException;
import io.andreygrigoriev.yfin.exception.YFSymbolNotFoundException;
import io.andreygrigoriev.yfin.spi.HttpResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class YahooJsonTest {

    private static HttpResponse fixture(int status, String name) throws IOException {
        byte[] body = Files.readAllBytes(Path.of("src/test/resources/fixtures/session", name));
        return new HttpResponse(status, Map.of(), body);
    }

    @Test
    void parsesNormalPayload() throws IOException {
        var json = YahooJson.read(fixture(200, "quote-ok.json"), "AAPL");

        assertThat(json.at("/quoteResponse/result/0/regularMarketPrice").asDouble())
                .isEqualTo(227.5);
    }

    @Test
    void chartNotFoundBecomesSymbolNotFound() throws IOException {
        assertThatThrownBy(() -> YahooJson.read(fixture(404, "chart-not-found.json"), "NOPE"))
                .isInstanceOfSatisfying(
                        YFSymbolNotFoundException.class,
                        e -> assertThat(e.symbol()).isEqualTo("NOPE"))
                .hasMessageContaining("chart")
                .hasMessageContaining("symbol may be delisted");
    }

    @Test
    void quoteSummaryNotFoundBecomesSymbolNotFound() throws IOException {
        assertThatThrownBy(() -> YahooJson.read(fixture(404, "quote-summary-not-found.json"), "NOPE"))
                .isInstanceOf(YFSymbolNotFoundException.class);
    }

    @Test
    void notFoundWithoutSymbolIsDataException() throws IOException {
        assertThatThrownBy(() -> YahooJson.read(fixture(404, "chart-not-found.json"), null))
                .isExactlyInstanceOf(YFDataException.class)
                .hasMessageContaining("Not Found");
    }

    @Test
    void otherErrorEnvelopeIsDataExceptionWithPayload() throws IOException {
        assertThatThrownBy(() -> YahooJson.read(fixture(400, "finance-error.json"), "AAPL"))
                .isInstanceOfSatisfying(
                        YFDataException.class,
                        e -> assertThat(e.payloadSnippet()).contains("missing parameter"))
                .hasMessageContaining("Yahoo finance error Bad Request: Invalid input - missing parameter");
    }

    @Test
    void errorEnvelopeOnSuccessStatusIsStillDetected() throws IOException {
        assertThatThrownBy(() -> YahooJson.read(fixture(200, "finance-error.json"), "AAPL"))
                .isInstanceOf(YFDataException.class);
    }

    @Test
    void nullErrorFieldIsNotAnError() {
        var json = YahooJson.read(HttpResponse.of(200, "{\"chart\":{\"result\":[],\"error\":null}}"), "AAPL");

        assertThat(json.path("chart").path("result").isArray()).isTrue();
    }

    @Test
    void invalidJsonIsDataException() {
        assertThatThrownBy(() -> YahooJson.read(HttpResponse.of(200, "<html>oops</html>"), "AAPL"))
                .isInstanceOfSatisfying(
                        YFDataException.class,
                        e -> assertThat(e.payloadSnippet()).isEqualTo("<html>oops</html>"))
                .hasMessageContaining("invalid JSON");
    }

    @Test
    void emptyBodyIsDataException() {
        assertThatThrownBy(() -> YahooJson.read(HttpResponse.of(200, ""), "AAPL"))
                .isInstanceOf(YFDataException.class)
                .hasMessageContaining("empty body");
    }

    @Test
    void errorStatusWithoutEnvelopeIsDataException() {
        assertThatThrownBy(() -> YahooJson.read(HttpResponse.of(502, "{\"message\":\"bad gateway\"}"), "AAPL"))
                .isInstanceOf(YFDataException.class)
                .hasMessageContaining("HTTP 502");
    }

    @Test
    void mapperIsShared() {
        assertThat(YahooJson.mapper()).isSameAs(YahooJson.mapper());
    }
}
