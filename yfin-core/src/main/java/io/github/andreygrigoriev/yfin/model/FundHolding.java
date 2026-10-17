package io.github.andreygrigoriev.yfin.model;

import org.jspecify.annotations.Nullable;

/**
 * One of a fund's top holdings (yfinance {@code funds_data.top_holdings}).
 *
 * @param symbol the holding's symbol, or {@code null} when Yahoo omits it
 * @param name the holding's name, or {@code null} when Yahoo omits it
 * @param holdingPercent share of the fund's assets, as a fraction ({@code 0.07} for 7%), or {@code
 *     null} when Yahoo omits it
 */
public record FundHolding(
        @Nullable String symbol,
        @Nullable String name,
        @Nullable Double holdingPercent) {}
