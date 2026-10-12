package io.andreygrigoriev.yfin.request;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Screener query for stocks (yfinance {@code EquityQuery}), screening quote type {@code EQUITY}.
 *
 * <pre>{@code
 * EquityQuery q = EquityQuery.and(
 *         EquityQuery.isIn("exchange", "NMS", "NYQ"),
 *         EquityQuery.lt("epsgrowth.lasttwelvemonths", 15));
 * }</pre>
 *
 * <p>Fields are those of {@link #validFields()}; fields listed in {@link #validValues()} only take
 * those values (e.g. {@code region} takes lower-case country codes such as {@code us}).
 */
public final class EquityQuery extends ScreenerQuery {

    private static final Spec SPEC =
            new Spec("EQUITY", EquityQuery.class, ScreenerFields.EQUITY_FIELDS, ScreenerFields.EQUITY_VALUES);

    private EquityQuery(Operator operator, List<?> operands) {
        super(SPEC, operator, operands);
    }

    /**
     * Query from a yfinance-style operator name ({@code and}, {@code or}, {@code eq}, {@code
     * is-in}, {@code btwn}, {@code gt}, {@code lt}, {@code gte}, {@code lte}; case-insensitive) and
     * operands, as {@code EquityQuery('gt', ['percentchange', 3])} in yfinance.
     *
     * @throws IllegalArgumentException for an unknown operator, field or value, or operands that do
     *     not fit the operator
     */
    public static EquityQuery of(String operator, List<?> operands) {
        return new EquityQuery(Operator.of(operator), operands);
    }

    /** All of {@code queries} match (at least two). */
    public static EquityQuery and(EquityQuery... queries) {
        return new EquityQuery(Operator.AND, List.of(queries));
    }

    /** All of {@code queries} match (at least two). */
    public static EquityQuery and(List<EquityQuery> queries) {
        return new EquityQuery(Operator.AND, queries);
    }

    /** Any of {@code queries} matches (at least two). */
    public static EquityQuery or(EquityQuery... queries) {
        return new EquityQuery(Operator.OR, List.of(queries));
    }

    /** Any of {@code queries} matches (at least two). */
    public static EquityQuery or(List<EquityQuery> queries) {
        return new EquityQuery(Operator.OR, queries);
    }

    /** {@code field} equals {@code value} (a {@code String} or a {@code Number}). */
    public static EquityQuery eq(String field, Object value) {
        return new EquityQuery(Operator.EQ, List.of(field, value));
    }

    /** {@code field} equals any of {@code values} (at least one). */
    public static EquityQuery isIn(String field, Object... values) {
        return isIn(field, List.of(values));
    }

    /** {@code field} equals any of {@code values} (at least one). */
    public static EquityQuery isIn(String field, List<?> values) {
        var operands = new ArrayList<Object>(values.size() + 1);
        operands.add(Objects.requireNonNull(field, "field"));
        operands.addAll(values);
        return new EquityQuery(Operator.IS_IN, operands);
    }

    /** {@code field} between {@code low} and {@code high}. */
    public static EquityQuery btwn(String field, Number low, Number high) {
        return new EquityQuery(Operator.BTWN, List.of(field, low, high));
    }

    /** {@code field} greater than {@code value}. */
    public static EquityQuery gt(String field, Number value) {
        return new EquityQuery(Operator.GT, List.of(field, value));
    }

    /** {@code field} less than {@code value}. */
    public static EquityQuery lt(String field, Number value) {
        return new EquityQuery(Operator.LT, List.of(field, value));
    }

    /** {@code field} greater than or equal to {@code value}. */
    public static EquityQuery gte(String field, Number value) {
        return new EquityQuery(Operator.GTE, List.of(field, value));
    }

    /** {@code field} less than or equal to {@code value}. */
    public static EquityQuery lte(String field, Number value) {
        return new EquityQuery(Operator.LTE, List.of(field, value));
    }

    /** Valid fields by category (yfinance {@code valid_fields}). Unmodifiable. */
    public static Map<String, Set<String>> validFields() {
        return SPEC.fields();
    }

    /** Fields with a restricted set of values, and those values (yfinance {@code valid_values}). Unmodifiable. */
    public static Map<String, Set<String>> validValues() {
        return SPEC.values();
    }
}
