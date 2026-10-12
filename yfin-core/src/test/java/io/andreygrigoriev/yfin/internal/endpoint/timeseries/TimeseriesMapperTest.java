package io.andreygrigoriev.yfin.internal.endpoint.timeseries;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import io.andreygrigoriev.yfin.Ticker;
import io.andreygrigoriev.yfin.YFinance;
import io.andreygrigoriev.yfin.internal.http.YahooJson;
import io.andreygrigoriev.yfin.model.FinancialStatement;
import io.andreygrigoriev.yfin.model.LineItem;
import io.andreygrigoriev.yfin.request.Frequency;
import io.andreygrigoriev.yfin.spi.Cache;
import io.andreygrigoriev.yfin.testing.FakeTransport;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TimeseriesMapperTest {

    static final String PATH = "/ws/fundamentals-timeseries/v1/finance/timeseries/";

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

    /**
     * Serves {@code timeseries/<fixture>-<n>.json} for chunk {@code n} of {@code statement}, the
     * chunk being recognized by its first requested type.
     */
    static void serve(FakeTransport fake, String symbol, StatementKeys statement, Frequency frequency, String fixture) {
        List<String> keys = statement.keys();
        for (int i = 0; i * TimeseriesEndpoint.CHUNK_KEYS < keys.size(); i++) {
            String first = frequency.yahooPrefix() + keys.get(i * TimeseriesEndpoint.CHUNK_KEYS);
            fake.onGet(PATH + symbol)
                    .queryMatching(q -> q.get("type").getFirst().startsWith(first + ","))
                    .respondFixture("timeseries/" + fixture + "-" + (i + 1) + ".json");
        }
    }

    private Ticker ticker(String symbol, StatementKeys statement, Frequency frequency, String fixture) {
        serve(fake, symbol, statement, frequency, fixture);
        return yf.ticker(symbol);
    }

    private static LocalDate d(String iso) {
        return LocalDate.parse(iso);
    }

    // ---- equity: income statement ----

    @Test
    void annualIncomeStatement() {
        FinancialStatement income = ticker("AAPL", StatementKeys.INCOME, Frequency.ANNUAL, "aapl-annual-financials")
                .incomeStatement(Frequency.ANNUAL);

        assertThat(income.symbol()).isEqualTo("AAPL");
        assertThat(income.frequency()).isEqualTo(Frequency.ANNUAL);
        assertThat(income.currency()).isEqualTo("USD");
        assertThat(income.periods())
                .containsExactly(d("2025-09-30"), d("2024-09-30"), d("2023-09-30"), d("2022-09-30"));
        assertThat(income.get(LineItem.TOTAL_REVENUE))
                .containsExactly(
                        entry("2025-09-30", 4.16161e11),
                        entry("2024-09-30", 3.91035e11),
                        entry("2023-09-30", 3.83285e11),
                        entry("2022-09-30", 3.94328e11));
        assertThat(income.get("NetIncome").get(d("2025-09-30"))).isEqualTo(1.1201e11);
        assertThat(income.value(LineItem.DILUTED_EPS, d("2022-09-30"))).isEqualTo(6.11);
        assertThat(income.value(LineItem.GROSS_PROFIT, d("2024-09-30"))).isEqualTo(1.80683e11);
        assertThat(fake.requests().stream()
                        .filter(r -> r.uri().getPath().startsWith(PATH))
                        .count())
                .isEqualTo(2);
    }

    @Test
    void lineItemsFollowYahooOrder() {
        FinancialStatement income = ticker("AAPL", StatementKeys.INCOME, Frequency.ANNUAL, "aapl-annual-financials")
                .incomeStatement(Frequency.ANNUAL);

        List<String> keys = income.lineItems().stream().map(LineItem::key).toList();
        assertThat(keys).hasSize(39).startsWith("TaxEffectOfUnusualItems", "TaxRateForCalcs", "NormalizedEBITDA");
        assertThat(keys.getLast()).isEqualTo("OperatingRevenue");
        assertThat(keys).isSubsetOf(StatementKeys.INCOME.keys());
        List<String> inKeyOrder =
                StatementKeys.INCOME.keys().stream().filter(keys::contains).toList();
        assertThat(keys).isEqualTo(inKeyOrder);
    }

    @Test
    void quarterlyIncomeStatement() {
        FinancialStatement income = ticker(
                        "AAPL", StatementKeys.INCOME, Frequency.QUARTERLY, "aapl-quarterly-financials")
                .incomeStatement(Frequency.QUARTERLY);

        assertThat(income.periods())
                .containsExactly(d("2026-06-30"), d("2026-03-31"), d("2025-12-31"), d("2025-09-30"), d("2025-06-30"));
        assertThat(income.lineItems()).hasSize(33);
        assertThat(income.get(LineItem.TOTAL_REVENUE).values())
                .containsExactly(1.09417e11, 1.11184e11, 1.43756e11, 1.02466e11, 9.4036e10);
        assertThat(FakeTransport.parseQuery(fake.requests().getLast().uri().getRawQuery())
                        .get("type")
                        .getFirst())
                .startsWith("quarterly");
    }

    @Test
    void trailingIncomeStatementKeepsOnlyNewestPeriod() {
        FinancialStatement ttm = ticker("AAPL", StatementKeys.INCOME, Frequency.TRAILING, "aapl-trailing-financials")
                .incomeStatement(Frequency.TRAILING);

        assertThat(ttm.frequency()).isEqualTo(Frequency.TRAILING);
        assertThat(ttm.periods()).containsExactly(d("2026-06-30"));
        assertThat(ttm.lineItems()).hasSize(33);
        assertThat(ttm.get(LineItem.TOTAL_REVENUE)).containsExactly(entry("2026-06-30", 4.66823e11));
        assertThat(ttm.get(LineItem.NET_INCOME)).containsExactly(entry("2026-06-30", 1.2893e11));
    }

    // ---- equity: balance sheet and cash flow ----

    @Test
    void annualBalanceSheet() {
        FinancialStatement balance = ticker(
                        "AAPL", StatementKeys.BALANCE_SHEET, Frequency.ANNUAL, "aapl-annual-balance-sheet")
                .balanceSheet(Frequency.ANNUAL);

        assertThat(balance.periods())
                .containsExactly(d("2025-09-30"), d("2024-09-30"), d("2023-09-30"), d("2022-09-30"));
        assertThat(balance.lineItems()).hasSize(69).startsWith(LineItem.of("TreasurySharesNumber"));
        assertThat(balance.get(LineItem.TOTAL_ASSETS).values())
                .containsExactly(3.59241e11, 3.6498e11, 3.52583e11, 3.52755e11);
        assertThat(balance.value(LineItem.STOCKHOLDERS_EQUITY, d("2025-09-30"))).isEqualTo(7.3733e10);
        assertThat(fake.requests().stream()
                        .filter(r -> r.uri().getPath().startsWith(PATH))
                        .count())
                .isEqualTo(3);
    }

    @Test
    void trailingCashFlow() {
        FinancialStatement ttm = ticker("AAPL", StatementKeys.CASH_FLOW, Frequency.TRAILING, "aapl-trailing-cash-flow")
                .cashFlow(Frequency.TRAILING);

        assertThat(ttm.periods()).containsExactly(d("2026-06-30"));
        assertThat(ttm.lineItems()).hasSize(45).startsWith(LineItem.FREE_CASH_FLOW);
        assertThat(ttm.get(LineItem.FREE_CASH_FLOW)).containsExactly(entry("2026-06-30", 1.36683e11));
        assertThat(ttm.value(LineItem.OPERATING_CASH_FLOW, d("2026-06-30"))).isEqualTo(1.46724e11);
    }

    @Test
    void trailingBalanceSheetIsRejected() {
        Ticker aapl = yf.ticker("AAPL");

        assertThatThrownBy(() -> aapl.balanceSheet(Frequency.TRAILING))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("balance sheet");
        assertThat(fake.requests()).isEmpty();
    }

    // ---- bank ----

    @Test
    void bankIncomeStatementHasBankLineItems() {
        FinancialStatement income = ticker("JPM", StatementKeys.INCOME, Frequency.ANNUAL, "jpm-annual-financials")
                .incomeStatement(Frequency.ANNUAL);

        assertThat(income.periods())
                .containsExactly(d("2025-12-31"), d("2024-12-31"), d("2023-12-31"), d("2022-12-31"));
        assertThat(income.lineItems())
                .hasSize(37)
                .contains(LineItem.NET_INTEREST_INCOME, LineItem.TOTAL_REVENUE)
                .doesNotContain(LineItem.GROSS_PROFIT, LineItem.COST_OF_REVENUE);
        assertThat(income.get(LineItem.GROSS_PROFIT)).isEmpty();
        assertThat(income.value(LineItem.GROSS_PROFIT, d("2025-12-31"))).isNaN();
        assertThat(income.get(LineItem.NET_INTEREST_INCOME).values())
                .containsExactly(9.5443e10, 9.2583e10, 8.9267e10, 6.671e10);
        assertThat(income.lineItems().getLast()).isEqualTo(LineItem.of("OtherNonInterestExpense"));
    }

    @Test
    void bankBalanceSheetHasNoCurrentAssets() {
        FinancialStatement balance = ticker(
                        "JPM", StatementKeys.BALANCE_SHEET, Frequency.ANNUAL, "jpm-annual-balance-sheet")
                .balanceSheet(Frequency.ANNUAL);

        assertThat(balance.lineItems())
                .hasSize(50)
                .contains(LineItem.of("NetLoan"))
                .doesNotContain(LineItem.CURRENT_ASSETS);
        assertThat(balance.value(LineItem.TOTAL_ASSETS, d("2025-12-31"))).isEqualTo(4.4249e12);
    }

    // ---- no fundamentals ----

    @Test
    void symbolWithoutFundamentalsGivesEmptyStatement() {
        FinancialStatement income = ticker("SPY", StatementKeys.INCOME, Frequency.ANNUAL, "spy-annual-financials")
                .incomeStatement(Frequency.ANNUAL);

        assertThat(income.isEmpty()).isTrue();
        assertThat(income.periods()).isEmpty();
        assertThat(income.lineItems()).isEmpty();
        assertThat(income.currency()).isNull();
        assertThat(income.get(LineItem.TOTAL_REVENUE)).isEmpty();
    }

    @Test
    void nullResultGivesEmptyStatement() {
        fake.onGet(PATH + "XYZ").respond(200, "{\"timeseries\":{\"result\":null,\"error\":null}}");

        FinancialStatement cash = yf.ticker("XYZ").cashFlow(Frequency.QUARTERLY);

        assertThat(cash.isEmpty()).isTrue();
        assertThat(cash.frequency()).isEqualTo(Frequency.QUARTERLY);
    }

    // ---- synthetic edge cases ----

    @Test
    void missingValuesBecomeNaNAndUnknownOrMalformedEntriesAreSkipped() throws Exception {
        List<JsonNode> results = List.of(json("""
                        {"meta":{"type":["annualTotalRevenue"]},"timestamp":[1664496000,1696032000],
                         "annualTotalRevenue":[
                           {"asOfDate":"2023-09-30","currencyCode":"EUR","reportedValue":{"raw":10.0}},
                           null,
                           {"asOfDate":"bad","reportedValue":{"raw":1.0}},
                           {"asOfDate":"2022-09-30","reportedValue":{}}]}"""), json("""
                        {"meta":{"type":["annualNetIncome"]},"timestamp":[1664496000],
                         "annualNetIncome":[{"asOfDate":"2022-09-30","reportedValue":{"raw":2.5}}]}"""), json("""
                        {"meta":{"type":["annualNotAStatementKey"]},"timestamp":[1664496000],
                         "annualNotAStatementKey":[{"asOfDate":"2022-09-30","reportedValue":{"raw":9}}]}"""), json("\"not an object\""));

        FinancialStatement s = TimeseriesMapper.map("X", Frequency.ANNUAL, StatementKeys.INCOME.keys(), results);

        assertThat(s.currency()).isEqualTo("EUR");
        assertThat(s.periods()).containsExactly(d("2023-09-30"), d("2022-09-30"));
        assertThat(s.lineItems()).containsExactly(LineItem.NET_INCOME, LineItem.TOTAL_REVENUE);
        assertThat(s.get(LineItem.TOTAL_REVENUE).get(d("2023-09-30"))).isEqualTo(10.0);
        assertThat(s.get(LineItem.TOTAL_REVENUE).get(d("2022-09-30"))).isNaN();
        assertThat(s.get(LineItem.NET_INCOME).get(d("2023-09-30"))).isNaN();
        assertThat(s.get(LineItem.NET_INCOME).get(d("2022-09-30"))).isEqualTo(2.5);
    }

    @Test
    void trailingRowWithoutNewestValueIsDropped() throws Exception {
        List<JsonNode> results = List.of(json("""
                        {"timestamp":[1751241600,1782777600],"trailingTotalRevenue":[
                          {"asOfDate":"2025-06-30","reportedValue":{"raw":1.0}},
                          {"asOfDate":"2026-06-30","reportedValue":{"raw":2.0}}]}"""), json("""
                        {"timestamp":[1751241600],"trailingNetIncome":[
                          {"asOfDate":"2025-06-30","reportedValue":{"raw":3.0}}]}"""));

        FinancialStatement s = TimeseriesMapper.map("X", Frequency.TRAILING, StatementKeys.INCOME.keys(), results);

        assertThat(s.periods()).containsExactly(d("2026-06-30"));
        assertThat(s.lineItems()).containsExactly(LineItem.TOTAL_REVENUE);
        assertThat(s.get(LineItem.TOTAL_REVENUE)).containsExactly(entry("2026-06-30", 2.0));
    }

    private static JsonNode json(String s) throws Exception {
        return YahooJson.mapper().readTree(s);
    }

    private static java.util.Map.Entry<LocalDate, Double> entry(String date, double value) {
        return java.util.Map.entry(d(date), value);
    }
}
