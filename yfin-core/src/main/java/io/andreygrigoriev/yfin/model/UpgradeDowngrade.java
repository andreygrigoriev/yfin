package io.andreygrigoriev.yfin.model;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * One analyst rating change (Yahoo {@code upgradeDowngradeHistory}, yfinance {@code
 * upgrades_downgrades}). Any field is {@code null} when Yahoo omits it.
 *
 * @param gradeDate when the rating was published
 * @param firm analyst firm
 * @param toGrade new rating, e.g. {@code Overweight}
 * @param fromGrade previous rating; equal to {@code toGrade} for a reiteration
 * @param action {@code up}, {@code down}, {@code main} (maintains), {@code reit} (reiterates) or
 *     {@code init}
 * @param priceTargetAction e.g. {@code Raises}, {@code Lowers}, {@code Maintains}; often empty
 * @param currentPriceTarget new price target; {@code 0} when the firm gave none
 * @param priorPriceTarget previous price target; {@code 0} when there was none
 */
public record UpgradeDowngrade(
        @Nullable Instant gradeDate,
        @Nullable String firm,
        @Nullable String toGrade,
        @Nullable String fromGrade,
        @Nullable String action,
        @Nullable String priceTargetAction,
        @Nullable Double currentPriceTarget,
        @Nullable Double priorPriceTarget) {}
