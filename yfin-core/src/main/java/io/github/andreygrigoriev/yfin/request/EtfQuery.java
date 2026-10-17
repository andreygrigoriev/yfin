package io.github.andreygrigoriev.yfin.request;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Screener query for ETFs (yfinance {@code ETFQuery}), screening quote type {@code ETF}.
 *
 * <pre>{@code
 * EtfQuery q = EtfQuery.and(
 *         EtfQuery.gt("intradayprice", 10),
 *         EtfQuery.isIn("performanceratingoverall", 4, 5),
 *         EtfQuery.eq("region", "us"));
 * }</pre>
 *
 * <p>Fields are those of {@link #validFields()}; fields listed in {@link #validValues()} only take
 * those values (e.g. {@code exchange} takes Yahoo exchange codes such as {@code PCX}).
 */
public final class EtfQuery extends ScreenerQuery {

    private static final Spec SPEC =
            new Spec("ETF", EtfQuery.class, ScreenerFields.ETF_FIELDS, ScreenerFields.ETF_VALUES);

    private EtfQuery(Operator operator, List<?> operands) {
        super(SPEC, operator, operands);
    }

    /**
     * Query from a yfinance-style operator name ({@code and}, {@code or}, {@code eq}, {@code
     * is-in}, {@code btwn}, {@code gt}, {@code lt}, {@code gte}, {@code lte}; case-insensitive) and
     * operands, as {@code EtfQuery('gt', ['percentchange', 3])} in yfinance.
     *
     * @throws IllegalArgumentException for an unknown operator, field or value, or operands that do
     *     not fit the operator
     */
    public static EtfQuery of(String operator, List<?> operands) {
        return new EtfQuery(Operator.of(operator), operands);
    }

    /** All of {@code queries} match (at least two). */
    public static EtfQuery and(EtfQuery... queries) {
        return new EtfQuery(Operator.AND, List.of(queries));
    }

    /** All of {@code queries} match (at least two). */
    public static EtfQuery and(List<EtfQuery> queries) {
        return new EtfQuery(Operator.AND, queries);
    }

    /** Any of {@code queries} matches (at least two). */
    public static EtfQuery or(EtfQuery... queries) {
        return new EtfQuery(Operator.OR, List.of(queries));
    }

    /** Any of {@code queries} matches (at least two). */
    public static EtfQuery or(List<EtfQuery> queries) {
        return new EtfQuery(Operator.OR, queries);
    }

    /** {@code field} equals {@code value} (a {@code String} or a {@code Number}). */
    public static EtfQuery eq(String field, Object value) {
        return new EtfQuery(Operator.EQ, List.of(field, value));
    }

    /** {@code field} equals any of {@code values} (at least one). */
    public static EtfQuery isIn(String field, Object... values) {
        return isIn(field, List.of(values));
    }

    /** {@code field} equals any of {@code values} (at least one). */
    public static EtfQuery isIn(String field, List<?> values) {
        var operands = new ArrayList<Object>(values.size() + 1);
        operands.add(Objects.requireNonNull(field, "field"));
        operands.addAll(values);
        return new EtfQuery(Operator.IS_IN, operands);
    }

    /** {@code field} between {@code low} and {@code high}. */
    public static EtfQuery btwn(String field, Number low, Number high) {
        return new EtfQuery(Operator.BTWN, List.of(field, low, high));
    }

    /** {@code field} greater than {@code value}. */
    public static EtfQuery gt(String field, Number value) {
        return new EtfQuery(Operator.GT, List.of(field, value));
    }

    /** {@code field} less than {@code value}. */
    public static EtfQuery lt(String field, Number value) {
        return new EtfQuery(Operator.LT, List.of(field, value));
    }

    /** {@code field} greater than or equal to {@code value}. */
    public static EtfQuery gte(String field, Number value) {
        return new EtfQuery(Operator.GTE, List.of(field, value));
    }

    /** {@code field} less than or equal to {@code value}. */
    public static EtfQuery lte(String field, Number value) {
        return new EtfQuery(Operator.LTE, List.of(field, value));
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
