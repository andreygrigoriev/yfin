package io.github.andreygrigoriev.yfin.internal.endpoint.quotesummary;

import static io.github.andreygrigoriev.yfin.internal.endpoint.quotesummary.ModuleNodes.dbl;
import static io.github.andreygrigoriev.yfin.internal.endpoint.quotesummary.ModuleNodes.str;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import io.github.andreygrigoriev.yfin.model.FundHolding;
import io.github.andreygrigoriev.yfin.model.FundsData;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Builds {@link FundsData} the way yfinance's {@code FundsData._fetch_and_parse} does. */
public final class FundsMapper {

    /** The {@code quoteSummary} modules behind {@code funds_data}. */
    public static final Set<QuoteSummaryModule> FUND_MODULES = Collections.unmodifiableSet(EnumSet.of(
            QuoteSummaryModule.QUOTE_TYPE,
            QuoteSummaryModule.SUMMARY_PROFILE,
            QuoteSummaryModule.TOP_HOLDINGS,
            QuoteSummaryModule.FUND_PROFILE));

    private static final List<String> ASSET_CLASSES = List.of(
            "cashPosition",
            "stockPosition",
            "bondPosition",
            "preferredPosition",
            "convertiblePosition",
            "otherPosition");

    private static final List<String> OPERATIONS =
            List.of("annualReportExpenseRatio", "annualHoldingsTurnover", "totalNetAssets");

    private static final List<String> EQUITY = List.of(
            "priceToEarnings",
            "priceToBook",
            "priceToSales",
            "priceToCashflow",
            "medianMarketCap",
            "threeYearEarningsGrowth");

    private static final List<String> BOND = List.of("duration", "maturity", "creditQuality");

    private FundsMapper() {}

    /**
     * Fund data of {@code symbol}; empty when Yahoo sends neither {@code topHoldings} nor {@code
     * fundProfile}, i.e. the symbol is not a fund (yfinance raises "No Fund data found").
     */
    public static Optional<FundsData> fundsData(String symbol, Map<QuoteSummaryModule, JsonNode> modules) {
        JsonNode holdings = modules.get(QuoteSummaryModule.TOP_HOLDINGS);
        JsonNode profile = modules.get(QuoteSummaryModule.FUND_PROFILE);
        if (holdings == null && profile == null) {
            return Optional.empty();
        }
        JsonNode top = orMissing(holdings);
        JsonNode fund = orMissing(profile);
        JsonNode quoteType = orMissing(modules.get(QuoteSummaryModule.QUOTE_TYPE));
        JsonNode summary = orMissing(modules.get(QuoteSummaryModule.SUMMARY_PROFILE));

        var assetClasses = new LinkedHashMap<String, Double>();
        for (String key : ASSET_CLASSES) {
            Double v = dbl(top, key);
            if (v != null) {
                assetClasses.put(key, v);
            }
        }
        List<FundHolding> topHoldings = ModuleNodes.list(
                top,
                "holdings",
                h -> new FundHolding(str(h, "symbol"), str(h, "holdingName"), dbl(h, "holdingPercent")));
        return Optional.of(new FundsData(
                symbol,
                str(quoteType, "quoteType"),
                str(summary, "longBusinessSummary"),
                str(fund, "categoryName"),
                str(fund, "family"),
                str(fund, "legalType"),
                metrics(fund.path("feesExpensesInvestment"), fund.path("feesExpensesInvestmentCat"), OPERATIONS, ""),
                assetClasses,
                topHoldings,
                metrics(top.path("equityHoldings"), top.path("equityHoldings"), EQUITY, "Cat"),
                metrics(top.path("bondHoldings"), top.path("bondHoldings"), BOND, "Cat"),
                weights(top.path("bondRatings")),
                weights(top.path("sectorWeightings"))));
    }

    /** Fund value {@code key} from {@code fund} with the category value {@code key + catSuffix} from {@code cat}. */
    private static Map<String, FundsData.Metric> metrics(
            JsonNode fund, JsonNode cat, List<String> keys, String catSuffix) {
        var out = new LinkedHashMap<String, FundsData.Metric>();
        for (String key : keys) {
            out.put(key, new FundsData.Metric(dbl(fund, key), dbl(cat, key + catSuffix)));
        }
        return out;
    }

    /** A list of single-entry objects ({@code [{"aaa": 0.5}, ...]}) as one map. */
    private static Map<String, Double> weights(JsonNode list) {
        var out = new LinkedHashMap<String, Double>();
        for (JsonNode entry : list) {
            entry.properties().forEach(e -> {
                if (e.getValue().isNumber()) {
                    out.put(e.getKey(), e.getValue().doubleValue());
                }
            });
        }
        return out;
    }

    private static JsonNode orMissing(@Nullable JsonNode node) {
        return node == null ? MissingNode.getInstance() : node;
    }
}
