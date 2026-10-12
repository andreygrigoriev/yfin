package io.andreygrigoriev.yfin.internal.endpoint.news;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import io.andreygrigoriev.yfin.YFinance;
import io.andreygrigoriev.yfin.exception.YFDataException;
import io.andreygrigoriev.yfin.internal.http.YahooJson;
import io.andreygrigoriev.yfin.model.NewsItem;
import io.andreygrigoriev.yfin.request.NewsTab;
import io.andreygrigoriev.yfin.spi.Cache;
import io.andreygrigoriev.yfin.spi.HttpRequest;
import io.andreygrigoriev.yfin.testing.FakeTransport;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class NewsEndpointTest {

    private static final String NCP = "/xhr/ncp";
    private static final String SEARCH = "/v1/finance/search";

    private final FakeTransport fake = FakeTransport.create();
    private YFinance yf;

    @BeforeEach
    void setUp() {
        fake.onGet("").respond(404, "not found");
        fake.onGet("/v1/test/getcrumb").respond(200, "crumb1");
        yf = YFinance.builder()
                .transport(fake)
                .cache(Cache.none())
                .maxRetries(0)
                .build();
    }

    @AfterEach
    void tearDown() {
        yf.close();
    }

    @Test
    void streamArticlesWithoutAds() {
        fake.onPost(NCP).respondFixture("news/aapl-ncp-synthetic.json");

        List<NewsItem> news = yf.ticker("AAPL").news();

        assertThat(news).extracting(NewsItem::title).containsExactly("Apple unveils new chips", "Market wrap");
        NewsItem first = news.getFirst();
        assertThat(first.id()).isEqualTo("9f1c2a7e-0b4d-3c55-a1e2-6d7f8a9b0c11");
        assertThat(first.summary()).isEqualTo("Apple announced new in-house processors on Monday.");
        assertThat(first.publisher()).isEqualTo("Reuters");
        assertThat(first.link()).isEqualTo("https://finance.yahoo.com/news/apple-unveils-new-chips-143000123.html");
        assertThat(first.publishTime()).isEqualTo(Instant.parse("2026-10-12T14:30:00Z"));
        assertThat(first.contentType()).isEqualTo("STORY");
        assertThat(first.thumbnailUrl()).isEqualTo("https://media.example.com/original.jpg");
        assertThat(first.relatedTickers()).containsExactly("AAPL", "NVDA");
        assertThat(first.raw()).containsKey("content");
        NewsItem video = news.get(1);
        assertThat(video.link()).isEqualTo("https://finance.yahoo.com/video/market-wrap-120000456.html");
        assertThat(video.thumbnailUrl()).isNull();
        assertThat(video.summary()).isNull();
        assertThat(video.relatedTickers()).isEmpty();
    }

    @Test
    void requestCarriesTabCountAndSymbol() {
        fake.onPost(NCP).respondFixture("news/empty-ncp-synthetic.json");

        yf.ticker("AAPL").news(25, NewsTab.PRESS_RELEASES);

        HttpRequest request = fake.requests().stream()
                .filter(r -> r.uri().getPath().equals(NCP))
                .findFirst()
                .orElseThrow();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.uri().getHost()).isEqualTo("finance.yahoo.com");
        Map<String, List<String>> q = FakeTransport.parseQuery(request.uri().getRawQuery());
        assertThat(q)
                .containsEntry("queryRef", List.of("pressRelease"))
                .containsEntry("serviceKey", List.of("ncp_fin"))
                .containsEntry("crumb", List.of("crumb1"));
        JsonNode body = readJson(request.bodyAsString().orElseThrow());
        assertThat(body.toString()).isEqualTo("{\"serviceConfig\":{\"snippetCount\":25,\"s\":[\"AAPL\"]}}");
    }

    @Test
    void tabsMapToYahooQueryRefs() {
        assertThat(NewsTab.NEWS.queryRef()).isEqualTo("latestNews");
        assertThat(NewsTab.ALL.queryRef()).isEqualTo("newsAll");
        assertThat(NewsTab.PRESS_RELEASES.queryRef()).isEqualTo("pressRelease");
    }

    @Test
    void emptyStreamGivesEmptyList() {
        fake.onPost(NCP).respondFixture("news/empty-ncp-synthetic.json");

        assertThat(yf.ticker("AAPL").news()).isEmpty();
    }

    @Test
    void notFoundFallsBackToSearchNews() {
        fake.onPost(NCP).respondFixture(404, "news/not-found.json");
        fake.onGet(SEARCH).respondFixture("news/search-aapl.json");

        List<NewsItem> news = yf.ticker("AAPL").news(3, NewsTab.NEWS);

        assertThat(news).hasSize(3);
        NewsItem first = news.getFirst();
        assertThat(first.id()).isEqualTo("35a540b0-3e49-3d82-8c58-498e22c4a3e9");
        assertThat(first.title()).isEqualTo("Market Chatter: Apple's Tim Cook Meets Chinese Vice Premier He Lifeng");
        assertThat(first.publisher()).isEqualTo("MT Newswires");
        assertThat(first.publishTime()).isEqualTo(Instant.ofEpochSecond(1791830013L));
        assertThat(first.contentType()).isEqualTo("STORY");
        assertThat(first.thumbnailUrl()).isNull();
        assertThat(first.relatedTickers()).containsExactly("AAPL");
        assertThat(news.get(1).thumbnailUrl())
                .isEqualTo("https://media.zenfs.com/en/thestreet_881/1b99aae7462f8d17bc1c93c5c8bc93af.jpg");
        HttpRequest search = fake.requests().stream()
                .filter(r -> r.uri().getPath().equals(SEARCH))
                .findFirst()
                .orElseThrow();
        Map<String, List<String>> q = FakeTransport.parseQuery(search.uri().getRawQuery());
        assertThat(q)
                .containsEntry("q", List.of("AAPL"))
                .containsEntry("quotesCount", List.of("0"))
                .containsEntry("newsCount", List.of("3"));
    }

    @Test
    void fallbackWithoutNewsGivesEmptyList() {
        fake.onPost(NCP).respondFixture(404, "news/not-found.json");
        fake.onGet(SEARCH).respondFixture("news/search-unknown.json");

        assertThat(yf.ticker("ZZZZNOTREAL").news(10, NewsTab.ALL)).isEmpty();
    }

    @Test
    void pressReleasesHaveNoFallback() {
        fake.onPost(NCP).respondFixture(404, "news/not-found.json");

        assertThat(yf.ticker("AAPL").news(10, NewsTab.PRESS_RELEASES)).isEmpty();
        assertThat(fake.requests()).noneMatch(r -> r.uri().getPath().equals(SEARCH));
    }

    @Test
    void yahooDownThrows() {
        fake.onPost(NCP).respond(200, "<html><body>Will be right back...</body></html>");

        assertThatThrownBy(() -> yf.ticker("AAPL").news())
                .isInstanceOf(YFDataException.class)
                .hasMessageContaining("down");
    }

    @Test
    void invalidJsonThrows() {
        fake.onPost(NCP).respond(200, "<html>oops</html>");

        assertThatThrownBy(() -> yf.ticker("AAPL").news()).isInstanceOf(YFDataException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void nonPositiveCountRejectedWithoutRequest(int count) {
        assertThatThrownBy(() -> yf.ticker("AAPL").news(count, NewsTab.NEWS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(fake.requests()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(NewsTab.class)
    void everyTabReadsTheStream(NewsTab tab) {
        fake.onPost(NCP).respondFixture("news/aapl-ncp-synthetic.json");

        assertThat(yf.ticker("AAPL").news(5, tab)).hasSize(2);
    }

    @Test
    void streamNotAListThrows() {
        JsonNode root = readJson("{\"data\":{\"tickerStream\":{\"stream\":{}}}}");

        assertThatThrownBy(() -> NewsEndpoint.parseStream(root)).isInstanceOf(YFDataException.class);
    }

    private static JsonNode readJson(String json) {
        try {
            return YahooJson.mapper().readTree(json);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
