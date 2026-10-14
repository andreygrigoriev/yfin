package io.andreygrigoriev.yfin.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CalendarRequestTest {

    private static final LocalDate MON = LocalDate.of(2026, 10, 19);
    private static final LocalDate FRI = LocalDate.of(2026, 10, 23);

    @Test
    void betweenDefaults() {
        CalendarRequest r = CalendarRequest.between(MON, FRI);

        assertThat(r.start()).isEqualTo(MON);
        assertThat(r.end()).isEqualTo(FRI);
        assertThat(r.limit()).isEqualTo(12);
        assertThat(r.offset()).isZero();
    }

    @Test
    void singleDayWindowIsAllowed() {
        assertThat(CalendarRequest.between(MON, MON).end()).isEqualTo(MON);
        assertThat(EarningsCalendarRequest.between(MON, MON).end()).isEqualTo(MON);
    }

    @Test
    void fromAddsSevenDays() {
        assertThat(CalendarRequest.from(MON).end()).isEqualTo(LocalDate.of(2026, 10, 26));
        assertThat(EarningsCalendarRequest.from(MON).end()).isEqualTo(LocalDate.of(2026, 10, 26));
    }

    @Test
    void nextWeekStartsTodayInTheClockZone() {
        // 2026-10-14T02:00Z is still Oct 13 in New York
        Clock clock = Clock.fixed(Instant.parse("2026-10-14T02:00:00Z"), ZoneId.of("America/New_York"));

        CalendarRequest r = CalendarRequest.nextWeek(clock);
        EarningsCalendarRequest e = EarningsCalendarRequest.nextWeek(clock);

        assertThat(r.start()).isEqualTo(LocalDate.of(2026, 10, 13));
        assertThat(r.end()).isEqualTo(LocalDate.of(2026, 10, 20));
        assertThat(e.start()).isEqualTo(r.start());
        assertThat(e.end()).isEqualTo(r.end());
    }

    @Test
    void nextWeekUsesTheSystemClock() {
        LocalDate before = LocalDate.now();
        LocalDate start = CalendarRequest.nextWeek().start();

        assertThat(start).isBetween(before, LocalDate.now());
    }

    @Test
    void endBeforeStartIsRejected() {
        assertThatThrownBy(() -> CalendarRequest.between(FRI, MON))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("before start");
        assertThatThrownBy(() -> EarningsCalendarRequest.between(FRI, MON))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullDatesAreRejected() {
        assertThatThrownBy(() -> CalendarRequest.between(null, FRI)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> CalendarRequest.between(MON, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> CalendarRequest.from(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> EarningsCalendarRequest.from(null)).isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 101})
    void limitOutOfRangeIsRejected(int limit) {
        CalendarRequest r = CalendarRequest.from(MON);
        EarningsCalendarRequest e = EarningsCalendarRequest.from(MON);

        assertThatThrownBy(() -> r.limit(limit)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> e.limit(limit)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void limitBoundsAreAccepted() {
        assertThat(CalendarRequest.from(MON).limit(1).limit()).isEqualTo(1);
        assertThat(CalendarRequest.from(MON).limit(100).limit()).isEqualTo(100);
        assertThat(EarningsCalendarRequest.from(MON).limit(100).limit()).isEqualTo(100);
    }

    @Test
    void negativeOffsetIsRejected() {
        assertThatThrownBy(() -> CalendarRequest.from(MON).offset(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EarningsCalendarRequest.from(MON).offset(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(doubles = {0, -1, Double.NaN, Double.POSITIVE_INFINITY})
    void nonPositiveMarketCapIsRejected(double cap) {
        EarningsCalendarRequest e = EarningsCalendarRequest.from(MON);

        assertThatThrownBy(() -> e.marketCap(cap)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void settersCopy() {
        CalendarRequest base = CalendarRequest.between(MON, FRI);
        CalendarRequest paged = base.limit(50).offset(100);

        assertThat(base.limit()).isEqualTo(12);
        assertThat(base.offset()).isZero();
        assertThat(paged.limit()).isEqualTo(50);
        assertThat(paged.offset()).isEqualTo(100);
        assertThat(paged.start()).isEqualTo(MON);
        assertThat(paged.end()).isEqualTo(FRI);
    }

    @Test
    void earningsDefaultsAndSettersCopy() {
        EarningsCalendarRequest base = EarningsCalendarRequest.between(MON, FRI);
        EarningsCalendarRequest custom =
                base.marketCap(1e9).filterMostActive(false).limit(50).offset(5);

        assertThat(base.limit()).isEqualTo(12);
        assertThat(base.offset()).isZero();
        assertThat(base.marketCap()).isEmpty();
        assertThat(base.filterMostActive()).isTrue();
        assertThat(custom.start()).isEqualTo(MON);
        assertThat(custom.end()).isEqualTo(FRI);
        assertThat(custom.limit()).isEqualTo(50);
        assertThat(custom.offset()).isEqualTo(5);
        assertThat(custom.marketCap()).hasValue(1e9);
        assertThat(custom.filterMostActive()).isFalse();
    }

    @Test
    void equality() {
        CalendarRequest a = CalendarRequest.between(MON, FRI).limit(20);

        assertThat(a).isEqualTo(CalendarRequest.between(MON, FRI).limit(20));
        assertThat(a).hasSameHashCodeAs(CalendarRequest.between(MON, FRI).limit(20));
        assertThat(a).isNotEqualTo(CalendarRequest.between(MON, FRI));
        assertThat(a).isNotEqualTo(a.offset(1));
        assertThat(a).isNotEqualTo(CalendarRequest.between(MON, MON).limit(20));
        assertThat(a.toString()).isEqualTo("CalendarRequest[start=2026-10-19, end=2026-10-23, limit=20, offset=0]");
    }

    @Test
    void earningsEquality() {
        EarningsCalendarRequest a = EarningsCalendarRequest.between(MON, FRI).marketCap(1e9);

        assertThat(a).isEqualTo(EarningsCalendarRequest.between(MON, FRI).marketCap(1e9));
        assertThat(a)
                .hasSameHashCodeAs(EarningsCalendarRequest.between(MON, FRI).marketCap(1e9));
        assertThat(a).isNotEqualTo(EarningsCalendarRequest.between(MON, FRI));
        assertThat(a).isNotEqualTo(a.filterMostActive(false));
        assertThat(a).isNotEqualTo(a.limit(13));
        assertThat(a.toString())
                .isEqualTo("EarningsCalendarRequest[start=2026-10-19, end=2026-10-23, limit=12, offset=0,"
                        + " marketCap=1.0E9, filterMostActive=true]");
    }
}
