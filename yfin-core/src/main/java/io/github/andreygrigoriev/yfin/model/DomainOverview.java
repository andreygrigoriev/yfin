package io.github.andreygrigoriev.yfin.model;

import org.jspecify.annotations.Nullable;

/**
 * Overview of a sector or an industry (yfinance {@code Sector.overview} / {@code
 * Industry.overview}). Fields Yahoo does not report are {@code null}.
 *
 * @param companiesCount number of companies
 * @param marketCap total market capitalization
 * @param messageBoardId Yahoo message board id
 * @param description description
 * @param industriesCount number of industries; sectors only
 * @param marketWeight weight in the market (sector) or in the sector (industry), a fraction
 * @param employeeCount total number of employees
 */
public record DomainOverview(
        @Nullable Long companiesCount,
        @Nullable Double marketCap,
        @Nullable String messageBoardId,
        @Nullable String description,
        @Nullable Long industriesCount,
        @Nullable Double marketWeight,
        @Nullable Long employeeCount) {

    /** An overview with every field {@code null}. */
    public static DomainOverview empty() {
        return new DomainOverview(null, null, null, null, null, null, null);
    }
}
