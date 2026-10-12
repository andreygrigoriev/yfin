package io.andreygrigoriev.yfin.internal.endpoint.quotesummary;

import static io.andreygrigoriev.yfin.internal.endpoint.quotesummary.ModuleNodes.date;
import static io.andreygrigoriev.yfin.internal.endpoint.quotesummary.ModuleNodes.dbl;
import static io.andreygrigoriev.yfin.internal.endpoint.quotesummary.ModuleNodes.list;
import static io.andreygrigoriev.yfin.internal.endpoint.quotesummary.ModuleNodes.lng;
import static io.andreygrigoriev.yfin.internal.endpoint.quotesummary.ModuleNodes.str;

import com.fasterxml.jackson.databind.JsonNode;
import io.andreygrigoriev.yfin.model.Holder;
import io.andreygrigoriev.yfin.model.InsiderPurchases;
import io.andreygrigoriev.yfin.model.InsiderRosterEntry;
import io.andreygrigoriev.yfin.model.InsiderTransaction;
import io.andreygrigoriev.yfin.model.MajorHolders;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Maps the holder {@code quoteSummary} modules the way yfinance's {@code Holders} scraper does.
 * Every method takes one module as returned (unwrapped) by {@link QuoteSummaryEndpoint#fetch}, or
 * {@code null} when Yahoo returned none; a missing module gives an empty result, never an
 * exception. List order is Yahoo's.
 *
 * <p>yfinance requests {@code majorDirectHolders} too but does not parse it ("need more data to
 * investigate"; Yahoo sends an empty list), so it is not mapped here either.
 */
public final class HoldersMapper {

    private HoldersMapper() {}

    /** {@code majorHoldersBreakdown}; empty when the module is missing. */
    public static Optional<MajorHolders> majorHolders(@Nullable JsonNode module) {
        if (module == null || !module.isObject()) {
            return Optional.empty();
        }
        return Optional.of(new MajorHolders(
                dbl(module, "insidersPercentHeld"),
                dbl(module, "institutionsPercentHeld"),
                dbl(module, "institutionsFloatPercentHeld"),
                lng(module, "institutionsCount")));
    }

    /** {@code institutionOwnership} or {@code fundOwnership}. */
    public static List<Holder> ownership(@Nullable JsonNode module) {
        return list(
                module,
                "ownershipList",
                o -> new Holder(
                        date(o, "reportDate"),
                        str(o, "organization"),
                        lng(o, "position"),
                        lng(o, "value"),
                        dbl(o, "pctHeld"),
                        dbl(o, "pctChange")));
    }

    /** {@code insiderTransactions}. */
    public static List<InsiderTransaction> insiderTransactions(@Nullable JsonNode module) {
        return list(
                module,
                "transactions",
                o -> new InsiderTransaction(
                        date(o, "startDate"),
                        str(o, "filerName"),
                        str(o, "filerRelation"),
                        str(o, "filerUrl"),
                        str(o, "moneyText"),
                        str(o, "transactionText"),
                        lng(o, "shares"),
                        lng(o, "value"),
                        str(o, "ownership")));
    }

    /** {@code insiderHolders}. */
    public static List<InsiderRosterEntry> insiderRoster(@Nullable JsonNode module) {
        return list(
                module,
                "holders",
                o -> new InsiderRosterEntry(
                        str(o, "name"),
                        str(o, "relation"),
                        str(o, "url"),
                        str(o, "transactionDescription"),
                        date(o, "latestTransDate"),
                        date(o, "positionDirectDate"),
                        lng(o, "positionDirect"),
                        date(o, "positionIndirectDate"),
                        lng(o, "positionIndirect")));
    }

    /** {@code netSharePurchaseActivity}; empty when the module is missing. */
    public static Optional<InsiderPurchases> insiderPurchases(@Nullable JsonNode module) {
        if (module == null || !module.isObject()) {
            return Optional.empty();
        }
        return Optional.of(new InsiderPurchases(
                str(module, "period"),
                lng(module, "buyInfoShares"),
                lng(module, "buyInfoCount"),
                lng(module, "sellInfoShares"),
                lng(module, "sellInfoCount"),
                lng(module, "netInfoShares"),
                lng(module, "netInfoCount"),
                lng(module, "totalInsiderShares"),
                dbl(module, "netPercentInsiderShares"),
                dbl(module, "buyPercentInsiderShares"),
                dbl(module, "sellPercentInsiderShares")));
    }
}
