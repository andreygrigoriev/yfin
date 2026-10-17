package io.github.andreygrigoriev.yfin.request;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A custom screener query (yfinance {@code QueryBase}): a value comparison on one field, or a
 * logical combination of queries of the same kind. Built with the static factories of {@link
 * EquityQuery}, {@link FundQuery} and {@link EtfQuery}, and run by {@code YFinance.screen}.
 *
 * <p>Fields and, where Yahoo restricts them, values are validated against yfinance's screener
 * tables when the query is built. Immutable and thread-safe.
 */
public abstract sealed class ScreenerQuery permits EquityQuery, FundQuery, EtfQuery {

    /** Query operators. */
    public enum Operator {
        /** All operands match; operands are queries. */
        AND("and"),
        /** Any operand matches; operands are queries. */
        OR("or"),
        /** Field equals a value. */
        EQ("eq"),
        /** Field equals any of the values; sent to Yahoo as an {@code OR} of {@code EQ}s. */
        IS_IN("is-in"),
        /** Field between two numbers (inclusive). */
        BTWN("btwn"),
        /** Field greater than a number. */
        GT("gt"),
        /** Field less than a number. */
        LT("lt"),
        /** Field greater than or equal to a number. */
        GTE("gte"),
        /** Field less than or equal to a number. */
        LTE("lte");

        private final String yfinanceName;

        Operator(String yfinanceName) {
            this.yfinanceName = yfinanceName;
        }

        /** yfinance's name of the operator, e.g. {@code is-in}. */
        public String yfinanceName() {
            return yfinanceName;
        }

        /**
         * The operator named {@code name} (yfinance names, case-insensitive), e.g. {@code
         * "is-in"}, {@code "GTE"}.
         *
         * @throws IllegalArgumentException for an unknown operator
         */
        public static Operator of(String name) {
            String n = Objects.requireNonNull(name, "name").trim().toLowerCase(Locale.ROOT);
            for (Operator op : values()) {
                if (op.yfinanceName.equals(n)) {
                    return op;
                }
            }
            throw new IllegalArgumentException("invalid screener operator: " + name);
        }

        /** Name sent to Yahoo: upper case, as yfinance. */
        String yahooName() {
            return yfinanceName.toUpperCase(Locale.ROOT);
        }
    }

    /** Validation tables and Yahoo quote type of one query kind. */
    record Spec(
            String quoteType,
            Class<? extends ScreenerQuery> type,
            Map<String, Set<String>> fields,
            Map<String, Set<String>> values) {

        boolean isField(String field) {
            for (Set<String> category : fields.values()) {
                if (category.contains(field)) {
                    return true;
                }
            }
            return false;
        }
    }

    private final Spec spec;
    private final Operator operator;
    private final List<Object> operands;

    ScreenerQuery(Spec spec, Operator operator, List<?> operands) {
        this.spec = spec;
        this.operator = Objects.requireNonNull(operator, "operator");
        Objects.requireNonNull(operands, "operands");
        if (operands.isEmpty()) {
            throw new IllegalArgumentException("operands are empty");
        }
        for (Object o : operands) {
            Objects.requireNonNull(o, "operand");
        }
        this.operands = List.copyOf(operands);
        switch (operator) {
            case AND, OR -> validateLogical();
            case EQ -> validateEq();
            case IS_IN -> validateIsIn();
            case BTWN -> validateBetween();
            case GT, LT, GTE, LTE -> validateComparison();
        }
    }

    /** The operator. */
    public Operator operator() {
        return operator;
    }

    /**
     * The operands: sub-queries for {@code AND}/{@code OR}, otherwise the field name followed by
     * the value(s). Unmodifiable.
     */
    public List<Object> operands() {
        return operands;
    }

    /** Yahoo's quote type this query screens: {@code EQUITY}, {@code MUTUALFUND} or {@code ETF}. */
    public String quoteType() {
        return spec.quoteType();
    }

