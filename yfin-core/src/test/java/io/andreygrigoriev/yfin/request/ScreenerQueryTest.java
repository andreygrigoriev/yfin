package io.andreygrigoriev.yfin.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.andreygrigoriev.yfin.request.ScreenerQuery.Operator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ScreenerQueryTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static String json(ScreenerQuery q) throws Exception {
        return JSON.writeValueAsString(q.toMap());
    }

    @Test
    void comparisonSerialization() throws Exception {
        assertThat(json(EquityQuery.gt("percentchange", 3)))
                .isEqualTo("{\"operator\":\"GT\",\"operands\":[\"percentchange\",3]}");
        assertThat(json(EquityQuery.lt("percentchange", -2.5)))
                .isEqualTo("{\"operator\":\"LT\",\"operands\":[\"percentchange\",-2.5]}");
        assertThat(json(EquityQuery.gte("intradaymarketcap", 2_000_000_000L)))
                .isEqualTo("{\"operator\":\"GTE\",\"operands\":[\"intradaymarketcap\",2000000000]}");
        assertThat(json(EquityQuery.lte("beta", 1))).isEqualTo("{\"operator\":\"LTE\",\"operands\":[\"beta\",1]}");
        assertThat(json(EquityQuery.btwn("peratio.lasttwelvemonths", 0, 20)))
                .isEqualTo("{\"operator\":\"BTWN\",\"operands\":[\"peratio.lasttwelvemonths\",0,20]}");
        assertThat(json(EquityQuery.eq("region", "us")))
                .isEqualTo("{\"operator\":\"EQ\",\"operands\":[\"region\",\"us\"]}");
    }

    @Test
    void isInExpandsToOrOfEq() throws Exception {
        assertThat(json(EquityQuery.isIn("exchange", "NMS", "NYQ")))
                .isEqualTo("{\"operator\":\"OR\",\"operands\":["
                        + "{\"operator\":\"EQ\",\"operands\":[\"exchange\",\"NMS\"]},"
                        + "{\"operator\":\"EQ\",\"operands\":[\"exchange\",\"NYQ\"]}]}");
        assertThat(json(FundQuery.isIn("performanceratingoverall", 4)))
                .isEqualTo("{\"operator\":\"OR\",\"operands\":["
                        + "{\"operator\":\"EQ\",\"operands\":[\"performanceratingoverall\",4]}]}");
    }

    @Test
    void nestedSerialization() throws Exception {
        EquityQuery q = EquityQuery.and(
                EquityQuery.isIn("exchange", "NMS", "NYQ"),
                EquityQuery.or(
                        EquityQuery.lt("epsgrowth.lasttwelvemonths", 15), EquityQuery.eq("sector", "Technology")));

        assertThat(json(q))
                .isEqualTo("{\"operator\":\"AND\",\"operands\":["
                        + "{\"operator\":\"OR\",\"operands\":["
                        + "{\"operator\":\"EQ\",\"operands\":[\"exchange\",\"NMS\"]},"
                        + "{\"operator\":\"EQ\",\"operands\":[\"exchange\",\"NYQ\"]}]},"
                        + "{\"operator\":\"OR\",\"operands\":["
                        + "{\"operator\":\"LT\",\"operands\":[\"epsgrowth.lasttwelvemonths\",15]},"
                        + "{\"operator\":\"EQ\",\"operands\":[\"sector\",\"Technology\"]}]}]}");
        assertThat(q.operator()).isEqualTo(Operator.AND);
        assertThat(q.operands()).hasSize(2).allMatch(o -> o instanceof EquityQuery);
        assertThat(q.quoteType()).isEqualTo("EQUITY");
        assertThat(q)
                .hasToString(
                        "EquityQuery(and, [EquityQuery(is-in, [exchange, NMS, NYQ]), "
                                + "EquityQuery(or, [EquityQuery(lt, [epsgrowth.lasttwelvemonths, 15]), EquityQuery(eq, [sector, Technology])])])");
    }

    @Test
    void quoteTypes() {
        assertThat(EquityQuery.eq("region", "us").quoteType()).isEqualTo("EQUITY");
        assertThat(FundQuery.eq("exchange", "NAS").quoteType()).isEqualTo("MUTUALFUND");
        assertThat(EtfQuery.eq("region", "us").quoteType()).isEqualTo("ETF");
    }

    @Test
    void yfinanceStyleFactory() {
        EquityQuery q = EquityQuery.of(
                "and",
                List.of(
                        EquityQuery.of("IS-IN", List.of("exchange", "NMS", "NYQ")),
                        EquityQuery.of("gt", List.of("percentchange", 3))));

        assertThat(q)
                .isEqualTo(EquityQuery.and(
                        EquityQuery.isIn("exchange", "NMS", "NYQ"), EquityQuery.gt("percentchange", 3)));
        assertThat(q.hashCode())
                .isEqualTo(
                        EquityQuery.and(EquityQuery.isIn("exchange", "NMS", "NYQ"), EquityQuery.gt("percentchange", 3))
                                .hashCode());
        assertThat(FundQuery.of("eq", List.of("exchange", "NMS"))).isNotEqualTo(EquityQuery.eq("exchange", "NMS"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"foo", "", "isin", "ne"})
    void invalidOperator(String op) {
        assertThatThrownBy(() -> EquityQuery.of(op, List.of("percentchange", 3)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid screener operator");
    }

    @Test
    void invalidField() {
        assertThatThrownBy(() -> EquityQuery.gt("nosuchfield", 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid field for EquityQuery: \"nosuchfield\"");
        // equity-only field is not valid for funds
        assertThatThrownBy(() -> FundQuery.eq("sector", "Technology"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid field for FundQuery");
        assertThatThrownBy(() -> EtfQuery.gt("beta", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid field for EtfQuery");
        assertThatThrownBy(() -> EquityQuery.of("eq", List.of(3, "us")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid field");
    }

    @Test
    void invalidValue() {
        assertThatThrownBy(() -> EquityQuery.eq("region", "atlantis"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid value for region: \"atlantis\"");
        assertThatThrownBy(() -> EquityQuery.isIn("exchange", "NMS", "XXX"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid value for exchange: \"XXX\"");
        assertThatThrownBy(() -> EquityQuery.eq("sector", 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid value for sector");
        assertThatThrownBy(() -> EquityQuery.eq("percentchange", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("string or a number");
    }

    @Test
    void unrestrictedFieldsTakeAnyValue() {
        assertThat(FundQuery.eq("categoryname", "Anything").operands()).containsExactly("categoryname", "Anything");
        assertThat(EquityQuery.eq("percentchange", 3).operands()).containsExactly("percentchange", 3);
    }

    @Test
    void nestedValuesAreFlattened() {
        // exchange and industry are grouped by region / sector in yfinance; any group's value is valid
        assertThat(EquityQuery.eq("exchange", "LSE").operands()).containsExactly("exchange", "LSE");
        assertThat(EquityQuery.eq("industry", "Semiconductors").operands())
                .containsExactly("industry", "Semiconductors");
        assertThat(EquityQuery.validValues().get("region")).contains("us", "gb", "de");
        assertThat(EquityQuery.validValues().get("exchange")).contains("NMS", "NYQ", "LSE");
        assertThat(EquityQuery.validFields().get("eq_fields")).contains("region", "sector", "exchange");
        assertThat(EquityQuery.validFields().get("price")).contains("percentchange", "intradayprice", "eodprice");
        assertThat(FundQuery.validFields().get("eq_fields")).contains("categoryname", "exchange");
        assertThat(EtfQuery.validValues()).containsKeys("categoryname", "fundfamilyname", "exchange");
    }

    @Test
    void numericOperandsRequired() {
        assertThatThrownBy(() -> EquityQuery.of("gt", List.of("percentchange", "3")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("GT needs a finite number");
        assertThatThrownBy(() -> EquityQuery.of("btwn", List.of("beta", 0, "2")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BTWN needs a finite number");
        assertThatThrownBy(() -> EquityQuery.lt("beta", Double.NaN)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void wrongOperandCount() {
        assertThatThrownBy(() -> EquityQuery.of("eq", List.of("percentchange")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EQ needs a field and a value");
        assertThatThrownBy(() -> EquityQuery.of("gt", List.of("percentchange", 1, 2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("GT needs a field and a number");
        assertThatThrownBy(() -> EquityQuery.of("btwn", List.of("beta", 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BTWN needs a field and two numbers");
        assertThatThrownBy(() -> EquityQuery.isIn("exchange"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("IS_IN needs a field and at least one value");
    }

    @Test
    void logicalOperands() {
        assertThatThrownBy(() -> EquityQuery.and(EquityQuery.gt("percentchange", 3)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AND needs at least 2 queries");
        assertThatThrownBy(() -> EquityQuery.of(
                        "or", List.of(EquityQuery.gt("percentchange", 3), FundQuery.eq("exchange", "NAS"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OR operands must be EquityQuery");
        assertThatThrownBy(() -> EquityQuery.of("and", List.of("percentchange", 3)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EquityQuery.of("and", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void toMapIsUnmodifiable() {
        Map<String, Object> map = EquityQuery.gt("percentchange", 3).toMap();
        assertThatThrownBy(() -> map.put("x", 1)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> EquityQuery.gt("percentchange", 3).operands().add(1))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void operatorNames() {
        assertThat(Operator.of(" Is-In ")).isEqualTo(Operator.IS_IN);
        assertThat(Operator.IS_IN.yfinanceName()).isEqualTo("is-in");
        assertThat(Operator.GTE.yahooName()).isEqualTo("GTE");
    }

    @Test
    void sortField() {
        assertThat(SortField.DEFAULT).isEqualTo(new SortField("ticker", false));
        assertThat(SortField.ascending("percentchange").sortType()).isEqualTo("ASC");
        assertThat(SortField.descending("percentchange").sortType()).isEqualTo("DESC");
        assertThatThrownBy(() -> SortField.ascending(" ")).isInstanceOf(IllegalArgumentException.class);
    }
}
