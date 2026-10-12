package io.andreygrigoriev.yfin.internal.endpoint.chart;

import io.andreygrigoriev.yfin.request.HistoryRequest;
import io.andreygrigoriev.yfin.request.Interval;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A history request resolved to Yahoo parameters.
 *
 * @param period1 inclusive start epoch second, or {@code null} when {@code range} is used
 * @param period2 exclusive end epoch second, or {@code null} when {@code range} is used
 * @param range Yahoo range code, or {@code null} when {@code period1}/{@code period2} are used
 */
record ChartQuery(
        String symbol,
        HistoryRequest request,
        @Nullable Long period1,
        @Nullable Long period2,
        @Nullable String range) {

    ChartQuery {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(request, "request");
        if ((period1 == null) != (period2 == null) || (period1 == null) == (range == null)) {
            throw new IllegalArgumentException("either period1/period2 or range");
        }
    }

    /** The interval asked for. */
    Interval interval() {
        return request.interval();
    }

    /** The interval fetched: Yahoo answers 30m with 60m bars, so 30m is fetched as 15m and aggregated. */
    Interval fetchInterval() {
        return request.interval() == Interval.THIRTY_MINUTES ? Interval.FIFTEEN_MINUTES : request.interval();
    }
}
