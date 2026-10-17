package io.github.andreygrigoriev.yfin.internal.endpoint.options;

import static io.github.andreygrigoriev.yfin.internal.http.YahooJson.text;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.andreygrigoriev.yfin.internal.http.YahooJson;
import io.github.andreygrigoriev.yfin.model.OptionChain;
import io.github.andreygrigoriev.yfin.model.OptionContract;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Maps a {@code /v7/finance/options} result to expirations and {@link OptionChain}s. */
final class OptionsMapper {

    private OptionsMapper() {}

    /**
     * Expiration dates (UTC) to the epoch seconds Yahoo expects as {@code date}, in Yahoo's order
     * (nearest first).
     */
    static Map<LocalDate, Long> expirations(JsonNode result) {
        var out = new LinkedHashMap<LocalDate, Long>();
        for (JsonNode epoch : result.path("expirationDates")) {
            if (epoch.isIntegralNumber()) {
                out.putIfAbsent(date(epoch.longValue()), epoch.longValue());
            }
        }
        return Collections.unmodifiableMap(out);
    }

    /** The chain in {@code result}, or empty when it has none (a symbol without options). */
    static Optional<OptionChain> chain(String symbol, JsonNode result) {
        JsonNode options = result.path("options").path(0);
        JsonNode expiration = options.path("expirationDate");
        if (!options.isObject() || !expiration.isIntegralNumber()) {
            return Optional.empty();
        }
        JsonNode quote = result.path("quote");
        ZoneId zone = zone(quote);
        return Optional.of(new OptionChain(
                symbol,
                date(expiration.longValue()),
                contracts(options.path("calls"), zone),
                contracts(options.path("puts"), zone),
                YahooJson.plainMap(quote)));
    }

    private static List<OptionContract> contracts(JsonNode nodes, ZoneId zone) {
        var out = new ArrayList<OptionContract>(nodes.size());
        for (JsonNode node : nodes) {
            String contractSymbol = text(node.path("contractSymbol"));
            if (contractSymbol == null) {
                continue;
            }
            JsonNode lastTrade = node.path("lastTradeDate");
            @Nullable
            ZonedDateTime lastTradeDate = lastTrade.isNumber()
                    ? Instant.ofEpochSecond(lastTrade.longValue()).atZone(zone)
                    : null;
            out.add(new OptionContract(
                    contractSymbol,
                    number(node.path("strike")),
                    number(node.path("lastPrice")),
                    number(node.path("bid")),
                    number(node.path("ask")),
                    number(node.path("change")),
                    number(node.path("percentChange")),
                    node.path("volume").asLong(0),
                    node.path("openInterest").asLong(0),
                    number(node.path("impliedVolatility")),
                    node.path("inTheMoney").asBoolean(false),
                    lastTradeDate,
                    text(node.path("contractSize")),
                    text(node.path("currency"))));
        }
        return out;
    }

    /** Exchange timezone of the underlying, UTC when missing or unknown (yfinance's default). */
    private static ZoneId zone(JsonNode quote) {
        String name = text(quote.path("exchangeTimezoneName"));
        if (name != null) {
            try {
                return ZoneId.of(name);
            } catch (DateTimeException e) {
                // fall through
            }
        }
        return ZoneOffset.UTC;
    }

    private static LocalDate date(long epochSeconds) {
        return LocalDate.ofInstant(Instant.ofEpochSecond(epochSeconds), ZoneOffset.UTC);
    }

    private static double number(JsonNode node) {
        return node.isNumber() ? node.doubleValue() : Double.NaN;
    }
}
