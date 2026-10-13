package io.andreygrigoriev.yfin.request;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.Temporal;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Parameters of a price history request, mirroring yfinance's {@code Ticker.history()}.
 *
 * <p>The window is a {@link Period}, a {@code start}/{@code end} pair, or a period anchored at one
 * of them ({@code end = start + period}, {@code start = end - period}). Without any of these the
 * period is one month. Dates ({@link LocalDate}) are midnight in the exchange's timezone; instants
 * are absolute. {@code start} is inclusive, {@code end} exclusive.
 *
 * <pre>{@code
 * HistoryRequest req = HistoryRequest.builder()
 *         .start(LocalDate.of(2024, 1, 1))
 *         .end(LocalDate.of(2024, 7, 1))
 *         .interval(Interval.ONE_WEEK)
 *         .build();
 * }</pre>
 *
 * <p>Immutable.
 */
public final class HistoryRequest {

    /** Allowance for exchange timezones and rounding when checking intraday limits. */
    private static final Duration LIMIT_SLACK = Duration.ofDays(1);

    private final @Nullable Period period;
    private final @Nullable Temporal start;
    private final @Nullable Temporal end;
    private final Interval interval;
    private final boolean prepost;
    private final boolean autoAdjust;
    private final boolean backAdjust;
    private final boolean actions;
    private final @Nullable Boolean repair;
    private final boolean keepNa;

