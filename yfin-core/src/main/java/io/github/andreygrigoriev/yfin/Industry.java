package io.github.andreygrigoriev.yfin;

import io.github.andreygrigoriev.yfin.exception.YFDataException;
import io.github.andreygrigoriev.yfin.internal.endpoint.domain.IndustryEndpoint;
import io.github.andreygrigoriev.yfin.model.DomainOverview;
import io.github.andreygrigoriev.yfin.model.GrowthCompany;
import io.github.andreygrigoriev.yfin.model.IndustryData;
import io.github.andreygrigoriev.yfin.model.PerformingCompany;
import io.github.andreygrigoriev.yfin.model.TopCompany;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * An industry within a sector (yfinance {@code Industry}), e.g. {@code semiconductors}. Obtained
 * from {@link YFinance#industry(String)}; makes no request until a data method is called. Industry
 * keys are listed by {@link Sector#industries()}.
 *
 * <p>Like yfinance, all data comes from one request, made on first access and at most once per
 * instance (a failed request is retried on the next access). Thread-safe.
 *
 * <p>Data methods throw {@link io.github.andreygrigoriev.yfin.exception.YFSymbolNotFoundException} for an
 * unknown key, and other {@link io.github.andreygrigoriev.yfin.exception.YFinanceException}s for
 * transport, rate-limit and parsing failures.
 */
public final class Industry {

    private final YFinance client;
    private final String key;
    private final String region;
    private final Object lock = new Object();
    private volatile @Nullable IndustryData data;

    Industry(YFinance client, String key, String region) {
        this.client = Objects.requireNonNull(client, "client");
        this.key = Objects.requireNonNull(key, "key");
        this.region = Objects.requireNonNull(region, "region");
    }

    /** The normalized (trimmed, lower-case) industry key. */
    public String key() {
        return key;
    }

    /** The region (ISO 3166-1 alpha-2, upper-case) company listings are scoped to. */
    public String region() {
        return region;
    }

    /** Everything Yahoo reports about the industry. */
    public IndustryData data() {
        IndustryData d = data;
        if (d == null) {
            synchronized (lock) {
                d = data;
                if (d == null) {
                    d = new IndustryEndpoint(client.session()).industry(key, region);
                    data = d;
                }
            }
        }
        return d;
    }

    /** Industry name, e.g. {@code Semiconductors}. */
    public @Nullable String name() {
        return data().name();
    }

    /** Yahoo's index symbol of the industry, e.g. {@code ^YH31130020}. */
    public @Nullable String symbol() {
        return data().symbol();
    }

    /**
     * A ticker for the industry's index {@link #symbol()}.
     *
     * @throws YFDataException when Yahoo reports no symbol for the industry
     */
    public Ticker ticker() {
        String s = symbol();
        if (s == null) {
            throw new YFDataException("industry " + key + " has no symbol");
        }
        return client.ticker(s);
    }

    /** Key of the sector the industry belongs to, e.g. {@code technology}. */
    public @Nullable String sectorKey() {
        return data().sectorKey();
    }

    /** Name of the sector the industry belongs to. */
    public @Nullable String sectorName() {
        return data().sectorName();
    }

    public DomainOverview overview() {
        return data().overview();
    }

    public List<TopCompany> topCompanies() {
        return data().topCompanies();
    }

    public List<PerformingCompany> topPerformingCompanies() {
        return data().topPerformingCompanies();
    }

    public List<GrowthCompany> topGrowthCompanies() {
        return data().topGrowthCompanies();
    }

    public List<Map<String, Object>> researchReports() {
        return data().researchReports();
    }

    @Override
    public String toString() {
        return "Industry[" + key + ", region=" + region + "]";
    }
}
