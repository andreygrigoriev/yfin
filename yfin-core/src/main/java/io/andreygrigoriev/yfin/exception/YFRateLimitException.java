package io.andreygrigoriev.yfin.exception;

import org.jspecify.annotations.Nullable;

/** Yahoo answered 429 and retries were exhausted. */
public class YFRateLimitException extends YFinanceException {

    public YFRateLimitException(String message) {
        super(message);
    }

    public YFRateLimitException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
