package io.github.andreygrigoriev.yfin.request;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Bar size of a price history. Intraday intervals are limited by Yahoo in how far back they reach
 * and, for {@link #ONE_MINUTE}, in how long a single request may span.
 */
public enum Interval {
    ONE_MINUTE("1m", Duration.ofMinutes(1), Duration.ofDays(30), Duration.ofDays(8)),
    TWO_MINUTES("2m", Duration.ofMinutes(2), Duration.ofDays(60), null),
    FIVE_MINUTES("5m", Duration.ofMinutes(5), Duration.ofDays(60), null),
    FIFTEEN_MINUTES("15m", Duration.ofMinutes(15), Duration.ofDays(60), null),
    /** Fetched as 15-minute bars and aggregated, working around a Yahoo bug (as yfinance does). */
    THIRTY_MINUTES("30m", Duration.ofMinutes(30), Duration.ofDays(60), null),
    SIXTY_MINUTES("60m", Duration.ofMinutes(60), Duration.ofDays(730), null),
    NINETY_MINUTES("90m", Duration.ofMinutes(90), Duration.ofDays(60), null),
    ONE_HOUR("1h", Duration.ofHours(1), Duration.ofDays(730), null),
    ONE_DAY("1d", Duration.ofDays(1), null, null),
    FIVE_DAYS("5d", Duration.ofDays(5), null, null),
    ONE_WEEK("1wk", Duration.ofDays(7), null, null),
    ONE_MONTH("1mo", Duration.ofDays(31), null, null),
    THREE_MONTHS("3mo", Duration.ofDays(92), null, null);

    private final String code;
    private final Duration nominal;
    private final @Nullable Duration maxLookback;
    private final @Nullable Duration maxSpan;

    Interval(String code, Duration nominal, @Nullable Duration maxLookback, @Nullable Duration maxSpan) {
        this.code = code;
        this.nominal = nominal;
        this.maxLookback = maxLookback;
        this.maxSpan = maxSpan;
    }

    /** Yahoo's code, e.g. {@code 1m}, {@code 1d}, {@code 1wk}. */
    public String code() {
        return code;
    }

    /** Whether bars are shorter than a day. */
    public boolean isIntraday() {
        return code.endsWith("m") && !code.endsWith("mo") || code.endsWith("h");
    }

    /** Approximate bar length; exact for intraday intervals. */
    public Duration nominalDuration() {
        return nominal;
    }

    /** How far back from now Yahoo serves this interval; empty when unlimited. */
    public Optional<Duration> maxLookback() {
        return Optional.ofNullable(maxLookback);
    }

    /** Longest span a single request may cover; empty when only {@link #maxLookback()} applies. */
    public Optional<Duration> maxSpan() {
        return Optional.ofNullable(maxSpan);
    }

    /**
     * The interval with Yahoo code {@code code} (case-insensitive).
     *
     * @throws IllegalArgumentException for an unknown code
     */
    public static Interval fromCode(String code) {
        String c = Objects.requireNonNull(code, "code").trim().toLowerCase(Locale.ROOT);
        for (Interval i : values()) {
            if (i.code.equals(c)) {
                return i;
            }
        }
        throw new IllegalArgumentException("unknown interval: " + code);
    }

    @Override
    public String toString() {
        return code;
    }
}
