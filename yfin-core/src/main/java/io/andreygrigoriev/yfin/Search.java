package io.andreygrigoriev.yfin;

import java.util.Objects;

/**
 * Options of a Yahoo Finance search (yfinance {@code Search}), passed to {@link
 * YFinance#search(Search)}. Immutable: every setter returns a copy.
 *
 * <pre>{@code
 * SearchResult r = yf.search(Search.of("apple").maxResults(3).newsCount(0).includeResearch(true));
 * }</pre>
 *
 * <p>Defaults follow yfinance: 8 quotes, 8 news articles, 8 lists, 8 recommendations, company
 * breakdown on, navigation links, research reports, cultural assets and fuzzy matching off.
 */
public final class Search {

    private final String query;
    private final int maxResults;
    private final int newsCount;
    private final int listsCount;
    private final int recommended;
    private final boolean fuzzy;
    private final boolean includeCompanyBreakdown;
    private final boolean includeNavLinks;
    private final boolean includeResearch;
    private final boolean includeCulturalAssets;

    private Search(
            String query,
            int maxResults,
            int newsCount,
            int listsCount,
            int recommended,
            boolean fuzzy,
            boolean includeCompanyBreakdown,
            boolean includeNavLinks,
            boolean includeResearch,
            boolean includeCulturalAssets) {
        this.query = query;
        this.maxResults = maxResults;
        this.newsCount = newsCount;
        this.listsCount = listsCount;
        this.recommended = recommended;
        this.fuzzy = fuzzy;
        this.includeCompanyBreakdown = includeCompanyBreakdown;
        this.includeNavLinks = includeNavLinks;
        this.includeResearch = includeResearch;
        this.includeCulturalAssets = includeCulturalAssets;
    }

    /**
     * Search for {@code query} (a symbol or a company name, trimmed) with yfinance's defaults.
     *
     * @throws IllegalArgumentException when {@code query} is blank
     */
    public static Search of(String query) {
        return new Search(checkQuery(query), 8, 8, 8, 8, false, true, false, false, false);
    }

    /** Maximum number of quotes (yfinance {@code max_results}); {@code 0} for none. */
    public Search maxResults(int maxResults) {
        return new Search(
                query,
                count(maxResults, "maxResults"),
                newsCount,
                listsCount,
                recommended,
                fuzzy,
                includeCompanyBreakdown,
                includeNavLinks,
                includeResearch,
                includeCulturalAssets);
    }

    /** Number of news articles (yfinance {@code news_count}); {@code 0} for none. */
    public Search newsCount(int newsCount) {
        return new Search(
                query,
                maxResults,
                count(newsCount, "newsCount"),
                listsCount,
                recommended,
                fuzzy,
                includeCompanyBreakdown,
                includeNavLinks,
                includeResearch,
                includeCulturalAssets);
    }

    /** Number of lists (yfinance {@code lists_count}); {@code 0} for none. */
    public Search listsCount(int listsCount) {
        return new Search(
                query,
                maxResults,
                newsCount,
                count(listsCount, "listsCount"),
                recommended,
                fuzzy,
                includeCompanyBreakdown,
                includeNavLinks,
                includeResearch,
                includeCulturalAssets);
    }

    /** Recommended number of results (yfinance {@code recommended}). */
    public Search recommended(int recommended) {
        return new Search(
                query,
                maxResults,
                newsCount,
                listsCount,
                count(recommended, "recommended"),
                fuzzy,
                includeCompanyBreakdown,
                includeNavLinks,
                includeResearch,
                includeCulturalAssets);
    }

    /** Fuzzy matching for typos (yfinance {@code enable_fuzzy_query}). */
    public Search fuzzy(boolean fuzzy) {
        return new Search(
                query,
                maxResults,
                newsCount,
                listsCount,
                recommended,
                fuzzy,
                includeCompanyBreakdown,
                includeNavLinks,
                includeResearch,
                includeCulturalAssets);
    }

    /** Include the company breakdown (yfinance {@code include_cb}). */
    public Search includeCompanyBreakdown(boolean include) {
        return new Search(
                query,
                maxResults,
                newsCount,
                listsCount,
                recommended,
                fuzzy,
                include,
                includeNavLinks,
                includeResearch,
                includeCulturalAssets);
    }

    /** Include navigation links (yfinance {@code include_nav_links}). */
    public Search includeNavLinks(boolean include) {
        return new Search(
                query,
                maxResults,
                newsCount,
                listsCount,
                recommended,
                fuzzy,
                includeCompanyBreakdown,
                include,
                includeResearch,
                includeCulturalAssets);
    }

    /** Include research reports (yfinance {@code include_research}). */
    public Search includeResearch(boolean include) {
        return new Search(
                query,
                maxResults,
                newsCount,
                listsCount,
                recommended,
                fuzzy,
                includeCompanyBreakdown,
                includeNavLinks,
                include,
                includeCulturalAssets);
    }

    /** Include cultural assets (yfinance {@code include_cultural_assets}). */
    public Search includeCulturalAssets(boolean include) {
        return new Search(
                query,
                maxResults,
                newsCount,
                listsCount,
                recommended,
                fuzzy,
                includeCompanyBreakdown,
                includeNavLinks,
                includeResearch,
                include);
    }

    public String query() {
        return query;
    }

    public int maxResults() {
        return maxResults;
    }

    public int newsCount() {
        return newsCount;
    }

    public int listsCount() {
        return listsCount;
    }

    public int recommended() {
        return recommended;
    }

    public boolean fuzzy() {
        return fuzzy;
    }

    public boolean includeCompanyBreakdown() {
        return includeCompanyBreakdown;
    }

    public boolean includeNavLinks() {
        return includeNavLinks;
    }

    public boolean includeResearch() {
        return includeResearch;
    }

    public boolean includeCulturalAssets() {
        return includeCulturalAssets;
    }

    static String checkQuery(String query) {
        String q = Objects.requireNonNull(query, "query").trim();
        if (q.isEmpty()) {
            throw new IllegalArgumentException("query is blank");
        }
        return q;
    }

    private static int count(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be >= 0: " + value);
        }
        return value;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Search s
                && query.equals(s.query)
                && maxResults == s.maxResults
                && newsCount == s.newsCount
                && listsCount == s.listsCount
                && recommended == s.recommended
                && fuzzy == s.fuzzy
                && includeCompanyBreakdown == s.includeCompanyBreakdown
                && includeNavLinks == s.includeNavLinks
                && includeResearch == s.includeResearch
                && includeCulturalAssets == s.includeCulturalAssets;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                query,
                maxResults,
                newsCount,
                listsCount,
                recommended,
                fuzzy,
                includeCompanyBreakdown,
                includeNavLinks,
                includeResearch,
                includeCulturalAssets);
    }

    @Override
    public String toString() {
        return "Search[query=" + query + ", maxResults=" + maxResults + ", newsCount=" + newsCount
                + ", listsCount=" + listsCount + ", recommended=" + recommended + ", fuzzy=" + fuzzy
                + ", companyBreakdown=" + includeCompanyBreakdown + ", navLinks=" + includeNavLinks
                + ", research=" + includeResearch + ", culturalAssets=" + includeCulturalAssets + "]";
    }
}
