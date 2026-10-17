package io.github.andreygrigoriev.yfin.internal.endpoint.search;

import static io.github.andreygrigoriev.yfin.internal.http.YahooJson.plainMap;
import static io.github.andreygrigoriev.yfin.internal.http.YahooJson.plainMaps;
import static io.github.andreygrigoriev.yfin.internal.http.YahooJson.text;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.andreygrigoriev.yfin.Search;
import io.github.andreygrigoriev.yfin.exception.YFDataException;
import io.github.andreygrigoriev.yfin.internal.endpoint.Uris;
import io.github.andreygrigoriev.yfin.internal.endpoint.news.NewsEndpoint;
import io.github.andreygrigoriev.yfin.internal.http.YahooJson;
import io.github.andreygrigoriev.yfin.internal.http.YahooSession;
import io.github.andreygrigoriev.yfin.model.SearchQuote;
import io.github.andreygrigoriev.yfin.model.SearchResult;
import io.github.andreygrigoriev.yfin.spi.HttpRequest;
import io.github.andreygrigoriev.yfin.spi.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code GET https://query2.finance.yahoo.com/v1/finance/search} with yfinance 1.7.0's
 * parameters.
 */
public final class SearchEndpoint {

    private static final Logger LOG = LoggerFactory.getLogger(SearchEndpoint.class);

    public static final String URL = "https://query2.finance.yahoo.com/v1/finance/search";

    private final YahooSession session;

    public SearchEndpoint(YahooSession session) {
        this.session = Objects.requireNonNull(session, "session");
    }

    /**
     * Runs {@code search}.
     *
     * @throws YFDataException when Yahoo Finance is down, reports an error or the payload is
     *     unexpected
     */
    public SearchResult search(Search search) {
        Objects.requireNonNull(search, "search");
        var params = new LinkedHashMap<String, String>();
        params.put("q", search.query());
        params.put("quotesCount", Integer.toString(search.maxResults()));
        params.put("enableFuzzyQuery", Boolean.toString(search.fuzzy()));
        params.put("newsCount", Integer.toString(search.newsCount()));
        params.put("quotesQueryId", "tss_match_phrase_query");
        params.put("newsQueryId", "news_cie_vespa");
        params.put("listsCount", Integer.toString(search.listsCount()));
        params.put("enableCb", Boolean.toString(search.includeCompanyBreakdown()));
        params.put("enableNavLinks", Boolean.toString(search.includeNavLinks()));
        params.put("enableResearchReports", Boolean.toString(search.includeResearch()));
        params.put("enableCulturalAssets", Boolean.toString(search.includeCulturalAssets()));
        params.put("recommendedCount", Integer.toString(search.recommended()));
        HttpRequest request = HttpRequest.get(Uris.uri(URL, params));
        LOG.debug("{}: GET {}", search.query(), request.uri());
        HttpResponse response = session.execute(request);
        YahooJson.checkNotDown(response);
        return parse(search.query(), YahooJson.read(response, null));
    }

    static SearchResult parse(String query, JsonNode root) {
        if (!root.isObject()) {
            throw new YFDataException("search response is not an object", root.toString());
        }
        return new SearchResult(
                query,
                quotes(root.path("quotes")),
                NewsEndpoint.parseSearch(root),
                plainMaps(root.path("lists")),
                plainMaps(root.path("researchReports")),
                plainMaps(root.path("nav")),
                plainMap(root));
    }

    /** Quotes with a symbol, as yfinance filters them (drops e.g. Crunchbase companies). */
    private static List<SearchQuote> quotes(JsonNode quotes) {
        var out = new ArrayList<SearchQuote>(quotes.size());
        for (JsonNode q : quotes) {
            String symbol = text(q.path("symbol"));
            if (symbol == null) {
                continue;
            }
            out.add(new SearchQuote(
                    symbol,
                    text(q.path("shortname")),
                    text(q.path("longname")),
                    text(q.path("quoteType")),
                    text(q.path("typeDisp")),
                    text(q.path("exchange")),
                    text(q.path("exchDisp")),
                    text(q.path("sector")),
                    text(q.path("industry")),
                    YahooJson.number(q.path("score")),
                    plainMap(q)));
        }
        return out;
    }
}
