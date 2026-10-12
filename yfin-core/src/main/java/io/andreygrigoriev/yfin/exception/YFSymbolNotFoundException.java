package io.andreygrigoriev.yfin.exception;

/** Yahoo does not know the requested symbol, or it is delisted. */
public class YFSymbolNotFoundException extends YFinanceException {

    private final String symbol;

    public YFSymbolNotFoundException(String symbol) {
        this(symbol, "symbol not found: " + symbol);
    }

    public YFSymbolNotFoundException(String symbol, String message) {
        super(message);
        this.symbol = symbol;
    }

    /** The symbol that was requested. */
    public String symbol() {
        return symbol;
    }
}
