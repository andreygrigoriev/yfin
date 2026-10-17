package io.github.andreygrigoriev.yfin.request;

/** Which news stream {@link io.github.andreygrigoriev.yfin.Ticker#news(int, NewsTab)} reads (yfinance {@code tab}). */
public enum NewsTab {
    /** Latest news articles (yfinance {@code "news"}, the default). */
    NEWS("latestNews"),
    /** News and press releases (yfinance {@code "all"}). */
    ALL("newsAll"),
    /** Press releases only (yfinance {@code "press releases"}). */
    PRESS_RELEASES("pressRelease");

    private final String queryRef;

    NewsTab(String queryRef) {
        this.queryRef = queryRef;
    }

    /** Yahoo's {@code queryRef} parameter for this tab. */
    public String queryRef() {
        return queryRef;
    }
}
