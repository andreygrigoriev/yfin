package io.andreygrigoriev.yfin.model;

import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * One institution or fund holding the symbol (Yahoo {@code institutionOwnership} / {@code
 * fundOwnership}, yfinance {@code institutional_holders} / {@code mutualfund_holders}). Any field
 * is {@code null} when Yahoo omits it.
 *
 * @param dateReported date of the filing the position comes from (UTC)
 * @param holder institution or fund name
 * @param shares shares held
 * @param value position value in the symbol's currency
 * @param pctHeld fraction of shares outstanding held, {@code 0..1}
 * @param pctChange relative change of the position since the previous filing ({@code 0.016} =
 *     +1.6%)
 */
public record Holder(
        @Nullable LocalDate dateReported,
        @Nullable String holder,
        @Nullable Long shares,
        @Nullable Long value,
        @Nullable Double pctHeld,
        @Nullable Double pctChange) {}
