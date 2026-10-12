package io.andreygrigoriev.yfin.internal.endpoint.options;

import com.fasterxml.jackson.databind.JsonNode;
import io.andreygrigoriev.yfin.exception.YFDataException;
import io.andreygrigoriev.yfin.internal.endpoint.Uris;
import io.andreygrigoriev.yfin.internal.http.YahooJson;
import io.andreygrigoriev.yfin.internal.http.YahooSession;
import io.andreygrigoriev.yfin.model.OptionChain;
import io.andreygrigoriev.yfin.spi.HttpRequest;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Yahoo {@code /v7/finance/options/{symbol}}: the expiration dates of a symbol and the chain of
 * one expiration (the nearest when no {@code date} is given). Like yfinance, a symbol without
 * options, including an unknown one, has no expirations.
 */
public final class OptionsEndpoint {

    private static final Logger LOG = LoggerFactory.getLogger(OptionsEndpoint.class);

    static final String BASE_URL = "https://query2.finance.yahoo.com/v7/finance/options/";

    private final YahooSession session;

    public OptionsEndpoint(YahooSession session) {
        this.session = Objects.requireNonNull(session, "session");
    }

    /** Expiration dates of {@code symbol}, nearest first; empty when it has no options. */
    public List<LocalDate> expirations(String symbol) {
        return fetch(symbol, null)
                .map(result -> List.copyOf(OptionsMapper.expirations(result).keySet()))
                .orElse(List.of());
    }

    /** The chain of the nearest expiration; empty when {@code symbol} has no options. */
    public Optional<OptionChain> nearest(String symbol) {
        return fetch(symbol, null).flatMap(result -> OptionsMapper.chain(symbol, result));
    }

    /**
     * The chain of {@code expiration}. One request when it is the nearest expiration, two otherwise.
     *
     * @throws IllegalArgumentException when {@code expiration} is not one of the symbol's
     *     expirations, listing the valid ones (yfinance's {@code ValueError})
     */
    public OptionChain chain(String symbol, LocalDate expiration) {
        Objects.requireNonNull(expiration, "expiration");
        Optional<JsonNode> first = fetch(symbol, null);
        Map<LocalDate, Long> expirations = first.map(OptionsMapper::expirations).orElse(Map.of());
        Long epoch = expirations.get(expiration);
        if (epoch == null) {
            throw new IllegalArgumentException("Expiration `" + expiration + "` cannot be found. "
                    + "Available expirations are: ["
                    + expirations.keySet().stream().map(LocalDate::toString).collect(Collectors.joining(", "))
                    + "]");
        }
        Optional<OptionChain> nearest = first.flatMap(result -> OptionsMapper.chain(symbol, result));
        if (nearest.isPresent() && nearest.get().expiration().equals(expiration)) {
            return nearest.get();
        }
        // Yahoo echoes the requested date as the chain's expiration; report the listed date
        return fetch(symbol, epoch)
                .flatMap(result -> OptionsMapper.chain(symbol, result))
                .map(c -> new OptionChain(symbol, expiration, c.calls(), c.puts(), c.underlying()))
                .orElseGet(() -> new OptionChain(symbol, expiration, List.of(), List.of(), Map.of()));
    }

    /**
     * The first result of the response, or empty when Yahoo returns none (unknown symbol).
     *
     * @param date expiration as Yahoo lists it (epoch seconds), or {@code null} for the nearest
     * @throws YFDataException for an error envelope or an unexpected payload
     */
    Optional<JsonNode> fetch(String symbol, @Nullable Long date) {
        Objects.requireNonNull(symbol, "symbol");
        URI uri = Uris.uri(BASE_URL + Uris.encode(symbol), date == null ? Map.of() : Map.of("date", date.toString()));
        LOG.debug("{}: GET {}", symbol, uri);
        return parse(YahooJson.read(session.execute(HttpRequest.get(uri)), symbol));
    }

    static Optional<JsonNode> parse(JsonNode root) {
        JsonNode results = root.path("optionChain").path("result");
        if (!results.isArray()) {
            throw new YFDataException("options response has no result list", root.toString());
        }
        JsonNode result = results.path(0);
        return result.isObject() ? Optional.of(result) : Optional.empty();
    }
}
