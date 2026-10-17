package io.github.andreygrigoriev.yfin.request;

/** Instrument type a {@link io.github.andreygrigoriev.yfin.Lookup} is restricted to (yfinance {@code Lookup.get_*}). */
public enum LookupType {
    /** Every type (yfinance {@code get_all}, the default). */
    ALL("all"),
    /** Stocks (yfinance {@code get_stock}). */
    STOCK("equity"),
    /** Mutual funds. */
    MUTUAL_FUND("mutualfund"),
    /** Exchange-traded funds. */
    ETF("etf"),
    /** Indices. */
    INDEX("index"),
    /** Futures. */
    FUTURE("future"),
    /** Currencies. */
    CURRENCY("currency"),
    /** Cryptocurrencies. */
    CRYPTOCURRENCY("cryptocurrency");

    private final String yahooType;

    LookupType(String yahooType) {
        this.yahooType = yahooType;
    }

    /** Yahoo's {@code type} parameter for this type. */
    public String yahooType() {
        return yahooType;
    }
}
