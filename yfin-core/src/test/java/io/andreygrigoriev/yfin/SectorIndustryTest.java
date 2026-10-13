package io.andreygrigoriev.yfin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.andreygrigoriev.yfin.exception.YFDataException;
import io.andreygrigoriev.yfin.exception.YFSymbolNotFoundException;
import io.andreygrigoriev.yfin.exception.YFTransportException;
import io.andreygrigoriev.yfin.model.DomainOverview;
import io.andreygrigoriev.yfin.model.GrowthCompany;
import io.andreygrigoriev.yfin.model.PerformingCompany;
import io.andreygrigoriev.yfin.model.SectorIndustry;
import io.andreygrigoriev.yfin.model.TopCompany;
import io.andreygrigoriev.yfin.spi.Cache;
import io.andreygrigoriev.yfin.spi.HttpRequest;
import io.andreygrigoriev.yfin.spi.Transport;
import io.andreygrigoriev.yfin.testing.FakeTransport;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SectorIndustryTest {

    private static final String SECTORS = "/v1/finance/sectors/";
    private static final String INDUSTRIES = "/v1/finance/industries/";

    private final FakeTransport fake = FakeTransport.create();
    private YFinance yf;

    @BeforeEach
    void setUp() {
        fake.onGet("").respond(404, "not found");
        fake.onGet("/v1/test/getcrumb").respond(200, "crumb1");
        yf = YFinance.builder()
                .transport(fake)
                .cache(Cache.none())
                .maxRetries(0)
                .build();
    }

    @AfterEach
    void tearDown() {
        yf.close();
    }

    @Test
    void sector() {
        fake.onGet(SECTORS + "technology").respondFixture("sector/technology.json");

        Sector s = yf.sector(" Technology ");

        assertThat(s.key()).isEqualTo("technology");
        assertThat(s.region()).isEqualTo("US");
        assertThat(s.name()).isEqualTo("Technology");
        assertThat(s.symbol()).isEqualTo("^YH311");
        assertThat(s.ticker().symbol()).isEqualTo("^YH311");

        DomainOverview o = s.overview();
        assertThat(o.companiesCount()).isEqualTo(870L);
        assertThat(o.marketCap()).isEqualTo(30091056775168.0);
        assertThat(o.messageBoardId()).isEqualTo("INDEXYH311");
        assertThat(o.description()).startsWith("Companies engaged in the design");
        assertThat(o.industriesCount()).isEqualTo(12L);
        assertThat(o.marketWeight()).isEqualTo(0.33607838);
        assertThat(o.employeeCount()).isEqualTo(7963655L);

        assertThat(s.topCompanies()).hasSize(50);
        TopCompany nvda = s.topCompanies().getFirst();
        assertThat(nvda.symbol()).isEqualTo("NVDA");
        assertThat(nvda.name()).isEqualTo("NVIDIA Corporation");
        assertThat(nvda.rating()).isEqualTo("Strong Buy");
        assertThat(nvda.marketWeight()).isEqualTo(0.17620951);
        assertThat(nvda.raw()).containsKeys("marketCap", "targetPrice", "ytdReturn");

        assertThat(s.topEtfs()).hasSize(10);
        assertThat(s.topEtfs().entrySet().iterator().next()).isEqualTo(Map.entry("QQQ", "Invesco QQQ Trust, Series 1"));
        assertThat(s.topEtfs().keySet()).startsWith("QQQ", "VGT", "XLK");
        assertThat(s.topMutualFunds()).hasSize(10).containsEntry("VITAX", "Vanguard Information Technology");

        assertThat(s.industries()).hasSize(12);
        assertThat(s.industries()).extracting(SectorIndustry::name).doesNotContain("All Industries");
        assertThat(s.industries().getFirst())
                .isEqualTo(new SectorIndustry("semiconductors", "Semiconductors", "^YH31130020", 0.37281495));

        assertThat(s.researchReports()).hasSize(4);
        assertThat(s.researchReports().getFirst())
                .containsEntry("id", "MS_0P00006TF8_AnalystReport_1791827015000")
                .containsEntry("provider", "Morningstar");
        assertThat(s.data().raw()).containsKeys("performance", "performanceOverviewBenchmark");

        // one request for everything, with yfinance's parameters
        List<HttpRequest> requests = sectorRequests();
        assertThat(requests).hasSize(1);
        HttpRequest r = requests.getFirst();
        assertThat(r.uri().getHost()).isEqualTo("query1.finance.yahoo.com");
        assertThat(FakeTransport.parseQuery(r.uri().getRawQuery()))
                .containsEntry("formatted", List.of("true"))
                .containsEntry("withReturns", List.of("true"))
                .containsEntry("lang", List.of("en-US"))
                .containsEntry("region", List.of("US"));
    }

    @Test
    void sectorRegion() {
        fake.onGet(SECTORS + "technology").query("region", "GB").respondFixture("sector/technology.json");

        Sector s = yf.sector("technology", " gb ");

        assertThat(s.region()).isEqualTo("GB");
        assertThat(s.name()).isEqualTo("Technology");
        assertThat(s.toString()).isEqualTo("Sector[technology, region=GB]");
    }

    @Test
    void sectorMissingFields() {
        fake.onGet(SECTORS + "utilities").respondFixture("sector/minimal.json");

        Sector s = yf.sector("utilities");

        assertThat(s.name()).isEqualTo("Utilities");
        assertThat(s.symbol()).isNull();
        assertThat(s.overview()).isEqualTo(DomainOverview.empty());
        assertThat(s.topCompanies()).isEmpty();
        assertThat(s.topEtfs()).isEmpty();
        assertThat(s.topMutualFunds()).isEmpty();
        assertThat(s.industries()).isEmpty();
        assertThat(s.researchReports()).isEmpty();
        assertThatThrownBy(s::ticker).isInstanceOf(YFDataException.class).hasMessageContaining("has no symbol");
    }

    @Test
    void unknownSectorKey() {
        fake.onGet(SECTORS + "notasector").respondFixture(404, "sector/unknown.html");

        Sector s = yf.sector("notasector");

        assertThatThrownBy(s::overview)
                .isInstanceOfSatisfying(
                        YFSymbolNotFoundException.class,
                        e -> assertThat(e.symbol()).isEqualTo("notasector"))
                .hasMessageContaining("unknown sector key: notasector");
    }

    @Test
    void sectorErrorPayload() {
        fake.onGet(SECTORS + "technology").respondFixture(400, "sector/error.json");

        assertThatThrownBy(() -> yf.sector("technology").data())
                .isInstanceOf(YFDataException.class)
                .hasMessageContaining("backend failure");
    }

    @Test
    void sectorWithoutDataObject() {
        fake.onGet(SECTORS + "technology").respond(200, "{\"other\":{}}");

        assertThatThrownBy(() -> yf.sector("technology").data())
                .isInstanceOf(YFDataException.class)
                .hasMessageContaining("no data object");
    }

    @Test
    void failedFetchIsRetriedThenMemoized() {
        fake.onGet(SECTORS + "technology")
                .thenRespond(503, "{}")
                .thenFixture("sector/technology.json")
                .register();
        Sector s = yf.sector("technology");

        assertThatThrownBy(s::name).isInstanceOf(YFTransportException.class);
        assertThat(s.name()).isEqualTo("Technology");
        assertThat(s.industries()).isNotEmpty();
        assertThat(sectorRequests()).hasSize(2);
    }

    @Test
    void concurrentAccessorsShareOneFetch() throws Exception {
        fake.onGet(SECTORS + "technology").respondFixture("sector/technology.json");
        fake.onGet(INDUSTRIES + "semiconductors").respondFixture("industry/semiconductors.json");
        Transport slow = request -> {
            if (request.uri().getPath().startsWith("/v1/finance/")) {
                try {
                    Thread.sleep(50); // keeps the first fetch in flight while the others arrive
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return fake.send(request);
        };
        try (YFinance client = YFinance.builder()
                .transport(slow)
                .cache(Cache.none())
                .maxRetries(0)
                .build()) {
            Sector sector = client.sector("technology");
            Industry industry = client.industry("semiconductors");
            int threads = 16;
            var start = new CountDownLatch(1);
            var names = new ConcurrentLinkedQueue<String>();
            var workers = new ArrayList<Thread>();
            for (int t = 0; t < threads; t++) {
                boolean even = t % 2 == 0;
                workers.add(Thread.ofVirtual().start(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    names.add(even ? sector.name() : industry.name());
                }));
            }
            start.countDown();
            for (Thread w : workers) {
                w.join(10_000);
            }

            assertThat(names).hasSize(threads).containsOnly("Technology", "Semiconductors");
        }
        assertThat(sectorRequests()).hasSize(1);
        assertThat(fake.requests())
                .filteredOn(r -> r.uri().getPath().startsWith(INDUSTRIES))
                .hasSize(1);
    }

    @Test
    void industryErrorPayload() {
        // the industries endpoint shares the /v1/finance error envelope of the sectors endpoint
        fake.onGet(INDUSTRIES + "semiconductors").respondFixture(400, "sector/error.json");

        assertThatThrownBy(() -> yf.industry("semiconductors").topCompanies())
                .isInstanceOf(YFDataException.class)
                .hasMessageContaining("backend failure");
    }

    @Test
    void industry() {
        fake.onGet(INDUSTRIES + "semiconductors").respondFixture("industry/semiconductors.json");

        Industry i = yf.industry("SEMICONDUCTORS");

        assertThat(i.key()).isEqualTo("semiconductors");
        assertThat(i.region()).isEqualTo("US");
        assertThat(i.name()).isEqualTo("Semiconductors");
        assertThat(i.symbol()).isEqualTo("^YH31130020");
        assertThat(i.ticker().symbol()).isEqualTo("^YH31130020");
        assertThat(i.sectorKey()).isEqualTo("technology");
        assertThat(i.sectorName()).isEqualTo("Technology");

        DomainOverview o = i.overview();
        assertThat(o.companiesCount()).isEqualTo(61L);
        assertThat(o.marketCap()).isEqualTo(11218395856896.0);
        assertThat(o.industriesCount()).isNull();
        assertThat(o.marketWeight()).isEqualTo(0.37281495);
        assertThat(o.employeeCount()).isEqualTo(1304502L);

        assertThat(i.topCompanies()).hasSize(48);
        assertThat(i.topCompanies().getFirst().symbol()).isEqualTo("NVDA");
        assertThat(i.topCompanies().getFirst().marketWeight()).isEqualTo(0.44316763);

        assertThat(i.topPerformingCompanies()).hasSize(5);
        assertThat(i.topPerformingCompanies().getFirst())
                .isEqualTo(new PerformingCompany("MXL", "MaxLinear, Inc.", 4.101, 88.91, 94.5454));
        assertThat(i.topGrowthCompanies()).hasSize(5);
        assertThat(i.topGrowthCompanies().getFirst())
                .isEqualTo(new GrowthCompany("MU", "Micron Technology, Inc.", 2.4554, 5.846153846153847));
        assertThat(i.researchReports()).hasSize(4);
        assertThat(i.researchReports().getFirst()).containsEntry("provider", "Argus Research");
        assertThat(i.toString()).isEqualTo("Industry[semiconductors, region=US]");

        List<HttpRequest> requests = fake.requests().stream()
                .filter(r -> r.uri().getPath().startsWith(INDUSTRIES))
                .toList();
        assertThat(requests).hasSize(1);
        assertThat(FakeTransport.parseQuery(requests.getFirst().uri().getRawQuery()))
                .containsEntry("withReturns", List.of("true"))
                .containsEntry("region", List.of("US"));
    }

    @Test
    void industryMissingFields() {
        fake.onGet(INDUSTRIES + "solar").respondFixture("industry/minimal.json");

        Industry i = yf.industry("solar");

        assertThat(i.name()).isEqualTo("Solar");
        assertThat(i.sectorKey()).isNull();
        assertThat(i.sectorName()).isNull();
        assertThat(i.overview().description()).isEqualTo("Solar");
        assertThat(i.overview().companiesCount()).isNull();
        assertThat(i.topCompanies()).isEmpty();
        assertThat(i.topPerformingCompanies()).isEmpty();
        assertThat(i.topGrowthCompanies()).isEmpty();
        assertThat(i.researchReports()).isEmpty();
    }

    @Test
    void unknownIndustryKey() {
        fake.onGet(INDUSTRIES + "notanindustry").respondFixture(404, "industry/unknown.html");

        assertThatThrownBy(() -> yf.industry("notanindustry").topCompanies())
                .isInstanceOfSatisfying(
                        YFSymbolNotFoundException.class,
                        e -> assertThat(e.symbol()).isEqualTo("notanindustry"))
                .hasMessageContaining("unknown industry key: notanindustry");
    }

    @Test
    void keyValidation() {
        assertThatThrownBy(() -> yf.sector(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> yf.industry("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> yf.sector("technology", " ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> yf.industry("solar", "")).isInstanceOf(IllegalArgumentException.class);
        yf.close();
        assertThatThrownBy(() -> yf.sector("technology")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> yf.industry("solar")).isInstanceOf(IllegalStateException.class);
    }

    private List<HttpRequest> sectorRequests() {
        return fake.requests().stream()
                .filter(r -> r.uri().getPath().startsWith(SECTORS))
                .toList();
    }
}
