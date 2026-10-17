package io.github.andreygrigoriev.yfin.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Everything Yahoo reports about a sector, from one request (yfinance {@code Sector}). All
 * collections are unmodifiable; categories Yahoo has nothing for are empty.
 *
 * @param key sector key, e.g. {@code technology}
 * @param name sector name
 * @param symbol Yahoo's index symbol of the sector, e.g. {@code ^YH311}
 * @param overview overview
 * @param topCompanies largest companies, by market weight
 * @param topEtfs top ETFs, symbol to name, in Yahoo's order
 * @param topMutualFunds top mutual funds, symbol to name, in Yahoo's order
 * @param industries the sector's industries (Yahoo's "All Industries" row dropped, as yfinance)
 * @param researchReports research reports, plain values as in {@link TickerInfo#raw()}
 * @param raw the whole {@code data} object as Yahoo returns it (also performance figures)
 */
public record SectorData(
        String key,
        @Nullable String name,
        @Nullable String symbol,
        DomainOverview overview,
        List<TopCompany> topCompanies,
        Map<String, String> topEtfs,
        Map<String, String> topMutualFunds,
        List<SectorIndustry> industries,
        List<Map<String, Object>> researchReports,
        Map<String, Object> raw) {

    public SectorData {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(overview, "overview");
        topCompanies = List.copyOf(topCompanies);
        topEtfs = Collections.unmodifiableMap(new LinkedHashMap<>(topEtfs));
        topMutualFunds = Collections.unmodifiableMap(new LinkedHashMap<>(topMutualFunds));
        industries = List.copyOf(industries);
        researchReports = List.copyOf(researchReports);
        raw = Collections.unmodifiableMap(new LinkedHashMap<>(raw));
    }
}
