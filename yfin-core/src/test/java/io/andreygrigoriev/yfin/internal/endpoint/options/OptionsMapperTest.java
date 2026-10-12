package io.andreygrigoriev.yfin.internal.endpoint.options;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import io.andreygrigoriev.yfin.YFinance;
import io.andreygrigoriev.yfin.exception.YFDataException;
import io.andreygrigoriev.yfin.exception.YFSymbolNotFoundException;
import io.andreygrigoriev.yfin.internal.http.YahooJson;
import io.andreygrigoriev.yfin.model.OptionChain;
import io.andreygrigoriev.yfin.model.OptionContract;
import io.andreygrigoriev.yfin.spi.Cache;
import io.andreygrigoriev.yfin.testing.FakeTransport;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OptionsMapperTest {

    private static final String PATH = "/v7/finance/options/";
    private static final ZoneId NY = ZoneId.of("America/New_York");
    private static final LocalDate NEAREST = LocalDate.of(2026, 10, 12);
    private static final LocalDate FAR = LocalDate.of(2029, 1, 19);

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
    void expirationsNearestFirst() {
        fake.onGet(PATH + "AAPL").respondFixture("options/aapl.json");

        List<LocalDate> expirations = yf.ticker("AAPL").optionExpirations();

        assertThat(expirations)
                .hasSize(24)
                .startsWith(NEAREST, LocalDate.of(2026, 10, 14))
                .endsWith(FAR);
        assertThat(expirations).isSorted();
        assertThat(fake.requests().getLast().uri().getHost()).isEqualTo("query2.finance.yahoo.com");
    }

    @Test
    void nearestChainParsing() {
        fake.onGet(PATH + "AAPL").respondFixture("options/aapl.json");

        OptionChain chain = yf.ticker("AAPL").optionChain().orElseThrow();

        assertThat(chain.symbol()).isEqualTo("AAPL");
        assertThat(chain.expiration()).isEqualTo(NEAREST);
        assertThat(chain.calls()).hasSize(46);
        assertThat(chain.puts()).hasSize(48);
        assertThat(chain.underlying())
                .containsEntry("symbol", "AAPL")
                .containsEntry("regularMarketPrice", 339.57)
                .containsEntry("exchangeTimezoneName", "America/New_York");
        OptionContract call = chain.calls().getFirst();
        assertThat(call)
                .isEqualTo(new OptionContract(
                        "AAPL261012C00245000",
                        245.0,
                        94.54,
                        93.55,
                        94.8,
                        2.7300034,
                        2.973536,
                        35,
                        11,
                        2.8730496923828124,
                        true,
                        ZonedDateTime.ofInstant(java.time.Instant.ofEpochSecond(1791825250), NY),
                        "REGULAR",
                        "USD"));
        assertThat(call.lastTradeDate().getZone()).isEqualTo(NY);
        assertThat(chain.calls()).extracting(OptionContract::strike).isSorted();
    }

    @Test
    void missingBidAndVolumeAreNaNAndZero() {
        fake.onGet(PATH + "AAPL").respondFixture("options/aapl.json");

        OptionChain chain = yf.ticker("AAPL").optionChain().orElseThrow();

        OptionContract noBid = chain.calls().stream()
                .filter(c -> c.contractSymbol().equals("AAPL261012C00395000"))
                .findFirst()
                .orElseThrow();
        assertThat(noBid.bid()).isNaN();
        assertThat(noBid.ask()).isEqualTo(0.01);
        assertThat(noBid.volume()).isZero();
        assertThat(noBid.openInterest()).isZero();
        assertThat(noBid.inTheMoney()).isFalse();
    }

    @Test
    void missingFieldsSynthetic() throws Exception {
        JsonNode result = YahooJson.mapper().readTree("""
                {"expirationDates":[1791763200,"x"],"quote":{"exchangeTimezoneName":"Nowhere/Zone"},
                 "options":[{"expirationDate":1791763200,
                   "calls":[{"contractSymbol":"X261012C00001000","strike":1.0},{"strike":2.0}],
                   "puts":[{"contractSymbol":"X261012P00001000","lastTradeDate":1791825250,"bid":null,"ask":"n/a"}]}]}""");

        assertThat(OptionsMapper.expirations(result)).containsExactly(java.util.Map.entry(NEAREST, 1791763200L));
        OptionChain chain = OptionsMapper.chain("X", result).orElseThrow();
        assertThat(chain.calls()).singleElement().satisfies(c -> {
            assertThat(c.strike()).isEqualTo(1.0);
            assertThat(c.lastPrice()).isNaN();
            assertThat(c.bid()).isNaN();
            assertThat(c.ask()).isNaN();
            assertThat(c.impliedVolatility()).isNaN();
            assertThat(c.lastTradeDate()).isNull();
            assertThat(c.contractSize()).isNull();
            assertThat(c.currency()).isNull();
        });
        OptionContract put = chain.puts().getFirst();
        assertThat(put.bid()).isNaN();
        assertThat(put.ask()).isNaN();
        assertThat(put.lastTradeDate().getZone()).isEqualTo(ZoneOffset.UTC);
        assertThat(OptionsMapper.chain("X", YahooJson.mapper().readTree("{\"options\":[]}")))
                .isEmpty();
    }

    @Test
    void chainOfLaterExpirationTakesSecondRequest() {
        fake.onGet(PATH + "AAPL").query("date", "1863475200").respondFixture("options/aapl-2029-01-19.json");
        fake.onGet(PATH + "AAPL").respondFixture("options/aapl.json");

        OptionChain chain = yf.ticker("AAPL").optionChain(FAR);

        assertThat(chain.expiration()).isEqualTo(FAR);
        assertThat(chain.calls()).hasSize(75);
        assertThat(chain.puts()).hasSize(55);
        assertThat(chain.calls().getFirst().contractSymbol()).isEqualTo("AAPL290119C00005000");
        assertThat(fake.requests())
                .filteredOn(r -> r.uri().getPath().startsWith(PATH))
                .hasSize(2);
    }

    @Test
    void chainOfNearestExpirationReusesFirstResponse() {
        fake.onGet(PATH + "AAPL").respondFixture("options/aapl.json");

        OptionChain chain = yf.ticker("AAPL").optionChain(NEAREST);

        assertThat(chain.expiration()).isEqualTo(NEAREST);
        assertThat(fake.requests())
                .filteredOn(r -> r.uri().getPath().startsWith(PATH))
                .hasSize(1);
    }

    @Test
    void listedExpirationWithoutContractsIsEmptyChain() {
        fake.onGet(PATH + "AAPL").query("date", "1791936000").respondFixture("options/aapl-not-an-expiration.json");
        fake.onGet(PATH + "AAPL").respondFixture("options/aapl.json");

        OptionChain chain = yf.ticker("AAPL").optionChain(LocalDate.of(2026, 10, 14));

        assertThat(chain.calls()).isEmpty();
        assertThat(chain.puts()).isEmpty();
        assertThat(chain.expiration()).isEqualTo(LocalDate.of(2026, 10, 14));
    }

    @Test
    void invalidExpirationListsValidDates() {
        fake.onGet(PATH + "AAPL").respondFixture("options/aapl.json");

        assertThatThrownBy(() -> yf.ticker("AAPL").optionChain(LocalDate.of(2026, 10, 13)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith(
                        "Expiration `2026-10-13` cannot be found. Available expirations are: [2026-10-12, 2026-10-14,")
                .hasMessageEndingWith("2029-01-19]");
    }

    @Test
    void symbolWithoutOptions() {
        fake.onGet(PATH + "SAP.DE").respondFixture("options/sap-de.json");

        var ticker = yf.ticker("SAP.DE");

        assertThat(ticker.optionExpirations()).isEmpty();
        assertThat(ticker.optionChain()).isEmpty();
        assertThatThrownBy(() -> ticker.optionChain(NEAREST))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Available expirations are: []");
    }

    @Test
    void unknownSymbolHasNoExpirations() {
        fake.onGet(PATH + "NOSUCHSYMBOLXYZ").respondFixture("options/unknown.json");

        assertThat(yf.ticker("NOSUCHSYMBOLXYZ").optionExpirations()).isEmpty();
        assertThat(yf.ticker("NOSUCHSYMBOLXYZ").optionChain()).isEmpty();
    }

    @Test
    void errors() throws Exception {
        fake.onGet(PATH + "AAPL").respondFixture(400, "options/bad-date.json");
        assertThatThrownBy(() -> yf.ticker("AAPL").optionExpirations())
                .isInstanceOf(YFDataException.class)
                .hasMessageContaining("Bad Request");
        fake.onGet(PATH + "GONE").respond(404, """
                {"finance":{"result":null,"error":{"code":"Not Found","description":"No data found"}}}""");
        assertThatThrownBy(() -> yf.ticker("GONE").optionExpirations()).isInstanceOf(YFSymbolNotFoundException.class);
        assertThatThrownBy(() -> OptionsEndpoint.parse(YahooJson.mapper().readTree("{}")))
                .isInstanceOf(YFDataException.class);
    }
}
