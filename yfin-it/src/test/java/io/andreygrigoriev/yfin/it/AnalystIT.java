package io.andreygrigoriev.yfin.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.andreygrigoriev.yfin.Ticker;
import io.andreygrigoriev.yfin.YFinance;
import io.andreygrigoriev.yfin.model.PriceTargets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class AnalystIT {

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
    void recommendationsAndTargets() {
        Ticker t = yf.ticker("MSFT");

        assertThat(t.recommendations()).isNotEmpty();
        assertThat(t.upgradesDowngrades()).isNotEmpty();
        PriceTargets targets = t.analystPriceTargets().orElseThrow();
        assertThat(targets.high()).isGreaterThanOrEqualTo(targets.low());
        assertThat(targets.mean()).isPositive();
    }

    @Test
    void estimates() {
        Ticker t = yf.ticker("MSFT");

        assertThat(t.earningsEstimate())
                .isNotEmpty()
                .anySatisfy(e -> assertThat(e.avg()).isNotNull());
        assertThat(t.revenueEstimate()).isNotEmpty();
        assertThat(t.earningsHistory()).isNotEmpty();
        assertThat(t.epsTrend()).isNotEmpty();
        assertThat(t.epsRevisions()).isNotEmpty();
        assertThat(t.growthEstimates()).isNotEmpty();
    }

    @Test
    void calendarAndEarningsDates() {
        Ticker t = yf.ticker("AAPL");

        assertThat(t.calendar())
                .hasValueSatisfying(c -> assertThat(c.earningsDates()).isNotEmpty());
        assertThat(t.earningsDates(12)).isNotEmpty().hasSizeLessThanOrEqualTo(12);
    }

    @Test
    void sustainabilityIsOptional() {
        // Yahoo drops ESG data for many symbols: the call must succeed, the data may be absent
        assertThatCode(() -> yf.ticker("AAPL").sustainability()).doesNotThrowAnyException();
    }
}
