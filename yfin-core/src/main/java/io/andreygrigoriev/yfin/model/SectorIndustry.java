package io.andreygrigoriev.yfin.model;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * An industry of a sector (a row of yfinance {@code Sector.industries}).
 *
 * @param key industry key, usable with {@code YFinance.industry(key)}
 * @param name industry name
 * @param symbol Yahoo's index symbol of the industry, e.g. {@code ^YH31130020}
 * @param marketWeight weight in the sector, a fraction
 */
public record SectorIndustry(
        String key,
        @Nullable String name,
        @Nullable String symbol,
        @Nullable Double marketWeight) {

    public SectorIndustry {
        Objects.requireNonNull(key, "key");
    }
}
