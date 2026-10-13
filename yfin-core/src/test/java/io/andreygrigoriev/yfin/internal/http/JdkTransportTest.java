package io.andreygrigoriev.yfin.internal.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.andreygrigoriev.yfin.exception.YFTransportException;
import io.andreygrigoriev.yfin.spi.HttpRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JdkTransportTest {

    private HttpServer server;
    private JdkTransport transport;
    private final Map<String, Recorded> received = new ConcurrentHashMap<>();

    private record Recorded(String method, Map<String, List<String>> headers, String body) {}

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/echo", exchange -> {
            record(exchange);
            respond(exchange, 200, "echo:" + exchange.getRequestMethod(), Map.of("X-Reply", "yes"));
        });
        server.createContext("/gzip", exchange -> {
            record(exchange);
            var buffer = new ByteArrayOutputStream();
            try (var gzip = new GZIPOutputStream(buffer)) {
                gzip.write("{\"zipped\":true}".getBytes(StandardCharsets.UTF_8));
            }
            exchange.getResponseHeaders().add("Content-Encoding", "gzip");
            byte[] bytes = buffer.toByteArray();
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.createContext("/deflate", exchange -> {
            record(exchange);
            var buffer = new ByteArrayOutputStream();
            try (var deflate = new DeflaterOutputStream(buffer)) {
                deflate.write("{\"deflated\":true}".getBytes(StandardCharsets.UTF_8));
            }
            sendEncoded(exchange, "deflate", buffer.toByteArray());
        });
        server.createContext("/brotli", exchange -> {
            record(exchange);
            sendEncoded(exchange, "br", new byte[] {1, 2, 3});
        });
        server.createContext("/corrupt-gzip", exchange -> {
            record(exchange);
            sendEncoded(exchange, "gzip", "not gzip at all".getBytes(StandardCharsets.UTF_8));
        });
        server.createContext("/set-cookie", exchange -> {
            record(exchange);
            respond(exchange, 200, "ok", Map.of("Set-Cookie", "A3=abc123; Path=/"));
        });
        server.createContext("/status", exchange -> {
            record(exchange);
            respond(exchange, 503, "unavailable", Map.of());
        });
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(3_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "late", Map.of());
        });
        server.start();
        transport =
                JdkTransport.builder().requestTimeout(Duration.ofMillis(300)).build();
    }

    @AfterEach
    void stopServer() {
        transport.close();
        server.stop(0);
    }

    private void record(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        received.put(
                exchange.getRequestURI().getPath(),
                new Recorded(exchange.getRequestMethod(), Map.copyOf(exchange.getRequestHeaders()), body));
    }

    private static void respond(HttpExchange exchange, int status, String body, Map<String, String> headers)
            throws IOException {
        headers.forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void sendEncoded(HttpExchange exchange, String encoding, byte[] bytes) throws IOException {
        exchange.getResponseHeaders().add("Content-Encoding", encoding);
        exchange.sendResponseHeaders(200, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    private static String header(Recorded recorded, String name) {
        return recorded.headers().entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase(name))
                .map(e -> String.join(",", e.getValue()))
                .findFirst()
                .orElse(null);
    }

    @Test
    void getSendsHeadersAndReturnsResponse() {
        var response = transport.send(HttpRequest.get(uri("/echo?q=1")).withHeader("X-Test", "42"));

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.bodyAsString()).isEqualTo("echo:GET");
        assertThat(response.header("x-reply")).hasValue("yes");
        var recorded = received.get("/echo");
        assertThat(recorded.method()).isEqualTo("GET");
        assertThat(header(recorded, "X-Test")).isEqualTo("42");
        assertThat(header(recorded, "User-Agent")).isEqualTo(JdkTransport.DEFAULT_USER_AGENT);
        assertThat(header(recorded, "Accept-Encoding")).contains("gzip");
    }

    @Test
    void postSendsBody() {
        var response = transport.send(HttpRequest.postJson(uri("/echo"), "{\"a\":1}"));

        assertThat(response.bodyAsString()).isEqualTo("echo:POST");
        var recorded = received.get("/echo");
        assertThat(recorded.method()).isEqualTo("POST");
        assertThat(recorded.body()).isEqualTo("{\"a\":1}");
        assertThat(header(recorded, "Content-Type")).isEqualTo("application/json");
    }

    @Test
    void requestUserAgentOverridesDefaultAndRestrictedHeadersAreDropped() {
        transport.send(HttpRequest.get(uri("/echo"))
                .withHeader("User-Agent", "custom/1.0")
                .withHeader("Connection", "close"));

        assertThat(header(received.get("/echo"), "User-Agent")).isEqualTo("custom/1.0");
    }

    @Test
    void builderUserAgentIsUsedByDefault() {
        try (var custom = JdkTransport.builder().userAgent("yfin-test").build()) {
            custom.send(HttpRequest.get(uri("/echo")));
        }

        assertThat(header(received.get("/echo"), "User-Agent")).isEqualTo("yfin-test");
    }

    @Test
    void decodesGzipBody() {
        var response = transport.send(HttpRequest.get(uri("/gzip")));

        assertThat(response.bodyAsString()).isEqualTo("{\"zipped\":true}");
        assertThat(response.header("Content-Encoding")).isEmpty();
    }

    @Test
    void decodesDeflateBody() {
        var response = transport.send(HttpRequest.get(uri("/deflate")));

        assertThat(response.bodyAsString()).isEqualTo("{\"deflated\":true}");
        assertThat(response.header("Content-Encoding")).isEmpty();
    }

    @Test
    void unsupportedOrCorruptEncodingIsANonRetryableTransportException() {
        assertThatThrownBy(() -> transport.send(HttpRequest.get(uri("/brotli"))))
                .isInstanceOf(NonRetryableTransportException.class)
                .hasMessageContaining("unsupported Content-Encoding 'br'");
        assertThatThrownBy(() -> transport.send(HttpRequest.get(uri("/corrupt-gzip"))))
                .isInstanceOf(NonRetryableTransportException.class)
                .hasMessageContaining("cannot decode gzip body")
                .hasCauseInstanceOf(IOException.class);
    }

    @Test
    void routesRequestsThroughTheConfiguredProxy() {
        var proxied = JdkTransport.builder()
                .proxy(ProxySelector.of(new InetSocketAddress(
                        InetAddress.getLoopbackAddress(), server.getAddress().getPort())))
                .build();
        try (proxied) {
            // the host does not resolve: only the proxy (our server) can answer
            var response = proxied.send(HttpRequest.get(URI.create("http://proxied.invalid/echo")));

            assertThat(response.bodyAsString()).isEqualTo("echo:GET");
            assertThat(header(received.get("/echo"), "Host")).contains("proxied.invalid");
        }
    }

    @Test
    void errorStatusIsReturnedNotThrown() {
        var response = transport.send(HttpRequest.get(uri("/status")));

        assertThat(response.status()).isEqualTo(503);
        assertThat(response.bodyAsString()).isEqualTo("unavailable");
    }

    @Test
    void storesCookiesAndSendsThemBack() {
        transport.send(HttpRequest.get(uri("/set-cookie")));
        transport.send(HttpRequest.get(uri("/echo")));

        assertThat(transport.cookieStore().getCookies()).anySatisfy(cookie -> {
            assertThat(cookie.getName()).isEqualTo("A3");
            assertThat(cookie.getValue()).isEqualTo("abc123");
        });
        assertThat(header(received.get("/echo"), "Cookie")).contains("A3=abc123");
    }

    @Test
    void configuresChromeLikeTls() {
        var params = transport.sslParameters();

        // Chrome's list in Chrome order, minus what the provider disables (TLS_RSA_* on JDK 24+)
        assertThat(params.getCipherSuites())
                .isSubsetOf(JdkTransport.CHROME_CIPHER_SUITES)
                .containsSubsequence(JdkTransport.CHROME_CIPHER_SUITES.subList(0, 11))
                .noneMatch(suite -> suite.contains("_DHE_"));
        assertThat(params.getProtocols()).containsExactly("TLSv1.3", "TLSv1.2");
    }

    @Test
    void mapsTimeoutToTransportException() {
        assertThatThrownBy(() -> transport.send(HttpRequest.get(uri("/slow"))))
                .isInstanceOf(YFTransportException.class)
                .hasMessageContaining("timeout")
                .hasCauseInstanceOf(HttpTimeoutException.class);
    }

    @Test
    void mapsConnectionRefusedToTransportException() throws IOException {
        int port;
        try (var socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        }

        assertThatThrownBy(() -> transport.send(HttpRequest.get(URI.create("http://127.0.0.1:" + port + "/"))))
                .isInstanceOf(YFTransportException.class)
                .hasMessageContaining("I/O error")
                .cause()
                .isInstanceOf(IOException.class);
    }

    @Test
    void mapsInterruptToTransportExceptionAndRestoresFlag() {
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> transport.send(HttpRequest.get(uri("/slow"))))
                    .isInstanceOf(YFTransportException.class)
                    .hasMessageContaining("interrupted")
                    .hasCauseInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void rejectsNonPositiveTimeouts() {
        assertThatThrownBy(() -> JdkTransport.builder().requestTimeout(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JdkTransport.builder().connectTimeout(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
