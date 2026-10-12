package io.andreygrigoriev.yfin.model;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Metadata Yahoo returns with a price chart: listing, latest regular-market quote and the valid
 * ranges. Fields Yahoo omits are {@code null}; {@link #raw()} holds every field as sent (JSON
 * objects as {@code Map}, arrays as {@code List}, numbers as {@code Long} or {@code Double}).
 *
 * @param exchangeZone the exchange's timezone
 * @param timezone the zone abbreviation Yahoo reports, e.g. {@code EDT}
 * @param gmtOffset the current UTC offset in seconds
 * @param validRanges period codes Yahoo accepts for this symbol
 */
public record HistoryMetadata(
        String symbol,
        @Nullable String currency,
        @Nullable String exchangeName,
        @Nullable String fullExchangeName,
        @Nullable String instrumentType,
        ZoneId exchangeZone,
        @Nullable String timezone,
        @Nullable Long gmtOffset,
        @Nullable ZonedDateTime firstTradeDate,
        @Nullable ZonedDateTime regularMarketTime,
        @Nullable Double regularMarketPrice,
        @Nullable Double regularMarketDayHigh,
        @Nullable Double regularMarketDayLow,
        @Nullable Long regularMarketVolume,
        @Nullable Double previousClose,
        @Nullable Double chartPreviousClose,
        @Nullable Double fiftyTwoWeekHigh,
        @Nullable Double fiftyTwoWeekLow,
        @Nullable String longName,
        @Nullable String shortName,
        @Nullable Integer priceHint,
        @Nullable Boolean hasPrePostMarketData,
        @Nullable String dataGranularity,
        @Nullable String range,
        List<String> validRanges,
        Map<String, Object> raw) {

    public HistoryMetadata {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(exchangeZone, "exchangeZone");
        validRanges = List.copyOf(validRanges);
        raw = Collections.unmodifiableMap(new LinkedHashMap<>(raw));
    }
}
