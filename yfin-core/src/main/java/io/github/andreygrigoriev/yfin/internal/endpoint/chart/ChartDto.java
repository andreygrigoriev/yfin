package io.github.andreygrigoriev.yfin.internal.endpoint.chart;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.andreygrigoriev.yfin.exception.YFDataException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The parts of a chart response the mapper needs, read from the JSON tree (no reflection). Rows
 * and events are sorted by time; missing prices are {@link Double#NaN}, missing volume {@code 0}.
 *
 * @param meta the {@code chart.result[0].meta} object
 * @param hasAdjClose whether Yahoo sent an adjusted close (daily and longer intervals)
 */
record ChartDto(
        JsonNode meta,
        List<Row> rows,
        boolean hasAdjClose,
        List<Amount> dividends,
        List<SplitTerms> splits,
        List<Amount> capitalGains) {

    record Row(long epoch, double open, double high, double low, double close, double adjClose, long volume) {}

    record Amount(long epoch, double amount) {}

    record SplitTerms(long epoch, double numerator, double denominator) {}

    ChartDto {
        Objects.requireNonNull(meta, "meta");
        rows = List.copyOf(rows);
        dividends = List.copyOf(dividends);
        splits = List.copyOf(splits);
        capitalGains = List.copyOf(capitalGains);
    }

    /**
     * Reads {@code root} (an envelope already checked for {@code chart.error}).
     *
     * @throws YFDataException when the result or its metadata is missing, or the arrays are malformed
     */
    static ChartDto parse(JsonNode root) {
        JsonNode results = root.path("chart").path("result");
        if (!results.isArray() || results.isEmpty() || !results.get(0).isObject()) {
            throw new YFDataException("chart response has no result", root.toString());
        }
        JsonNode result = results.get(0);
        JsonNode meta = result.path("meta");
        if (!meta.isObject()) {
            throw new YFDataException("chart result has no meta", root.toString());
        }
        try {
            boolean hasAdjClose = result.path("indicators")
                    .path("adjclose")
                    .path(0)
                    .path("adjclose")
                    .isArray();
            return new ChartDto(
                    meta,
                    rows(result, hasAdjClose),
                    hasAdjClose,
                    amounts(result.path("events").path("dividends")),
                    splits(result.path("events").path("splits")),
                    amounts(result.path("events").path("capitalGains")));
        } catch (IllegalArgumentException e) {
            throw new YFDataException("malformed chart result: " + e.getMessage(), root.toString(), e);
        }
    }

    private static List<Row> rows(JsonNode result, boolean hasAdjClose) {
        JsonNode timestamps = result.path("timestamp");
        if (timestamps.isMissingNode() || timestamps.isNull()) {
            return List.of();
        }
        if (!timestamps.isArray()) {
            throw new IllegalArgumentException("timestamp is not an array");
        }
        int n = timestamps.size();
        JsonNode quote = result.path("indicators").path("quote").path(0);
        double[] open = prices(quote.path("open"), n, "open");
        double[] high = prices(quote.path("high"), n, "high");
        double[] low = prices(quote.path("low"), n, "low");
        double[] close = prices(quote.path("close"), n, "close");
        double[] adjClose = hasAdjClose
                ? prices(result.path("indicators").path("adjclose").path(0).path("adjclose"), n, "adjclose")
                : close;
        JsonNode volume = quote.path("volume");
        checkArray(volume, n, "volume");
        var rows = new ArrayList<Row>(n);
        for (int i = 0; i < n; i++) {
            JsonNode ts = timestamps.get(i);
            if (!ts.isIntegralNumber()) {
                throw new IllegalArgumentException("timestamp[" + i + "] is not an integer: " + ts);
            }
            JsonNode v = volume.path(i);
            long vol;
            if (v.isMissingNode() || v.isNull()) {
                vol = 0;
            } else if (v.isNumber()) {
                vol = v.asLong();
            } else {
                throw new IllegalArgumentException("volume[" + i + "] is not a number: " + v);
            }
            rows.add(new Row(ts.asLong(), open[i], high[i], low[i], close[i], adjClose[i], vol));
        }
        rows.sort(Comparator.comparingLong(Row::epoch));
        return rows;
    }

    /** Values of {@code node} as doubles; a missing array means all NaN. */
    private static double[] prices(JsonNode node, int n, String name) {
        double[] values = new double[n];
        if (node.isMissingNode() || node.isNull()) {
            Arrays.fill(values, Double.NaN);
            return values;
        }
        checkArray(node, n, name);
        for (int i = 0; i < n; i++) {
            JsonNode v = node.get(i);
            if (v.isNull()) {
                values[i] = Double.NaN;
            } else if (v.isNumber()) {
                values[i] = v.asDouble();
            } else {
                throw new IllegalArgumentException(name + "[" + i + "] is not a number: " + v);
            }
        }
        return values;
    }

    private static void checkArray(JsonNode node, int n, String name) {
        if (node.isMissingNode() || node.isNull()) {
            return;
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException(name + " is not an array");
        }
        if (node.size() != n) {
            throw new IllegalArgumentException(name + " has " + node.size() + " values for " + n + " timestamps");
        }
    }

    private static List<Amount> amounts(JsonNode events) {
        var result = new ArrayList<Amount>();
        for (Map.Entry<String, JsonNode> e : events.properties()) {
            JsonNode event = e.getValue();
            result.add(new Amount(eventEpoch(e.getKey(), event), number(event, "amount")));
        }
        result.sort(Comparator.comparingLong(Amount::epoch));
        return result;
    }

    private static List<SplitTerms> splits(JsonNode events) {
        var result = new ArrayList<SplitTerms>();
        for (Map.Entry<String, JsonNode> e : events.properties()) {
            JsonNode event = e.getValue();
            result.add(new SplitTerms(
                    eventEpoch(e.getKey(), event), number(event, "numerator"), number(event, "denominator")));
        }
        result.sort(Comparator.comparingLong(SplitTerms::epoch));
        return result;
    }

    private static long eventEpoch(String key, JsonNode event) {
        JsonNode date = event.path("date");
        if (date.isIntegralNumber()) {
            return date.asLong();
        }
        try {
            return Long.parseLong(key);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("event " + key + " has no date");
        }
    }

    private static double number(JsonNode event, String field) {
        JsonNode v = event.path(field);
        if (!v.isNumber()) {
            throw new IllegalArgumentException("event field " + field + " is not a number: " + v);
        }
        return v.asDouble();
    }
}
