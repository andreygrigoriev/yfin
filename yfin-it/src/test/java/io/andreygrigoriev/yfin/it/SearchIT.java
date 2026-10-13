package io.andreygrigoriev.yfin.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.andreygrigoriev.yfin.Lookup;
import io.andreygrigoriev.yfin.Search;
import io.andreygrigoriev.yfin.YFinance;
import io.andreygrigoriev.yfin.model.LookupResult;
import io.andreygrigoriev.yfin.model.SearchResult;
import io.andreygrigoriev.yfin.request.LookupType;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SearchIT {

    private static YFinance yf;

    @BeforeAll
    static void setUp() {
        yf = LiveClients.create();
    }

    @AfterAll
    static void tearDown() {
        yf.close();
    }

    @Test
    void searchFindsQuotesAndNews() {
        SearchResult r = yf.search(Search.of("apple").maxResults(5).newsCount(3));

        assertThat(r.quotes()).isNotEmpty().hasSizeLessThanOrEqualTo(5);
        assertThat(r.quotes()).anySatisfy(q -> assertThat(q.symbol()).isEqualTo("AAPL"));
        assertThat(r.news()).hasSizeLessThanOrEqualTo(3);
    }

    @Test
    void searchWithNoHits() {
        assertThat(yf.search("zzqqxxjjvvkk").quotes()).isEmpty();
    }

    @Test
    void lookupByType() {
        List<LookupResult> etfs =
                yf.lookup(Lookup.of("gold").type(LookupType.ETF).count(10));

        assertThat(etfs).isNotEmpty().hasSizeLessThanOrEqualTo(10);
        assertThat(etfs).allSatisfy(r -> assertThat(r.symbol()).isNotBlank());
    }
}
