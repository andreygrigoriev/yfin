package io.github.andreygrigoriev.yfin.model;

import org.jspecify.annotations.Nullable;

/**
 * Insider buying and selling over a recent period (Yahoo {@code netSharePurchaseActivity},
 * yfinance {@code insider_purchases}). Fractions are {@code 0..1} of the shares insiders hold; any
 * field is {@code null} when Yahoo omits it.
 *
 * @param period the period covered, e.g. {@code 6m}
 * @param purchasesShares shares bought
 * @param purchasesCount number of purchase transactions
 * @param salesShares shares sold
 * @param salesCount number of sale transactions
 * @param netSharesPurchased shares bought minus shares sold
 * @param netCount number of transactions
 * @param totalInsiderShares shares held by insiders
 * @param netPercentInsiderShares net shares purchased as a fraction of insider shares
 * @param buyPercentInsiderShares shares bought as a fraction of insider shares
 * @param sellPercentInsiderShares shares sold as a fraction of insider shares
 */
public record InsiderPurchases(
        @Nullable String period,
        @Nullable Long purchasesShares,
        @Nullable Long purchasesCount,
        @Nullable Long salesShares,
        @Nullable Long salesCount,
        @Nullable Long netSharesPurchased,
        @Nullable Long netCount,
        @Nullable Long totalInsiderShares,
        @Nullable Double netPercentInsiderShares,
        @Nullable Double buyPercentInsiderShares,
        @Nullable Double sellPercentInsiderShares) {}
