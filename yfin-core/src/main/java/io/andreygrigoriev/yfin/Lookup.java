package io.andreygrigoriev.yfin;

import io.andreygrigoriev.yfin.request.LookupType;
import java.util.Objects;

/**
 * Options of a Yahoo Finance symbol lookup (yfinance {@code Lookup}), passed to {@link
 * YFinance#lookup(Lookup)}. Immutable: every setter returns a copy.
 *
 * <pre>{@code
 * List<LookupResult> etfs = yf.lookup(Lookup.of("gold").type(LookupType.ETF).count(10));
 * }</pre>
 *
 * <p>Defaults follow yfinance: {@link LookupType#ALL}, 25 results.
 */
public final class Lookup {

    /** Default number of results, as in yfinance. */
    public static final int DEFAULT_COUNT = 25;

    private final String query;
    private final LookupType type;
    private final int count;

    private Lookup(String query, LookupType type, int count) {
        this.query = query;
        this.type = type;
        this.count = count;
    }

    /**
     * Lookup of {@code query} (trimmed) across all types, 25 results.
     *
     * @throws IllegalArgumentException when {@code query} is blank
     */
    public static Lookup of(String query) {
        return new Lookup(Search.checkQuery(query), LookupType.ALL, DEFAULT_COUNT);
    }

    /** Restricts results to {@code type}. */
    public Lookup type(LookupType type) {
        return new Lookup(query, Objects.requireNonNull(type, "type"), count);
    }

    /**
     * Maximum number of results.
     *
     * @throws IllegalArgumentException when {@code count} is not positive
     */
    public Lookup count(int count) {
        if (count < 1) {
            throw new IllegalArgumentException("count must be positive: " + count);
        }
        return new Lookup(query, type, count);
    }

    public String query() {
        return query;
    }

    public LookupType type() {
        return type;
    }

    public int count() {
        return count;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Lookup l && query.equals(l.query) && type == l.type && count == l.count;
    }

    @Override
    public int hashCode() {
        return Objects.hash(query, type, count);
    }

    @Override
    public String toString() {
        return "Lookup[query=" + query + ", type=" + type + ", count=" + count + "]";
    }
}
