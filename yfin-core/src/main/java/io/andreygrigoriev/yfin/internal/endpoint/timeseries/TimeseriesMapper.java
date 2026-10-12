package io.andreygrigoriev.yfin.internal.endpoint.timeseries;

import com.fasterxml.jackson.databind.JsonNode;
import io.andreygrigoriev.yfin.model.FinancialStatement;
import io.andreygrigoriev.yfin.model.LineItem;
import io.andreygrigoriev.yfin.request.Frequency;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * Reshapes {@code fundamentals-timeseries} results into a {@link FinancialStatement}, like
 * yfinance's {@code _get_financials_time_series}: periods are the union of all series timestamps
 * (UTC dates), values are keyed by {@code asOfDate}, rows follow the statement key order, and a
 * trailing statement keeps only the newest period.
 *
 * <p>Deviation from yfinance: line items with no value in any kept period are dropped instead of
 * being kept as all-{@code NaN} rows.
 */
final class TimeseriesMapper {

    private TimeseriesMapper() {}

    static FinancialStatement map(String symbol, Frequency frequency, List<String> keys, List<JsonNode> results) {
        String prefix = frequency.yahooPrefix();
        var periods = new TreeSet<LocalDate>(Comparator.reverseOrder());
        var series = new HashMap<String, Map<LocalDate, Double>>();
        String currency = null;
        for (JsonNode result : results) {
            if (!result.isObject()) {
                continue;
            }
            for (JsonNode ts : result.path("timestamp")) {
                if (ts.isNumber() && ts.canConvertToLong()) {
                    periods.add(LocalDate.ofInstant(Instant.ofEpochSecond(ts.longValue()), ZoneOffset.UTC));
                }
            }
            for (Map.Entry<String, JsonNode> field : result.properties()) {
                String name = field.getKey();
                if (name.equals("meta")
                        || name.equals("timestamp")
                        || !field.getValue().isArray()) {
                    continue;
                }
                String key = name.startsWith(prefix) ? name.substring(prefix.length()) : name;
                Map<LocalDate, Double> values = series.computeIfAbsent(key, k -> new HashMap<>());
                for (JsonNode point : field.getValue()) {
                    LocalDate date = asOfDate(point);
                    JsonNode raw = point.path("reportedValue").path("raw");
                    if (date != null && raw.isNumber()) {
                        values.put(date, raw.doubleValue());
                        if (currency == null && point.path("currencyCode").isTextual()) {
                            currency = point.path("currencyCode").textValue();
                        }
                    }
                }
            }
        }
        List<LocalDate> kept =
                frequency == Frequency.TRAILING && !periods.isEmpty() ? List.of(periods.first()) : List.copyOf(periods);
        var rows = new LinkedHashMap<LineItem, SortedMap<LocalDate, Double>>();
        for (String key : keys) {
            Map<LocalDate, Double> values = series.get(key);
            if (values == null) {
                continue;
            }
            var row = new TreeMap<LocalDate, Double>(Comparator.reverseOrder());
            for (LocalDate period : kept) {
                Double v = values.get(period);
                if (v != null) {
                    row.put(period, v);
                }
            }
            if (!row.isEmpty()) {
                rows.put(LineItem.of(key), row);
            }
        }
        if (rows.isEmpty()) {
            return new FinancialStatement(symbol, frequency, null, List.of(), Map.of());
        }
        return new FinancialStatement(symbol, frequency, currency, kept, rows);
    }

    private static @Nullable LocalDate asOfDate(JsonNode point) {
        JsonNode date = point.path("asOfDate");
        if (!date.isTextual()) {
            return null;
        }
        try {
            return LocalDate.parse(date.textValue());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
