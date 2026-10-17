package io.github.andreygrigoriev.yfin.internal.endpoint.domain;

import static io.github.andreygrigoriev.yfin.internal.http.YahooJson.plainMap;
import static io.github.andreygrigoriev.yfin.internal.http.YahooJson.text;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.andreygrigoriev.yfin.exception.YFDataException;
import io.github.andreygrigoriev.yfin.internal.endpoint.Uris;
import io.github.andreygrigoriev.yfin.internal.http.YahooJson;
import io.github.andreygrigoriev.yfin.internal.http.YahooSession;
import io.github.andreygrigoriev.yfin.model.MarketStatus;
import io.github.andreygrigoriev.yfin.model.MarketSummaryItem;
import io.github.andreygrigoriev.yfin.request.MarketRegion;
import io.github.andreygrigoriev.yfin.spi.HttpRequest;
import io.github.andreygrigoriev.yfin.spi.HttpResponse;
import java.net.URI;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Yahoo's market summary ({@code GET https://query1.finance.yahoo.com/v6/finance/quote/marketSummary})
 * and market time ({@code …/v6/finance/markettime}) endpoints, with yfinance 1.7.0's parameters.
 */
public final class MarketEndpoint {

    private static final Logger LOG = LoggerFactory.getLogger(MarketEndpoint.class);

    static final String SUMMARY_URL = "https://query1.finance.yahoo.com/v6/finance/quote/marketSummary";
    static final String TIME_URL = "https://query1.finance.yahoo.com/v6/finance/markettime";
    static final String SUMMARY_FIELDS = "shortName,regularMarketPrice,regularMarketChange,regularMarketChangePercent";

    private final YahooSession session;

    public MarketEndpoint(YahooSession session) {
        this.session = Objects.requireNonNull(session, "session");
    }

    /**
     * Headline instruments of {@code region}, in Yahoo's order.
     *
     * @throws YFDataException when Yahoo is down, reports an error or the payload is unexpected
     */
    public List<MarketSummaryItem> summary(MarketRegion region) {
        var params = new LinkedHashMap<String, String>();
        params.put("fields", SUMMARY_FIELDS);
        params.put("formatted", "false");
        params.put("lang", "en-US");
        params.put("market", region.name());
        return parseSummary(get(Uris.uri(SUMMARY_URL, params), region));
    }

    /**
     * Trading hours of {@code region}; empty when Yahoo has none for it. Yahoo's market time
     * endpoint ignores the region and always answers for the U.S. market, so in practice only
     * {@link MarketRegion#US} has a status (as in yfinance, which returns {@code None} otherwise).
     *
     * @throws YFDataException when Yahoo is down, reports an error or the payload is unexpected
     */
    public Optional<MarketStatus> status(MarketRegion region) {
        var params = new LinkedHashMap<String, String>();
        params.put("formatted", "true");
        params.put("key", "finance");
        params.put("lang", "en-US");
        params.put("market", region.name());
        return parseStatus(get(Uris.uri(TIME_URL, params), region), region);
    }

    private JsonNode get(URI uri, MarketRegion region) {
        LOG.debug("market {}: GET {}", region, uri);
        HttpResponse response = session.execute(HttpRequest.get(uri));
        YahooJson.checkNotDown(response);
        return YahooJson.read(response, null);
    }

    static List<MarketSummaryItem> parseSummary(JsonNode root) {
        JsonNode result = root.path("marketSummaryResponse").path("result");
        if (!result.isArray()) {
            throw new YFDataException("market summary response has no result", root.toString());
        }
        var out = new ArrayList<MarketSummaryItem>(result.size());
        for (JsonNode q : result) {
            String symbol = text(q.path("symbol"));
            if (symbol == null) {
                continue;
            }
            Long time = DomainJson.integral(q.path("regularMarketTime"));
            out.add(new MarketSummaryItem(
                    symbol,
                    text(q.path("exchange")),
                    text(q.path("shortName")),
                    text(q.path("quoteType")),
                    DomainJson.number(q.path("regularMarketPrice")),
                    DomainJson.number(q.path("regularMarketChange")),
                    DomainJson.number(q.path("regularMarketChangePercent")),
                    DomainJson.number(q.path("regularMarketPreviousClose")),
                    time != null ? Instant.ofEpochSecond(time) : null,
                    text(q.path("marketState")),
                    plainMap(q)));
        }
        return out;
    }

    static Optional<MarketStatus> parseStatus(JsonNode root, MarketRegion region) {
        JsonNode time = root.path("finance")
                .path("marketTimes")
                .path(0)
                .path("marketTime")
                .path(0);
        if (!time.isObject()) {
            throw new YFDataException("market time response has no market time", root.toString());
        }
        String id = text(time.path("id"));
        if (region != MarketRegion.US && "us".equals(id)) {
            LOG.warn("{}: Yahoo's markettime endpoint does not support market {}; no status", region, region);
            return Optional.empty();
        }
        JsonNode tz = time.path("timezone").path(0);
        ZoneId zone = zone(tz, root);
        Map<String, Object> raw = new LinkedHashMap<>(plainMap(time));
        raw.remove("time"); // redundant, as yfinance
        return Optional.of(new MarketStatus(
                id,
                text(time.path("name")),
                text(time.path("status")),
                text(time.path("message")),
                instant(time.path("open"), root).atZone(zone),
                instant(time.path("close"), root).atZone(zone),
                zone,
                text(tz.path("short")),
                raw));
    }

    private static ZoneId zone(JsonNode tz, JsonNode root) {
        String name = text(tz.path("$text"));
        if (name != null) {
            try {
                return ZoneId.of(name);
            } catch (DateTimeException e) {
                LOG.debug("unknown market time zone {}, using the GMT offset", name);
            }
        }
        String offset = text(tz.path("gmtoffset"));
        if (offset != null) {
            try {
                return ZoneOffset.ofTotalSeconds(Integer.parseInt(offset.trim()));
            } catch (NumberFormatException | DateTimeException e) {
                throw new YFDataException("invalid market GMT offset: " + offset, root.toString(), e);
            }
        }
        throw new YFDataException("market time has no time zone", root.toString());
    }

    private static Instant instant(JsonNode node, JsonNode root) {
        @Nullable String text = text(node);
        if (text == null) {
            throw new YFDataException("market time has no open/close", root.toString());
        }
        try {
            return Instant.parse(text.toUpperCase(Locale.ROOT));
        } catch (DateTimeException e) {
            throw new YFDataException("invalid market time: " + text, root.toString(), e);
        }
    }
}
