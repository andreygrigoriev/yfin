package io.github.andreygrigoriev.yfin.internal.endpoint.visualization;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.andreygrigoriev.yfin.internal.http.YahooJson;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Builds the {@code query} operator tree of a visualization request: nodes of the form {@code
 * {"operator": "and", "operands": [...]}}. A comparison has the field name as its first operand.
 *
 * <p>Yahoo accepts operators in either case. Earnings dates send them lower case, the calendars
 * upper case like yfinance's {@code CalendarQuery}.
 */
public final class VisualizationQuery {

    private static final VisualizationQuery LOWER = new VisualizationQuery(false);
    private static final VisualizationQuery UPPER = new VisualizationQuery(true);

    private final boolean upperCase;

    private VisualizationQuery(boolean upperCase) {
        this.upperCase = upperCase;
    }

    /** A builder writing lower-case operators ({@code and}, {@code eq}). */
    public static VisualizationQuery lowerCase() {
        return LOWER;
    }

    /** A builder writing upper-case operators ({@code AND}, {@code EQ}). */
    public static VisualizationQuery upperCase() {
        return UPPER;
    }

    /** All {@code operands} must match. */
    public ObjectNode and(ObjectNode... operands) {
        return and(List.of(operands));
    }

    /** All {@code operands} must match. */
    public ObjectNode and(List<ObjectNode> operands) {
        return combine("and", operands);
    }

    /** At least one of {@code operands} must match. */
    public ObjectNode or(ObjectNode... operands) {
        return or(List.of(operands));
    }

    /** At least one of {@code operands} must match. */
    public ObjectNode or(List<ObjectNode> operands) {
        return combine("or", operands);
    }

    /** {@code field} equals {@code value}. */
    public ObjectNode eq(String field, String value) {
        return comparison("eq", field, Objects.requireNonNull(value, "value"));
    }

    /** {@code field} is at least {@code value}. */
    public ObjectNode gte(String field, String value) {
        return comparison("gte", field, Objects.requireNonNull(value, "value"));
    }

    /** {@code field} is at least {@code value}. */
    public ObjectNode gte(String field, double value) {
        ObjectNode node = comparison("gte", field);
        operands(node).add(value);
        return node;
    }

    /** {@code field} is at most {@code value}. */
    public ObjectNode lte(String field, String value) {
        return comparison("lte", field, Objects.requireNonNull(value, "value"));
    }

    /** {@code field} is between {@code from} and {@code to}: Yahoo's {@code gtelt}. */
    public ObjectNode gtelt(String field, String from, String to) {
        return comparison("gtelt", field, Objects.requireNonNull(from, "from"), Objects.requireNonNull(to, "to"));
    }

    private ObjectNode combine(String operator, List<ObjectNode> operands) {
        ObjectNode node = YahooJson.mapper().createObjectNode();
        node.put("operator", operator(operator));
        operands.forEach(node.putArray("operands")::add);
        return node;
    }

    private ObjectNode comparison(String operator, String field, String... values) {
        ObjectNode node = YahooJson.mapper().createObjectNode();
        node.put("operator", operator(operator));
        ArrayNode operands = node.putArray("operands").add(Objects.requireNonNull(field, "field"));
        for (String value : values) {
            operands.add(value);
        }
        return node;
    }

    private static ArrayNode operands(ObjectNode node) {
        return (ArrayNode) node.get("operands");
    }

    private String operator(String operator) {
        return upperCase ? operator.toUpperCase(Locale.ROOT) : operator;
    }
}
