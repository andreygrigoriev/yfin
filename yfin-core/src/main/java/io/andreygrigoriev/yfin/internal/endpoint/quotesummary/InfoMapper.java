package io.andreygrigoriev.yfin.internal.endpoint.quotesummary;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.andreygrigoriev.yfin.internal.endpoint.timeseries.TimeseriesEndpoint;
import io.andreygrigoriev.yfin.internal.http.YahooJson;
import io.andreygrigoriev.yfin.model.TickerInfo;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Builds {@link TickerInfo} the way yfinance's {@code Quote._fetch_info} does. */
public final class InfoMapper {

    /** The {@code quoteSummary} modules behind {@code info}. */
    public static final Set<QuoteSummaryModule> INFO_MODULES = Collections.unmodifiableSet(EnumSet.of(
            QuoteSummaryModule.FINANCIAL_DATA,
            QuoteSummaryModule.QUOTE_TYPE,
            QuoteSummaryModule.DEFAULT_KEY_STATISTICS,
            QuoteSummaryModule.ASSET_PROFILE,
            QuoteSummaryModule.SUMMARY_DETAIL));

    private static final long DAY_SECONDS = 86_400;

    private InfoMapper() {}

    /**
     * Merges the modules (in response order) and the {@code /v7/quote} fields into one map, then
     * flattens one level: members of object values are lifted to the top, {@code null}s are
     * dropped, and {@code maxAge: 1} (days) becomes {@code 86400} (seconds). Later keys win.
     *
     * @param modules unwrapped modules, as returned by {@link QuoteSummaryEndpoint#fetch}
     * @param quote the quote object for the symbol, or {@code null} when Yahoo returned none
     */
    public static TickerInfo map(String symbol, Map<QuoteSummaryModule, JsonNode> modules, @Nullable JsonNode quote) {
        return map(symbol, modules, quote, null);
    }

    /**
     * {@link #map(String, Map, JsonNode)} plus yfinance's complementary {@code trailingPegRatio},
     * added last; absent when {@code trailingPegRatio} is {@code null}.
     */
    public static TickerInfo map(
            String symbol,
            Map<QuoteSummaryModule, JsonNode> modules,
            @Nullable JsonNode quote,
            @Nullable Double trailingPegRatio) {
        var merged = new LinkedHashMap<String, JsonNode>();
        if (!modules.isEmpty()) {
            modules.forEach((m, node) -> merged.put(m.yahooName(), node));
            merged.put("symbol", TextNode.valueOf(symbol));
        }
        if (quote != null && quote.isObject()) {
            JsonNode q = QuoteSummaryEndpoint.unwrap(quote);
            q.properties().forEach(e -> merged.put(e.getKey(), e.getValue()));
            merged.put("symbol", TextNode.valueOf(symbol));
        }

        var flat = new LinkedHashMap<String, JsonNode>();
        merged.forEach((key, value) -> {
            if (value.isObject()) {
                value.properties().forEach(e -> {
                    JsonNode v = e.getValue();
                    if (!v.isNull()) {
                        flat.put(e.getKey(), isMaxAgeInDays(e.getKey(), v) ? LongNode.valueOf(DAY_SECONDS) : v);
                    }
                });
            } else if (!value.isNull()) {
                flat.put(key, value);
            }
        });

        var raw = new LinkedHashMap<String, Object>();
        flat.forEach((key, value) -> {
            Object plain = YahooJson.toPlain(value);
            if (plain != null) {
                raw.put(key, plain);
            }
        });
        if (trailingPegRatio != null) {
            raw.put(TimeseriesEndpoint.TRAILING_PEG_RATIO, trailingPegRatio);
        }
        return new TickerInfo(symbol, raw);
    }

    private static boolean isMaxAgeInDays(String key, JsonNode value) {
        return key.equals("maxAge") && value.isIntegralNumber() && value.longValue() == 1;
    }
}
