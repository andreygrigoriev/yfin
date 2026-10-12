package io.andreygrigoriev.yfin.request;

import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

/** Markets Yahoo's market summary knows (yfinance {@code MarketRegion}). */
public enum MarketRegion {
    US,
    GB,
    ASIA,
    EUROPE,
    RATES,
    COMMODITIES,
    CURRENCIES,
    CRYPTOCURRENCIES;

    /**
     * The region named {@code name}, ignoring case and surrounding blanks.
     *
     * @throws IllegalArgumentException when {@code name} is not a known region
     */
    public static MarketRegion of(String name) {
        String n = Objects.requireNonNull(name, "name").trim().toUpperCase(Locale.ROOT);
        for (MarketRegion r : values()) {
            if (r.name().equals(n)) {
                return r;
            }
        }
        throw new IllegalArgumentException("unknown market " + name + ", valid markets: " + Arrays.toString(values()));
    }
}
