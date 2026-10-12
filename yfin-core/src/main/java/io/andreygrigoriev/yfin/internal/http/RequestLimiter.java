package io.andreygrigoriev.yfin.internal.http;

import io.andreygrigoriev.yfin.exception.YFTransportException;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * Caps the number of in-flight requests with a fair semaphore. Waiting is interruptible: an
 * interrupt while waiting for a permit becomes {@link YFTransportException} with the interrupt flag
 * restored.
 */
public final class RequestLimiter {

    /** Default {@code maxConcurrentRequests}. */
    public static final int DEFAULT_MAX_CONCURRENT_REQUESTS = 8;

    private final int maxConcurrentRequests;
    private final Semaphore permits;

    public RequestLimiter(int maxConcurrentRequests) {
        if (maxConcurrentRequests < 1) {
            throw new IllegalArgumentException("maxConcurrentRequests must be >= 1: " + maxConcurrentRequests);
        }
        this.maxConcurrentRequests = maxConcurrentRequests;
        this.permits = new Semaphore(maxConcurrentRequests, true);
    }

    public int maxConcurrentRequests() {
        return maxConcurrentRequests;
    }

    /** Permits currently free. */
    int availablePermits() {
        return permits.availablePermits();
    }

    /** Runs {@code call} while holding a permit, blocking until one is free. */
    public <T> T call(Supplier<T> call) {
        try {
            permits.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new YFTransportException("interrupted while waiting for a request permit", e);
        }
        try {
            return call.get();
        } finally {
            permits.release();
        }
    }
}
