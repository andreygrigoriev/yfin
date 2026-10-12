package io.andreygrigoriev.yfin.internal.endpoint.search;

import com.fasterxml.jackson.databind.JsonNode;
import io.andreygrigoriev.yfin.Search;
import io.andreygrigoriev.yfin.exception.YFDataException;
import io.andreygrigoriev.yfin.internal.endpoint.Uris;
import io.andreygrigoriev.yfin.internal.endpoint.news.NewsEndpoint;
import io.andreygrigoriev.yfin.internal.http.YahooJson;
import io.andreygrigoriev.yfin.internal.http.YahooSession;
import io.andreygrigoriev.yfin.model.SearchQuote;
import io.andreygrigoriev.yfin.model.SearchResult;
import io.andreygrigoriev.yfin.spi.HttpRequest;
import io.andreygrigoriev.yfin.spi.HttpResponse;
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

    static final String URL = "https://query2.finance.yahoo.com/v1/finance/search";

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
                Plain.maps(root.path("lists")),
                Plain.maps(root.path("researchReports")),
                Plain.maps(root.path("nav")),
                Plain.map(root));
    }

    /** Quotes with a symbol, as yfinance filters them (drops e.g. Crunchbase companies). */
    private static List<SearchQuote> quotes(JsonNode quotes) {
        var out = new ArrayList<SearchQuote>(quotes.size());
        for (JsonNode q : quotes) {
            String symbol = Plain.text(q.path("symbol"));
            if (symbol == null) {
                continue;
            }
            out.add(new SearchQuote(
                    symbol,
                    Plain.text(q.path("shortname")),
                    Plain.text(q.path("longname")),
                    Plain.text(q.path("quoteType")),
                    Plain.text(q.path("typeDisp")),
                    Plain.text(q.path("exchange")),
                    Plain.text(q.path("exchDisp")),
                    Plain.text(q.path("sector")),
                    Plain.text(q.path("industry")),
                    Plain.number(q.path("score")),
                    Plain.map(q)));
        }
        return out;
    }
}
