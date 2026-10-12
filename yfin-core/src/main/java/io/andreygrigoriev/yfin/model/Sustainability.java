package io.andreygrigoriev.yfin.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * ESG risk scores (Yahoo {@code esgScores}, yfinance {@code sustainability}). Scores are
 * Sustainalytics risk ratings: lower means less risk. Any typed field is {@code null} when Yahoo
 * omits it; {@link #raw()} holds every field as sent, including peer statistics and the
 * product-involvement flags ({@code tobacco}, {@code gambling}, …).
 *
 * @param totalEsg total ESG risk score
 * @param environmentScore environment risk score
 * @param socialScore social risk score
 * @param governanceScore governance risk score
 * @param percentile percentile of {@code totalEsg} within the peer group
 * @param highestControversy highest controversy level, {@code 0..5}
 * @param esgPerformance rating against peers, e.g. {@code AVG_PERF}, {@code UNDER_PERF}
 * @param peerGroup peer group name
 * @param peerCount number of peers
 * @param ratingYear year of the rating
 * @param ratingMonth month of the rating, {@code 1..12}
 * @param relatedControversy controversy topics; unmodifiable, empty when none
 * @param raw all fields, unmodifiable, in Yahoo's order: numbers as {@code Long} or {@code
 *     Double}, objects as {@code Map}, arrays as {@code List}
 */
public record Sustainability(
        @Nullable Double totalEsg,
        @Nullable Double environmentScore,
        @Nullable Double socialScore,
        @Nullable Double governanceScore,
        @Nullable Double percentile,
        @Nullable Double highestControversy,
        @Nullable String esgPerformance,
        @Nullable String peerGroup,
        @Nullable Long peerCount,
        @Nullable Long ratingYear,
        @Nullable Long ratingMonth,
        List<String> relatedControversy,
        Map<String, Object> raw) {

    public Sustainability {
        relatedControversy = List.copyOf(relatedControversy);
        raw = Collections.unmodifiableMap(new LinkedHashMap<>(raw));
    }
}
