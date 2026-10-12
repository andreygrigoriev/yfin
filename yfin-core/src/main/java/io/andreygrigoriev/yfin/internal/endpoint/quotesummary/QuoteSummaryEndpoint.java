package io.andreygrigoriev.yfin.internal.endpoint.quotesummary;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.andreygrigoriev.yfin.exception.YFDataException;
import io.andreygrigoriev.yfin.internal.endpoint.Uris;
import io.andreygrigoriev.yfin.internal.http.YahooJson;
import io.andreygrigoriev.yfin.internal.http.YahooSession;
import io.andreygrigoriev.yfin.spi.HttpRequest;
import io.andreygrigoriev.yfin.spi.HttpResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Yahoo {@code /v10/finance/quoteSummary/{symbol}}: an arbitrary set of modules in one request.
 * Requested with {@code formatted=false}; any {@code {raw, fmt}} value objects Yahoo still sends
 * are unwrapped to their raw value.
 */
public final class QuoteSummaryEndpoint {

    private static final Logger LOG = LoggerFactory.getLogger(QuoteSummaryEndpoint.class);

    static final String BASE_URL = "https://query2.finance.yahoo.com/v10/finance/quoteSummary/";

    private static final String NO_FUNDAMENTALS = "No fundamentals data found";

    private final YahooSession session;

    public QuoteSummaryEndpoint(YahooSession session) {
        this.session = Objects.requireNonNull(session, "session");
    }

    /**
     * Fetches {@code modules} for {@code symbol}.
     *
     * @return the modules Yahoo returned, in response order, values unwrapped; modules Yahoo has no data for are
     *     absent (never an exception); empty when Yahoo has no fundamentals data for the symbol at all
     * @throws IllegalArgumentException when {@code modules} is empty
     * @throws io.andreygrigoriev.yfin.exception.YFSymbolNotFoundException when Yahoo does not know
     *     the symbol
     * @throws YFDataException for other error envelopes or an unexpected payload
     */
    public Map<QuoteSummaryModule, JsonNode> fetch(String symbol, Collection<QuoteSummaryModule> modules) {
        Objects.requireNonNull(symbol, "symbol");
        if (modules.isEmpty()) {
            throw new IllegalArgumentException("no modules requested");
        }
        Set<QuoteSummaryModule> requested = EnumSet.copyOf(modules);
        URI uri = Uris.uri(BASE_URL + Uris.encode(symbol), params(symbol, requested));
        LOG.debug("{}: GET {}", symbol, uri);
        HttpResponse response = session.execute(HttpRequest.get(uri));
        if (isNoFundamentals(response)) {
            LOG.debug("{}: Yahoo has no fundamentals data", symbol);
            return Map.of();
        }
        JsonNode root = YahooJson.read(response, symbol);
        return parse(root, requested);
    }

    /**
     * Whether Yahoo answered {@code Not Found} with "No fundamentals data found": the symbol exists
     * (an ETF, index or crypto pair) but none of the requested modules applies to it. An unknown
     * symbol is reported as "Quote not found" instead.
     */
    static boolean isNoFundamentals(HttpResponse response) {
        if (response.status() != 404) {
            return false;
        }
        try {
            JsonNode root = YahooJson.mapper().readTree(response.body());
            return root != null
                    && root.path("quoteSummary")
                            .path("error")
                            .path("description")
                            .asText("")
                            .startsWith(NO_FUNDAMENTALS);
        } catch (IOException e) {
            return false;
        }
    }

    static Map<String, String> params(String symbol, Set<QuoteSummaryModule> modules) {
        var params = new LinkedHashMap<String, String>();
        params.put(
                "modules", modules.stream().map(QuoteSummaryModule::yahooName).collect(Collectors.joining(",")));
        params.put("corsDomain", "finance.yahoo.com");
        params.put("formatted", "false");
        params.put("symbol", symbol);
        params.put("lang", "en-US");
        params.put("region", "US");
        return params;
    }

    /** Picks the requested modules out of the first result, in response order, unwrapping their values. */
    static Map<QuoteSummaryModule, JsonNode> parse(JsonNode root, Set<QuoteSummaryModule> requested) {
        JsonNode envelope = root.path("quoteSummary");
        if (!envelope.isObject()) {
            throw new YFDataException("quoteSummary response has no quoteSummary object", root.toString());
        }
        JsonNode results = envelope.path("result");
        var modules = new LinkedHashMap<QuoteSummaryModule, JsonNode>();
        if (results.isNull() || results.isMissingNode() || results.isEmpty()) {
            return Collections.unmodifiableMap(modules);
        }
        if (!results.isArray() || !results.get(0).isObject()) {
            throw new YFDataException("quoteSummary result is not a list of objects", root.toString());
        }
        for (Map.Entry<String, JsonNode> e : results.get(0).properties()) {
            QuoteSummaryModule.fromYahooName(e.getKey())
                    .filter(requested::contains)
                    .filter(m -> e.getValue().isObject())
                    .ifPresent(m -> modules.put(m, unwrap(e.getValue())));
        }
        return Collections.unmodifiableMap(modules);
    }

    /**
     * Returns a copy of {@code node} with Yahoo value objects replaced: {@code {raw, fmt[,
     * longFmt]}} becomes {@code raw}, an empty object member becomes {@code null}, and non-breaking
     * spaces in strings become plain spaces.
     */
    public static JsonNode unwrap(JsonNode node) {
        if (node.isObject()) {
            if (isValueObject(node)) {
                return unwrap(node.get("raw"));
            }
            ObjectNode copy = JsonNodeFactory.instance.objectNode();
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                JsonNode v = e.getValue();
                copy.set(e.getKey(), v.isObject() && v.isEmpty() ? NullNode.getInstance() : unwrap(v));
            }
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = JsonNodeFactory.instance.arrayNode(node.size());
            node.forEach(element -> copy.add(unwrap(element)));
            return copy;
        }
        if (node.isTextual() && node.textValue().indexOf(' ') >= 0) {
            return TextNode.valueOf(node.textValue().replace(' ', ' '));
        }
        return node;
    }

    private static boolean isValueObject(JsonNode node) {
        return node.has("raw") && (node.has("fmt") || node.has("longFmt"));
    }
}
