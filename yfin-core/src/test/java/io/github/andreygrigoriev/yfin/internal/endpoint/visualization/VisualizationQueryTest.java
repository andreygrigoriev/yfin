package io.github.andreygrigoriev.yfin.internal.endpoint.visualization;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;

class VisualizationQueryTest {

    @Test
    void comparisonsPutFieldFirst() {
        VisualizationQuery q = VisualizationQuery.lowerCase();

        assertThat(q.eq("region", "us").toString()).isEqualTo("{\"operator\":\"eq\",\"operands\":[\"region\",\"us\"]}");
        assertThat(q.gte("startdatetime", "2026-10-14").toString())
                .isEqualTo("{\"operator\":\"gte\",\"operands\":[\"startdatetime\",\"2026-10-14\"]}");
        assertThat(q.lte("startdatetime", "2026-10-21").toString())
                .isEqualTo("{\"operator\":\"lte\",\"operands\":[\"startdatetime\",\"2026-10-21\"]}");
        assertThat(q.gtelt("filingdate", "2026-10-14", "2026-10-21").toString())
                .isEqualTo("{\"operator\":\"gtelt\",\"operands\":[\"filingdate\",\"2026-10-14\",\"2026-10-21\"]}");
    }

    @Test
    void numericOperandStaysNumeric() {
        ObjectNode node = VisualizationQuery.upperCase().gte("intradaymarketcap", 1.0e9);

        assertThat(node.toString()).isEqualTo("{\"operator\":\"GTE\",\"operands\":[\"intradaymarketcap\",1.0E9]}");
        assertThat(node.path("operands").get(1).isNumber()).isTrue();
    }

    @Test
    void upperCaseAppliesToEveryLevel() {
        VisualizationQuery q = VisualizationQuery.upperCase();

        ObjectNode tree = q.and(
                q.eq("region", "us"),
                q.or(q.eq("eventtype", "EAD"), q.eq("eventtype", "ERA")),
                q.gtelt("startdatetime", "a", "b"),
                q.lte("startdatetime", "b"));

        assertThat(tree.toString())
                .isEqualTo("{\"operator\":\"AND\",\"operands\":["
                        + "{\"operator\":\"EQ\",\"operands\":[\"region\",\"us\"]},"
                        + "{\"operator\":\"OR\",\"operands\":[{\"operator\":\"EQ\",\"operands\":[\"eventtype\",\"EAD\"]},"
                        + "{\"operator\":\"EQ\",\"operands\":[\"eventtype\",\"ERA\"]}]},"
                        + "{\"operator\":\"GTELT\",\"operands\":[\"startdatetime\",\"a\",\"b\"]},"
                        + "{\"operator\":\"LTE\",\"operands\":[\"startdatetime\",\"b\"]}]}");
    }

    @Test
    void listOverloadsKeepOrder() {
        VisualizationQuery q = VisualizationQuery.lowerCase();
        List<ObjectNode> tickers = List.of(q.eq("ticker", "B"), q.eq("ticker", "A"));

        assertThat(q.or(tickers).path("operands").get(0).path("operands").get(1).asText())
                .isEqualTo("B");
        assertThat(q.and(List.of()).toString()).isEqualTo("{\"operator\":\"and\",\"operands\":[]}");
    }
}
