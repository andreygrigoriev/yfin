package io.github.andreygrigoriev.yfin.model;

import java.time.LocalDate;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One row of the IPO calendar (yfinance {@code Calendars.get_ipo_info_calendar()}). Prices and the
 * share count are {@link Double#NaN} when Yahoo does not have them. Dates are in {@code
 * America/New_York}.
 *
 * @param symbol ticker symbol
 * @param companyName short company name
 * @param exchange short exchange name, e.g. {@code "NASDAQ"}
 * @param filingDate date of the filing
 * @param date expected or actual first trading date
 * @param amendedDate date of the last amendment
 * @param priceFrom low end of the expected price range
 * @param priceTo high end of the expected price range
 * @param offerPrice final offer price
 * @param currency currency name of the prices
 * @param shares number of shares offered
 * @param dealType Yahoo's deal status, e.g. {@code "Expected"}
 */
public record IpoEvent(
        String symbol,
        @Nullable String companyName,
        @Nullable String exchange,
        @Nullable LocalDate filingDate,
        @Nullable LocalDate date,
        @Nullable LocalDate amendedDate,
        double priceFrom,
        double priceTo,
        double offerPrice,
        @Nullable String currency,
        double shares,
        @Nullable String dealType) {

    public IpoEvent {
        Objects.requireNonNull(symbol, "symbol");
    }
}
