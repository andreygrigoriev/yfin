package io.github.andreygrigoriev.yfin.internal.endpoint.quotesummary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.andreygrigoriev.yfin.Ticker;
import io.github.andreygrigoriev.yfin.YFinance;
import io.github.andreygrigoriev.yfin.exception.YFSymbolNotFoundException;
import io.github.andreygrigoriev.yfin.internal.http.YahooJson;
import io.github.andreygrigoriev.yfin.model.Holder;
import io.github.andreygrigoriev.yfin.model.InsiderPurchases;
import io.github.andreygrigoriev.yfin.model.InsiderRosterEntry;
import io.github.andreygrigoriev.yfin.model.InsiderTransaction;
import io.github.andreygrigoriev.yfin.model.MajorHolders;
import io.github.andreygrigoriev.yfin.spi.Cache;
import io.github.andreygrigoriev.yfin.testing.FakeTransport;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class HoldersMapperTest {

    private static final String PATH = "/v10/finance/quoteSummary/";

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

    private Ticker ticker(String symbol, String fixture) {
        fake.onGet(PATH + symbol).respondFixture("quoteSummary/holders/" + fixture);
        return yf.ticker(symbol);
    }

    private Ticker ticker(String symbol, int status, String fixture) {
        fake.onGet(PATH + symbol).respondFixture(status, "quoteSummary/holders/" + fixture);
        return yf.ticker(symbol);
    }

    private static JsonNode module(String json) throws Exception {
        return QuoteSummaryEndpoint.unwrap(YahooJson.mapper().readTree(json));
    }

    // ---- equity with full data ----

    @Test
    void majorHolders() {
        MajorHolders major = ticker("AAPL", "aapl.json").majorHolders().orElseThrow();

        assertThat(major).isEqualTo(new MajorHolders(0.01648, 0.66324997, 0.67437, 7689L));
        assertThat(FakeTransport.parseQuery(fake.requests().getLast().uri().getRawQuery()))
                .containsEntry("modules", List.of("majorHoldersBreakdown"));
    }

    @Test
    void institutionalHolders() {
        List<Holder> holders = ticker("AAPL", "aapl.json").institutionalHolders();

        assertThat(holders).hasSize(10);
        assertThat(holders.getFirst())
                .isEqualTo(new Holder(
                        LocalDate.of(2026, 6, 30), "Blackrock Inc.", 1_162_996_939L, 395_041_680_895L, 0.0797, 0.016));
        assertThat(holders.get(1).holder()).isEqualTo("Vanguard Capital Management LLC");
        assertThat(holders).allSatisfy(h -> {
            assertThat(h.dateReported()).isNotNull();
            assertThat(h.shares()).isPositive();
        });
        assertThat(FakeTransport.parseQuery(fake.requests().getLast().uri().getRawQuery()))
                .containsEntry("modules", List.of("institutionOwnership"));
    }

    @Test
    void mutualFundHolders() {
        List<Holder> holders = ticker("AAPL", "aapl.json").mutualFundHolders();

        assertThat(holders).hasSize(10);
        Holder first = holders.getFirst();
        assertThat(first.holder()).isEqualTo("VANGUARD INDEX FUNDS-Vanguard Total Stock Market Index Fund");
        assertThat(first.dateReported()).isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(first.shares()).isEqualTo(466_272_244L);
        assertThat(first.value()).isEqualTo(158_381_303_378L);
        assertThat(first.pctHeld()).isEqualTo(0.0319);
        assertThat(first.pctChange()).isEqualTo(0.0001);
    }

    @Test
    void insiderTransactions() {
        List<InsiderTransaction> transactions = ticker("AAPL", "aapl.json").insiderTransactions();

        assertThat(transactions).hasSize(82);
        assertThat(transactions.getFirst())
                .isEqualTo(new InsiderTransaction(
                        LocalDate.of(2026, 10, 6),
                        "NEWSTEAD JENNIFER",
                        "General Counsel",
                        "",
                        "",
                        "Sale at price 332.01 per share.",
                        2399L,
                        796_492L,
                        "D"));
        // {"raw":0,"fmt":null} is still a value object
        assertThat(transactions.get(1).value()).isZero();
        assertThat(transactions.get(1).insider()).isEqualTo("COOK TIMOTHY D");
        assertThat(transactions).allSatisfy(t -> assertThat(t.startDate()).isNotNull());
    }

    @Test
    void insiderPurchases() {
        InsiderPurchases purchases =
                ticker("AAPL", "aapl.json").insiderPurchases().orElseThrow();

        assertThat(purchases)
                .isEqualTo(new InsiderPurchases(
                        "6m", 843_593L, 12L, 582_307L, 17L, 261_286L, 29L, 240_512_096L, 0.001, 0.004, 0.002));
    }

    @Test
    void insiderRosterHolders() {
        List<InsiderRosterEntry> roster = ticker("AAPL", "aapl.json").insiderRosterHolders();

        assertThat(roster).hasSize(10);
        assertThat(roster.getFirst())
                .isEqualTo(new InsiderRosterEntry(
                        "ADAMS KATHERINE L",
                        "General Counsel",
                        "",
                        "Stock Gift",
                        LocalDate.of(2025, 11, 12),
                        LocalDate.of(2025, 11, 12),
                        175_408L,
                        null,
                        null));
        assertThat(roster)
                .extracting(InsiderRosterEntry::name)
                .contains("COOK TIMOTHY D", "TERNUS JOHN", "O'BRIEN DEIRDRE");
    }

    // ---- partial and missing data ----

    @Test
    void equityWithPartialHolderData() {
        // SAP.DE: no insiderTransactions module, an empty insiderHolders list, sparse purchase activity
        Ticker sap = ticker("SAP.DE", "sap-de.json");

        assertThat(sap.insiderTransactions()).isEmpty();
        assertThat(sap.insiderRosterHolders()).isEmpty();
        assertThat(sap.institutionalHolders()).hasSize(8);
        assertThat(sap.mutualFundHolders()).hasSize(10);
        assertThat(sap.majorHolders())
                .hasValueSatisfying(m -> assertThat(m.institutionsCount()).isEqualTo(1123L));

        InsiderPurchases purchases = sap.insiderPurchases().orElseThrow();
        assertThat(purchases.period()).isEqualTo("6m");
        assertThat(purchases.purchasesShares()).isZero();
        assertThat(purchases.totalInsiderShares()).isEqualTo(179_766_656L);
        assertThat(purchases.salesShares()).isNull();
        assertThat(purchases.netPercentInsiderShares()).isNull();
    }

    @ParameterizedTest
    @CsvSource({"SPY, spy.json", "BTC-USD, btc-usd.json"})
    void symbolWithoutFundamentalsGivesEmptyResults(String symbol, String fixture) {
        // Yahoo answers 404 "No fundamentals data found for symbol" for ETFs and crypto pairs
        Ticker t = ticker(symbol, 404, fixture);

        assertThat(t.majorHolders()).isEmpty();
        assertThat(t.institutionalHolders()).isEmpty();
        assertThat(t.mutualFundHolders()).isEmpty();
        assertThat(t.insiderTransactions()).isEmpty();
        assertThat(t.insiderPurchases()).isEmpty();
        assertThat(t.insiderRosterHolders()).isEmpty();
    }

    @Test
    void unknownSymbolStillThrows() {
        fake.onGet(PATH + "NOSUCHSYMBOLXYZ").respondFixture(404, "quoteSummary/not-found.json");

        assertThatThrownBy(() -> yf.ticker("NOSUCHSYMBOLXYZ").institutionalHolders())
                .isInstanceOf(YFSymbolNotFoundException.class)
                .hasMessageContaining("Quote not found");
    }

    // ---- mapper edge cases (synthetic) ----

    @Test
    void nullModulesGiveEmptyResults() {
        assertThat(HoldersMapper.majorHolders(null)).isEmpty();
        assertThat(HoldersMapper.insiderPurchases(null)).isEmpty();
        assertThat(HoldersMapper.ownership(null)).isEmpty();
        assertThat(HoldersMapper.insiderTransactions(null)).isEmpty();
        assertThat(HoldersMapper.insiderRoster(null)).isEmpty();
    }

    @Test
    void missingListAndMissingFields() throws Exception {
        assertThat(HoldersMapper.ownership(module("{\"maxAge\":1}"))).isEmpty();
        assertThat(HoldersMapper.ownership(module("{\"ownershipList\":{}}"))).isEmpty();

        List<Holder> holders = HoldersMapper.ownership(module("{\"ownershipList\":[{\"maxAge\":1},null,7]}"));
        assertThat(holders).containsExactly(new Holder(null, null, null, null, null, null));

        assertThat(HoldersMapper.majorHolders(module("{\"maxAge\":1}")))
                .contains(new MajorHolders(null, null, null, null));
    }

    @Test
    void indirectPositionsAndWrongTypes() throws Exception {
        List<InsiderRosterEntry> roster = HoldersMapper.insiderRoster(module("""
                {"holders":[{"name":"X","positionIndirect":{"raw":500,"fmt":"500"},
                  "positionIndirectDate":{"raw":1782777600,"fmt":"2026-06-30"},
                  "positionDirect":"n/a","latestTransDate":"2026-01-01"}]}"""));

        assertThat(roster).singleElement().satisfies(e -> {
            assertThat(e.sharesOwnedIndirectly()).isEqualTo(500L);
            assertThat(e.positionIndirectDate()).isEqualTo(LocalDate.of(2026, 6, 30));
            assertThat(e.sharesOwnedDirectly()).isNull();
            assertThat(e.latestTransactionDate()).isNull();
            assertThat(e.position()).isNull();
        });
    }
}
