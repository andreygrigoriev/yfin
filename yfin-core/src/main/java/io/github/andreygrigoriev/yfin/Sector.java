package io.github.andreygrigoriev.yfin;

import io.github.andreygrigoriev.yfin.exception.YFDataException;
import io.github.andreygrigoriev.yfin.internal.endpoint.domain.SectorEndpoint;
import io.github.andreygrigoriev.yfin.model.DomainOverview;
import io.github.andreygrigoriev.yfin.model.SectorData;
import io.github.andreygrigoriev.yfin.model.SectorIndustry;
import io.github.andreygrigoriev.yfin.model.TopCompany;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A market sector (yfinance {@code Sector}), e.g. {@code technology}, {@code healthcare}, {@code
 * consumer-cyclical}. Obtained from {@link YFinance#sector(String)}; makes no request until a data
 * method is called.
 *
 * <p>Like yfinance, all data comes from one request, made on first access and at most once per
 * instance (a failed request is retried on the next access). Thread-safe.
 *
 * <p>Data methods throw {@link io.github.andreygrigoriev.yfin.exception.YFSymbolNotFoundException} for an
 * unknown key, and other {@link io.github.andreygrigoriev.yfin.exception.YFinanceException}s for
 * transport, rate-limit and parsing failures.
 */
public final class Sector {

    private final YFinance client;
    private final String key;
    private final String region;
    private final Object lock = new Object();
    private volatile @Nullable SectorData data;

    Sector(YFinance client, String key, String region) {
        this.client = Objects.requireNonNull(client, "client");
        this.key = Objects.requireNonNull(key, "key");
        this.region = Objects.requireNonNull(region, "region");
    }

    /** The normalized (trimmed, lower-case) sector key. */
    public String key() {
        return key;
    }

    /** The region (ISO 3166-1 alpha-2, upper-case) top companies, ETFs and funds are scoped to. */
    public String region() {
        return region;
    }

    /** Everything Yahoo reports about the sector. */
    public SectorData data() {
        SectorData d = data;
        if (d == null) {
            synchronized (lock) {
                d = data;
                if (d == null) {
                    d = new SectorEndpoint(client.session()).sector(key, region);
                    data = d;
                }
            }
        }
        return d;
    }

    /** Sector name, e.g. {@code Technology}. */
    public @Nullable String name() {
        return data().name();
    }

    /** Yahoo's index symbol of the sector, e.g. {@code ^YH311}. */
    public @Nullable String symbol() {
        return data().symbol();
    }

    /**
     * A ticker for the sector's index {@link #symbol()}.
     *
     * @throws YFDataException when Yahoo reports no symbol for the sector
     */
    public Ticker ticker() {
        String s = symbol();
        if (s == null) {
            throw new YFDataException("sector " + key + " has no symbol");
        }
        return client.ticker(s);
    }

    public DomainOverview overview() {
        return data().overview();
    }

    public List<TopCompany> topCompanies() {
        return data().topCompanies();
    }

    /** Top ETFs, symbol to name. */
    public Map<String, String> topEtfs() {
        return data().topEtfs();
    }

    /** Top mutual funds, symbol to name. */
    public Map<String, String> topMutualFunds() {
        return data().topMutualFunds();
    }

    /** The sector's industries; their keys work with {@link YFinance#industry(String)}. */
    public List<SectorIndustry> industries() {
        return data().industries();
    }

    public List<Map<String, Object>> researchReports() {
        return data().researchReports();
    }

    @Override
    public String toString() {
        return "Sector[" + key + ", region=" + region + "]";
    }
}
