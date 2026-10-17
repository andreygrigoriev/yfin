package io.github.andreygrigoriev.yfin.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Profile and portfolio of an ETF or mutual fund (yfinance {@code funds_data}), from the {@code
 * quoteType}, {@code summaryProfile}, {@code topHoldings} and {@code fundProfile} modules. Maps
 * are keyed by Yahoo field name and keep Yahoo's (or yfinance's) order; all collections are
 * unmodifiable.
 *
 * @param symbol the symbol
 * @param quoteType Yahoo quote type, e.g. {@code ETF} or {@code MUTUALFUND}
 * @param description the fund's summary ({@code longBusinessSummary})
 * @param categoryName Morningstar-style category, e.g. {@code Large Blend}
 * @param family fund family, e.g. {@code SPDR State Street Global Advisors}
 * @param legalType legal structure, e.g. {@code Exchange Traded Fund}
 * @param operations {@code annualReportExpenseRatio}, {@code annualHoldingsTurnover} and {@code
 *     totalNetAssets}, fund versus category average
 * @param assetClasses share of assets per class ({@code cashPosition}, {@code stockPosition},
 *     {@code bondPosition}, {@code preferredPosition}, {@code convertiblePosition}, {@code
 *     otherPosition}) as fractions; classes Yahoo omits are absent
 * @param topHoldings the largest holdings, as Yahoo orders them
 * @param equityHoldings {@code priceToEarnings}, {@code priceToBook}, {@code priceToSales},
 *     {@code priceToCashflow}, {@code medianMarketCap} and {@code threeYearEarningsGrowth}, fund
 *     versus category average
 * @param bondHoldings {@code duration}, {@code maturity} and {@code creditQuality}, fund versus
 *     category average
 * @param bondRatings share of bond assets per rating (e.g. {@code aaa}, {@code bb}) as fractions
 * @param sectorWeightings share of equity assets per sector (e.g. {@code technology}) as fractions
 */
public record FundsData(
        String symbol,
        @Nullable String quoteType,
        @Nullable String description,
        @Nullable String categoryName,
        @Nullable String family,
        @Nullable String legalType,
        Map<String, Metric> operations,
        Map<String, Double> assetClasses,
        List<FundHolding> topHoldings,
        Map<String, Metric> equityHoldings,
        Map<String, Metric> bondHoldings,
        Map<String, Double> bondRatings,
        Map<String, Double> sectorWeightings) {

    /**
     * A fund figure next to its category average; either is {@code null} when Yahoo omits it.
     *
     * @param fund the fund's value
     * @param categoryAverage the average over the fund's category
     */
    public record Metric(@Nullable Double fund, @Nullable Double categoryAverage) {}

    public FundsData {
        Objects.requireNonNull(symbol, "symbol");
        operations = copy(operations);
        assetClasses = copy(assetClasses);
        topHoldings = List.copyOf(topHoldings);
        equityHoldings = copy(equityHoldings);
        bondHoldings = copy(bondHoldings);
        bondRatings = copy(bondRatings);
        sectorWeightings = copy(sectorWeightings);
    }

    private static <V> Map<String, V> copy(Map<String, V> map) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }
}
