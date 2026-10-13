package io.andreygrigoriev.yfin.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.andreygrigoriev.yfin.Ticker;
import io.andreygrigoriev.yfin.YFinance;
import io.andreygrigoriev.yfin.model.MajorHolders;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class HoldersIT {

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
    void majorAndInstitutionalHolders() {
        Ticker t = yf.ticker("AAPL");

        MajorHolders major = t.majorHolders().orElseThrow();
        assertThat(major.institutionsPercentHeld()).isBetween(0.0, 1.5);
        assertThat(t.institutionalHolders()).isNotEmpty().allSatisfy(h -> {
            assertThat(h.holder()).isNotBlank();
            assertThat(h.shares()).isPositive();
        });
        assertThat(t.mutualFundHolders()).isNotEmpty();
    }

    @Test
    void insiderData() {
        Ticker t = yf.ticker("AAPL");

        assertThat(t.insiderTransactions()).isNotEmpty();
        assertThat(t.insiderRosterHolders())
                .isNotEmpty()
                .allSatisfy(r -> assertThat(r).isNotNull());
        assertThat(t.insiderPurchases()).isPresent();
    }
}
