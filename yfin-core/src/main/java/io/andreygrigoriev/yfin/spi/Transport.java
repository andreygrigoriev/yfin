package io.andreygrigoriev.yfin.spi;

import java.net.CookieStore;
import org.jspecify.annotations.Nullable;

/**
 * Sends HTTP requests to Yahoo. The default implementation is backed by the JDK
 * {@code HttpClient}; supply another through the client builder to change TLS, proxies or
 * testing behaviour.
 *
 * <p>Implementations must be thread-safe: the client calls {@link #send} concurrently from
 * virtual threads. Any HTTP status, including 4xx and 5xx, is returned as a response; retries
 * and status interpretation happen above the transport.
 */
@FunctionalInterface
public interface Transport {

    /**
     * Sends {@code request} and blocks until the full response body has been read.
     *
     * @throws io.andreygrigoriev.yfin.exception.YFTransportException on network failure, timeout
     *     or interrupt
     */
    HttpResponse send(HttpRequest request);

    /**
     * The cookie store this transport applies to requests and fills from {@code Set-Cookie}
     * responses, or {@code null} (the default) when it does not handle cookies. With {@code null},
     * the client keeps its own store and sends a {@code Cookie} header itself. A decorator around a
     * cookie-managing transport should return its delegate's store, so cookies are neither sent
     * twice nor kept in two stores.
     */
    default @Nullable CookieStore cookieStore() {
        return null;
    }
}
