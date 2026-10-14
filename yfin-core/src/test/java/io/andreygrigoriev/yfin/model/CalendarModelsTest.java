package io.andreygrigoriev.yfin.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class CalendarModelsTest {

    private static final ZonedDateTime TIME =
            ZonedDateTime.of(2026, 10, 15, 8, 30, 0, 0, ZoneId.of("America/New_York"));
    private static final LocalDate DAY = LocalDate.of(2026, 10, 15);

    private static EarningsEvent earnings(double reportedEps) {
        return new EarningsEvent("AAPL", "Apple Inc.", 3e12, null, TIME, "AMC", 1.5, reportedEps, Double.NaN);
    }

    private static SplitEvent split(double oldWorth, double newWorth) {
        return new SplitEvent("XYZ", null, DAY, true, oldWorth, newWorth);
    }

    @Test
    void earningsIsReportedOnlyWithReportedEps() {
        assertThat(earnings(1.6).isReported()).isTrue();
        assertThat(earnings(0).isReported()).isTrue();
        assertThat(earnings(Double.NaN).isReported()).isFalse();
    }

    @Test
    void splitRatio() {
        assertThat(split(1, 4).ratio()).isEqualTo(4);
        assertThat(split(10, 1).ratio()).isEqualTo(0.1);
        // share_worth 0 appears in old Yahoo data; the ratio is then 0, not NaN
        assertThat(split(1, 0).ratio()).isZero();
    }

    @Test
    void splitRatioIsNaNWhenUnknown() {
        assertThat(split(Double.NaN, 4).ratio()).isNaN();
        assertThat(split(1, Double.NaN).ratio()).isNaN();
        assertThat(split(0, 4).ratio()).isNaN();
    }

    @Test
    void nullableFieldsMayBeNull() {
        assertThat(new IpoEvent(
                                "NEW",
                                null,
                                null,
                                null,
                                null,
                                null,
                                Double.NaN,
                                Double.NaN,
                                Double.NaN,
                                null,
                                Double.NaN,
                                null)
                        .symbol())
                .isEqualTo("NEW");
        assertThat(new EconomicEvent("CPI", null, Instant.EPOCH, null, 1, 2, 3, 4).event())
                .isEqualTo("CPI");
        assertThat(new SplitEvent("XYZ", null, DAY, null, 1, 2).optionable()).isNull();
    }

    @Test
    void requiredFieldsAreChecked() {
        assertThatThrownBy(() -> new EarningsEvent(null, null, 0, null, TIME, null, 0, 0, 0))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("symbol");
        assertThatThrownBy(() -> new EarningsEvent("A", null, 0, null, null, null, 0, 0, 0))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("startTime");
        assertThatThrownBy(() -> new IpoEvent(null, null, null, null, null, null, 0, 0, 0, null, 0, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("symbol");
        assertThatThrownBy(() -> new EconomicEvent(null, null, Instant.EPOCH, null, 0, 0, 0, 0))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("event");
        assertThatThrownBy(() -> new EconomicEvent("CPI", null, null, null, 0, 0, 0, 0))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("time");
        assertThatThrownBy(() -> new SplitEvent(null, null, DAY, null, 1, 2))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("symbol");
        assertThatThrownBy(() -> new SplitEvent("XYZ", null, null, null, 1, 2))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("payableDate");
    }
}
