package io.andreygrigoriev.yfin.internal.endpoint.news;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.andreygrigoriev.yfin.exception.YFDataException;
import io.andreygrigoriev.yfin.internal.endpoint.Uris;
import io.andreygrigoriev.yfin.internal.http.YahooJson;
import io.andreygrigoriev.yfin.internal.http.YahooSession;
import io.andreygrigoriev.yfin.model.NewsItem;
import io.andreygrigoriev.yfin.request.NewsTab;
import io.andreygrigoriev.yfin.spi.HttpRequest;
import io.andreygrigoriev.yfin.spi.HttpResponse;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * News of one symbol. Primary source is the endpoint yfinance 1.7.0 uses, {@code POST
 * https://finance.yahoo.com/xhr/ncp?queryRef=…&serviceKey=ncp_fin}, whose {@code
 * data.tickerStream.stream} holds the articles (ads are dropped).
 *
 * <p>As of October 2026 that endpoint answers 404 (yfinance then returns no news). On a 404 the
 * endpoint falls back to the news of {@code /v1/finance/search}, which has no press-release
 * stream: {@link NewsTab#PRESS_RELEASES} then gives an empty list.
 */
public final class NewsEndpoint {

    private static final Logger LOG = LoggerFactory.getLogger(NewsEndpoint.class);

    static final String NCP_URL = "https://finance.yahoo.com/xhr/ncp";
    static final String SEARCH_URL = "https://query2.finance.yahoo.com/v1/finance/search";

    private final YahooSession session;

    public NewsEndpoint(YahooSession session) {
        this.session = Objects.requireNonNull(session, "session");
    }

    /**
     * Up to {@code count} articles about {@code symbol} from {@code tab}, as Yahoo orders them
     * (newest first); empty when Yahoo has none.
     *
     * @throws IllegalArgumentException when {@code count} is not positive
     * @throws YFDataException when Yahoo Finance is down or the payload is unexpected
     */
    public List<NewsItem> news(String symbol, int count, NewsTab tab) {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(tab, "tab");
        if (count < 1) {
            throw new IllegalArgumentException("count must be positive: " + count);
        }
        var params = new LinkedHashMap<String, String>();
        params.put("queryRef", tab.queryRef());
        params.put("serviceKey", "ncp_fin");
        HttpRequest request = HttpRequest.postJson(Uris.uri(NCP_URL, params), body(symbol, count));
        LOG.debug("{}: POST {}", symbol, request.uri());
        HttpResponse response = session.execute(request);
        if (response.status() == 404) {
            LOG.debug("{}: news endpoint answered 404, falling back to search", symbol);
            return tab == NewsTab.PRESS_RELEASES ? List.of() : searchNews(symbol, count);
        }
        YahooJson.checkNotDown(response);
        return parseStream(YahooJson.read(response, null));
    }

    private List<NewsItem> searchNews(String symbol, int count) {
        var params = new LinkedHashMap<String, String>();
        params.put("q", symbol);
        params.put("quotesCount", "0");
        params.put("newsCount", Integer.toString(count));
        params.put("enableFuzzyQuery", "false");
        HttpRequest request = HttpRequest.get(Uris.uri(SEARCH_URL, params));
        LOG.debug("{}: GET {}", symbol, request.uri());
        HttpResponse response = session.execute(request);
        YahooJson.checkNotDown(response);
        List<NewsItem> items = parseSearch(YahooJson.read(response, null));
        return items.size() > count ? items.subList(0, count) : items;
    }

    static String body(String symbol, int count) {
        ObjectNode body = YahooJson.mapper().createObjectNode();
        ObjectNode config = body.putObject("serviceConfig");
        config.put("snippetCount", count);
        config.putArray("s").add(symbol);
        return body.toString();
    }

    /** Articles of an {@code xhr/ncp} response, ads dropped. */
    static List<NewsItem> parseStream(JsonNode root) {
        JsonNode stream = root.path("data").path("tickerStream").path("stream");
        if (stream.isMissingNode() || stream.isNull()) {
            return List.of();
        }
        if (!stream.isArray()) {
            throw new YFDataException("news stream is not a list", root.toString());
        }
        var out = new ArrayList<NewsItem>(stream.size());
        for (JsonNode article : stream) {
            if (isAd(article)) {
                continue;
            }
            JsonNode content = article.path("content").isObject() ? article.path("content") : article;
            String id = firstText(content.path("id"), article.path("id"));
            String title = text(content.path("title"));
            if (id == null || title == null) {
                continue;
            }
            JsonNode tickers = content.path("finance").path("stockTickers");
            var related = new ArrayList<String>();
            for (JsonNode t : tickers) {
                String s = text(t.path("symbol"));
                if (s != null) {
                    related.add(s);
                }
            }
            out.add(new NewsItem(
                    id,
                    title,
                    text(content.path("summary")),
                    text(content.path("provider").path("displayName")),
                    firstText(
                            content.path("canonicalUrl").path("url"),
                            content.path("clickThroughUrl").path("url")),
                    instant(text(content.path("pubDate"))),
                    text(content.path("contentType")),
                    thumbnail(content.path("thumbnail")),
                    related,
                    plain(article)));
        }
        return List.copyOf(out);
    }

    /** Articles of a {@code /v1/finance/search} response. */
    public static List<NewsItem> parseSearch(JsonNode root) {
        JsonNode news = root.path("news");
        if (news.isMissingNode() || news.isNull()) {
            return List.of();
        }
        if (!news.isArray()) {
            throw new YFDataException("search news is not a list", root.toString());
        }
        var out = new ArrayList<NewsItem>(news.size());
        for (JsonNode article : news) {
            String id = text(article.path("uuid"));
            String title = text(article.path("title"));
            if (id == null || title == null) {
                continue;
            }
            JsonNode time = article.path("providerPublishTime");
            var related = new ArrayList<String>();
            for (JsonNode t : article.path("relatedTickers")) {
                String s = text(t);
                if (s != null) {
                    related.add(s);
                }
            }
            out.add(new NewsItem(
                    id,
                    title,
                    null,
                    text(article.path("publisher")),
                    text(article.path("link")),
                    time.isNumber() ? Instant.ofEpochSecond(time.longValue()) : null,
                    text(article.path("type")),
                    thumbnail(article.path("thumbnail")),
                    related,
                    plain(article)));
        }
        return List.copyOf(out);
    }

    private static boolean isAd(JsonNode article) {
        JsonNode ad = article.path("ad");
        if (ad.isMissingNode() || ad.isNull()) {
            return false;
        }
        if (ad.isBoolean()) {
            return ad.booleanValue();
        }
        if (ad.isContainerNode()) {
            return !ad.isEmpty();
        }
        return !ad.asText().isEmpty();
    }

    /** The original-size thumbnail: {@code originalUrl}, else the {@code original} resolution, else the first. */
    private static @Nullable String thumbnail(JsonNode thumbnail) {
        String original = text(thumbnail.path("originalUrl"));
        if (original != null) {
            return original;
        }
        String first = null;
        for (JsonNode resolution : thumbnail.path("resolutions")) {
            String url = text(resolution.path("url"));
            if (url == null) {
                continue;
            }
            if ("original".equals(text(resolution.path("tag")))) {
                return url;
            }
            if (first == null) {
                first = url;
            }
        }
        return first;
    }

    private static @Nullable Instant instant(@Nullable String text) {
        if (text == null) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeException e) {
            LOG.debug("ignoring unparsable news time {}", text);
            return null;
        }
    }

    private static @Nullable String firstText(JsonNode a, JsonNode b) {
        String first = text(a);
        return first != null ? first : text(b);
    }

    private static @Nullable String text(JsonNode node) {
        return node.isTextual() && !node.asText().isEmpty() ? node.asText() : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> plain(JsonNode node) {
        Object value = YahooJson.toPlain(node);
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }
}
