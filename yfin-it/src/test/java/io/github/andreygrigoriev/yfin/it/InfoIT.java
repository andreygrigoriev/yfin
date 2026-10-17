package io.github.andreygrigoriev.yfin.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.andreygrigoriev.yfin.FastInfo;
import io.github.andreygrigoriev.yfin.YFinance;
import io.github.andreygrigoriev.yfin.model.FundsData;
import io.github.andreygrigoriev.yfin.model.SecFiling;
import io.github.andreygrigoriev.yfin.model.TickerInfo;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class InfoIT {

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
    void equityInfo() {
        TickerInfo info = yf.ticker("AAPL").info();

        assertThat(info.quoteType()).isEqualTo("EQUITY");
        assertThat(info.longName()).containsIgnoringCase("apple");
        assertThat(info.currency()).isEqualTo("USD");
        assertThat(info.sector()).isNotBlank();
        assertThat(info.marketCap()).isGreaterThan(100_000_000_000L);
        assertThat(info.regularMarketPrice()).isPositive();
        assertThat(info.fiftyTwoWeekHigh()).isGreaterThanOrEqualTo(info.fiftyTwoWeekLow());
        assertThat(info.raw()).hasSizeGreaterThan(50);
        assertThat(info.trailingPegRatio()).isPositive();
    }

    @Test
    void fundsData() {
        FundsData spy = yf.ticker("SPY").fundsData().orElseThrow();

        assertThat(spy.quoteType()).isEqualTo("ETF");
        assertThat(spy.topHoldings()).isNotEmpty();
        assertThat(spy.sectorWeightings()).isNotEmpty();
        assertThat(yf.ticker("AAPL").fundsData()).isEmpty();
    }

    @Test
    void secFilings() {
        List<SecFiling> filings = yf.ticker("AAPL").secFilings();

        assertThat(filings).isNotEmpty().allSatisfy(f -> assertThat(f.date()).isNotNull());
        assertThat(filings).anySatisfy(f -> assertThat(f.type()).isEqualTo("10-K"));
    }

    @Test
    void etfInfo() {
        TickerInfo info = yf.ticker("SPY").info();

        assertThat(info.quoteType()).isEqualTo("ETF");
        assertThat(info.totalAssets()).isPositive();
    }

    @Test
    void fastInfo() {
        FastInfo fi = yf.ticker("MSFT").fastInfo();

        assertThat(fi.currency()).isEqualTo("USD");
        assertThat(fi.timezone()).isEqualTo(ZoneId.of("America/New_York"));
        assertThat(fi.lastPrice()).isPositive();
        assertThat(fi.marketCap()).isPositive();
        assertThat(fi.yearHigh()).isGreaterThanOrEqualTo(fi.yearLow());
    }

    @Test
    void indexInfo() {
        TickerInfo info = yf.ticker("^GSPC").info();

        assertThat(info.quoteType()).isEqualTo("INDEX");
        assertThat(info.regularMarketPrice()).isGreaterThan(1000);
    }
}
