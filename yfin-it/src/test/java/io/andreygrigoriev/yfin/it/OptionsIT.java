package io.andreygrigoriev.yfin.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.andreygrigoriev.yfin.Ticker;
import io.andreygrigoriev.yfin.YFinance;
import io.andreygrigoriev.yfin.model.OptionChain;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class OptionsIT {

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
    void expirationsAndChain() {
        Ticker t = yf.ticker("AAPL");

        List<LocalDate> expirations = t.optionExpirations();
        assertThat(expirations).hasSizeGreaterThan(5).isSorted();

        OptionChain chain = t.optionChain(expirations.get(1));
        assertThat(chain.expiration()).isEqualTo(expirations.get(1));
        assertThat(chain.calls()).isNotEmpty();
        assertThat(chain.puts()).isNotEmpty();
        assertThat(chain.calls()).allSatisfy(c -> {
            assertThat(c.contractSymbol()).startsWith("AAPL");
            assertThat(c.strike()).isPositive();
        });
    }

    @Test
    void symbolWithoutOptions() {
        assertThat(yf.ticker("BTC-USD").optionExpirations()).isEmpty();
        assertThat(yf.ticker("BTC-USD").optionChain()).isEmpty();
    }
}
