package io.andreygrigoriev.yfin.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.andreygrigoriev.yfin.YFinance;
import io.andreygrigoriev.yfin.model.EarningsEvent;
import io.andreygrigoriev.yfin.model.EconomicEvent;
import io.andreygrigoriev.yfin.model.IpoEvent;
import io.andreygrigoriev.yfin.model.SplitEvent;
import io.andreygrigoriev.yfin.request.CalendarRequest;
import io.andreygrigoriev.yfin.request.EarningsCalendarRequest;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Shape checks only: calendar contents vary by week, and IPO or splits weeks can be empty. */
class CalendarsIT {

    private static final LocalDate START = LocalDate.now();
    private static final LocalDate END = START.plusDays(14);

    private static YFinance yf;

    @BeforeAll
    static void setUp() {
        yf = LiveClients.create();
    }

    @AfterAll
    static void tearDown() {
        yf.close();
    }

    @Test
    void earningsCalendarIsSortedByStartTimeDescending() {
        List<EarningsEvent> events = yf.earningsCalendar(EarningsCalendarRequest.between(START, END)
                .filterMostActive(false)
                .limit(50));

        assertThat(events).isNotEmpty().hasSizeLessThanOrEqualTo(50);
        assertThat(events).allSatisfy(e -> {
            assertThat(e.symbol()).isNotBlank();
            assertThat(e.startTime().getZone().getId()).isEqualTo("America/New_York");
        });
        assertThat(events)
                .extracting(EarningsEvent::startTime)
                .isSortedAccordingTo(
                        Comparator.comparing(ZonedDateTime::toInstant).reversed());
    }

    @Test
    void earningsCalendarWithMostActiveFilter() {
        List<EarningsEvent> events = yf.earningsCalendar(EarningsCalendarRequest.between(START, END));

        assertThat(events).hasSizeLessThanOrEqualTo(CalendarRequest.DEFAULT_LIMIT);
        assertThat(events).allSatisfy(e -> assertThat(e.symbol()).isNotBlank());
    }

    @Test
    void ipoCalendar() {
        List<IpoEvent> events =
                yf.ipoCalendar(CalendarRequest.between(START, END).limit(100));

        assertThat(events).hasSizeLessThanOrEqualTo(100);
        assertThat(events).allSatisfy(e -> assertThat(e.symbol()).isNotBlank());
    }

    @Test
    void economicEventsCalendar() {
        List<EconomicEvent> events = yf.economicEventsCalendar(CalendarRequest.between(START, END));

        assertThat(events).isNotEmpty().hasSizeLessThanOrEqualTo(CalendarRequest.DEFAULT_LIMIT);
        assertThat(events).allSatisfy(e -> {
            assertThat(e.event()).isNotBlank();
            assertThat(e.time()).isNotNull();
        });
    }

    @Test
    void splitsCalendar() {
        List<SplitEvent> events = yf.splitsCalendar(CalendarRequest.between(START, END));

        assertThat(events).allSatisfy(e -> {
            assertThat(e.symbol()).isNotBlank();
            assertThat(e.payableDate()).isNotNull();
        });
    }

    @Test
    void limitCapsTheRowCount() {
        assertThat(yf.economicEventsCalendar(CalendarRequest.between(START, END).limit(5)))
                .hasSizeLessThanOrEqualTo(5);
        assertThat(yf.earningsCalendar(EarningsCalendarRequest.between(START, END)
                        .filterMostActive(false)
                        .limit(5)))
                .hasSizeLessThanOrEqualTo(5);
    }
}
