package io.andreygrigoriev.yfin.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Everything Yahoo reports about an industry, from one request (yfinance {@code Industry}). All
 * collections are unmodifiable; categories Yahoo has nothing for are empty.
 *
 * @param key industry key, e.g. {@code semiconductors}
 * @param name industry name
 * @param symbol Yahoo's index symbol of the industry, e.g. {@code ^YH31130020}
 * @param sectorKey key of the sector the industry belongs to
 * @param sectorName name of that sector
 * @param overview overview
 * @param topCompanies largest companies, by market weight
 * @param topPerformingCompanies best performing companies
 * @param topGrowthCompanies fastest growing companies
 * @param researchReports research reports, plain values as in {@link TickerInfo#raw()}
 * @param raw the whole {@code data} object as Yahoo returns it (also performance figures)
 */
public record IndustryData(
        String key,
        @Nullable String name,
        @Nullable String symbol,
        @Nullable String sectorKey,
        @Nullable String sectorName,
        DomainOverview overview,
        List<TopCompany> topCompanies,
        List<PerformingCompany> topPerformingCompanies,
        List<GrowthCompany> topGrowthCompanies,
        List<Map<String, Object>> researchReports,
        Map<String, Object> raw) {

    public IndustryData {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(overview, "overview");
        topCompanies = List.copyOf(topCompanies);
        topPerformingCompanies = List.copyOf(topPerformingCompanies);
        topGrowthCompanies = List.copyOf(topGrowthCompanies);
        researchReports = List.copyOf(researchReports);
        raw = Collections.unmodifiableMap(new LinkedHashMap<>(raw));
    }
}
