package io.github.andreygrigoriev.yfin.model;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Trading hours of a market today (yfinance {@code Market.status}).
 *
 * @param id Yahoo's market id, e.g. {@code us}
 * @param name market name, e.g. {@code U.S. markets}
 * @param status {@code open} or {@code closed}
 * @param message human-readable status, e.g. {@code U.S. markets close in 17 minutes}
 * @param open today's (or the next session's) open, in {@code zone}
 * @param close today's (or the next session's) close, in {@code zone}
 * @param zone the market's time zone
 * @param zoneShortName short zone name, e.g. {@code EDT}
 * @param raw the market time object as Yahoo returns it; unmodifiable
 */
public record MarketStatus(
        @Nullable String id,
        @Nullable String name,
        @Nullable String status,
        @Nullable String message,
        ZonedDateTime open,
        ZonedDateTime close,
        ZoneId zone,
        @Nullable String zoneShortName,
        Map<String, Object> raw) {

    public MarketStatus {
        Objects.requireNonNull(open, "open");
        Objects.requireNonNull(close, "close");
        Objects.requireNonNull(zone, "zone");
        raw = Collections.unmodifiableMap(new LinkedHashMap<>(raw));
    }

    /** Whether Yahoo reports the market as open. */
    public boolean isOpen() {
        return "open".equalsIgnoreCase(status);
    }
}
