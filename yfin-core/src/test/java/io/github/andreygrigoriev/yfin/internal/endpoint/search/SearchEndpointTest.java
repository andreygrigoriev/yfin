package io.github.andreygrigoriev.yfin.internal.endpoint.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.andreygrigoriev.yfin.Search;
import io.github.andreygrigoriev.yfin.YFinance;
import io.github.andreygrigoriev.yfin.exception.YFDataException;
import io.github.andreygrigoriev.yfin.model.NewsItem;
import io.github.andreygrigoriev.yfin.model.SearchQuote;
import io.github.andreygrigoriev.yfin.model.SearchResult;
import io.github.andreygrigoriev.yfin.spi.Cache;
import io.github.andreygrigoriev.yfin.spi.HttpRequest;
import io.github.andreygrigoriev.yfin.testing.FakeTransport;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SearchEndpointTest {

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
    void quotesNewsAndResearch() {
        fake.onGet(SEARCH).respondFixture("search/apple.json");

        SearchResult r = yf.search(Search.of("apple").includeResearch(true).includeNavLinks(true));

        assertThat(r.query()).isEqualTo("apple");
        assertThat(r.quotes())
                .extracting(SearchQuote::symbol)
                .containsExactly("AAPL", "SAAPL=F", "XAAPL=F", "APLE", "AAPL.TO");
        SearchQuote aapl = r.quotes().getFirst();
        assertThat(aapl.shortName()).isEqualTo("Apple Inc.");
        assertThat(aapl.longName()).isEqualTo("Apple Inc.");
        assertThat(aapl.quoteType()).isEqualTo("EQUITY");
        assertThat(aapl.typeDisplay()).isEqualTo("Equity");
        assertThat(aapl.exchange()).isEqualTo("NMS");
        assertThat(aapl.exchangeDisplay()).isEqualTo("NASDAQ");
        assertThat(aapl.sector()).isEqualTo("Technology");
        assertThat(aapl.industry()).isEqualTo("Consumer Electronics");
        assertThat(aapl.score()).isEqualTo(176832.0);
        assertThat(aapl.raw()).containsEntry("isYahooFinance", true);
        SearchQuote future = r.quotes().get(2);
        assertThat(future.longName()).isNull();
        assertThat(future.sector()).isNull();

        assertThat(r.news()).hasSize(5);
        NewsItem first = r.news().getFirst();
        assertThat(first.id()).isEqualTo("35a540b0-3e49-3d82-8c58-498e22c4a3e9");
        assertThat(first.publisher()).isEqualTo("MT Newswires");
        assertThat(first.publishTime()).isEqualTo(Instant.ofEpochSecond(1791830013L));
        assertThat(r.news().get(1).thumbnailUrl())
                .isEqualTo("https://media.zenfs.com/en/thestreet_881/1b99aae7462f8d17bc1c93c5c8bc93af.jpg");

        assertThat(r.research()).hasSize(3);
        assertThat(r.research().getFirst())
                .containsEntry("provider", "Argus")
                .containsEntry("reportDate", 1791284068000L);
        assertThat(r.lists()).isEmpty();
        assertThat(r.nav()).isEmpty();
        assertThat(r.raw()).containsKeys("quotes", "news", "count");
        assertThat(r.isEmpty()).isFalse();
    }

    @Test
    void listsAndNavLinks() {
        fake.onGet(SEARCH).respondFixture("search/tech.json");

        SearchResult r = yf.search(Search.of("tech").maxResults(3).listsCount(3).includeNavLinks(true));

        assertThat(r.quotes()).extracting(SearchQuote::symbol).containsExactly("TECH", "MU", "SPCX");
        assertThat(r.lists()).hasSize(3);
        assertThat(r.lists().getFirst())
                .containsEntry("slug", "tech-stocks-that-move-the-market")
                .containsEntry("type", "ALGO_WATCHLIST")
                .containsEntry("symbolCount", 19L);
        assertThat(r.lists().get(2)).containsEntry("type", "PREDEFINED_SCREENER");
        assertThat(r.nav()).containsExactly(Map.of("navName", "Tech", "navUrl", "https://finance.yahoo.com/tech"));
    }

    @Test
    void defaultParametersMatchYfinance() {
        fake.onGet(SEARCH).respondFixture("search/empty.json");

        yf.search("  apple  ");

        HttpRequest request = searchRequest();
        assertThat(request.uri().getHost()).isEqualTo("query2.finance.yahoo.com");
        Map<String, List<String>> q = FakeTransport.parseQuery(request.uri().getRawQuery());
        assertThat(q)
                .containsEntry("q", List.of("apple"))
                .containsEntry("quotesCount", List.of("8"))
                .containsEntry("enableFuzzyQuery", List.of("false"))
                .containsEntry("newsCount", List.of("8"))
                .containsEntry("quotesQueryId", List.of("tss_match_phrase_query"))
                .containsEntry("newsQueryId", List.of("news_cie_vespa"))
                .containsEntry("listsCount", List.of("8"))
                .containsEntry("enableCb", List.of("true"))
                .containsEntry("enableNavLinks", List.of("false"))
                .containsEntry("enableResearchReports", List.of("false"))
                .containsEntry("enableCulturalAssets", List.of("false"))
                .containsEntry("recommendedCount", List.of("8"));
    }

    @Test
    void optionsBecomeParameters() {
        fake.onGet(SEARCH).respondFixture("search/aplpe-fuzzy.json");

        SearchResult r = yf.search(Search.of("aplpe")
                .maxResults(3)
                .newsCount(3)
                .listsCount(0)
                .recommended(2)
                .fuzzy(true)
                .includeCompanyBreakdown(false)
                .includeNavLinks(true)
                .includeResearch(true)
                .includeCulturalAssets(true));

        assertThat(r.quotes()).extracting(SearchQuote::symbol).containsExactly("AAPL", "SAAPL=F", "XAAPL=F");
        assertThat(r.news()).hasSize(2);
        Map<String, List<String>> q =
                FakeTransport.parseQuery(searchRequest().uri().getRawQuery());
        assertThat(q)
                .containsEntry("q", List.of("aplpe"))
                .containsEntry("quotesCount", List.of("3"))
                .containsEntry("enableFuzzyQuery", List.of("true"))
                .containsEntry("newsCount", List.of("3"))
                .containsEntry("listsCount", List.of("0"))
                .containsEntry("enableCb", List.of("false"))
                .containsEntry("enableNavLinks", List.of("true"))
                .containsEntry("enableResearchReports", List.of("true"))
                .containsEntry("enableCulturalAssets", List.of("true"))
                .containsEntry("recommendedCount", List.of("2"));
    }

    @Test
    void emptyResults() {
        fake.onGet(SEARCH).respondFixture("search/empty.json");

        SearchResult r = yf.search("zzzzqqqxnotreal");

        assertThat(r.isEmpty()).isTrue();
        assertThat(r.quotes()).isEmpty();
        assertThat(r.news()).isEmpty();
        assertThat(r.lists()).isEmpty();
        assertThat(r.research()).isEmpty();
        assertThat(r.nav()).isEmpty();
    }

    @Test
    void missingCategoriesAreEmpty() {
        fake.onGet(SEARCH).respond(200, "{\"count\":0}");

        assertThat(yf.search("apple").isEmpty()).isTrue();
    }

    @Test
    void errorPayloadThrows() {
        fake.onGet(SEARCH).respondFixture(400, "search/bad-request.json");

        assertThatThrownBy(() -> yf.search("apple"))
                .isInstanceOf(YFDataException.class)
                .hasMessageContaining("Bad Request")
                .hasMessageContaining("quotesCount");
    }

    @Test
    void outagePageThrows() {
        fake.onGet(SEARCH).respond(200, "<html>Will be right back</html>");

        assertThatThrownBy(() -> yf.search("apple"))
                .isInstanceOf(YFDataException.class)
                .hasMessageContaining("down");
    }

    @Test
    void nonObjectPayloadThrows() {
        fake.onGet(SEARCH).respond(200, "[]");

        assertThatThrownBy(() -> yf.search("apple")).isInstanceOf(YFDataException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t"})
    void blankQueryIsRejected(String query) {
        assertThatThrownBy(() -> yf.search(query)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Search.of(query)).isInstanceOf(IllegalArgumentException.class);
        assertThat(fake.requests()).isEmpty();
    }

    @Test
    void negativeCountsAreRejected() {
        Search s = Search.of("apple");
        assertThatThrownBy(() -> s.maxResults(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.newsCount(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.listsCount(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.recommended(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void optionsAreImmutableValues() {
        Search base = Search.of("apple");
        Search changed = base.maxResults(3);

        assertThat(base.maxResults()).isEqualTo(8);
        assertThat(changed.maxResults()).isEqualTo(3);
        assertThat(changed).isNotEqualTo(base).isEqualTo(Search.of(" apple ").maxResults(3));
        assertThat(changed.hashCode())
                .isEqualTo(Search.of("apple").maxResults(3).hashCode());
        assertThat(base.includeCompanyBreakdown()).isTrue();
        assertThat(base.fuzzy()).isFalse();
    }

    @Test
    void closedClientRejectsSearch() {
        yf.close();

        assertThatThrownBy(() -> yf.search("apple")).isInstanceOf(IllegalStateException.class);
    }

    private HttpRequest searchRequest() {
        return fake.requests().stream()
                .filter(r -> r.uri().getPath().equals(SEARCH))
                .findFirst()
                .orElseThrow();
    }
}
