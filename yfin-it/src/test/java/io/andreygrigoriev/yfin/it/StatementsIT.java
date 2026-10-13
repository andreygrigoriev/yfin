package io.andreygrigoriev.yfin.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.andreygrigoriev.yfin.Ticker;
import io.andreygrigoriev.yfin.YFinance;
import io.andreygrigoriev.yfin.model.FinancialStatement;
import io.andreygrigoriev.yfin.model.LineItem;
import io.andreygrigoriev.yfin.request.Frequency;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class StatementsIT {

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
    void annualIncomeStatement() {
        FinancialStatement s = yf.ticker("AAPL").incomeStatement(Frequency.ANNUAL);

        assertThat(s.periods()).hasSizeGreaterThanOrEqualTo(3);
        assertThat(s.periods()).isSortedAccordingTo((a, b) -> b.compareTo(a));
        assertThat(s.get(LineItem.TOTAL_REVENUE)).isNotEmpty();
        assertThat(s.value(LineItem.TOTAL_REVENUE, s.periods().getFirst())).isGreaterThan(1e10);
    }

    @Test
    void quarterlyBalanceSheetAndTrailingCashFlow() {
        Ticker t = yf.ticker("MSFT");

        FinancialStatement balance = t.balanceSheet(Frequency.QUARTERLY);
        assertThat(balance.periods()).hasSizeGreaterThanOrEqualTo(4);
        assertThat(balance.get(LineItem.TOTAL_ASSETS)).isNotEmpty();

        FinancialStatement cash = t.cashFlow(Frequency.TRAILING);
        assertThat(cash.get(LineItem.OPERATING_CASH_FLOW)).isNotEmpty();
    }

    @Test
    void sharesHistory() {
        assertThat(yf.ticker("AAPL").sharesFull())
                .isNotEmpty()
                .allSatisfy(s -> assertThat(s.shares()).isPositive());
    }
}
