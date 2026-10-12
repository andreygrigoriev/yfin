package io.andreygrigoriev.yfin;

import io.andreygrigoriev.yfin.internal.endpoint.chart.ChartEndpoint;
import io.andreygrigoriev.yfin.model.Actions;
import io.andreygrigoriev.yfin.model.CapitalGain;
import io.andreygrigoriev.yfin.model.Dividend;
import io.andreygrigoriev.yfin.model.HistoryMetadata;
import io.andreygrigoriev.yfin.model.PriceHistory;
import io.andreygrigoriev.yfin.model.Split;
import io.andreygrigoriev.yfin.request.HistoryRequest;
import io.andreygrigoriev.yfin.request.Interval;
import io.andreygrigoriev.yfin.request.Period;
import java.util.List;
import java.util.Objects;

/**
 * Data for one symbol. Obtained from {@link YFinance#ticker(String)}; cheap to create, makes no
 * request until a data method is called. Holds no data itself: every call fetches. Thread-safe.
 *
 * <p>Data methods throw {@link io.andreygrigoriev.yfin.exception.YFSymbolNotFoundException} for an
 * unknown or delisted symbol, and other {@link io.andreygrigoriev.yfin.exception.YFinanceException}s
 * for transport, rate-limit and parsing failures.
 */
public final class Ticker {

    private static final HistoryRequest ALL_DAILY = HistoryRequest.builder()
            .period(Period.MAX)
            .interval(Interval.ONE_DAY)
            .prepost(true)
            .build();

    private final YFinance client;
    private final String symbol;

    Ticker(YFinance client, String symbol) {
        this.client = Objects.requireNonNull(client, "client");
        this.symbol = Objects.requireNonNull(symbol, "symbol");
    }

    /** The normalized (trimmed, upper-case) symbol. */
    public String symbol() {
        return symbol;
    }

    /** Price history for {@code request}. */
    public PriceHistory history(HistoryRequest request) {
        Objects.requireNonNull(request, "request");
        return chart().history(symbol, request);
    }

    /** Daily price history over {@code period}, other settings at their defaults. */
    public PriceHistory history(Period period) {
        return history(HistoryRequest.of(period));
    }

    /** Daily price history of the last month. */
    public PriceHistory history() {
        return history(HistoryRequest.builder().build());
    }

    /** All dividends, oldest first. */
    public List<Dividend> dividends() {
        return history(ALL_DAILY).dividends();
    }

    /** All stock splits, oldest first. */
    public List<Split> splits() {
        return history(ALL_DAILY).splits();
    }

    /** All capital gains distributions, oldest first; empty unless the symbol is a fund or ETF. */
    public List<CapitalGain> capitalGains() {
        return history(ALL_DAILY).capitalGains();
    }

    /** All dividends, splits and capital gains, from one request. */
    public Actions actions() {
        return history(ALL_DAILY).actions();
    }

    /** Chart metadata (exchange, timezone, latest regular-market quote, valid ranges). */
    public HistoryMetadata historyMetadata() {
        return chart().metadata(symbol);
    }

    YFinance client() {
        return client;
    }

    private ChartEndpoint chart() {
        return new ChartEndpoint(client.session(), client.cache());
    }

    @Override
    public String toString() {
        return "Ticker[" + symbol + "]";
    }
}
