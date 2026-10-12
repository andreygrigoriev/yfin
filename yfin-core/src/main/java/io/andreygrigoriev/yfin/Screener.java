package io.andreygrigoriev.yfin;

import static io.andreygrigoriev.yfin.request.SortField.ascending;
import static io.andreygrigoriev.yfin.request.SortField.descending;

import io.andreygrigoriev.yfin.request.EquityQuery;
import io.andreygrigoriev.yfin.request.EtfQuery;
import io.andreygrigoriev.yfin.request.FundQuery;
import io.andreygrigoriev.yfin.request.ScreenerQuery;
import io.andreygrigoriev.yfin.request.SortField;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * A screen (yfinance {@code screen}), passed to {@link YFinance#screen(Screener)}: either a custom
 * {@link ScreenerQuery} or the name of one of Yahoo's predefined screens. Immutable: every setter
 * returns a copy.
 *
 * <pre>{@code
 * ScreenerResult gainers = yf.screen(Screener.predefined("day_gainers").size(50));
 * ScreenerResult custom = yf.screen(Screener.of(EquityQuery.and(
 *                 EquityQuery.gt("percentchange", 3),
 *                 EquityQuery.eq("region", "us")))
 *         .sort(SortField.ascending("percentchange")));
 * }</pre>
 *
 * <p>Custom screens default to offset 0, {@value #DEFAULT_SIZE} results, sorted by {@link
 * SortField#DEFAULT}. A predefined screen runs on Yahoo's predefined endpoint, which applies the
 * screen's own sort; it ignores offsets, so a predefined screen with an {@link #offset(int)} runs
 * as the equivalent custom query of {@link #predefinedQuery(String)} instead, as in yfinance.
 */
public final class Screener {

    /** Default number of results, as in yfinance. */
    public static final int DEFAULT_SIZE = 25;
    /** Maximum number of results Yahoo returns per request. */
    public static final int MAX_SIZE = 250;

    private static final Map<String, Screener> PREDEFINED = predefinedTable();

    private final @Nullable ScreenerQuery query;
    private final @Nullable String predefined;
    private final @Nullable Integer offset;
    private final @Nullable Integer size;
    private final @Nullable SortField sort;

    private Screener(
            @Nullable ScreenerQuery query,
            @Nullable String predefined,
            @Nullable Integer offset,
            @Nullable Integer size,
            @Nullable SortField sort) {
        this.query = query;
        this.predefined = predefined;
        this.offset = offset;
        this.size = size;
        this.sort = sort;
    }

    /** Custom screen of {@code query}. */
    public static Screener of(ScreenerQuery query) {
        return new Screener(Objects.requireNonNull(query, "query"), null, null, null, null);
    }

    /**
     * Yahoo's predefined screen {@code name} (trimmed), e.g. {@code day_gainers}, {@code
     * most_actives}. Names outside {@link #predefinedNames()} are passed to Yahoo as they are.
     *
     * @throws IllegalArgumentException when {@code name} is blank
     */
    public static Screener predefined(String name) {
        String n = Objects.requireNonNull(name, "name").trim();
        if (n.isEmpty()) {
            throw new IllegalArgumentException("predefined screen name is blank");
        }
        return new Screener(null, n, null, null, null);
    }

    /**
     * Index of the first result.
     *
     * @throws IllegalArgumentException when {@code offset} is negative
     */
    public Screener offset(int offset) {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be >= 0: " + offset);
        }
        return new Screener(query, predefined, offset, size, sort);
    }

    /**
     * Number of results, at most {@value #MAX_SIZE} (yfinance {@code size}, or {@code count} for
     * predefined screens).
     *
     * @throws IllegalArgumentException when {@code size} is not in 1..250
     */
    public Screener size(int size) {
        if (size < 1 || size > MAX_SIZE) {
            throw new IllegalArgumentException("size must be in 1.." + MAX_SIZE + ": " + size);
        }
        return new Screener(query, predefined, offset, size, sort);
    }

    /** Sort order of the results. */
    public Screener sort(SortField sort) {
        return new Screener(query, predefined, offset, size, Objects.requireNonNull(sort, "sort"));
    }

    /** Custom query, or {@code null} for a predefined screen. */
    public @Nullable ScreenerQuery query() {
        return query;
    }

    /** Predefined screen name, or {@code null} for a custom screen. */
    public @Nullable String predefinedName() {
        return predefined;
    }

    /** Offset, or {@code null} when not set. */
    public @Nullable Integer offset() {
        return offset;
    }

    /** Number of results, or {@code null} when not set. */
    public @Nullable Integer size() {
        return size;
    }

    /** Sort order, or {@code null} when not set. */
    public @Nullable SortField sort() {
        return sort;
    }

    /**
     * Names of the predefined screens whose queries yfinance knows (yfinance {@code
     * PREDEFINED_SCREENER_QUERIES}), in yfinance's order.
     */
    public static Set<String> predefinedNames() {
        return PREDEFINED.keySet();
    }

    /**
     * The custom screen equivalent to predefined screen {@code name}: its query and sort order.
     *
     * @throws IllegalArgumentException when {@code name} is not one of {@link #predefinedNames()}
     */
    public static Screener predefinedQuery(String name) {
        Screener s = PREDEFINED.get(Objects.requireNonNull(name, "name").trim());
        if (s == null) {
            throw new IllegalArgumentException("unknown predefined screen: " + name + "; known: " + predefinedNames());
        }
        return s;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Screener s
                && Objects.equals(query, s.query)
                && Objects.equals(predefined, s.predefined)
                && Objects.equals(offset, s.offset)
                && Objects.equals(size, s.size)
                && Objects.equals(sort, s.sort);
    }

    @Override
    public int hashCode() {
        return Objects.hash(query, predefined, offset, size, sort);
    }

    @Override
    public String toString() {
        return "Screener[" + (query != null ? "query=" + query : "predefined=" + predefined) + ", offset=" + offset
                + ", size=" + size + ", sort=" + sort + "]";
    }

    /** yfinance 1.7.0 {@code PREDEFINED_SCREENER_QUERIES}. */
    private static Map<String, Screener> predefinedTable() {
        var m = new LinkedHashMap<String, Screener>();
        EquityQuery nmsNyq = EquityQuery.isIn("exchange", "NMS", "NYQ");
        m.put(
                "aggressive_small_caps",
                of(EquityQuery.and(nmsNyq, EquityQuery.lt("epsgrowth.lasttwelvemonths", 15)))
                        .sort(descending("eodvolume")));
        m.put(
                "day_gainers",
                of(EquityQuery.and(
                                EquityQuery.gt("percentchange", 3),
                                EquityQuery.eq("region", "us"),
                                EquityQuery.gte("intradaymarketcap", 2_000_000_000L),
                                EquityQuery.gte("intradayprice", 5),
                                EquityQuery.gt("dayvolume", 15_000)))
                        .sort(descending("percentchange")));
        m.put(
                "day_losers",
                of(EquityQuery.and(
                                EquityQuery.lt("percentchange", -2.5),
                                EquityQuery.eq("region", "us"),
                                EquityQuery.gte("intradaymarketcap", 2_000_000_000L),
                                EquityQuery.gte("intradayprice", 5),
                                EquityQuery.gt("dayvolume", 20_000)))
                        .sort(ascending("percentchange")));
        m.put(
                "growth_technology_stocks",
                of(EquityQuery.and(
                                EquityQuery.gte("quarterlyrevenuegrowth.quarterly", 25),
                                EquityQuery.gte("epsgrowth.lasttwelvemonths", 25),
                                EquityQuery.eq("sector", "Technology"),
                                nmsNyq))
                        .sort(descending("eodvolume")));
        m.put(
                "most_actives",
                of(EquityQuery.and(
                                EquityQuery.eq("region", "us"),
                                EquityQuery.gte("intradaymarketcap", 2_000_000_000L),
                                EquityQuery.gt("dayvolume", 5_000_000)))
                        .sort(descending("dayvolume")));
        m.put(
                "most_shorted_stocks",
                of(EquityQuery.and(
                                EquityQuery.eq("region", "us"),
                                EquityQuery.gt("intradayprice", 1),
                                EquityQuery.gt("avgdailyvol3m", 200_000)))
                        .sort(descending("short_percentage_of_shares_outstanding.value")));
        m.put(
                "small_cap_gainers",
                of(EquityQuery.and(EquityQuery.lt("intradaymarketcap", 2_000_000_000L), nmsNyq))
                        .sort(descending("eodvolume")));
        m.put(
                "undervalued_growth_stocks",
                of(EquityQuery.and(
                                EquityQuery.btwn("peratio.lasttwelvemonths", 0, 20),
                                EquityQuery.lt("pegratio_5y", 1),
                                EquityQuery.gte("epsgrowth.lasttwelvemonths", 25),
                                nmsNyq))
                        .sort(descending("eodvolume")));
        m.put(
                "undervalued_large_caps",
                of(EquityQuery.and(
                                EquityQuery.btwn("peratio.lasttwelvemonths", 0, 20),
                                EquityQuery.lt("pegratio_5y", 1),
                                EquityQuery.btwn("intradaymarketcap", 10_000_000_000L, 100_000_000_000L),
                                nmsNyq))
                        .sort(descending("eodvolume")));
        FundQuery rated = FundQuery.isIn("performanceratingoverall", 4, 5);
        FundQuery minInvestment = FundQuery.lt("initialinvestment", 100_001);
        FundQuery topHalf = FundQuery.lt("annualreturnnavy1categoryrank", 50);
        FundQuery lowRisk = FundQuery.isIn("riskratingoverall", 1, 2, 3);
        FundQuery nas = FundQuery.eq("exchange", "NAS");
        m.put(
                "conservative_foreign_funds",
                of(FundQuery.and(
                                FundQuery.isIn(
                                        "categoryname",
                                        "Foreign Large Value",
                                        "Foreign Large Blend",
                                        "Foreign Large Growth",
                                        "Foreign Small/Mid Growth",
                                        "Foreign Small/Mid Blend",
                                        "Foreign Small/Mid Value"),
                                rated,
                                minInvestment,
                                topHalf,
                                lowRisk,
                                nas))
                        .sort(descending("fundnetassets")));
        m.put(
                "high_yield_bond",
                of(FundQuery.and(
                                rated,
                                minInvestment,
                                topHalf,
                                lowRisk,
                                FundQuery.eq("categoryname", "High Yield Bond"),
                                nas))
                        .sort(descending("fundnetassets")));
        m.put(
                "portfolio_anchors",
                of(FundQuery.and(FundQuery.eq("categoryname", "Large Blend"), rated, minInvestment, topHalf, nas))
                        .sort(descending("fundnetassets")));
        m.put(
                "solid_large_growth_funds",
                of(FundQuery.and(FundQuery.eq("categoryname", "Large Growth"), rated, minInvestment, topHalf, nas))
                        .sort(descending("fundnetassets")));
        m.put(
                "solid_midcap_growth_funds",
                of(FundQuery.and(FundQuery.eq("categoryname", "Mid-Cap Growth"), rated, minInvestment, topHalf, nas))
                        .sort(descending("fundnetassets")));
        m.put(
                "top_mutual_funds",
                of(FundQuery.and(
                                FundQuery.gt("intradayprice", 15), rated, FundQuery.gt("initialinvestment", 1000), nas))
                        .sort(descending("percentchange")));
        EtfQuery etfRated = EtfQuery.isIn("performanceratingoverall", 4, 5);
        EtfQuery us = EtfQuery.eq("region", "us");
        m.put(
                "top_etfs_us",
                of(EtfQuery.and(EtfQuery.gt("intradayprice", 10), etfRated, us)).sort(descending("percentchange")));
        m.put(
                "top_performing_etfs",
                of(EtfQuery.and(us, etfRated, EtfQuery.gt("intradayprice", 10)))
                        .sort(ascending("annualreportnetexpenseratio")));
        m.put(
                "technology_etfs",
                of(EtfQuery.and(us, EtfQuery.eq("categoryname", "Technology")))
                        .sort(ascending("annualreportnetexpenseratio")));
        m.put(
                "bond_etfs",
                of(EtfQuery.and(
                                us,
                                EtfQuery.isIn(
                                        "categoryname",
                                        "Corporate Bond",
                                        "Emerging Markets Bond",
                                        "Emerging-Markets Local-Currency Bond",
                                        "High Yield Bond",
                                        "Intermediate-Term Bond",
                                        "Long-Term Bond",
                                        "Inflation-Protected Bond",
                                        "Multisector Bond",
                                        "Nontraditional Bond",
                                        "Short-Term Bond",
                                        "Ultrashort Bond",
                                        "World Bond")))
                        .sort(ascending("annualreportnetexpenseratio")));
        return Collections.unmodifiableMap(m);
    }
}
