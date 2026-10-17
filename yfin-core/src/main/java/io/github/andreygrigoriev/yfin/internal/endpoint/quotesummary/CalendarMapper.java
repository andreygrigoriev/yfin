package io.github.andreygrigoriev.yfin.internal.endpoint.quotesummary;

import static io.github.andreygrigoriev.yfin.internal.endpoint.quotesummary.ModuleNodes.bool;
import static io.github.andreygrigoriev.yfin.internal.endpoint.quotesummary.ModuleNodes.date;
import static io.github.andreygrigoriev.yfin.internal.endpoint.quotesummary.ModuleNodes.dbl;
import static io.github.andreygrigoriev.yfin.internal.endpoint.quotesummary.ModuleNodes.toDate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import io.github.andreygrigoriev.yfin.model.Calendar;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Maps {@code calendarEvents} the way yfinance's {@code calendar} does. Dates are epoch seconds
 * read as UTC dates (yfinance uses the local time zone).
 */
public final class CalendarMapper {

    private CalendarMapper() {}

    /** {@code calendarEvents}; empty when the module is missing. */
    public static Optional<Calendar> calendar(@Nullable JsonNode module) {
        if (module == null || !module.isObject()) {
            return Optional.empty();
        }
        JsonNode earnings = module.path("earnings");
        if (!earnings.isObject()) {
            earnings = MissingNode.getInstance();
        }
        var dates = new ArrayList<LocalDate>();
        for (JsonNode d : earnings.path("earningsDate")) {
            LocalDate date = toDate(d);
            if (date != null) {
                dates.add(date);
            }
        }
        return Optional.of(new Calendar(
                List.copyOf(dates),
                bool(earnings, "isEarningsDateEstimate"),
                dbl(earnings, "earningsHigh"),
                dbl(earnings, "earningsLow"),
                dbl(earnings, "earningsAverage"),
                dbl(earnings, "revenueHigh"),
                dbl(earnings, "revenueLow"),
                dbl(earnings, "revenueAverage"),
                date(module, "exDividendDate"),
                date(module, "dividendDate")));
    }
}
