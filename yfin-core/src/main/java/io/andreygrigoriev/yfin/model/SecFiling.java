package io.andreygrigoriev.yfin.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One SEC filing (Yahoo {@code secFilings}, yfinance {@code sec_filings}). Any scalar is {@code
 * null} when Yahoo omits it.
 *
 * @param date filing date
 * @param time filing time
 * @param type form type, e.g. {@code 10-K} or {@code 8-K}
 * @param title Yahoo's description of the filing
 * @param edgarUrl link to the filing on Yahoo's EDGAR viewer
 * @param exhibits exhibit URLs by exhibit type (e.g. {@code EX-31.1}), in Yahoo's order;
 *     unmodifiable, empty when there are none
 */
public record SecFiling(
        @Nullable LocalDate date,
        @Nullable Instant time,
        @Nullable String type,
        @Nullable String title,
        @Nullable String edgarUrl,
        Map<String, String> exhibits) {

    public SecFiling {
        exhibits = Collections.unmodifiableMap(new LinkedHashMap<>(exhibits));
    }
}
