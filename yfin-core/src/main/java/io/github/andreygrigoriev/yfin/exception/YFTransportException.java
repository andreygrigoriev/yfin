package io.github.andreygrigoriev.yfin.exception;

import org.jspecify.annotations.Nullable;

/** Network failure, timeout, interrupt, or 5xx response after retries were exhausted. */
public class YFTransportException extends YFinanceException {

    public YFTransportException(String message) {
        super(message);
    }

    public YFTransportException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
