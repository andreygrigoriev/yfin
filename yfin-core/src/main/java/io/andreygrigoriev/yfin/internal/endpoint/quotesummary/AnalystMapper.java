package io.andreygrigoriev.yfin.internal.endpoint.quotesummary;

import static io.andreygrigoriev.yfin.internal.endpoint.quotesummary.ModuleNodes.date;
import static io.andreygrigoriev.yfin.internal.endpoint.quotesummary.ModuleNodes.dbl;
import static io.andreygrigoriev.yfin.internal.endpoint.quotesummary.ModuleNodes.instant;
import static io.andreygrigoriev.yfin.internal.endpoint.quotesummary.ModuleNodes.isoDate;
import static io.andreygrigoriev.yfin.internal.endpoint.quotesummary.ModuleNodes.list;
import static io.andreygrigoriev.yfin.internal.endpoint.quotesummary.ModuleNodes.lng;
import static io.andreygrigoriev.yfin.internal.endpoint.quotesummary.ModuleNodes.str;

import com.fasterxml.jackson.databind.JsonNode;
import io.andreygrigoriev.yfin.model.EarningsEstimate;
import io.andreygrigoriev.yfin.model.EarningsHistoryEntry;
import io.andreygrigoriev.yfin.model.EpsRevisions;
import io.andreygrigoriev.yfin.model.EpsTrend;
import io.andreygrigoriev.yfin.model.GrowthEstimate;
import io.andreygrigoriev.yfin.model.PriceTargets;
import io.andreygrigoriev.yfin.model.Recommendation;
import io.andreygrigoriev.yfin.model.RevenueEstimate;
import io.andreygrigoriev.yfin.model.UpgradeDowngrade;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import org.jspecify.annotations.Nullable;

/**
 * Maps the analyst {@code quoteSummary} modules the way yfinance's {@code Analysis} and {@code
 * Quote} scrapers do. Every method takes modules as returned (unwrapped) by {@link
 * QuoteSummaryEndpoint#fetch}, {@code null} for a module Yahoo did not return; a missing module
 * gives an empty result, never an exception. List order is Yahoo's.
 */
public final class AnalystMapper {

    /** The modules {@link #growthEstimates} reads, fetched together. */
    public static final Set<QuoteSummaryModule> GROWTH_MODULES = Set.of(
            QuoteSummaryModule.EARNINGS_TREND,
            QuoteSummaryModule.INDUSTRY_TREND,
            QuoteSummaryModule.SECTOR_TREND,
            QuoteSummaryModule.INDEX_TREND);

    /** yfinance's periodic tables use the first four trend entries ({@code 0q, +1q, 0y, +1y}). */
    private static final int PERIODIC_ROWS = 4;

    private AnalystMapper() {}

    /** {@code recommendationTrend}. */
    public static List<Recommendation> recommendations(@Nullable JsonNode module) {
        return list(
                module,
                "trend",
                o -> new Recommendation(
                        str(o, "period"),
                        lng(o, "strongBuy"),
                        lng(o, "buy"),
                        lng(o, "hold"),
                        lng(o, "sell"),
                        lng(o, "strongSell")));
    }

    /** {@code upgradeDowngradeHistory}; newest first, as Yahoo sends it. */
    public static List<UpgradeDowngrade> upgradesDowngrades(@Nullable JsonNode module) {
        return list(
                module,
                "history",
                o -> new UpgradeDowngrade(
                        instant(o, "epochGradeDate"),
                        str(o, "firm"),
                        str(o, "toGrade"),
                        str(o, "fromGrade"),
                        str(o, "action"),
                        str(o, "priceTargetAction"),
                        dbl(o, "currentPriceTarget"),
                        dbl(o, "priorPriceTarget")));
    }

    /** The {@code target*Price} and {@code currentPrice} fields of {@code financialData}. */
    public static Optional<PriceTargets> priceTargets(@Nullable JsonNode module) {
        if (module == null || !module.isObject()) {
            return Optional.empty();
        }
        return Optional.of(new PriceTargets(
                dbl(module, "currentPrice"),
                dbl(module, "targetHighPrice"),
                dbl(module, "targetLowPrice"),
                dbl(module, "targetMeanPrice"),
                dbl(module, "targetMedianPrice")));
    }

    /** {@code earningsTrend[].earningsEstimate}. */
    public static List<EarningsEstimate> earningsEstimate(@Nullable JsonNode module) {
        return periodic(
                module,
                "earningsEstimate",
                (t, o) -> new EarningsEstimate(
                        str(t, "period"),
                        isoDate(t, "endDate"),
                        lng(o, "numberOfAnalysts"),
                        dbl(o, "avg"),
                        dbl(o, "low"),
                        dbl(o, "high"),
                        dbl(o, "yearAgoEps"),
                        dbl(o, "growth"),
                        str(o, "earningsCurrency")));
    }

