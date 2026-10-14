package io.andreygrigoriev.yfin.request;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;
import java.util.OptionalDouble;
import org.jspecify.annotations.Nullable;

/**
 * Window, paging and filters of the market-wide earnings calendar (yfinance {@code
 * Calendars.get_earnings_calendar(market_cap, filter_most_active, start, end, limit, offset)}).
 * Immutable: every setter returns a copy.
 *
 * <pre>{@code
 * List<EarningsEvent> events = yf.earningsCalendar(EarningsCalendarRequest
 *         .between(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 23))
 *         .marketCap(1e9).filterMostActive(false).limit(50));
 * }</pre>
 *
 * <p>Window and paging work as in {@link CalendarRequest}. By default there is no market-cap floor
 * and results are restricted to today's most active stocks (yfinance {@code
 * filter_most_active=True}); that filter applies to the first page ({@code offset == 0}) only.
 */
public final class EarningsCalendarRequest {

    private final LocalDate start;
    private final LocalDate end;
    private final int limit;
    private final int offset;
    private final @Nullable Double marketCap;
    private final boolean filterMostActive;

    private EarningsCalendarRequest(
            LocalDate start,
            LocalDate end,
            int limit,
            int offset,
            @Nullable Double marketCap,
            boolean filterMostActive) {
        this.start = start;
        this.end = end;
        this.limit = limit;
        this.offset = offset;
        this.marketCap = marketCap;
        this.filterMostActive = filterMostActive;
    }

    /**
     * Earnings calendar from {@code start} to {@code end}, both inclusive.
     *
     * @throws IllegalArgumentException when {@code end} is before {@code start}
     */
    public static EarningsCalendarRequest between(LocalDate start, LocalDate end) {
        return new EarningsCalendarRequest(
                start, CalendarRequest.checkWindow(start, end), CalendarRequest.DEFAULT_LIMIT, 0, null, true);
    }

    /** Earnings calendar from {@code start} to {@code start + 7 days}. */
    public static EarningsCalendarRequest from(LocalDate start) {
        Objects.requireNonNull(start, "start");
        return between(start, start.plusDays(CalendarRequest.DEFAULT_DAYS));
    }

    /** Earnings calendar from today (system default timezone) to today + 7 days, the yfinance default. */
    public static EarningsCalendarRequest nextWeek() {
        return nextWeek(Clock.systemDefaultZone());
    }

    static EarningsCalendarRequest nextWeek(Clock clock) {
        return from(LocalDate.now(clock));
    }

    /**
     * Maximum number of rows.
     *
     * @throws IllegalArgumentException when {@code limit} is not in {@code 1..100}
     */
    public EarningsCalendarRequest limit(int limit) {
        return new EarningsCalendarRequest(
                start, end, CalendarRequest.checkLimit(limit), offset, marketCap, filterMostActive);
    }

    /**
     * Number of rows to skip, for paging.
     *
     * @throws IllegalArgumentException when {@code offset} is negative
     */
    public EarningsCalendarRequest offset(int offset) {
        return new EarningsCalendarRequest(
                start, end, limit, CalendarRequest.checkOffset(offset), marketCap, filterMostActive);
    }

    /**
     * Keeps companies with an intraday market cap of at least {@code marketCap} (yfinance {@code
     * market_cap}). It also filters the most-active list.
     *
     * @throws IllegalArgumentException when {@code marketCap} is not a positive finite number
     */
    public EarningsCalendarRequest marketCap(double marketCap) {
        if (!(marketCap > 0) || Double.isInfinite(marketCap)) {
            throw new IllegalArgumentException("marketCap must be positive: " + marketCap);
        }
        return new EarningsCalendarRequest(start, end, limit, offset, marketCap, filterMostActive);
    }

    /** Whether to keep only today's most active stocks (yfinance {@code filter_most_active}). */
    public EarningsCalendarRequest filterMostActive(boolean filterMostActive) {
        return new EarningsCalendarRequest(start, end, limit, offset, marketCap, filterMostActive);
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

    /** The market-cap floor; empty when there is none. */
    public OptionalDouble marketCap() {
        return marketCap == null ? OptionalDouble.empty() : OptionalDouble.of(marketCap);
    }

    public boolean filterMostActive() {
        return filterMostActive;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof EarningsCalendarRequest r
                && start.equals(r.start)
                && end.equals(r.end)
                && limit == r.limit
                && offset == r.offset
                && Objects.equals(marketCap, r.marketCap)
                && filterMostActive == r.filterMostActive;
    }

    @Override
    public int hashCode() {
        return Objects.hash(start, end, limit, offset, marketCap, filterMostActive);
    }

    @Override
    public String toString() {
        return "EarningsCalendarRequest[start=" + start + ", end=" + end + ", limit=" + limit + ", offset=" + offset
                + ", marketCap=" + marketCap + ", filterMostActive=" + filterMostActive + "]";
    }
}
