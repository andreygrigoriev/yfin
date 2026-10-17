package io.github.andreygrigoriev.yfin.model;

import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * One insider transaction (Yahoo {@code insiderTransactions}, yfinance {@code
 * insider_transactions}). Text fields are as Yahoo sends them, often empty strings; any field is
 * {@code null} when Yahoo omits it.
 *
 * @param startDate transaction date (UTC)
 * @param insider filer name
 * @param position filer's relation to the company, e.g. {@code Director}
 * @param url filer URL
 * @param transaction Yahoo {@code moneyText}
 * @param text description, e.g. {@code Sale at price 332.01 per share.}
 * @param shares shares traded
 * @param value transaction value in the symbol's currency
 * @param ownership {@code D} (direct) or {@code I} (indirect)
 */
public record InsiderTransaction(
        @Nullable LocalDate startDate,
        @Nullable String insider,
        @Nullable String position,
        @Nullable String url,
        @Nullable String transaction,
        @Nullable String text,
        @Nullable Long shares,
        @Nullable Long value,
        @Nullable String ownership) {}
