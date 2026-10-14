package io.andreygrigoriev.yfin.internal.endpoint.visualization;

import com.fasterxml.jackson.databind.JsonNode;
import io.andreygrigoriev.yfin.exception.YFDataException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The first document of a visualization response: its column ids and rows. Cells are read by
 * column id, never by position or label, so a reordered or missing column does not shift values.
 */
public final class VisualizationTable {

    private final JsonNode root;
    private final Map<String, Integer> columns;
    private final List<JsonNode> rows;

    private VisualizationTable(JsonNode root, Map<String, Integer> columns, List<JsonNode> rows) {
        this.root = root;
        this.columns = columns;
        this.rows = rows;
    }

    /**
     * Reads the first document of {@code root}. No documents or no rows give an empty table.
     *
     * @throws YFDataException when the response has no result list or its rows are not a list
     */
    public static VisualizationTable of(JsonNode root) {
        Objects.requireNonNull(root, "root");
        JsonNode results = root.path("finance").path("result");
        if (!results.isArray()) {
            throw new YFDataException("visualization response has no result list", root.toString());
        }
        JsonNode document = results.path(0).path("documents").path(0);
        JsonNode rowNodes = document.path("rows");
        if (rowNodes.isMissingNode() || rowNodes.isNull() || rowNodes.isEmpty()) {
            return new VisualizationTable(root, Map.of(), List.of());
        }
        if (!rowNodes.isArray()) {
            throw new YFDataException("visualization rows are not a list", root.toString());
        }
        Map<String, Integer> columns = new HashMap<>();
        JsonNode columnNodes = document.path("columns");
        for (int i = 0; i < columnNodes.size(); i++) {
            columns.putIfAbsent(columnNodes.get(i).path("id").asText(), i);
        }
        var rows = new ArrayList<JsonNode>(rowNodes.size());
        rowNodes.forEach(rows::add);
        return new VisualizationTable(root, Map.copyOf(columns), List.copyOf(rows));
    }

    /** Whether there are no rows. */
    public boolean isEmpty() {
        return rows.isEmpty();
    }

    /** The rows, in Yahoo's order. */
    public List<JsonNode> rows() {
        return rows;
    }

    /** Whether a column with this id exists. */
    public boolean hasColumn(String id) {
        return columns.containsKey(id);
    }

    /** Position of the first column with this id, or {@code -1}. */
    public int columnIndex(String id) {
        return columns.getOrDefault(id, -1);
    }

    /** The raw response, for error payloads. */
    public String payload() {
        return root.toString();
    }

    /** The cell as text, or {@code null} when the column is missing or the cell is not a non-empty string. */
    public @Nullable String text(JsonNode row, String column) {
        JsonNode value = cell(row, column);
        return value == null || !value.isTextual() || value.asText().isEmpty() ? null : value.asText();
    }

    /**
     * The cell as a double: a number, or a string holding one. {@code NaN} when the column is
     * missing, the cell is null or the string does not parse.
     */
    public double number(JsonNode row, String column) {
        JsonNode value = cell(row, column);
        if (value == null) {
            return Double.NaN;
        }
        if (value.isNumber()) {
            return value.doubleValue();
        }
        if (value.isTextual()) {
            try {
                return Double.parseDouble(value.asText().strip());
            } catch (NumberFormatException e) {
                return Double.NaN;
            }
        }
        return Double.NaN;
    }

    /** Like {@link #number}, but {@code 0} is also {@code NaN}: Yahoo's placeholder in some columns. */
    public double zeroAsNaN(JsonNode row, String column) {
        double value = number(row, column);
        return value == 0.0 ? Double.NaN : value;
    }

    /**
     * The cell as an instant, from an ISO-8601 string; {@code null} when missing or empty.
     *
     * @throws YFDataException when the string is not an instant
     */
    public @Nullable Instant instant(JsonNode row, String column) {
        String text = text(row, column);
        if (text == null) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeException e) {
            throw new YFDataException("invalid " + column + " value: " + text, payload());
        }
    }

    /**
     * The cell as the date of its instant in {@code zone}; {@code null} when missing or empty.
     *
     * @throws YFDataException when the string is not an instant
     */
    public @Nullable LocalDate localDate(JsonNode row, String column, ZoneId zone) {
        Instant instant = instant(row, column);
        return instant == null ? null : instant.atZone(zone).toLocalDate();
    }

    /** The cell as a boolean, or {@code null} when the column is missing or the cell is not a boolean. */
    public @Nullable Boolean bool(JsonNode row, String column) {
        JsonNode value = cell(row, column);
        return value != null && value.isBoolean() ? value.booleanValue() : null;
    }

    private @Nullable JsonNode cell(JsonNode row, String column) {
        Integer index = columns.get(column);
        return index == null ? null : row.get(index);
    }
}
