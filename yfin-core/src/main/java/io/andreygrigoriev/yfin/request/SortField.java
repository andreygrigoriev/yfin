package io.andreygrigoriev.yfin.request;

import java.util.Objects;

/**
 * Sort order of screener results: a Yahoo screener field (e.g. {@code percentchange}, {@code
 * dayvolume}, {@code ticker}) and a direction.
 *
 * @param field the field to sort by
 * @param ascending {@code true} for ascending, {@code false} for descending
 */
public record SortField(String field, boolean ascending) {

    /** yfinance's default for custom screens: {@code ticker}, descending. */
    public static final SortField DEFAULT = descending("ticker");

    public SortField {
        Objects.requireNonNull(field, "field");
        if (field.isBlank()) {
            throw new IllegalArgumentException("sort field is blank");
        }
    }

    /** Ascending by {@code field}. */
    public static SortField ascending(String field) {
        return new SortField(field, true);
    }

    /** Descending by {@code field}. */
    public static SortField descending(String field) {
        return new SortField(field, false);
    }

    /** Yahoo's {@code sortType}: {@code ASC} or {@code DESC}. */
    public String sortType() {
        return ascending ? "ASC" : "DESC";
    }
}