    private HistoryRequest(Builder b) {
        this.period = b.period == null && b.start == null && b.end == null ? Period.ONE_MONTH : b.period;
        this.start = b.start;
        this.end = b.end;
        this.interval = b.interval;
        this.prepost = b.prepost;
        this.autoAdjust = b.autoAdjust;
        this.backAdjust = b.backAdjust;
        this.actions = b.actions;
        this.repair = b.repair;
        this.keepNa = b.keepNa;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Daily bars over {@code period}, other settings at their defaults. */
    public static HistoryRequest of(Period period) {
        return builder().period(period).build();
    }

    /** {@code interval} bars over {@code period}, other settings at their defaults. */
    public static HistoryRequest of(Period period, Interval interval) {
        return builder().period(period).interval(interval).build();
    }

    /** The look-back period; empty when the window is given by {@code start} and {@code end}. */
    public Optional<Period> period() {
        return Optional.ofNullable(period);
    }

    /** Inclusive start: a {@link LocalDate} (exchange timezone) or an {@link Instant}. */
    public Optional<Temporal> start() {
        return Optional.ofNullable(start);
    }

    /** Exclusive end: a {@link LocalDate} (exchange timezone) or an {@link Instant}. */
    public Optional<Temporal> end() {
        return Optional.ofNullable(end);
    }

    public Interval interval() {
        return interval;
    }

    /** Include pre- and post-market bars (intraday only); default {@code false}. */
    public boolean prepost() {
        return prepost;
    }

    /** Scale OHLC by adjusted close / close; default {@code true}. */
    public boolean autoAdjust() {
        return autoAdjust;
    }

    /** Scale open/high/low but keep the raw close; ignored when {@link #autoAdjust()}. */
    public boolean backAdjust() {
        return backAdjust;
    }

    /** Return dividends, splits and capital gains; default {@code true}. */
    public boolean actions() {
        return actions;
    }

    /**
     * Price repair (yfinance's {@code repair=True}); empty means the client default. Repaired bars
     * have {@link io.andreygrigoriev.yfin.model.Bar#repaired()} set. Multi-day intervals are then
     * fetched as daily bars and resampled; {@link Interval#FIVE_DAYS} is not supported.
     */
    public Optional<Boolean> repair() {
        return Optional.ofNullable(repair);
    }

    /** Keep bars whose prices and volume are all missing; default {@code false}. */
    public boolean keepNa() {
        return keepNa;
    }

    /** Copy with the given interval. */
    public HistoryRequest withInterval(Interval interval) {
        return toBuilder().interval(interval).build();
    }

    /** Builder pre-filled with this request's settings. */
    public Builder toBuilder() {
        var b = new Builder();
        b.period = period;
        b.start = start;
        b.end = end;
        b.interval = interval;
        b.prepost = prepost;
        b.autoAdjust = autoAdjust;
        b.backAdjust = backAdjust;
        b.actions = actions;
        b.repair = repair;
        b.keepNa = keepNa;
        return b;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        return o instanceof HistoryRequest r
                && period == r.period
                && Objects.equals(start, r.start)
                && Objects.equals(end, r.end)
                && interval == r.interval
                && prepost == r.prepost
                && autoAdjust == r.autoAdjust
                && backAdjust == r.backAdjust
                && actions == r.actions
                && Objects.equals(repair, r.repair)
                && keepNa == r.keepNa;
    }

    @Override
    public int hashCode() {
        return Objects.hash(period, start, end, interval, prepost, autoAdjust, backAdjust, actions, repair, keepNa);
    }

    @Override
    public String toString() {
        return "HistoryRequest[period=" + period + ", start=" + start + ", end=" + end + ", interval=" + interval
                + ", prepost=" + prepost + ", autoAdjust=" + autoAdjust + ", backAdjust=" + backAdjust
                + ", actions=" + actions + ", repair=" + repair + ", keepNa=" + keepNa + "]";
    }

    /** Builder for {@link HistoryRequest}; {@link #build()} validates the combination. */
    public static final class Builder {

        private @Nullable Period period;
        private @Nullable Temporal start;
        private @Nullable Temporal end;
        private Interval interval = Interval.ONE_DAY;
        private boolean prepost;
        private boolean autoAdjust = true;
        private boolean backAdjust;
        private boolean actions = true;
        private @Nullable Boolean repair;
        private boolean keepNa;

        private Builder() {}

        public Builder period(Period period) {
            this.period = Objects.requireNonNull(period, "period");
            return this;
        }

        /** Inclusive start date, midnight in the exchange's timezone. */
        public Builder start(LocalDate start) {
            this.start = Objects.requireNonNull(start, "start");
            return this;
        }

        /** Inclusive start instant. */
        public Builder start(Instant start) {
            this.start = Objects.requireNonNull(start, "start");
            return this;
        }

        /** Exclusive end date, midnight in the exchange's timezone. */
        public Builder end(LocalDate end) {
            this.end = Objects.requireNonNull(end, "end");
            return this;
        }

        /** Exclusive end instant. */
        public Builder end(Instant end) {
            this.end = Objects.requireNonNull(end, "end");
            return this;
        }

        /** Bar size; default {@link Interval#ONE_DAY}. */
        public Builder interval(Interval interval) {
            this.interval = Objects.requireNonNull(interval, "interval");
            return this;
        }

        public Builder prepost(boolean prepost) {
            this.prepost = prepost;
            return this;
        }

        public Builder autoAdjust(boolean autoAdjust) {
            this.autoAdjust = autoAdjust;
            return this;
        }

        public Builder backAdjust(boolean backAdjust) {
            this.backAdjust = backAdjust;
            return this;
        }

        public Builder actions(boolean actions) {
            this.actions = actions;
            return this;
        }

        public Builder repair(boolean repair) {
            this.repair = repair;
            return this;
        }

        public Builder keepNa(boolean keepNa) {
            this.keepNa = keepNa;
            return this;
        }

        /**
         * @throws IllegalArgumentException when period, start and end are all set, {@code ytd} or
         *     {@code max} is combined with start or end, start is not before end, or an intraday
         *     interval exceeds Yahoo's limits (e.g. {@code 1m}: at most 8 days per request and 30
         *     days back; other minute intervals: 60 days back; hourly: 730 days back)
         */
        public HistoryRequest build() {
            return build(Clock.systemUTC());
        }

        HistoryRequest build(Clock clock) {
            if (period != null && start != null && end != null) {
                throw new IllegalArgumentException("set at most two of period, start and end");
            }
            if ((period == Period.YTD || period == Period.MAX) && (start != null || end != null)) {
                throw new IllegalArgumentException("period " + period + " cannot be combined with start or end");
            }
            if (start != null && end != null && !approxInstant(start).isBefore(approxInstant(end))) {
                throw new IllegalArgumentException("start " + start + " is not before end " + end);
            }
            if (interval.isIntraday() && period != Period.MAX) {
                checkIntradayLimits(clock.instant());
            }
            return new HistoryRequest(this);
        }

        private void checkIntradayLimits(Instant now) {
            Instant e = end != null
                    ? approxInstant(end)
                    : start != null && period != null ? plus(approxInstant(start), period, now) : now;
            Instant s;
            if (start != null) {
                s = approxInstant(start);
            } else if (period != null) {
                s = minus(e, period, now);
            } else {
                s = minus(e, Period.ONE_MONTH, now);
            }
            Optional<Duration> maxSpan = interval.maxSpan();
            if (maxSpan.isPresent()) {
                Duration span = Duration.between(s, e);
                if (span.compareTo(maxSpan.get().plus(LIMIT_SLACK)) > 0) {
                    throw new IllegalArgumentException(interval + " data is limited to "
                            + maxSpan.get().toDays() + " days per request, requested " + span.toDays() + " days");
                }
            }
            Optional<Duration> lookback = interval.maxLookback();
            if (lookback.isPresent() && s.isBefore(now.minus(lookback.get()).minus(LIMIT_SLACK))) {
                throw new IllegalArgumentException(interval + " data is only available for the last "
                        + lookback.get().toDays() + " days, requested from " + s);
            }
        }

        private static Instant approxInstant(Temporal t) {
            return t instanceof LocalDate d ? d.atStartOfDay(ZoneOffset.UTC).toInstant() : (Instant) t;
        }

        private static Instant plus(Instant from, Period p, Instant now) {
            return from.atZone(ZoneOffset.UTC).plus(length(p, now)).toInstant();
        }

        private static Instant minus(Instant from, Period p, Instant now) {
            if (p == Period.YTD) {
                return LocalDate.ofInstant(now, ZoneOffset.UTC)
                        .withDayOfYear(1)
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant();
            }
            return from.atZone(ZoneOffset.UTC).minus(length(p, now)).toInstant();
        }

        private static java.time.Period length(Period p, Instant now) {
            return p.length()
                    .orElseGet(() -> java.time.Period.ofDays(
                            LocalDate.ofInstant(now, ZoneOffset.UTC).getDayOfYear()));
        }
    }
}
