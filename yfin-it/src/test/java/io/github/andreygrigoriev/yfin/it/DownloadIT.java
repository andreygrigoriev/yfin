package io.github.andreygrigoriev.yfin.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.andreygrigoriev.yfin.YFinance;
import io.github.andreygrigoriev.yfin.exception.YFDownloadException;
import io.github.andreygrigoriev.yfin.model.PriceHistory;
import io.github.andreygrigoriev.yfin.request.HistoryRequest;
import io.github.andreygrigoriev.yfin.request.Interval;
import io.github.andreygrigoriev.yfin.request.Period;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class DownloadIT {

    private static final List<String> SYMBOLS =
            List.of("AAPL", "MSFT", "GOOGL", "AMZN", "NVDA", "META", "JPM", "SPY", "BTC-USD", "SAP.DE");

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
    void downloadsAllSymbolsConcurrently() {
        Map<String, PriceHistory> result = yf.download(SYMBOLS, HistoryRequest.of(Period.ONE_MONTH, Interval.ONE_DAY));

        assertThat(result.keySet()).containsExactlyInAnyOrderElementsOf(SYMBOLS);
        assertThat(result.values()).allSatisfy(h -> assertThat(h.bars()).isNotEmpty());
    }

    @Test
    void tickersGroupHistory() {
        Map<String, PriceHistory> result = yf.tickers("AAPL", "MSFT").history(Period.FIVE_DAYS);

        assertThat(result).containsOnlyKeys("AAPL", "MSFT");
    }

    @Test
    void failureCarriesPartialResults() {
        assertThatThrownBy(() -> yf.download(List.of("AAPL", "NOSUCHSYMBOLXYZ"), HistoryRequest.of(Period.FIVE_DAYS)))
                .isInstanceOfSatisfying(YFDownloadException.class, e -> {
                    assertThat(e.failures()).containsOnlyKeys("NOSUCHSYMBOLXYZ");
                    assertThat(e.partialResults()).containsOnlyKeys("AAPL");
                });
    }
}
