package io.github.andreygrigoriev.yfin.model;

import io.github.andreygrigoriev.yfin.request.Frequency;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * A financial statement (income statement, balance sheet or cash flow statement) as a matrix of
 * line items by period, like yfinance's statement DataFrames. Empty when Yahoo has no fundamentals
 * for the symbol (ETFs, indices, crypto, unknown symbols).
 *
 * @param symbol the symbol
 * @param frequency reporting period; a {@link Frequency#TRAILING} statement has one period, the
 *     latest trailing twelve months
 * @param currency currency of the reported values, or {@code null} when Yahoo reports none (or the
 *     statement is empty)
 * @param periods period end dates, newest first
 * @param rows values per line item, in Yahoo website order; each row has every period (newest
 *     first), {@link Double#NaN} where Yahoo reports no value
 */
public record FinancialStatement(
        String symbol,
        Frequency frequency,
        @Nullable String currency,
        List<LocalDate> periods,
        Map<LineItem, SortedMap<LocalDate, Double>> rows) {

    /**
     * Copies {@code periods} and {@code rows}; a row is completed to every period with {@code NaN}
     * and values for other dates are dropped.
     */
    public FinancialStatement {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(frequency, "frequency");
        periods = periods.stream().distinct().sorted(Comparator.reverseOrder()).toList();
        var copy = new LinkedHashMap<LineItem, SortedMap<LocalDate, Double>>();
        for (Map.Entry<LineItem, SortedMap<LocalDate, Double>> row : rows.entrySet()) {
            var values = new TreeMap<LocalDate, Double>(Comparator.reverseOrder());
            for (LocalDate period : periods) {
                Double value = row.getValue().get(period);
                values.put(period, value == null ? Double.NaN : value);
            }
            copy.put(Objects.requireNonNull(row.getKey(), "line item"), Collections.unmodifiableSortedMap(values));
        }
        rows = Collections.unmodifiableMap(copy);
    }

    /** The line items present, in Yahoo website order. */
    public List<LineItem> lineItems() {
        return List.copyOf(rows.keySet());
    }

    /** Whether the statement has no line items. */
    public boolean isEmpty() {
        return rows.isEmpty();
    }

    /**
     * Values of {@code item} per period, newest first, {@code NaN} where missing; empty when the
     * statement does not have the line item.
     */
    public SortedMap<LocalDate, Double> get(LineItem item) {
        SortedMap<LocalDate, Double> row = rows.get(Objects.requireNonNull(item, "item"));
        return row == null ? Collections.emptySortedMap() : row;
    }

    /** {@link #get(LineItem)} by Yahoo key, e.g. {@code TotalRevenue}. */
    public SortedMap<LocalDate, Double> get(String key) {
        return get(LineItem.of(key));
    }

    /** Value of {@code item} for {@code period}, {@code NaN} when missing. */
    public double value(LineItem item, LocalDate period) {
        Double v = get(item).get(period);
        return v == null ? Double.NaN : v;
    }
}
