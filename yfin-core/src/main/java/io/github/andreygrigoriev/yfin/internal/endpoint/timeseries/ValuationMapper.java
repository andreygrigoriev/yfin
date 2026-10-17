package io.github.andreygrigoriev.yfin.internal.endpoint.timeseries;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.andreygrigoriev.yfin.model.ValuationMeasures;
import io.github.andreygrigoriev.yfin.request.Frequency;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Reshapes valuation {@code fundamentals-timeseries} results into {@link ValuationMeasures}, like
 * yfinance's {@code _fetch_valuation_measures}: values keyed by {@code asOfDate}, {@code current}
 * from the newest trailing point of each measure, periods the union of the dates of the requested
 * frequency's series.
 */
final class ValuationMapper {

    static final List<String> MEASURES = ValuationMeasures.MEASURES;

    private static final String TRAILING = Frequency.TRAILING.yahooPrefix();

    private ValuationMapper() {}

    static ValuationMeasures map(String symbol, Frequency frequency, List<JsonNode> results) {
        String prefix = frequency.yahooPrefix();
        var period = new HashMap<String, Map<LocalDate, Double>>();
        var trailing = new HashMap<String, Map<LocalDate, Double>>();
        for (JsonNode result : results) {
            if (!result.isObject()) {
                continue;
            }
            for (Map.Entry<String, JsonNode> field : result.properties()) {
                String name = field.getKey();
                if (name.equals("meta")
                        || name.equals("timestamp")
                        || !field.getValue().isArray()) {
                    continue;
                }
                Map<String, Map<LocalDate, Double>> target;
                String base;
                if (frequency != Frequency.TRAILING && name.startsWith(prefix)) {
                    base = name.substring(prefix.length());
                    target = period;
                } else if (name.startsWith(TRAILING)) {
                    base = name.substring(TRAILING.length());
                    target = trailing;
                } else {
                    continue;
                }
                if (!MEASURES.contains(base)) {
                    continue;
                }
                for (JsonNode point : field.getValue()) {
                    LocalDate date = TimeseriesMapper.asOfDate(point);
                    JsonNode raw = point.path("reportedValue").path("raw");
                    if (date != null && raw.isNumber()) {
                        target.computeIfAbsent(base, k -> new HashMap<>()).put(date, raw.doubleValue());
                    }
                }
            }
        }
        Map<String, Map<LocalDate, Double>> rowsSource = frequency == Frequency.TRAILING ? trailing : period;
        if (rowsSource.isEmpty() && trailing.isEmpty()) {
            return new ValuationMeasures(symbol, frequency, Map.of(), List.of(), Map.of());
        }
        var current = new LinkedHashMap<String, Double>();
        for (String measure : MEASURES) {
            Map<LocalDate, Double> series = trailing.get(measure);
            if (series != null && !series.isEmpty()) {
                current.put(measure, series.get(new TreeSet<>(series.keySet()).last()));
            }
        }
        var dates = new TreeSet<LocalDate>(Comparator.reverseOrder());
        rowsSource.values().forEach(s -> dates.addAll(s.keySet()));
        var rows = new LinkedHashMap<String, SortedMap<LocalDate, Double>>();
        for (String measure : MEASURES) {
            rows.put(measure, new TreeMap<>(rowsSource.getOrDefault(measure, Map.of())));
        }
        return new ValuationMeasures(symbol, frequency, current, List.copyOf(dates), rows);
    }
}
