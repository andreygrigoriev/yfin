package io.andreygrigoriev.yfin.model;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Everything Yahoo reports about a symbol, flattened the way yfinance's {@code info} is: the
 * {@code quoteSummary} modules {@code financialData}, {@code quoteType}, {@code
 * defaultKeyStatistics}, {@code assetProfile} and {@code summaryDetail} merged into one map, then
 * the {@code /v7/finance/quote} fields on top.
 *
 * <p>Typed accessors cover the common fields and return {@code null} when Yahoo omits a field
 * (many apply only to some quote types: an index has no sector, a crypto pair no P/E). {@link
 * #raw()} holds every field as sent: numbers as {@code Long} or {@code Double}, JSON objects as
 * {@code Map}, arrays as {@code List}. Epoch-second timestamps are exposed as {@link Instant}.
 *
 * @param symbol the requested symbol
 * @param raw all fields, unmodifiable, in Yahoo's order
 */
public record TickerInfo(String symbol, Map<String, Object> raw) {

    public TickerInfo {
        Objects.requireNonNull(symbol, "symbol");
        raw = Collections.unmodifiableMap(new LinkedHashMap<>(raw));
    }

    // ---- generic access ----

    /** The value of {@code key} as text, or {@code null} when absent or not a string. */
    public @Nullable String getString(String key) {
        return raw.get(key) instanceof String s ? s : null;
    }

    /** The value of {@code key} as a double, or {@code null} when absent or not a number. */
    public @Nullable Double getDouble(String key) {
        return raw.get(key) instanceof Number n ? n.doubleValue() : null;
    }

    /**
     * The value of {@code key} as a long (fractions truncated), or {@code null} when absent, not a
     * number, or out of range.
     */
    public @Nullable Long getLong(String key) {
        return switch (raw.get(key)) {
            case BigInteger b -> b.bitLength() < 64 ? b.longValue() : null;
            case Double d -> Double.isFinite(d) ? d.longValue() : null;
            case Number n -> n.longValue();
            case null, default -> null;
        };
    }

    /** The value of {@code key}, epoch seconds, as an instant, or {@code null}. */
    public @Nullable Instant getInstant(String key) {
        Long seconds = getLong(key);
        return seconds == null ? null : Instant.ofEpochSecond(seconds);
    }

    // ---- identity and listing ----

    /** E.g. {@code EQUITY}, {@code ETF}, {@code MUTUALFUND}, {@code CRYPTOCURRENCY}, {@code INDEX}. */
    public @Nullable String quoteType() {
        return getString("quoteType");
    }

    public @Nullable String shortName() {
        return getString("shortName");
    }

    public @Nullable String longName() {
        return getString("longName");
    }

    /** Trading currency. */
    public @Nullable String currency() {
        return getString("currency");
    }

    /** Currency of the financial statements. */
    public @Nullable String financialCurrency() {
        return getString("financialCurrency");
    }

    /** Exchange code, e.g. {@code NMS}. */
    public @Nullable String exchange() {
        return getString("exchange");
    }

    public @Nullable String fullExchangeName() {
        return getString("fullExchangeName");
    }

    /** IANA zone of the exchange, e.g. {@code America/New_York}. */
    public @Nullable String exchangeTimezoneName() {
        return getString("exchangeTimezoneName");
    }

    /** E.g. {@code us_market}. */
    public @Nullable String market() {
        return getString("market");
    }

    /** {@code PRE}, {@code REGULAR}, {@code POST}, {@code CLOSED}, … */
    public @Nullable String marketState() {
        return getString("marketState");
    }

    // ---- profile ----

    public @Nullable String sector() {
        return getString("sector");
    }

    public @Nullable String industry() {
        return getString("industry");
    }

    public @Nullable String country() {
        return getString("country");
    }

    public @Nullable String website() {
        return getString("website");
    }

    public @Nullable String longBusinessSummary() {
        return getString("longBusinessSummary");
    }

    public @Nullable Long fullTimeEmployees() {
        return getLong("fullTimeEmployees");
    }

    // ---- price ----

    public @Nullable Double currentPrice() {
        return getDouble("currentPrice");
    }

    public @Nullable Double regularMarketPrice() {
        return getDouble("regularMarketPrice");
    }

    public @Nullable Double previousClose() {
        return getDouble("previousClose");
    }

    public @Nullable Double open() {
        return getDouble("open");
    }

    public @Nullable Double dayHigh() {
        return getDouble("dayHigh");
    }

    public @Nullable Double dayLow() {
        return getDouble("dayLow");
    }

    public @Nullable Long volume() {
        return getLong("volume");
    }

    public @Nullable Long averageVolume() {
        return getLong("averageVolume");
    }

    public @Nullable Double fiftyTwoWeekHigh() {
        return getDouble("fiftyTwoWeekHigh");
    }

    public @Nullable Double fiftyTwoWeekLow() {
        return getDouble("fiftyTwoWeekLow");
    }

    public @Nullable Double fiftyDayAverage() {
        return getDouble("fiftyDayAverage");
    }

    public @Nullable Double twoHundredDayAverage() {
        return getDouble("twoHundredDayAverage");
    }

    public @Nullable Double beta() {
        return getDouble("beta");
    }

    // ---- size and valuation ----

    public @Nullable Long marketCap() {
        return getLong("marketCap");
    }

    public @Nullable Long enterpriseValue() {
        return getLong("enterpriseValue");
    }

    public @Nullable Long sharesOutstanding() {
        return getLong("sharesOutstanding");
    }

    public @Nullable Long floatShares() {
        return getLong("floatShares");
    }

    public @Nullable Double trailingPE() {
        return getDouble("trailingPE");
    }

    public @Nullable Double forwardPE() {
        return getDouble("forwardPE");
    }

    /**
     * The latest trailing PEG ratio, which yfinance adds to {@code info} from the fundamentals
     * timeseries endpoint; {@code null} when Yahoo has none (ETFs, indices, crypto).
     */
    public @Nullable Double trailingPegRatio() {
        return getDouble("trailingPegRatio");
    }

    public @Nullable Double priceToBook() {
        return getDouble("priceToBook");
    }

    public @Nullable Double priceToSalesTrailing12Months() {
        return getDouble("priceToSalesTrailing12Months");
    }

    public @Nullable Double trailingEps() {
        return getDouble("trailingEps");
    }

    public @Nullable Double forwardEps() {
        return getDouble("forwardEps");
    }

    public @Nullable Double bookValue() {
        return getDouble("bookValue");
    }

    // ---- dividends ----

    public @Nullable Double dividendRate() {
        return getDouble("dividendRate");
    }

    /**
     * Dividend yield as Yahoo reports it. As in yfinance, the quote's value (in percent, {@code
     * 0.38} for 0.38%) replaces {@code summaryDetail}'s fraction when both are present.
     */
    public @Nullable Double dividendYield() {
        return getDouble("dividendYield");
    }

    public @Nullable Double payoutRatio() {
        return getDouble("payoutRatio");
    }

    public @Nullable Instant exDividendDate() {
        return getInstant("exDividendDate");
    }

    // ---- financials ----

    public @Nullable Long totalRevenue() {
        return getLong("totalRevenue");
    }

    public @Nullable Long ebitda() {
        return getLong("ebitda");
    }

    public @Nullable Long totalCash() {
        return getLong("totalCash");
    }

    public @Nullable Long totalDebt() {
        return getLong("totalDebt");
    }

    public @Nullable Double profitMargins() {
        return getDouble("profitMargins");
    }

    public @Nullable Double returnOnEquity() {
        return getDouble("returnOnEquity");
    }

    public @Nullable Double revenueGrowth() {
        return getDouble("revenueGrowth");
    }

    public @Nullable Double earningsGrowth() {
        return getDouble("earningsGrowth");
    }

    // ---- analysts ----

    public @Nullable Double targetMeanPrice() {
        return getDouble("targetMeanPrice");
    }

    /** E.g. {@code buy}, {@code hold}. */
    public @Nullable String recommendationKey() {
        return getString("recommendationKey");
    }

    // ---- funds ----

    public @Nullable Double totalAssets() {
        return getDouble("totalAssets");
    }

    public @Nullable Double yield() {
        return getDouble("yield");
    }

    public @Nullable String category() {
        return getString("category");
    }

    public @Nullable String fundFamily() {
        return getString("fundFamily");
    }
}
