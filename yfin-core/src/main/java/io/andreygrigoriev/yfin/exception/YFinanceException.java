package io.andreygrigoriev.yfin.exception;

import org.jspecify.annotations.Nullable;

/** Base class of every exception thrown by yfin. Unchecked. */
public class YFinanceException extends RuntimeException {

    public YFinanceException(String message) {
        super(message);
    }

    public YFinanceException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
