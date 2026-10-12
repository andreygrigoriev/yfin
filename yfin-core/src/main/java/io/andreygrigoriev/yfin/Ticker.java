package io.andreygrigoriev.yfin;

import java.util.Objects;

/**
 * Data for one symbol. Obtained from {@link YFinance#ticker(String)}; cheap to create, makes no
 * request until a data method is called. Thread-safe.
 */
public final class Ticker {

    private final YFinance client;
    private final String symbol;

    Ticker(YFinance client, String symbol) {
        this.client = Objects.requireNonNull(client, "client");
        this.symbol = Objects.requireNonNull(symbol, "symbol");
    }

    /** The normalized (trimmed, upper-case) symbol. */
    public String symbol() {
        return symbol;
    }

    YFinance client() {
        return client;
    }

    @Override
    public String toString() {
        return "Ticker[" + symbol + "]";
    }
}
