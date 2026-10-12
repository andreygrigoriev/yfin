package io.andreygrigoriev.yfin.exception;

import io.andreygrigoriev.yfin.model.PriceHistory;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A bulk download failed for at least one symbol. Carries the failure of every failed symbol and
 * the histories of the symbols that succeeded; each failure is also added as a suppressed
 * exception.
 */
public class YFDownloadException extends YFinanceException {

    private final Map<String, YFinanceException> failures;
    private final Map<String, PriceHistory> partialResults;

    public YFDownloadException(
            Map<String, ? extends YFinanceException> failures, Map<String, PriceHistory> partialResults) {
        super(message(failures, partialResults));
        this.failures = Collections.unmodifiableMap(new LinkedHashMap<>(failures));
        this.partialResults = Collections.unmodifiableMap(new LinkedHashMap<>(partialResults));
        this.failures.values().forEach(this::addSuppressed);
    }

    /** Failure per failed symbol, in input order. */
    public Map<String, YFinanceException> failures() {
        return failures;
    }

    /** History per succeeded symbol, in input order. */
    public Map<String, PriceHistory> partialResults() {
        return partialResults;
    }

    private static String message(
            Map<String, ? extends YFinanceException> failures, Map<String, PriceHistory> partialResults) {
        int total = failures.size() + partialResults.size();
        return "download failed for " + failures.size() + " of " + total + " symbols: " + failures.keySet();
    }
}
