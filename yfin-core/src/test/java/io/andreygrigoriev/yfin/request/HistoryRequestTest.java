package io.andreygrigoriev.yfin.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class HistoryRequestTest {

    private static final Instant NOW = Instant.parse("2026-10-12T15:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 12);

    @Test
    void defaults() {
        HistoryRequest r = HistoryRequest.builder().build();

        assertThat(r.period()).contains(Period.ONE_MONTH);
        assertThat(r.start()).isEmpty();
        assertThat(r.end()).isEmpty();
        assertThat(r.interval()).isEqualTo(Interval.ONE_DAY);
        assertThat(r.prepost()).isFalse();
        assertThat(r.autoAdjust()).isTrue();
        assertThat(r.backAdjust()).isFalse();
        assertThat(r.actions()).isTrue();
        assertThat(r.repair()).isEmpty();
        assertThat(r.keepNa()).isFalse();
    }

    @Test
    void startAndEndWithoutPeriod() {
        HistoryRequest r = HistoryRequest.builder()
                .start(LocalDate.of(2024, 1, 1))
                .end(Instant.parse("2024-06-01T00:00:00Z"))
                .build();

        assertThat(r.period()).isEmpty();
        assertThat(r.start()).contains(LocalDate.of(2024, 1, 1));
        assertThat(r.end()).contains(Instant.parse("2024-06-01T00:00:00Z"));
    }

    @Test
    void periodAnchoredAtStart() {
        HistoryRequest r = HistoryRequest.builder()
                .period(Period.ONE_YEAR)
                .start(LocalDate.of(2020, 1, 1))
                .build();

        assertThat(r.period()).contains(Period.ONE_YEAR);
        assertThat(r.start()).isPresent();
    }

    @Test
    void settingsRoundTripThroughToBuilder() {
        HistoryRequest r = HistoryRequest.builder()
                .period(Period.FIVE_DAYS)
                .interval(Interval.FIVE_MINUTES)
                .prepost(true)
                .autoAdjust(false)
                .backAdjust(true)
                .actions(false)
                .repair(true)
                .keepNa(true)
                .build();

        assertThat(r.toBuilder().build()).isEqualTo(r).hasSameHashCodeAs(r);
        assertThat(r.withInterval(Interval.ONE_HOUR).interval()).isEqualTo(Interval.ONE_HOUR);
        assertThat(r.repair()).contains(true);
        assertThat(r.toString()).contains("interval=5m", "period=5d");
        assertThat(HistoryRequest.of(Period.MAX, Interval.ONE_WEEK).interval()).isEqualTo(Interval.ONE_WEEK);
    }

    @Test
    void periodStartAndEndTogetherRejected() {
        var b = HistoryRequest.builder()
                .period(Period.ONE_MONTH)
                .start(LocalDate.of(2024, 1, 1))
                .end(LocalDate.of(2024, 2, 1));

        assertThatThrownBy(b::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most two");
    }

    @ParameterizedTest
    @EnumSource(
            value = Period.class,
            names = {"YTD", "MAX"})
    void openEndedPeriodsCannotBeAnchored(Period period) {
        var b = HistoryRequest.builder().period(period).start(LocalDate.of(2024, 1, 1));

        assertThatThrownBy(b::build).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void startMustBeBeforeEnd() {
        var b = HistoryRequest.builder().start(LocalDate.of(2024, 2, 1)).end(LocalDate.of(2024, 2, 1));

        assertThatThrownBy(b::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not before");
    }

    @Test
    void oneMinuteLimitedToEightDaysPerRequest() {
        var tooLong = HistoryRequest.builder().period(Period.ONE_MONTH).interval(Interval.ONE_MINUTE);
        var tooLongRange = HistoryRequest.builder()
                .interval(Interval.ONE_MINUTE)
                .start(TODAY.minusDays(20))
                .end(TODAY.minusDays(5));

        assertThatThrownBy(() -> tooLong.build(CLOCK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("8 days per request");
        assertThatThrownBy(() -> tooLongRange.build(CLOCK)).isInstanceOf(IllegalArgumentException.class);
        assertThat(HistoryRequest.builder()
                        .period(Period.FIVE_DAYS)
                        .interval(Interval.ONE_MINUTE)
                        .build(CLOCK)
                        .interval())
                .isEqualTo(Interval.ONE_MINUTE);
        assertThat(HistoryRequest.builder()
                        .interval(Interval.ONE_MINUTE)
                        .start(TODAY.minusDays(7))
                        .build(CLOCK))
                .isNotNull();
    }

    @Test
    void oneMinuteLimitedToThirtyDaysBack() {
        var b = HistoryRequest.builder()
                .interval(Interval.ONE_MINUTE)
                .start(TODAY.minusDays(40))
                .end(TODAY.minusDays(35));

        assertThatThrownBy(() -> b.build(CLOCK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("last 30 days");
    }

    @Test
    void minuteIntervalsLimitedToSixtyDaysBack() {
        var period = HistoryRequest.builder().period(Period.THREE_MONTHS).interval(Interval.FIVE_MINUTES);
        var start = HistoryRequest.builder().interval(Interval.FIFTEEN_MINUTES).start(LocalDate.of(2020, 1, 1));

        assertThatThrownBy(() -> period.build(CLOCK)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> start.build(CLOCK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("last 60 days");
        assertThat(HistoryRequest.builder()
                        .period(Period.ONE_MONTH)
                        .interval(Interval.NINETY_MINUTES)
                        .build(CLOCK))
                .isNotNull();
    }

    @Test
    void hourlyLimitedTo730DaysBack() {
        var b = HistoryRequest.builder().period(Period.FIVE_YEARS).interval(Interval.ONE_HOUR);

        assertThatThrownBy(() -> b.build(CLOCK)).isInstanceOf(IllegalArgumentException.class);
        assertThat(HistoryRequest.builder()
                        .period(Period.TWO_YEARS)
                        .interval(Interval.SIXTY_MINUTES)
                        .build(CLOCK))
                .isNotNull();
    }

    @Test
    void maxPeriodAllowedForIntraday() {
        assertThat(HistoryRequest.builder()
                        .period(Period.MAX)
                        .interval(Interval.ONE_MINUTE)
                        .build(CLOCK))
                .isNotNull();
    }

    @Test
    void dailyIntervalsUnlimited() {
        assertThat(HistoryRequest.builder()
                        .start(LocalDate.of(1980, 1, 1))
                        .interval(Interval.ONE_DAY)
                        .build(CLOCK))
                .isNotNull();
    }

    @Test
    void intervalCodes() {
        assertThat(Interval.fromCode("1M")).isEqualTo(Interval.ONE_MINUTE);
        assertThat(Interval.fromCode("1mo")).isEqualTo(Interval.ONE_MONTH);
        assertThat(Interval.fromCode(" 1wk ")).isEqualTo(Interval.ONE_WEEK);
        assertThat(Interval.ONE_HOUR.isIntraday()).isTrue();
        assertThat(Interval.NINETY_MINUTES.isIntraday()).isTrue();
        assertThat(Interval.ONE_DAY.isIntraday()).isFalse();
        assertThat(Interval.THREE_MONTHS.isIntraday()).isFalse();
        assertThat(Interval.ONE_MINUTE.maxSpan()).contains(Duration.ofDays(8));
        assertThat(Interval.ONE_DAY.maxLookback()).isEmpty();
        assertThat(Interval.FIFTEEN_MINUTES).hasToString("15m");
        assertThatThrownBy(() -> Interval.fromCode("7m")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void periodCodes() {
        assertThat(Period.fromCode("YTD")).isEqualTo(Period.YTD);
        assertThat(Period.fromCode("10y")).isEqualTo(Period.TEN_YEARS);
        assertThat(Period.ONE_MONTH.length()).contains(java.time.Period.ofMonths(1));
        assertThat(Period.MAX.length()).isEmpty();
        assertThat(Period.SIX_MONTHS).hasToString("6mo");
        assertThatThrownBy(() -> Period.fromCode("3y")).isInstanceOf(IllegalArgumentException.class);
    }
}
