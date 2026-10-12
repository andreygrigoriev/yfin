package io.andreygrigoriev.yfin.exception;

import org.jspecify.annotations.Nullable;

/** Cookie or crumb acquisition failed, or Yahoo rejected the session. */
public class YFAuthException extends YFinanceException {

    public YFAuthException(String message) {
        super(message);
    }

    public YFAuthException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