    /**
     * The query as Yahoo expects it in the screener request body (yfinance {@code to_dict}):
     * {@code {"operator": "AND", "operands": [...]}}, operators in upper case, {@code IS_IN}
     * expanded to an {@code OR} of {@code EQ}s. Plain, unmodifiable maps and lists.
     */
    public Map<String, Object> toMap() {
        String op = operator.yahooName();
        List<Object> ops = new ArrayList<>(operands.size());
        if (operator == Operator.IS_IN) {
            op = Operator.OR.yahooName();
            for (Object value : operands.subList(1, operands.size())) {
                ops.add(eqMap(operands.getFirst(), value));
            }
        } else {
            for (Object o : operands) {
                ops.add(o instanceof ScreenerQuery q ? q.toMap() : o);
            }
        }
        var map = new LinkedHashMap<String, Object>();
        map.put("operator", op);
        map.put("operands", Collections.unmodifiableList(ops));
        return Collections.unmodifiableMap(map);
    }

    private static Map<String, Object> eqMap(Object field, Object value) {
        var map = new LinkedHashMap<String, Object>();
        map.put("operator", Operator.EQ.yahooName());
        map.put("operands", List.of(field, value));
        return Collections.unmodifiableMap(map);
    }

    private void validateLogical() {
        if (operands.size() < 2) {
            throw new IllegalArgumentException(operator + " needs at least 2 queries, got " + operands.size());
        }
        for (Object o : operands) {
            if (!spec.type().isInstance(o)) {
                throw new IllegalArgumentException(
                        operator + " operands must be " + spec.type().getSimpleName() + ", got " + describe(o));
            }
        }
    }

    private void validateEq() {
        if (operands.size() != 2) {
            throw new IllegalArgumentException("EQ needs a field and a value, got " + operands);
        }
        String field = field();
        checkValue(field, operands.get(1));
    }

    private void validateIsIn() {
        if (operands.size() < 2) {
            throw new IllegalArgumentException("IS_IN needs a field and at least one value, got " + operands);
        }
        String field = field();
        for (Object value : operands.subList(1, operands.size())) {
            checkValue(field, value);
        }
    }

    private void validateBetween() {
        if (operands.size() != 3) {
            throw new IllegalArgumentException("BTWN needs a field and two numbers, got " + operands);
        }
        field();
        checkNumber(operands.get(1));
        checkNumber(operands.get(2));
    }

    private void validateComparison() {
        if (operands.size() != 2) {
            throw new IllegalArgumentException(operator + " needs a field and a number, got " + operands);
        }
        field();
        checkNumber(operands.get(1));
    }

    private String field() {
        if (!(operands.getFirst() instanceof String field) || !spec.isField(field)) {
            throw new IllegalArgumentException(
                    "invalid field for " + getClass().getSimpleName() + ": " + describe(operands.getFirst()));
        }
        return field;
    }

    private void checkValue(String field, Object value) {
        if (!(value instanceof String) && !(value instanceof Number)) {
            throw new IllegalArgumentException("value must be a string or a number, got " + describe(value));
        }
        Set<String> allowed = spec.values().get(field);
        if (allowed != null && !(value instanceof String s && allowed.contains(s))) {
            throw new IllegalArgumentException("invalid value for " + field + ": " + describe(value));
        }
    }

    private void checkNumber(Object value) {
        if (!(value instanceof Number n) || !isFinite(n)) {
            throw new IllegalArgumentException(operator + " needs a finite number, got " + describe(value));
        }
    }

    private static boolean isFinite(Number n) {
        return !(n instanceof Double || n instanceof Float) || Double.isFinite(n.doubleValue());
    }

    private static String describe(Object o) {
        return o instanceof String s ? "\"" + s + "\"" : o + " (" + o.getClass().getSimpleName() + ")";
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ScreenerQuery q
                && getClass() == q.getClass()
                && operator == q.operator
                && operands.equals(q.operands);
    }

    @Override
    public int hashCode() {
        return Objects.hash(getClass(), operator, operands);
    }

    /** E.g. {@code EquityQuery(and, [EquityQuery(gt, [percentchange, 3]), ...])}. */
    @Override
    public String toString() {
        return getClass().getSimpleName() + "(" + operator.yfinanceName() + ", " + operands + ")";
    }
}