    /** {@code earningsTrend[].revenueEstimate}. */
    public static List<RevenueEstimate> revenueEstimate(@Nullable JsonNode module) {
        return periodic(
                module,
                "revenueEstimate",
                (t, o) -> new RevenueEstimate(
                        str(t, "period"),
                        isoDate(t, "endDate"),
                        lng(o, "numberOfAnalysts"),
                        dbl(o, "avg"),
                        dbl(o, "low"),
                        dbl(o, "high"),
                        dbl(o, "yearAgoRevenue"),
                        dbl(o, "growth"),
                        str(o, "revenueCurrency")));
    }

    /** {@code earningsTrend[].epsTrend}. */
    public static List<EpsTrend> epsTrend(@Nullable JsonNode module) {
        return periodic(
                module,
                "epsTrend",
                (t, o) -> new EpsTrend(
                        str(t, "period"),
                        isoDate(t, "endDate"),
                        dbl(o, "current"),
                        dbl(o, "7daysAgo"),
                        dbl(o, "30daysAgo"),
                        dbl(o, "60daysAgo"),
                        dbl(o, "90daysAgo"),
                        str(o, "epsTrendCurrency")));
    }

    /** {@code earningsTrend[].epsRevisions}. Yahoo spells the field names inconsistently. */
    public static List<EpsRevisions> epsRevisions(@Nullable JsonNode module) {
        return periodic(
                module,
                "epsRevisions",
                (t, o) -> new EpsRevisions(
                        str(t, "period"),
                        isoDate(t, "endDate"),
                        lng(o, "upLast7days"),
                        lng(o, "upLast30days"),
                        lng(o, "downLast7Days"),
                        lng(o, "downLast30days"),
                        str(o, "epsRevisionsCurrency")));
    }

    /** {@code earningsHistory}, oldest quarter first, as Yahoo sends it. */
    public static List<EarningsHistoryEntry> earningsHistory(@Nullable JsonNode module) {
        return list(
                module,
                "history",
                o -> new EarningsHistoryEntry(
                        date(o, "quarter"),
                        str(o, "period"),
                        dbl(o, "epsEstimate"),
                        dbl(o, "epsActual"),
                        dbl(o, "epsDifference"),
                        dbl(o, "surprisePercent"),
                        str(o, "currency")));
    }

    /**
     * Growth per period: the symbol's from every {@code earningsTrend} entry, then the {@code
     * industryTrend}, {@code sectorTrend} and {@code indexTrend} estimates joined by period. Periods
     * only a benchmark has (e.g. {@code LTG}) are appended; periods with no figure at all are
     * dropped, like yfinance's {@code dropna(how="all")}.
     */
    public static List<GrowthEstimate> growthEstimates(Map<QuoteSummaryModule, JsonNode> modules) {
        var rows = new LinkedHashMap<String, Double[]>();
        JsonNode trend = modules.get(QuoteSummaryModule.EARNINGS_TREND);
        if (trend != null) {
            for (JsonNode t : trend.path("trend")) {
                String period = t.isObject() ? str(t, "period") : null;
                if (period != null) {
                    rows.computeIfAbsent(period, p -> new Double[4])[0] = dbl(t, "growth");
                }
            }
        }
        benchmark(rows, modules.get(QuoteSummaryModule.INDUSTRY_TREND), 1);
        benchmark(rows, modules.get(QuoteSummaryModule.SECTOR_TREND), 2);
        benchmark(rows, modules.get(QuoteSummaryModule.INDEX_TREND), 3);

        var out = new ArrayList<GrowthEstimate>(rows.size());
        rows.forEach((period, g) -> {
            if (g[0] != null || g[1] != null || g[2] != null || g[3] != null) {
                out.add(new GrowthEstimate(period, g[0], g[1], g[2], g[3]));
            }
        });
        return List.copyOf(out);
    }

    private static void benchmark(Map<String, Double[]> rows, @Nullable JsonNode module, int column) {
        if (module == null) {
            return;
        }
        for (JsonNode e : module.path("estimates")) {
            String period = e.isObject() ? str(e, "period") : null;
            if (period != null) {
                rows.computeIfAbsent(period, p -> new Double[4])[column] = dbl(e, "growth");
            }
        }
    }

    /** Maps the {@code key} sub-object of the first four {@code earningsTrend} entries that have one. */
    private static <T> List<T> periodic(
            @Nullable JsonNode module, String key, BiFunction<JsonNode, JsonNode, T> mapper) {
        if (module == null) {
            return List.of();
        }
        var out = new ArrayList<T>(PERIODIC_ROWS);
        int seen = 0;
        for (JsonNode t : module.path("trend")) {
            if (seen++ == PERIODIC_ROWS) {
                break;
            }
            JsonNode sub = t.path(key);
            if (t.isObject() && sub.isObject()) {
                out.add(mapper.apply(t, sub));
            }
        }
        return List.copyOf(out);
    }
}
