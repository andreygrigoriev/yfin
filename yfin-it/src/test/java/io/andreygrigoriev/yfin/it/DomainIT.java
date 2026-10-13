package io.andreygrigoriev.yfin.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.andreygrigoriev.yfin.Industry;
import io.andreygrigoriev.yfin.Market;
import io.andreygrigoriev.yfin.Sector;
import io.andreygrigoriev.yfin.YFinance;
import io.andreygrigoriev.yfin.model.MarketStatus;
import io.andreygrigoriev.yfin.request.MarketRegion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Sector, industry and market data. */
class DomainIT {

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
    void sector() {
        Sector s = yf.sector("technology");

        assertThat(s.name()).isEqualTo("Technology");
        assertThat(s.overview().companiesCount()).isPositive();
        assertThat(s.topCompanies()).isNotEmpty();
        assertThat(s.industries()).anySatisfy(i -> assertThat(i.key()).isEqualTo("semiconductors"));
    }

    @Test
    void industry() {
        Industry i = yf.industry("semiconductors");

        assertThat(i.sectorKey()).isEqualTo("technology");
        assertThat(i.topCompanies()).isNotEmpty();
        // Yahoo often has no performing companies; when it has, each has a symbol
        assertThat(i.topPerformingCompanies())
                .allSatisfy(c -> assertThat(c.symbol()).isNotBlank());
    }

    @Test
    void market() {
        Market m = yf.market(MarketRegion.US);

        assertThat(m.summary())
                .isNotEmpty()
                .allSatisfy(item -> assertThat(item.symbol()).isNotBlank());
        MarketStatus status = m.status().orElseThrow();
        assertThat(status.close()).isAfter(status.open());
    }
}
