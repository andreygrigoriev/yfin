package io.andreygrigoriev.yfin.live.internal;

import com.google.protobuf.InvalidProtocolBufferException;
import io.andreygrigoriev.yfin.exception.YFDataException;
import io.andreygrigoriev.yfin.live.PriceTick;
import io.andreygrigoriev.yfin.live.internal.proto.PricingData;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Decodes the streamer's base64-encoded {@code PricingData} protobuf into {@link PriceTick}. */
public final class PricingDecoder {

    private PricingDecoder() {}

    /**
     * Decodes one {@code message} value of a streamer frame.
     *
     * @throws YFDataException when the value is not base64 or not a {@code PricingData} message, or
     *     the message has no symbol
     */
    public static PriceTick decode(String base64) {
        Objects.requireNonNull(base64, "base64");
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64.strip());
        } catch (IllegalArgumentException e) {
            throw new YFDataException("streamer message is not base64: " + e.getMessage(), base64, e);
        }
        PricingData data;
        try {
            data = PricingData.parseFrom(bytes);
        } catch (InvalidProtocolBufferException e) {
            throw new YFDataException("streamer message is not PricingData: " + e.getMessage(), base64, e);
        }
        if (data.getId().isEmpty()) {
            throw new YFDataException("streamer message has no symbol", base64);
        }
        return toTick(data);
    }

    static PriceTick toTick(PricingData d) {
        return new PriceTick(
                d.getId(),
                widen(d.getPrice()),
                Instant.ofEpochMilli(d.getTime()),
                text(d.getCurrency()),
                text(d.getExchange()),
                d.getQuoteType(),
                d.getMarketHours(),
                widen(d.getChangePercent()),
                d.getDayVolume(),
                widen(d.getDayHigh()),
                widen(d.getDayLow()),
                widen(d.getChange()),
                text(d.getShortName()),
                d.getExpireDate(),
                widen(d.getOpenPrice()),
                widen(d.getPreviousClose()),
                widen(d.getStrikePrice()),
                text(d.getUnderlyingSymbol()),
                d.getOpenInterest(),
                d.getOptionsType(),
                d.getMiniOption(),
                d.getLastSize(),
                widen(d.getBid()),
                d.getBidSize(),
                widen(d.getAsk()),
                d.getAskSize(),
                d.getPriceHint(),
                d.getVol24Hr(),
                d.getVolAllCurrencies(),
                text(d.getFromCurrency()),
                text(d.getLastMarket()),
                d.getCirculatingSupply(),
                d.getMarketCap());
    }

    /** The shortest double that prints like {@code f}: {@code 189.97f} becomes {@code 189.97}. */
    static double widen(float f) {
        return Float.isFinite(f) ? Double.parseDouble(Float.toString(f)) : f;
    }

    private static @Nullable String text(String s) {
        return s.isEmpty() ? null : s;
    }
}
