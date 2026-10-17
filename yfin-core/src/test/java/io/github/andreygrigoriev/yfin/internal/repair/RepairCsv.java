package io.github.andreygrigoriev.yfin.internal.repair;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.andreygrigoriev.yfin.internal.repair.Frame.Col;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Reads yfinance's price repair test CSVs ({@code tests/data}) into a {@link Frame}. */
final class RepairCsv {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssXXX");

    private static final Map<String, Col> COLUMNS = Map.of(
            "Open", Col.OPEN,
            "High", Col.HIGH,
            "Low", Col.LOW,
            "Close", Col.CLOSE,
            "Adj Close", Col.ADJ_CLOSE,
            "Volume", Col.VOLUME,
            "Dividends", Col.DIVIDENDS,
            "Stock Splits", Col.SPLITS,
            "Capital Gains", Col.CAPITAL_GAINS);

    private RepairCsv() {}

    /** The CSV {@code repair/<name>.csv}, timestamps in {@code zone}, sorted by time. */
    static Frame read(String name, ZoneId zone) {
        List<String> lines;
        try (InputStream in = RepairCsv.class.getResourceAsStream("/repair/" + name + ".csv")) {
            assertThat(in).as("fixture %s", name).isNotNull();
            lines = new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .lines()
                    .filter(l -> !l.isBlank())
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<String> header = Arrays.asList(lines.getFirst().split(",", -1));
        List<String[]> rows = new ArrayList<>();
        for (String l : lines.subList(1, lines.size())) {
            rows.add(l.split(",", -1));
        }
        ZonedDateTime[] times = new ZonedDateTime[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            times[i] = OffsetDateTime.parse(rows.get(i)[0], TIME).atZoneSameInstant(zone);
        }
        Frame f = new Frame(times, header.contains("Capital Gains"));
        int repairedCol = header.indexOf("Repaired?");
        for (int j = 1; j < header.size(); j++) {
            Col c = COLUMNS.get(header.get(j));
            if (c == null) {
                continue;
            }
            for (int i = 0; i < rows.size(); i++) {
                String v = rows.get(i)[j];
                f.set(c, i, parse(v));
            }
        }
        if (repairedCol >= 0) {
            for (int i = 0; i < rows.size(); i++) {
                f.repaired()[i] = "True".equals(rows.get(i)[repairedCol]);
            }
        }
        return f.sortedAscending();
    }

    private static double parse(String v) {
        return switch (v) {
            case "", "nan", "NaN" -> Double.NaN;
            case "inf" -> Double.POSITIVE_INFINITY;
            case "-inf" -> Double.NEGATIVE_INFINITY;
            default -> Double.parseDouble(v);
        };
    }

    /** numpy {@code isclose(a, b, rtol, atol=1e-8, equal_nan=True)}. */
    static boolean isClose(double a, double b, double rtol) {
        if (Double.isNaN(a) || Double.isNaN(b)) {
            return Double.isNaN(a) && Double.isNaN(b);
        }
        return Math.abs(a - b) <= 1e-8 + rtol * Math.abs(b);
    }

    /** Asserts column {@code c} of {@code actual} matches {@code expected} row by row. */
    static void assertColumnClose(String label, Frame actual, Frame expected, Col c, double rtol) {
        assertThat(actual.size()).as("%s rows", label).isEqualTo(expected.size());
        var mismatches = new ArrayList<String>();
        for (int i = 0; i < expected.size(); i++) {
            assertThat(actual.time(i).toInstant()).isEqualTo(expected.time(i).toInstant());
            double a = actual.get(c, i);
            double e = expected.get(c, i);
            if (!isClose(a, e, rtol)) {
                mismatches.add(expected.time(i).toLocalDateTime() + " actual=" + a + " expected=" + e);
            }
        }
        assertThat(mismatches).as("%s %s mismatches", label, c).isEmpty();
    }
}
