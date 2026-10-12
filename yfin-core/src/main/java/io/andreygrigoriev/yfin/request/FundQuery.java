package io.andreygrigoriev.yfin.request;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Screener query for mutual funds (yfinance {@code FundQuery}), screening quote type {@code
 * MUTUALFUND}.
 *
 * <pre>{@code
 * FundQuery q = FundQuery.and(
 *         FundQuery.eq("categoryname", "Large Growth"),
 *         FundQuery.isIn("performanceratingoverall", 4, 5),
 *         FundQuery.eq("exchange", "NAS"));
 * }</pre>
 *
 * <p>Fields are those of {@link #validFields()}; fields listed in {@link #validValues()} only take
 * those values (e.g. {@code exchange} takes Yahoo exchange codes such as {@code NAS}).
 */
public final class FundQuery extends ScreenerQuery {

    private static final Spec SPEC =
            new Spec("MUTUALFUND", FundQuery.class, ScreenerFields.FUND_FIELDS, ScreenerFields.FUND_VALUES);

    private FundQuery(Operator operator, List<?> operands) {
        super(SPEC, operator, operands);
    }

    /**
     * Query from a yfinance-style operator name ({@code and}, {@code or}, {@code eq}, {@code
     * is-in}, {@code btwn}, {@code gt}, {@code lt}, {@code gte}, {@code lte}; case-insensitive) and
     * operands, as {@code FundQuery('gt', ['percentchange', 3])} in yfinance.
     *
     * @throws IllegalArgumentException for an unknown operator, field or value, or operands that do
     *     not fit the operator
     */
    public static FundQuery of(String operator, List<?> operands) {
        return new FundQuery(Operator.of(operator), operands);
    }

    /** All of {@code queries} match (at least two). */
    public static FundQuery and(FundQuery... queries) {
        return new FundQuery(Operator.AND, List.of(queries));
    }

    /** All of {@code queries} match (at least two). */
    public static FundQuery and(List<FundQuery> queries) {
        return new FundQuery(Operator.AND, queries);
    }

    /** Any of {@code queries} matches (at least two). */
    public static FundQuery or(FundQuery... queries) {
        return new FundQuery(Operator.OR, List.of(queries));
    }

    /** Any of {@code queries} matches (at least two). */
    public static FundQuery or(List<FundQuery> queries) {
        return new FundQuery(Operator.OR, queries);
    }

    /** {@code field} equals {@code value} (a {@code String} or a {@code Number}). */
    public static FundQuery eq(String field, Object value) {
        return new FundQuery(Operator.EQ, List.of(field, value));
    }

    /** {@code field} equals any of {@code values} (at least one). */
    public static FundQuery isIn(String field, Object... values) {
        return isIn(field, List.of(values));
    }

    /** {@code field} equals any of {@code values} (at least one). */
    public static FundQuery isIn(String field, List<?> values) {
        var operands = new ArrayList<Object>(values.size() + 1);
        operands.add(Objects.requireNonNull(field, "field"));
        operands.addAll(values);
        return new FundQuery(Operator.IS_IN, operands);
    }

    /** {@code field} between {@code low} and {@code high}. */
    public static FundQuery btwn(String field, Number low, Number high) {
        return new FundQuery(Operator.BTWN, List.of(field, low, high));
    }

    /** {@code field} greater than {@code value}. */
    public static FundQuery gt(String field, Number value) {
        return new FundQuery(Operator.GT, List.of(field, value));
    }

    /** {@code field} less than {@code value}. */
    public static FundQuery lt(String field, Number value) {
        return new FundQuery(Operator.LT, List.of(field, value));
    }

    /** {@code field} greater than or equal to {@code value}. */
    public static FundQuery gte(String field, Number value) {
        return new FundQuery(Operator.GTE, List.of(field, value));
    }

    /** {@code field} less than or equal to {@code value}. */
    public static FundQuery lte(String field, Number value) {
        return new FundQuery(Operator.LTE, List.of(field, value));
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
