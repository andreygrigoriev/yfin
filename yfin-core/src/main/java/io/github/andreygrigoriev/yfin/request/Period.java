package io.github.andreygrigoriev.yfin.request;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Look-back window of a price history, relative to now. */
public enum Period {
    ONE_DAY("1d", java.time.Period.ofDays(1)),
    FIVE_DAYS("5d", java.time.Period.ofDays(5)),
    ONE_MONTH("1mo", java.time.Period.ofMonths(1)),
    THREE_MONTHS("3mo", java.time.Period.ofMonths(3)),
    SIX_MONTHS("6mo", java.time.Period.ofMonths(6)),
    ONE_YEAR("1y", java.time.Period.ofYears(1)),
    TWO_YEARS("2y", java.time.Period.ofYears(2)),
    FIVE_YEARS("5y", java.time.Period.ofYears(5)),
    TEN_YEARS("10y", java.time.Period.ofYears(10)),
    /** Since January 1st of the current year. */
    YTD("ytd", null),
    /** All available data. */
    MAX("max", null);

    private final String code;
    private final java.time.@Nullable Period length;

    Period(String code, java.time.@Nullable Period length) {
        this.code = code;
        this.length = length;
    }

    /** Yahoo's code, e.g. {@code 1mo}, {@code ytd}. */
    public String code() {
        return code;
    }

    /** Calendar length; empty for {@link #YTD} and {@link #MAX}. */
    public Optional<java.time.Period> length() {
        return Optional.ofNullable(length);
    }

    /**
     * The period with Yahoo code {@code code} (case-insensitive).
     *
     * @throws IllegalArgumentException for an unknown code
     */
    public static Period fromCode(String code) {
        String c = Objects.requireNonNull(code, "code").trim().toLowerCase(Locale.ROOT);
        for (Period p : values()) {
            if (p.code.equals(c)) {
                return p;
            }
        }
        throw new IllegalArgumentException("unknown period: " + code);
    }

    @Override
    public String toString() {
        return code;
    }
}
