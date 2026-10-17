package io.github.andreygrigoriev.yfin.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.andreygrigoriev.yfin.request.Frequency;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class FinancialStatementTest {

    private static final LocalDate Y22 = LocalDate.of(2022, 12, 31);
    private static final LocalDate Y23 = LocalDate.of(2023, 12, 31);
    private static final LocalDate Y24 = LocalDate.of(2024, 12, 31);

    private static FinancialStatement sample() {
        var rows = new LinkedHashMap<LineItem, SortedMap<LocalDate, Double>>();
        rows.put(LineItem.TOTAL_REVENUE, new TreeMap<>(Map.of(Y22, 1.0, Y23, 2.0, Y24, 3.0)));
        rows.put(LineItem.NET_INCOME, new TreeMap<>(Map.of(Y23, 0.5, LocalDate.of(2019, 1, 1), 9.0)));
        return new FinancialStatement("ABC", Frequency.ANNUAL, "USD", List.of(Y22, Y24, Y23, Y24), rows);
    }

    @Test
    void periodsAreDistinctNewestFirst() {
        assertThat(sample().periods()).containsExactly(Y24, Y23, Y22);
    }

    @Test
    void rowsHaveEveryPeriodNewestFirstWithNaNForMissing() {
        FinancialStatement s = sample();

        assertThat(s.get(LineItem.TOTAL_REVENUE))
                .containsExactly(Map.entry(Y24, 3.0), Map.entry(Y23, 2.0), Map.entry(Y22, 1.0));
        SortedMap<LocalDate, Double> net = s.get("NetIncome");
        assertThat(net.keySet()).containsExactly(Y24, Y23, Y22);
        assertThat(net.get(Y24)).isNaN();
        assertThat(net.get(Y23)).isEqualTo(0.5);
        assertThat(net.get(Y22)).isNaN();
        assertThat(net).doesNotContainKey(LocalDate.of(2019, 1, 1));
    }

    @Test
    void lineItemsKeepInsertionOrder() {
        assertThat(sample().lineItems()).containsExactly(LineItem.TOTAL_REVENUE, LineItem.NET_INCOME);
        assertThat(sample().isEmpty()).isFalse();
    }

    @Test
    void absentLineItemGivesEmptyRowAndNaNValue() {
        FinancialStatement s = sample();

        assertThat(s.get(LineItem.GROSS_PROFIT)).isEmpty();
        assertThat(s.value(LineItem.GROSS_PROFIT, Y24)).isNaN();
        assertThat(s.value(LineItem.TOTAL_REVENUE, LocalDate.of(2000, 1, 1))).isNaN();
        assertThat(s.value(LineItem.TOTAL_REVENUE, Y23)).isEqualTo(2.0);
    }

    @Test
    void isImmutable() {
        FinancialStatement s = sample();

        assertThatThrownBy(() -> s.periods().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> s.rows().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> s.get(LineItem.TOTAL_REVENUE).put(Y22, 0.0))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> s.lineItems().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void equalityIncludesNaNCells() {
        assertThat(sample()).isEqualTo(sample()).hasSameHashCodeAs(sample());
    }

    @Test
    void emptyStatement() {
        var s = new FinancialStatement("SPY", Frequency.QUARTERLY, null, List.of(), Map.of());

        assertThat(s.isEmpty()).isTrue();
        assertThat(s.lineItems()).isEmpty();
        assertThat(s.get(LineItem.TOTAL_REVENUE)).isEmpty();
    }

    @Test
    void lineItemValidation() {
        assertThat(LineItem.of("TotalRevenue")).isEqualTo(LineItem.TOTAL_REVENUE);
        assertThat(LineItem.TOTAL_REVENUE).hasToString("TotalRevenue");
        assertThatThrownBy(() -> LineItem.of(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LineItem.of(null)).isInstanceOf(NullPointerException.class);
    }
}
