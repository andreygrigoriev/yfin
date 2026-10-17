package io.github.andreygrigoriev.yfin.internal.endpoint.visualization;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.andreygrigoriev.yfin.exception.YFDataException;
import io.github.andreygrigoriev.yfin.internal.endpoint.Uris;
import io.github.andreygrigoriev.yfin.internal.http.YahooJson;
import io.github.andreygrigoriev.yfin.internal.http.YahooSession;
import io.github.andreygrigoriev.yfin.spi.HttpRequest;
import java.util.LinkedHashMap;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Yahoo {@code POST /v1/finance/visualization}: one request with a JSON body naming the entity type,
 * the columns and an operator-tree query, answered with a table.
 */
public final class VisualizationEndpoint {

    private static final Logger LOG = LoggerFactory.getLogger(VisualizationEndpoint.class);

    static final String URL = "https://query1.finance.yahoo.com/v1/finance/visualization";

    private final YahooSession session;

    public VisualizationEndpoint(YahooSession session) {
        this.session = Objects.requireNonNull(session, "session");
    }

    /**
     * Posts {@code body} and returns the first document of the response.
     *
     * @param symbol the symbol the request is about, or {@code null} for a market-wide request
     * @throws YFDataException for an error envelope or an unexpected payload
     */
    public VisualizationTable post(ObjectNode body, @Nullable String symbol) {
        Objects.requireNonNull(body, "body");
        var params = new LinkedHashMap<String, String>();
        params.put("lang", "en-US");
        params.put("region", "US");
        HttpRequest request = HttpRequest.postJson(Uris.uri(URL, params), body.toString());
        LOG.debug("{}: POST {}", symbol == null ? "visualization" : symbol, request.uri());
        return VisualizationTable.of(YahooJson.read(session.execute(request), symbol));
    }
}
