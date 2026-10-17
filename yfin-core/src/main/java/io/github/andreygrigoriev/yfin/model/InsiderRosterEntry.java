package io.github.andreygrigoriev.yfin.model;

import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * One insider and their current position (Yahoo {@code insiderHolders}, yfinance {@code
 * insider_roster_holders}). Any field is {@code null} when Yahoo omits it.
 *
 * @param name insider name
 * @param position relation to the company, e.g. {@code Chief Executive Officer}
 * @param url insider URL, often empty
 * @param mostRecentTransaction description of the latest transaction, e.g. {@code Sale}
 * @param latestTransactionDate date of the latest transaction (UTC)
 * @param positionDirectDate date of the direct position (UTC)
 * @param sharesOwnedDirectly shares owned directly
 * @param positionIndirectDate date of the indirect position (UTC)
 * @param sharesOwnedIndirectly shares owned indirectly
 */
public record InsiderRosterEntry(
        @Nullable String name,
        @Nullable String position,
        @Nullable String url,
        @Nullable String mostRecentTransaction,
        @Nullable LocalDate latestTransactionDate,
        @Nullable LocalDate positionDirectDate,
        @Nullable Long sharesOwnedDirectly,
        @Nullable LocalDate positionIndirectDate,
        @Nullable Long sharesOwnedIndirectly) {}
