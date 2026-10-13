package io.andreygrigoriev.yfin.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.andreygrigoriev.yfin.Screener;
import io.andreygrigoriev.yfin.YFinance;
import io.andreygrigoriev.yfin.model.ScreenerResult;
import io.andreygrigoriev.yfin.request.EquityQuery;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ScreenerIT {

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
    void predefinedScreener() {
        ScreenerResult r = yf.screen("most_actives");

        assertThat(r.quotes()).isNotEmpty();
        assertThat(r.quotes()).allSatisfy(q -> assertThat(q).containsKey("symbol"));
    }

    @Test
    void customEquityQuery() {
        ScreenerResult r = yf.screen(Screener.of(EquityQuery.and(
                        EquityQuery.eq("region", "us"), EquityQuery.gt("intradaymarketcap", 100_000_000_000L)))
                .size(10));

        assertThat(r.quotes()).isNotEmpty().hasSizeLessThanOrEqualTo(10);
        assertThat(r.total()).isPositive();
    }
}
