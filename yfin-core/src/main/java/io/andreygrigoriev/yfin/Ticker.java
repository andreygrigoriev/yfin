package io.andreygrigoriev.yfin;

import com.fasterxml.jackson.databind.JsonNode;
import io.andreygrigoriev.yfin.internal.endpoint.chart.ChartEndpoint;
import io.andreygrigoriev.yfin.internal.endpoint.earnings.EarningsDatesEndpoint;
import io.andreygrigoriev.yfin.internal.endpoint.options.OptionsEndpoint;
import io.andreygrigoriev.yfin.internal.endpoint.quote.QuoteEndpoint;
import io.andreygrigoriev.yfin.internal.endpoint.quotesummary.AnalystMapper;
import io.andreygrigoriev.yfin.internal.endpoint.quotesummary.CalendarMapper;
import io.andreygrigoriev.yfin.internal.endpoint.quotesummary.HoldersMapper;
import io.andreygrigoriev.yfin.internal.endpoint.quotesummary.InfoMapper;
import io.andreygrigoriev.yfin.internal.endpoint.quotesummary.QuoteSummaryEndpoint;
import io.andreygrigoriev.yfin.internal.endpoint.quotesummary.QuoteSummaryModule;
import io.andreygrigoriev.yfin.internal.endpoint.quotesummary.SustainabilityMapper;
import io.andreygrigoriev.yfin.internal.endpoint.timeseries.StatementKeys;
import io.andreygrigoriev.yfin.internal.endpoint.timeseries.TimeseriesEndpoint;
import io.andreygrigoriev.yfin.internal.http.YahooJson;
import io.andreygrigoriev.yfin.model.Actions;
import io.andreygrigoriev.yfin.model.Calendar;
import io.andreygrigoriev.yfin.model.CapitalGain;
import io.andreygrigoriev.yfin.model.Dividend;
import io.andreygrigoriev.yfin.model.EarningsDate;
import io.andreygrigoriev.yfin.model.EarningsEstimate;
import io.andreygrigoriev.yfin.model.EarningsHistoryEntry;
import io.andreygrigoriev.yfin.model.EpsRevisions;
import io.andreygrigoriev.yfin.model.EpsTrend;
import io.andreygrigoriev.yfin.model.FastInfo;
import io.andreygrigoriev.yfin.model.FinancialStatement;
import io.andreygrigoriev.yfin.model.GrowthEstimate;
import io.andreygrigoriev.yfin.model.HistoryMetadata;
import io.andreygrigoriev.yfin.model.Holder;
import io.andreygrigoriev.yfin.model.InsiderPurchases;
import io.andreygrigoriev.yfin.model.InsiderRosterEntry;
import io.andreygrigoriev.yfin.model.InsiderTransaction;
import io.andreygrigoriev.yfin.model.MajorHolders;
import io.andreygrigoriev.yfin.model.OptionChain;
import io.andreygrigoriev.yfin.model.PriceHistory;
import io.andreygrigoriev.yfin.model.PriceTargets;
import io.andreygrigoriev.yfin.model.Recommendation;
import io.andreygrigoriev.yfin.model.RevenueEstimate;
import io.andreygrigoriev.yfin.model.Split;
import io.andreygrigoriev.yfin.model.Sustainability;
import io.andreygrigoriev.yfin.model.TickerInfo;
import io.andreygrigoriev.yfin.model.UpgradeDowngrade;
import io.andreygrigoriev.yfin.request.Frequency;
import io.andreygrigoriev.yfin.request.HistoryRequest;
import io.andreygrigoriev.yfin.request.Interval;
import io.andreygrigoriev.yfin.request.Period;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

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

    private static final HistoryRequest FAST_INFO_YEAR = HistoryRequest.builder()
            .period(Period.ONE_YEAR)
            .interval(Interval.ONE_DAY)
            .autoAdjust(false)
            .keepNa(true)
            .build();

    private static final HistoryRequest FAST_INFO_WEEK_HOURLY = HistoryRequest.builder()
            .period(Period.FIVE_DAYS)
            .interval(Interval.ONE_HOUR)
            .autoAdjust(false)
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

    /**
     * Everything Yahoo reports about the symbol (profile, valuation, key statistics, latest quote),
     * flattened like yfinance's {@code info}. Two requests: {@code quoteSummary} and {@code
     * /v7/finance/quote}. Fields Yahoo has no data for are absent, never an error.
     */
    public TickerInfo info() {
        Map<QuoteSummaryModule, JsonNode> modules =
                new QuoteSummaryEndpoint(client.session()).fetch(symbol, InfoMapper.INFO_MODULES);
        JsonNode quote = new QuoteEndpoint(client.session()).quote(symbol).orElse(null);
        return InfoMapper.map(symbol, modules, quote);
    }

    /**
     * A lazily computed subset of {@link #info()}, like yfinance's {@code fast_info}: mostly from
     * one year of daily bars. Requests are made on first access to a value that needs them and at
     * most once per returned instance.
     */
    public FastInfo fastInfo() {
        return fastInfo(Instant::now);
    }

    /** {@link #fastInfo()} with a custom clock, for tests. */
    FastInfo fastInfo(Supplier<Instant> clock) {
        return new FastInfo(new FastInfo.Source() {
            @Override
            public PriceHistory yearDaily() {
                return history(FAST_INFO_YEAR);
            }

            @Override
            public PriceHistory weekHourly(boolean prepost) {
                return history(
                        FAST_INFO_WEEK_HOURLY.toBuilder().prepost(prepost).build());
            }

            @Override
            @SuppressWarnings("unchecked")
            public Map<String, Object> quote() {
                return new QuoteEndpoint(client.session())
                        .quote(symbol)
                        .map(q -> (Map<String, Object>) YahooJson.toPlain(q))
                        .orElse(Map.of());
            }

            @Override
            public Instant now() {
                return clock.get();
            }

            @Override
            public String toString() {
                return symbol;
            }
        });
    }

    /** Ownership breakdown (insiders, institutions); empty when Yahoo has none, e.g. for an ETF. */
    public Optional<MajorHolders> majorHolders() {
        return HoldersMapper.majorHolders(module(QuoteSummaryModule.MAJOR_HOLDERS_BREAKDOWN));
    }

    /** Top institutional holders, as Yahoo orders them; empty when Yahoo has none. */
    public List<Holder> institutionalHolders() {
        return HoldersMapper.ownership(module(QuoteSummaryModule.INSTITUTION_OWNERSHIP));
    }

    /** Top mutual fund holders, as Yahoo orders them; empty when Yahoo has none. */
    public List<Holder> mutualFundHolders() {
        return HoldersMapper.ownership(module(QuoteSummaryModule.FUND_OWNERSHIP));
    }

    /** Recent insider transactions, newest first; empty when Yahoo has none. */
    public List<InsiderTransaction> insiderTransactions() {
        return HoldersMapper.insiderTransactions(module(QuoteSummaryModule.INSIDER_TRANSACTIONS));
    }

    /** Insider buying and selling over the last months; empty when Yahoo has none. */
    public Optional<InsiderPurchases> insiderPurchases() {
        return HoldersMapper.insiderPurchases(module(QuoteSummaryModule.NET_SHARE_PURCHASE_ACTIVITY));
    }

    /** Insiders and their current positions; empty when Yahoo has none. */
    public List<InsiderRosterEntry> insiderRosterHolders() {
        return HoldersMapper.insiderRoster(module(QuoteSummaryModule.INSIDER_HOLDERS));
    }

    /** Analyst rating counts per month, current month ({@code 0m}) first; empty when Yahoo has none. */
    public List<Recommendation> recommendations() {
        return AnalystMapper.recommendations(module(QuoteSummaryModule.RECOMMENDATION_TREND));
    }

    /** Same as {@link #recommendations()}, like yfinance's {@code recommendations_summary}. */
    public List<Recommendation> recommendationsSummary() {
        return recommendations();
    }

    /** Analyst rating changes, newest first; empty when Yahoo has none. */
    public List<UpgradeDowngrade> upgradesDowngrades() {
        return AnalystMapper.upgradesDowngrades(module(QuoteSummaryModule.UPGRADE_DOWNGRADE_HISTORY));
    }

    /** Analyst price targets and the current price; empty when Yahoo has none, e.g. for an ETF. */
    public Optional<PriceTargets> analystPriceTargets() {
        return AnalystMapper.priceTargets(module(QuoteSummaryModule.FINANCIAL_DATA));
    }

    /** EPS estimates for the current and next quarter and fiscal year; empty when Yahoo has none. */
    public List<EarningsEstimate> earningsEstimate() {
        return AnalystMapper.earningsEstimate(module(QuoteSummaryModule.EARNINGS_TREND));
    }

    /** Revenue estimates for the current and next quarter and fiscal year; empty when Yahoo has none. */
    public List<RevenueEstimate> revenueEstimate() {
        return AnalystMapper.revenueEstimate(module(QuoteSummaryModule.EARNINGS_TREND));
    }

    /** Reported versus estimated EPS for the last four quarters, oldest first; empty when Yahoo has none. */
    public List<EarningsHistoryEntry> earningsHistory() {
        return AnalystMapper.earningsHistory(module(QuoteSummaryModule.EARNINGS_HISTORY));
    }

    /** How the mean EPS estimates moved over the last 90 days; empty when Yahoo has none. */
    public List<EpsTrend> epsTrend() {
        return AnalystMapper.epsTrend(module(QuoteSummaryModule.EARNINGS_TREND));
    }

    /** Upward and downward EPS estimate revisions; empty when Yahoo has none. */
    public List<EpsRevisions> epsRevisions() {
        return AnalystMapper.epsRevisions(module(QuoteSummaryModule.EARNINGS_TREND));
    }

    /**
     * Expected growth of the symbol next to its industry, sector and index, per period; one
     * request. Empty when Yahoo has none.
     */
    public List<GrowthEstimate> growthEstimates() {
        return AnalystMapper.growthEstimates(
                new QuoteSummaryEndpoint(client.session()).fetch(symbol, AnalystMapper.GROWTH_MODULES));
    }

    /** Next earnings date with estimate ranges, and dividend dates; empty when Yahoo has none. */
    public Optional<Calendar> calendar() {
        return CalendarMapper.calendar(module(QuoteSummaryModule.CALENDAR_EVENTS));
    }

    /** {@link #earningsDates(int)} with yfinance's default limit of 12. */
    public List<EarningsDate> earningsDates() {
        return earningsDates(12);
    }

    /**
     * Up to {@code limit} past and upcoming earnings announcements with EPS estimate, reported EPS
     * and surprise, newest (upcoming) first. Times are in the exchange timezone, which may take
     * one extra chart request when it is not cached. Empty when Yahoo has none, e.g. for an ETF.
     *
     * @throws IllegalArgumentException when {@code limit} is not in {@code 1..100} (Yahoo's cap)
     */
    public List<EarningsDate> earningsDates(int limit) {
        return new EarningsDatesEndpoint(client.session(), chart()::timezone).earningsDates(symbol, limit);
    }

    /**
     * Option expiration dates (yfinance {@code options}), nearest first. Empty when the symbol has
     * no listed options, including an unknown symbol.
     */
    public List<LocalDate> optionExpirations() {
        return new OptionsEndpoint(client.session()).expirations(symbol);
    }

    /**
     * Calls and puts of the nearest expiration (yfinance {@code option_chain()}); empty when the
     * symbol has no listed options.
     */
    public Optional<OptionChain> optionChain() {
        return new OptionsEndpoint(client.session()).nearest(symbol);
    }

    /**
     * Calls and puts expiring on {@code expiration} (yfinance {@code option_chain(date)}). One
     * request for the nearest expiration, two otherwise. Last trade times are in the exchange
     * timezone.
     *
     * @throws IllegalArgumentException when {@code expiration} is not one of {@link
     *     #optionExpirations()}; the message lists the valid dates
     */
    public OptionChain optionChain(LocalDate expiration) {
        Objects.requireNonNull(expiration, "expiration");
        return new OptionsEndpoint(client.session()).chain(symbol, expiration);
    }

    /** ESG risk scores; empty when Yahoo has none (currently the case for most symbols). */
    public Optional<Sustainability> sustainability() {
        return SustainabilityMapper.sustainability(module(QuoteSummaryModule.ESG_SCORES));
    }

    /**
     * Income statement (yfinance {@code income_stmt} / {@code financials}); {@link
     * Frequency#TRAILING} gives the latest trailing twelve months. Empty when Yahoo has no
     * fundamentals for the symbol, e.g. for an ETF.
     */
    public FinancialStatement incomeStatement(Frequency frequency) {
        return statement(StatementKeys.INCOME, frequency);
    }

    /**
     * Balance sheet. Empty when Yahoo has no fundamentals for the symbol.
     *
     * @throws IllegalArgumentException for {@link Frequency#TRAILING}, which Yahoo does not offer
     *     for the balance sheet
     */
    public FinancialStatement balanceSheet(Frequency frequency) {
        return statement(StatementKeys.BALANCE_SHEET, frequency);
    }

    /**
     * Cash flow statement; {@link Frequency#TRAILING} gives the latest trailing twelve months.
     * Empty when Yahoo has no fundamentals for the symbol.
     */
    public FinancialStatement cashFlow(Frequency frequency) {
        return statement(StatementKeys.CASH_FLOW, frequency);
    }

    YFinance client() {
        return client;
    }

    /** One {@code quoteSummary} module, or {@code null} when Yahoo has no data for it. */
    private @Nullable JsonNode module(QuoteSummaryModule module) {
        return new QuoteSummaryEndpoint(client.session())
                .fetch(symbol, List.of(module))
                .get(module);
    }

    private FinancialStatement statement(StatementKeys statement, Frequency frequency) {
        Objects.requireNonNull(frequency, "frequency");
        return new TimeseriesEndpoint(client.session()).statement(symbol, statement, frequency);
    }

    private ChartEndpoint chart() {
        return new ChartEndpoint(client.session(), client.cache());
    }

    @Override
    public String toString() {
        return "Ticker[" + symbol + "]";
    }
}
