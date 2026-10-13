package io.andreygrigoriev.yfin.internal.repair;

import io.andreygrigoriev.yfin.request.Interval;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * What the repairs know about the symbol beyond the table: the state yfinance keeps on its {@code
 * PriceHistory} object (metadata currency, {@code currencyRepaired}, the reconstruction depth
 * guard) and the way to fetch finer-grained history. Not thread-safe; one per history request.
 */
public final class RepairContext {

    /** Fetches finer-grained history for price reconstruction. */
    @FunctionalInterface
    public interface FinerHistory {

        /**
         * Unadjusted, repaired history with actions of {@code interval} from {@code start}
         * (inclusive) to {@code end} (exclusive), both midnight in the exchange zone, as a table
         * sorted by time; an empty table when Yahoo has no such data.
         *
         * @param reconstructStart the interval the outermost reconstruction started from, so that
         *     nested repairs stop at yfinance's maximum depth of two
         */
        Frame fetch(Interval interval, LocalDate start, LocalDate end, boolean prepost, Interval reconstructStart);
    }

    private final String symbol;
    private final ZoneId zone;
    private final Clock clock;
    private final @Nullable Double regularMarketPrice;
    private final @Nullable FinerHistory finer;
    private String currency;
    private boolean currencyRepaired;
    private @Nullable Interval reconstructStart;

    /**
     * @param currency the metadata currency, empty when unknown
     * @param regularMarketPrice the metadata's latest price, used to tell pence from pounds
     * @param finer source of finer-grained data; {@code null} disables reconstruction
     * @param reconstructStart for nested repairs, the interval the outer reconstruction started from
     */
    public RepairContext(
            String symbol,
            ZoneId zone,
            String currency,
            @Nullable Double regularMarketPrice,
            Clock clock,
            @Nullable FinerHistory finer,
            @Nullable Interval reconstructStart) {
        this.symbol = Objects.requireNonNull(symbol, "symbol");
        this.zone = Objects.requireNonNull(zone, "zone");
        this.currency = Objects.requireNonNull(currency, "currency");
        this.regularMarketPrice = regularMarketPrice;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.finer = finer;
        this.reconstructStart = reconstructStart;
    }

    /** A context without reconstruction, for repairs that need no finer data. */
    static RepairContext offline(String symbol, ZoneId zone, String currency, Clock clock) {
        return new RepairContext(symbol, zone, currency, null, clock, null, null);
    }

    public String symbol() {
        return symbol;
    }

    public ZoneId zone() {
        return zone;
    }

    Clock clock() {
        return clock;
    }

    @Nullable
    Double regularMarketPrice() {
        return regularMarketPrice;
    }

    @Nullable
    FinerHistory finer() {
        return finer;
    }

    /** The price currency after repair (e.g. {@code GBP} when pence prices were fixed to pounds). */
    public String currency() {
        return currency;
    }

    void currency(String currency) {
        this.currency = currency;
    }

    boolean currencyRepaired() {
        return currencyRepaired;
    }

    void currencyRepaired(boolean repaired) {
        this.currencyRepaired = repaired;
    }

    @Nullable
    Interval reconstructStart() {
        return reconstructStart;
    }

    void reconstructStart(Interval interval) {
        this.reconstructStart = interval;
    }

    /** 1000 for the Kuwaiti dinar (fils), otherwise 100. */
    int currencyDivide() {
        return "KWF".equals(currency) ? 1000 : 100;
    }
}
