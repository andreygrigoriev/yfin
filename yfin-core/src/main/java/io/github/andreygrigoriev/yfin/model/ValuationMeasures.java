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

/**
 * Valuation measures (market cap, P/E, P/S, P/B, EV/EBITDA, ...) by period, like yfinance's
 * {@code get_valuation_measures}. Measures are named by their Yahoo key, see {@link #MEASURES}.
 * Empty when Yahoo has none for the symbol (ETFs, indices, crypto, unknown symbols).
 *
 * @param symbol the symbol
 * @param frequency the period series in {@link #rows()}
 * @param current each measure's latest trailing value; measures Yahoo has no value for are absent
 * @param periods period end dates, newest first; every period Yahoo returns (yfinance caps this
 *     at five by default)
 * @param rows values per measure, in {@link #MEASURES} order, every measure present unless the
 *     whole result is empty; each row has every period (newest first), {@link Double#NaN} where
 *     Yahoo reports no value
 */
public record ValuationMeasures(
        String symbol,
        Frequency frequency,
        Map<String, Double> current,
        List<LocalDate> periods,
        Map<String, SortedMap<LocalDate, Double>> rows) {

    /** Yahoo keys of the measures, in yfinance (key-statistics page) order. */
    public static final List<String> MEASURES = List.of(
            "MarketCap",
            "EnterpriseValue",
            "PeRatio",
            "ForwardPeRatio",
            "PegRatio",
            "PsRatio",
            "PbRatio",
            "EnterprisesValueRevenueRatio",
            "EnterprisesValueEBITDARatio");

    /**
     * Copies the collections; a row is completed to every period with {@code NaN} and values for
     * other dates are dropped.
     */
    public ValuationMeasures {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(frequency, "frequency");
        current = Collections.unmodifiableMap(new LinkedHashMap<>(current));
        periods = periods.stream().distinct().sorted(Comparator.reverseOrder()).toList();
        var copy = new LinkedHashMap<String, SortedMap<LocalDate, Double>>();
        for (Map.Entry<String, SortedMap<LocalDate, Double>> row : rows.entrySet()) {
            var values = new TreeMap<LocalDate, Double>(Comparator.reverseOrder());
            for (LocalDate period : periods) {
                Double value = row.getValue().get(period);
                values.put(period, value == null ? Double.NaN : value);
            }
            copy.put(Objects.requireNonNull(row.getKey(), "measure"), Collections.unmodifiableSortedMap(values));
        }
        rows = Collections.unmodifiableMap(copy);
    }

    /** Whether Yahoo returned no valuation data at all. */
    public boolean isEmpty() {
        return current.isEmpty() && periods.isEmpty();
    }

    /** The latest trailing value of {@code measure}, {@code NaN} when missing. */
    public double current(String measure) {
        Double v = current.get(Objects.requireNonNull(measure, "measure"));
        return v == null ? Double.NaN : v;
    }

    /** Values of {@code measure} per period, newest first; empty when the measure is unknown. */
    public SortedMap<LocalDate, Double> get(String measure) {
        SortedMap<LocalDate, Double> row = rows.get(Objects.requireNonNull(measure, "measure"));
        return row == null ? Collections.emptySortedMap() : row;
    }
}
