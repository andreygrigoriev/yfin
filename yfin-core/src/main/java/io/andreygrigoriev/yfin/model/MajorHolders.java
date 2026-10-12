package io.andreygrigoriev.yfin.model;

import org.jspecify.annotations.Nullable;

/**
 * Ownership breakdown (Yahoo {@code majorHoldersBreakdown}, yfinance {@code major_holders}).
 * Fractions are {@code 0..1}; any field is {@code null} when Yahoo omits it.
 *
 * @param insidersPercentHeld fraction of shares held by insiders
 * @param institutionsPercentHeld fraction of shares held by institutions
 * @param institutionsFloatPercentHeld fraction of the float held by institutions
 * @param institutionsCount number of institutions holding shares
 */
public record MajorHolders(
        @Nullable Double insidersPercentHeld,
        @Nullable Double institutionsPercentHeld,
        @Nullable Double institutionsFloatPercentHeld,
        @Nullable Long institutionsCount) {}
