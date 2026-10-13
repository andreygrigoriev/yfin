package io.andreygrigoriev.yfin;

import io.andreygrigoriev.yfin.exception.YFDownloadException;
import io.andreygrigoriev.yfin.model.NewsItem;
import io.andreygrigoriev.yfin.model.PriceHistory;
import io.andreygrigoriev.yfin.request.HistoryRequest;
import io.andreygrigoriev.yfin.request.Period;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SequencedSet;

/**
 * A fixed group of {@link Ticker}s, created by {@link YFinance#tickers(java.util.Collection)}.
 * Symbols are normalized and deduplicated; iteration follows input order. Holds no data.
 */
public final class Tickers {

    private final YFinance client;
    private final SequencedSet<String> symbols;
    private final Map<String, Ticker> tickers;

    Tickers(YFinance client, SequencedSet<String> symbols) {
        this.client = Objects.requireNonNull(client, "client");
        this.symbols = Objects.requireNonNull(symbols, "symbols");
        Map<String, Ticker> map = new LinkedHashMap<>();
        symbols.forEach(s -> map.put(s, new Ticker(client, s)));
        this.tickers = Collections.unmodifiableMap(map);
    }

    /** The normalized symbols, in input order. */
    public SequencedSet<String> symbols() {
        return symbols;
    }

    /** Ticker per normalized symbol, in input order. */
    public Map<String, Ticker> tickers() {
        return tickers;
    }

    /**
     * The ticker for {@code symbol} (normalized before lookup).
     *
     * @throws IllegalArgumentException when {@code symbol} is not part of this group
     */
    public Ticker ticker(String symbol) {
        Ticker t = tickers.get(YFinance.normalizeSymbol(symbol));
        if (t == null) {
            throw new IllegalArgumentException("not in this group: " + symbol + "; symbols: " + symbols);
        }
        return t;
    }

    /**
     * Price history of every symbol, via {@link YFinance#download(java.util.Collection,
     * HistoryRequest)}.
     *
     * @throws YFDownloadException when at least one symbol failed
     */
    public Map<String, PriceHistory> history(HistoryRequest request) {
        Objects.requireNonNull(request, "request");
        return client.download(symbols, request);
    }

    /** Daily price history over {@code period} of every symbol. */
    public Map<String, PriceHistory> history(Period period) {
        return history(HistoryRequest.of(period));
    }

    /** Daily price history of the last month of every symbol. */
    public Map<String, PriceHistory> history() {
        return history(HistoryRequest.builder().build());
    }

    /**
     * {@link Ticker#news()} of every symbol, in symbol order (yfinance {@code Tickers.news}); one
     * request per symbol, made one after another. A failure for any symbol propagates.
     */
    public Map<String, List<NewsItem>> news() {
        Map<String, List<NewsItem>> out = new LinkedHashMap<>();
        tickers.forEach((symbol, ticker) -> out.put(symbol, ticker.news()));
        return Collections.unmodifiableMap(out);
    }

    @Override
    public String toString() {
        return "Tickers" + symbols;
    }
}
