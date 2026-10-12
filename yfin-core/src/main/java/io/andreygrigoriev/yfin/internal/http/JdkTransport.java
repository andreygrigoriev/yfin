package io.andreygrigoriev.yfin.internal.http;

import io.andreygrigoriev.yfin.exception.YFTransportException;
import io.andreygrigoriev.yfin.spi.HttpRequest;
import io.andreygrigoriev.yfin.spi.HttpResponse;
import io.andreygrigoriev.yfin.spi.Transport;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.CookieStore;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.jspecify.annotations.Nullable;

/**
 * Default {@link Transport} backed by the JDK {@link HttpClient}.
 *
 * <p>The TLS ClientHello is tuned towards Chrome's: Yahoo blocks the default JDK fingerprint with
 * 429 on the first request (see {@code docs/notes/20261012-transport-spike.md}). The cipher suite
 * list is what matters; protocols are restricted to TLS 1.3/1.2 as well. Named groups and signature
 * schemes are not set: {@code HttpClient} copies only cipher suites and protocols (among others)
 * out of the supplied {@link SSLParameters}, so setting them there has no effect, and the spike
 * showed they are not needed.
 *
 * <p>Cookies live in an in-memory {@link CookieManager} exposed through {@link #cookieStore()} so
 * the session can persist and restore them. Gzip and deflate response bodies are decoded. Close the
 * transport to release the client and its virtual-thread executor.
 */
public final class JdkTransport implements Transport, AutoCloseable {

    /** User-Agent sent when a request does not set its own. */
    public static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36";

    /**
     * Chrome's cipher suites, in Chrome order. Suites the JSSE provider does not offer are dropped
     * at build time (JDK 24+ disables the {@code TLS_RSA_*} key exchange by default).
     */
    static final List<String> CHROME_CIPHER_SUITES = List.of(
            "TLS_AES_128_GCM_SHA256",
            "TLS_AES_256_GCM_SHA384",
            "TLS_CHACHA20_POLY1305_SHA256",
            "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
            "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256",
            "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA",
            "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA",
            "TLS_RSA_WITH_AES_128_GCM_SHA256",
            "TLS_RSA_WITH_AES_256_GCM_SHA384",
            "TLS_RSA_WITH_AES_128_CBC_SHA",
            "TLS_RSA_WITH_AES_256_CBC_SHA");

    static final List<String> PROTOCOLS = List.of("TLSv1.3", "TLSv1.2");

    /** Headers the JDK client manages itself and rejects when set by the caller. */
    private static final Set<String> RESTRICTED_HEADERS =
            Set.of("connection", "content-length", "expect", "host", "upgrade");

    private final HttpClient client;
    private final ExecutorService executor;
    private final CookieManager cookieManager;
    private final Duration requestTimeout;
    private final String userAgent;

