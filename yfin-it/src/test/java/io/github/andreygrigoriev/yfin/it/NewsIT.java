package io.github.andreygrigoriev.yfin.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.andreygrigoriev.yfin.YFinance;
import io.github.andreygrigoriev.yfin.model.NewsItem;
import io.github.andreygrigoriev.yfin.request.NewsTab;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class NewsIT {

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
    void tickerNews() {
        List<NewsItem> news = yf.ticker("AAPL").news();

        assertThat(news).isNotEmpty().allSatisfy(n -> {
            assertThat(n.id()).isNotBlank();
            assertThat(n.title()).isNotBlank();
        });
    }

    @Test
    void pressReleases() {
        assertThat(yf.ticker("MSFT").news(5, NewsTab.PRESS_RELEASES)).hasSizeLessThanOrEqualTo(5);
    }

    @Test
    void isin() {
        assertThat(yf.ticker("AAPL").isin()).contains("US0378331005");
    }
}
