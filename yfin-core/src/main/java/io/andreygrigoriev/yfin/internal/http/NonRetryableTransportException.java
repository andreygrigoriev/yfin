package io.andreygrigoriev.yfin.internal.http;

import io.andreygrigoriev.yfin.exception.YFTransportException;
import org.jspecify.annotations.Nullable;

/**
 * Transport failure that would fail the same way again (an undecodable body, an interrupt), so
 * {@link RetryPolicy} rethrows it at once instead of backing off.
 */
public final class NonRetryableTransportException extends YFTransportException {

    public NonRetryableTransportException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