    private JdkTransport(Builder builder) {
        this.cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.requestTimeout = builder.requestTimeout;
        this.userAgent = builder.userAgent;
        var clientBuilder = HttpClient.newBuilder()
                .version(builder.version)
                .connectTimeout(builder.connectTimeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .cookieHandler(cookieManager)
                .executor(executor)
                .sslContext(defaultSslContext())
                .sslParameters(chromeSslParameters());
        if (builder.proxy != null) {
            clientBuilder.proxy(builder.proxy);
        }
        this.client = clientBuilder.build();
    }

    /** Transport with default settings. */
    public static JdkTransport create() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The in-memory cookie store, for the session to persist and restore cookies. */
    public CookieStore cookieStore() {
        return cookieManager.getCookieStore();
    }

    /** TLS parameters the underlying client was built with. */
    SSLParameters sslParameters() {
        return client.sslParameters();
    }

    @Override
    public HttpResponse send(HttpRequest request) {
        java.net.http.HttpResponse<byte[]> response;
        try {
            response = client.send(toJdk(request), java.net.http.HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpTimeoutException e) {
            throw new YFTransportException("timeout: " + describe(request), e);
        } catch (IOException e) {
            throw new YFTransportException("I/O error: " + describe(request) + ": " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new YFTransportException("interrupted: " + describe(request), e);
        }
        return fromJdk(request, response);
    }

    @Override
    public void close() {
        client.close();
        executor.close();
    }

    private java.net.http.HttpRequest toJdk(HttpRequest request) {
        var body = request.body();
        var builder = java.net.http.HttpRequest.newBuilder(request.uri())
                .timeout(requestTimeout)
                .method(
                        request.method(),
                        body == null
                                ? java.net.http.HttpRequest.BodyPublishers.noBody()
                                : java.net.http.HttpRequest.BodyPublishers.ofByteArray(body));
        request.headers().forEach((name, values) -> {
            if (!RESTRICTED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                values.forEach(value -> builder.header(name, value));
            }
        });
        if (request.header("User-Agent").isEmpty()) {
            builder.header("User-Agent", userAgent);
        }
        if (request.header("Accept-Encoding").isEmpty()) {
            builder.header("Accept-Encoding", "gzip, deflate");
        }
        return builder.build();
    }

    private static HttpResponse fromJdk(HttpRequest request, java.net.http.HttpResponse<byte[]> response) {
        var headers = new LinkedHashMap<String, List<String>>();
        response.headers().map().forEach((name, values) -> {
            if (!name.startsWith(":")) {
                headers.put(name, values);
            }
        });
        var encoding = response.headers()
                .firstValue("Content-Encoding")
                .map(e -> e.trim().toLowerCase(Locale.ROOT))
                .orElse("identity");
        byte[] body = response.body() == null ? new byte[0] : response.body();
        if (!encoding.equals("identity") && body.length > 0) {
            body = decode(request, encoding, body);
            removeHeader(headers, "Content-Encoding");
            removeHeader(headers, "Content-Length");
        }
        return new HttpResponse(response.statusCode(), headers, body);
    }

    private static byte[] decode(HttpRequest request, String encoding, byte[] body) {
        try (InputStream in = switch (encoding) {
            case "gzip", "x-gzip" -> new GZIPInputStream(new ByteArrayInputStream(body));
            case "deflate" -> new InflaterInputStream(new ByteArrayInputStream(body));
            default ->
                throw new YFTransportException("unsupported Content-Encoding '" + encoding + "': " + describe(request));
        }) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new YFTransportException("cannot decode " + encoding + " body: " + describe(request), e);
        }
    }

    private static void removeHeader(Map<String, List<String>> headers, String name) {
        headers.keySet().removeIf(key -> key.equalsIgnoreCase(name));
    }

    private static String describe(HttpRequest request) {
        return request.method() + " " + request.uri();
    }

    private static SSLContext defaultSslContext() {
        try {
            return SSLContext.getDefault();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("no default SSLContext", e);
        }
    }

    private static SSLParameters chromeSslParameters() {
        var supported = defaultSslContext().getSupportedSSLParameters();
        var params = new SSLParameters();
        params.setCipherSuites(retainSupported(CHROME_CIPHER_SUITES, supported.getCipherSuites()));
        params.setProtocols(retainSupported(PROTOCOLS, supported.getProtocols()));
        return params;
    }

    /** {@code wanted} in its own order, minus entries the JSSE provider does not support. */
    private static String[] retainSupported(List<String> wanted, String[] supported) {
        var available = Set.copyOf(Arrays.asList(supported));
        return wanted.stream().filter(available::contains).toArray(String[]::new);
    }

    /** Settings for {@link JdkTransport}. */
    public static final class Builder {

        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration requestTimeout = Duration.ofSeconds(30);
        private @Nullable ProxySelector proxy;
        private String userAgent = DEFAULT_USER_AGENT;
        private HttpClient.Version version = HttpClient.Version.HTTP_2;

        private Builder() {}

        public Builder connectTimeout(Duration timeout) {
            this.connectTimeout = positive(timeout, "connectTimeout");
            return this;
        }

        /** Timeout for a whole request, until the response headers arrive. */
        public Builder requestTimeout(Duration timeout) {
            this.requestTimeout = positive(timeout, "requestTimeout");
            return this;
        }

        /** Proxy selector, or {@code null} for a direct connection (default). */
        public Builder proxy(@Nullable ProxySelector proxy) {
            this.proxy = proxy;
            return this;
        }

        /** User-Agent sent when a request does not set its own. */
        public Builder userAgent(String userAgent) {
            if (Objects.requireNonNull(userAgent, "userAgent").isBlank()) {
                throw new IllegalArgumentException("userAgent is blank");
            }
            this.userAgent = userAgent;
            return this;
        }

        /** Preferred HTTP version; HTTP/2 by default. */
        public Builder version(HttpClient.Version version) {
            this.version = Objects.requireNonNull(version, "version");
            return this;
        }

        public JdkTransport build() {
            return new JdkTransport(this);
        }

        private static Duration positive(Duration d, String name) {
            Objects.requireNonNull(d, name);
            if (d.isNegative() || d.isZero()) {
                throw new IllegalArgumentException(name + " must be positive: " + d);
            }
            return d;
        }
    }
}
