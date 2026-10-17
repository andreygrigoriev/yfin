package io.github.andreygrigoriev.yfin.live;

import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One pricing update from the Yahoo streamer (yfinance {@code PricingData}).
 *
 * <p>The wire format is proto3, which cannot tell an absent number from zero: numeric fields Yahoo
 * did not send are {@code 0}. Absent strings are {@code null}. Prices are Yahoo's 32-bit floats
 * widened to the shortest {@code double} that prints the same (so {@code 189.97f} becomes {@code
 * 189.97}), like yfinance's {@code MessageToDict}.
 *
 * @param id symbol, e.g. {@code AAPL}
 * @param price last price
 * @param time time of the update
 * @param currency quote currency
 * @param exchange exchange code, e.g. {@code NMS}
 * @param quoteType Yahoo quote type code (e.g. 8 equity, 20 ETF, 41 cryptocurrency, 14 currency)
 * @param marketHours Yahoo market session code ({@code 1} regular trading; other values mark the
 *     pre-market, post-market and overnight sessions)
 * @param changePercent change from previous close, percent
 * @param dayVolume volume traded today
 * @param dayHigh day high
 * @param dayLow day low
 * @param change change from previous close
 * @param shortName short display name
 * @param expireDate expiry of an option or future as Yahoo sends it (epoch-based), {@code 0} when
 *     not sent
 * @param openPrice day open
 * @param previousClose previous close
 * @param strikePrice option strike
 * @param underlyingSymbol option or future underlying
 * @param openInterest open interest
 * @param optionsType option type code
 * @param miniOption mini option flag
 * @param lastSize size of the last trade
 * @param bid bid price
 * @param bidSize bid size
 * @param ask ask price
 * @param askSize ask size
 * @param priceHint number of decimals Yahoo displays
 * @param vol24hr 24h volume (crypto)
 * @param volAllCurrencies 24h volume across all currencies (crypto)
 * @param fromCurrency base currency of a pair (crypto, FX)
 * @param lastMarket market of the last trade (crypto)
 * @param circulatingSupply circulating supply (crypto)
 * @param marketCap market capitalization
 */
public record PriceTick(
        String id,
        double price,
        Instant time,
        @Nullable String currency,
        @Nullable String exchange,
        int quoteType,
        int marketHours,
        double changePercent,
        long dayVolume,
        double dayHigh,
        double dayLow,
        double change,
        @Nullable String shortName,
        long expireDate,
        double openPrice,
        double previousClose,
        double strikePrice,
        @Nullable String underlyingSymbol,
        long openInterest,
        long optionsType,
        long miniOption,
        long lastSize,
        double bid,
        long bidSize,
        double ask,
        long askSize,
        long priceHint,
        long vol24hr,
        long volAllCurrencies,
        @Nullable String fromCurrency,
        @Nullable String lastMarket,
        double circulatingSupply,
        double marketCap) {

    public PriceTick {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(time, "time");
    }
}
