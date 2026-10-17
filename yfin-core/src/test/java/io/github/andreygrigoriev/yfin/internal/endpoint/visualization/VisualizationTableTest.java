package io.github.andreygrigoriev.yfin.internal.endpoint.visualization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.andreygrigoriev.yfin.exception.YFDataException;
import io.github.andreygrigoriev.yfin.internal.http.YahooJson;
import io.github.andreygrigoriev.yfin.testing.FakeTransport;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class VisualizationTableTest {

    private static final ZoneId NY = ZoneId.of("America/New_York");

    @Test
    void cellsAreReadByColumnId() {
        VisualizationTable table = table("""
                [{"id":"share_worth"},{"id":"ticker"},{"id":"optionable"},{"id":"startdatetime"}]""", """
                [[2,"ABC",true,"2026-10-15T04:00:00.000Z"]]""");
        JsonNode row = table.rows().getFirst();

        assertThat(table.isEmpty()).isFalse();
        assertThat(table.columnIndex("ticker")).isEqualTo(1);
        assertThat(table.hasColumn("ticker")).isTrue();
        assertThat(table.text(row, "ticker")).isEqualTo("ABC");
        assertThat(table.number(row, "share_worth")).isEqualTo(2.0);
        assertThat(table.bool(row, "optionable")).isTrue();
        assertThat(table.instant(row, "startdatetime")).isEqualTo(Instant.parse("2026-10-15T04:00:00Z"));
        assertThat(table.localDate(row, "startdatetime", NY)).isEqualTo(LocalDate.of(2026, 10, 15));
    }

    @Test
    void duplicateColumnIdKeepsTheFirst() {
        VisualizationTable table = table("""
                [{"id":"ticker"},{"id":"ticker"}]""", """
                [["FIRST","SECOND"]]""");

        assertThat(table.columnIndex("ticker")).isZero();
        assertThat(table.text(table.rows().getFirst(), "ticker")).isEqualTo("FIRST");
    }

    @Test
    void missingNullAndEmptyCells() {
        VisualizationTable table = table("""
                [{"id":"name"},{"id":"price"},{"id":"date"},{"id":"flag"}]""", """
                [[null,null,null,null],["",{},"",1],["x"]]""");

        assertThat(table.columnIndex("nope")).isEqualTo(-1);
        assertThat(table.hasColumn("nope")).isFalse();
        for (JsonNode row : table.rows()) {
            assertThat(table.number(row, "price")).isNaN();
            assertThat(table.number(row, "nope")).isNaN();
            assertThat(table.text(row, "nope")).isNull();
            assertThat(table.instant(row, "date")).isNull();
            assertThat(table.localDate(row, "date", NY)).isNull();
            assertThat(table.bool(row, "flag")).isNull();
        }
        assertThat(table.text(table.rows().get(0), "name")).isNull();
        assertThat(table.text(table.rows().get(1), "name")).isNull();
        assertThat(table.text(table.rows().get(2), "name")).isEqualTo("x");
    }

    @Test
    void numbersMayBeStrings() {
        VisualizationTable table = table("""
                [{"id":"v"}]""", """
                [["1.8"],["-1111.9"],[" 3 "],["n/a"],[7]]""");

        assertThat(table.rows())
                .extracting(row -> table.number(row, "v"))
                .containsExactly(1.8, -1111.9, 3.0, Double.NaN, 7.0);
    }

    @Test
    void zeroAsNaN() {
        VisualizationTable table = table("""
                [{"id":"v"}]""", """
                [[0],[0.0],["0"],["0.0"],[-0.0],[0.5],[null]]""");

        assertThat(table.rows())
                .extracting(row -> table.zeroAsNaN(row, "v"))
                .containsExactly(Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0.5, Double.NaN);
        // number() keeps genuine zeros
        assertThat(table.number(table.rows().getFirst(), "v")).isZero();
    }

    @Test
    void badDateThrows() {
        VisualizationTable table = table("""
                [{"id":"startdatetime"}]""", """
                [["soon"]]""");
        JsonNode row = table.rows().getFirst();

        assertThatThrownBy(() -> table.instant(row, "startdatetime"))
                .isInstanceOf(YFDataException.class)
                .hasMessageContaining("startdatetime")
                .hasMessageContaining("soon");
        assertThatThrownBy(() -> table.localDate(row, "startdatetime", NY)).isInstanceOf(YFDataException.class);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"finance\":{\"result\":[],\"error\":null}}",
                "{\"finance\":{\"result\":[{\"documents\":[]}]}}",
                "{\"finance\":{\"result\":[{\"documents\":[{\"columns\":[{\"id\":\"a\"}],\"rows\":[]}]}]}}",
                "{\"finance\":{\"result\":[{\"documents\":[{\"columns\":[{\"id\":\"a\"}],\"rows\":null}]}]}}"
            })
    void noRowsGiveEmptyTable(String json) {
        VisualizationTable table = VisualizationTable.of(readJson(json));

        assertThat(table.isEmpty()).isTrue();
        assertThat(table.rows()).isEmpty();
    }

    @Test
    void missingResultListThrows() {
        assertThatThrownBy(() -> VisualizationTable.of(readJson("{\"finance\":{\"result\":null,\"error\":null}}")))
                .isInstanceOf(YFDataException.class)
                .hasMessageContaining("no result list");
    }

    @Test
    void nonArrayRowsThrow() {
        assertThatThrownBy(() -> VisualizationTable.of(
                        readJson("{\"finance\":{\"result\":[{\"documents\":[{\"rows\":{\"a\":1}}]}]}}")))
                .isInstanceOf(YFDataException.class)
                .hasMessageContaining("not a list");
    }

    @Test
    void readsRecordedFixture() throws Exception {
        // from the file system, like FakeTransport: the native test image has no fixture resources
        JsonNode root = YahooJson.mapper()
                .readTree(FakeTransport.DEFAULT_FIXTURE_ROOT
                        .resolve("calendar/economic-week.json")
                        .toFile());
        VisualizationTable table = VisualizationTable.of(root);

        assertThat(table.isEmpty()).isFalse();
        assertThat(table.columnIndex("econ_release")).isZero();
        assertThat(table.rows())
                .allSatisfy(
                        row -> assertThat(table.instant(row, "startdatetime")).isNotNull());
    }

    private static VisualizationTable table(String columns, String rows) {
        return VisualizationTable.of(readJson("{\"finance\":{\"result\":[{\"documents\":[{\"columns\":" + columns
                + ",\"rows\":" + rows + "}]}],\"error\":null}}"));
    }

    private static JsonNode readJson(String json) {
        try {
            return YahooJson.mapper().readTree(json);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
