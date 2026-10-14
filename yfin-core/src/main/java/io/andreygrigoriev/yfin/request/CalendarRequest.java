package io.andreygrigoriev.yfin.request;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Window and paging of a market-wide IPO, economic-events or splits calendar (yfinance {@code
 * Calendars(start, end)} with {@code limit} and {@code offset}). Immutable: every setter returns a
 * copy.
 *
 * <pre>{@code
 * List<IpoEvent> ipos = yf.ipoCalendar(CalendarRequest.from(LocalDate.now()).limit(100));
 * List<SplitEvent> splits = yf.splitsCalendar(CalendarRequest.between(a, b).offset(12));
 * }</pre>
 *
 * <p>Both dates are inclusive. Defaults follow yfinance: a 7-day window, 12 rows, offset 0. Unlike
 * yfinance, the window is always complete and {@code limit} is validated instead of capped at 100.
 */
public final class CalendarRequest {

    /** Default number of rows, as in yfinance. */
    public static final int DEFAULT_LIMIT = 12;

    /** Largest number of rows Yahoo returns per request. */
    public static final int MAX_LIMIT = 100;

    /** Default window length in days, as in yfinance ({@code end = start + 7 days}). */
    public static final int DEFAULT_DAYS = 7;

    private final LocalDate start;
    private final LocalDate end;
    private final int limit;
    private final int offset;

    private CalendarRequest(LocalDate start, LocalDate end, int limit, int offset) {
        this.start = start;
        this.end = end;
        this.limit = limit;
        this.offset = offset;
    }

    /**
     * Calendar from {@code start} to {@code end}, both inclusive.
     *
     * @throws IllegalArgumentException when {@code end} is before {@code start}
     */
    public static CalendarRequest between(LocalDate start, LocalDate end) {
        return new CalendarRequest(start, checkWindow(start, end), DEFAULT_LIMIT, 0);
    }

    /** Calendar from {@code start} to {@code start + 7 days}. */
    public static CalendarRequest from(LocalDate start) {
        Objects.requireNonNull(start, "start");
        return new CalendarRequest(start, start.plusDays(DEFAULT_DAYS), DEFAULT_LIMIT, 0);
    }

    /** Calendar from today (system default timezone) to today + 7 days, the yfinance default. */
    public static CalendarRequest nextWeek() {
        return nextWeek(Clock.systemDefaultZone());
    }

    static CalendarRequest nextWeek(Clock clock) {
        return from(LocalDate.now(clock));
    }

    /**
     * Maximum number of rows.
     *
     * @throws IllegalArgumentException when {@code limit} is not in {@code 1..100}
     */
    public CalendarRequest limit(int limit) {
        return new CalendarRequest(start, end, checkLimit(limit), offset);
    }

    /**
     * Number of rows to skip, for paging.
     *
     * @throws IllegalArgumentException when {@code offset} is negative
     */
    public CalendarRequest offset(int offset) {
        return new CalendarRequest(start, end, limit, checkOffset(offset));
    }

    /** First day of the window, inclusive. */
    public LocalDate start() {
        return start;
    }

    /** Last day of the window, inclusive. */
    public LocalDate end() {
        return end;
    }

    public int limit() {
        return limit;
    }

    public int offset() {
        return offset;
    }

    static LocalDate checkWindow(LocalDate start, LocalDate end) {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("end " + end + " is before start " + start);
        }
        return end;
    }

    static int checkLimit(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be in 1.." + MAX_LIMIT + ": " + limit);
        }
        return limit;
    }

    static int checkOffset(int offset) {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative: " + offset);
        }
        return offset;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof CalendarRequest r
                && start.equals(r.start)
                && end.equals(r.end)
                && limit == r.limit
                && offset == r.offset;
    }

    @Override
    public int hashCode() {
        return Objects.hash(start, end, limit, offset);
    }

    @Override
    public String toString() {
        return "CalendarRequest[start=" + start + ", end=" + end + ", limit=" + limit + ", offset=" + offset + "]";
    }
}
