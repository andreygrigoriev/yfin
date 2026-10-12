package io.andreygrigoriev.yfin;

import io.andreygrigoriev.yfin.internal.endpoint.domain.MarketEndpoint;
import io.andreygrigoriev.yfin.model.MarketStatus;
import io.andreygrigoriev.yfin.model.MarketSummaryItem;
import io.andreygrigoriev.yfin.request.MarketRegion;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A market (yfinance {@code Market}): its trading hours and headline instruments. Obtained from
 * {@link YFinance#market(MarketRegion)}; makes no request until a data method is called.
 *
 * <p>Like yfinance, the summary and the status are fetched together on first access, so they
 * describe the same moment, and at most once per instance (a failed fetch is retried on the next
 * access). Thread-safe.
 */
public final class Market {

    private record Snapshot(List<MarketSummaryItem> summary, Optional<MarketStatus> status) {}

    private final YFinance client;
    private final MarketRegion region;
    private final Object lock = new Object();
    private volatile @Nullable Snapshot snapshot;

    Market(YFinance client, MarketRegion region) {
        this.client = Objects.requireNonNull(client, "client");
        this.region = Objects.requireNonNull(region, "region");
    }

    public MarketRegion region() {
        return region;
    }

    /**
     * Today's trading hours. Empty for every region but {@link MarketRegion#US}: Yahoo's market
     * time endpoint only answers for the U.S. market (yfinance returns {@code None} then).
     */
    public Optional<MarketStatus> status() {
        return snapshot().status();
    }

    /**
     * Headline instruments (indices, rates, commodities or currency pairs, depending on the
     * region), in Yahoo's order. yfinance keys these by exchange, which drops instruments sharing
     * one; this list keeps them all.
     */
    public List<MarketSummaryItem> summary() {
        return snapshot().summary();
    }

    private Snapshot snapshot() {
        Snapshot s = snapshot;
        if (s == null) {
            synchronized (lock) {
                s = snapshot;
                if (s == null) {
                    var endpoint = new MarketEndpoint(client.session());
                    s = new Snapshot(List.copyOf(endpoint.summary(region)), endpoint.status(region));
                    snapshot = s;
                }
            }
        }
        return s;
    }

    @Override
    public String toString() {
        return "Market[" + region + "]";
    }
}
