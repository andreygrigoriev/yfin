package io.github.andreygrigoriev.yfin.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.andreygrigoriev.yfin.YFinance;
import io.github.andreygrigoriev.yfin.exception.YFSymbolNotFoundException;
import io.github.andreygrigoriev.yfin.model.Bar;
import io.github.andreygrigoriev.yfin.model.PriceHistory;
import io.github.andreygrigoriev.yfin.request.HistoryRequest;
import io.github.andreygrigoriev.yfin.request.Interval;
import io.github.andreygrigoriev.yfin.request.Period;
import java.time.ZoneId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class HistoryIT {

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
    void dailyBarsForUsEquity() {
        PriceHistory h = yf.ticker("AAPL").history(HistoryRequest.of(Period.ONE_MONTH, Interval.ONE_DAY));

        assertThat(h.symbol()).isEqualTo("AAPL");
        assertThat(h.exchangeZone()).isEqualTo(ZoneId.of("America/New_York"));
        assertThat(h.currency()).isEqualTo("USD");
        assertThat(h.bars()).hasSizeBetween(15, 25);
        for (Bar b : h.bars()) {
            assertThat(b.time().getZone()).isEqualTo(h.exchangeZone());
            assertThat(b.high()).isGreaterThanOrEqualTo(b.low());
            assertThat(b.close()).isPositive();
            assertThat(b.volume()).isNotNegative();
        }
        assertThat(h.bars()).isSortedAccordingTo((a, b) -> a.time().compareTo(b.time()));
    }

    @Test
    void intradayBars() {
        PriceHistory h = yf.ticker("MSFT").history(HistoryRequest.of(Period.FIVE_DAYS, Interval.ONE_HOUR));

        assertThat(h.interval()).isEqualTo(Interval.ONE_HOUR);
        assertThat(h.bars()).hasSizeGreaterThan(10);
    }

    @Test
    void cryptoTradesEveryDay() {
        PriceHistory h = yf.ticker("BTC-USD").history(HistoryRequest.of(Period.ONE_MONTH, Interval.ONE_DAY));

        assertThat(h.exchangeZone()).isEqualTo(ZoneId.of("UTC"));
        assertThat(h.bars()).hasSizeGreaterThanOrEqualTo(28);
        assertThat(h.bars().getLast().close()).isGreaterThan(1000);
    }

    @Test
    void nonUsExchangeUsesItsOwnZoneAndCurrency() {
        PriceHistory h = yf.ticker("SAP.DE").history(HistoryRequest.of(Period.ONE_MONTH, Interval.ONE_DAY));

        assertThat(h.exchangeZone()).isEqualTo(ZoneId.of("Europe/Berlin"));
        assertThat(h.currency()).isEqualTo("EUR");
        assertThat(h.bars()).isNotEmpty();
    }

    @Test
    void longHistoryHasDividendsAndSplits() {
        PriceHistory h = yf.ticker("AAPL").history(HistoryRequest.of(Period.MAX, Interval.ONE_MONTH));

        assertThat(h.dividends()).hasSizeGreaterThan(40);
        assertThat(h.splits()).anySatisfy(s -> assertThat(s.ratio()).isEqualTo(4.0));
        assertThat(yf.ticker("AAPL").dividends()).isNotEmpty();
    }

    @Test
    void repairedHistoryKeepsShape() {
        PriceHistory h = yf.ticker("AAPL")
                .history(HistoryRequest.builder()
                        .period(Period.SIX_MONTHS)
                        .interval(Interval.ONE_DAY)
                        .repair(true)
                        .build());

        assertThat(h.bars()).hasSizeGreaterThan(100);
        assertThat(h.bars()).allSatisfy(b -> assertThat(b.close()).isPositive());
    }

    @Test
    void unknownSymbolIsNotFound() {
        assertThatThrownBy(() -> yf.ticker("NOSUCHSYMBOLXYZ").history(Period.ONE_MONTH))
                .isInstanceOf(YFSymbolNotFoundException.class);
    }
}
