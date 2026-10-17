package io.github.andreygrigoriev.yfin.live.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.andreygrigoriev.yfin.exception.YFDataException;
import io.github.andreygrigoriev.yfin.live.PriceTick;
import io.github.andreygrigoriev.yfin.live.internal.proto.PricingData;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class PricingDecoderTest {

    static String fixture(String name) throws IOException {
        try (InputStream in = PricingDecoderTest.class.getResourceAsStream("/fixtures/streamer/" + name)) {
            assertThat(in).as(name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        }
    }

    @Test
    void decodesRecordedEquityTick() throws IOException {
        PriceTick tick = StreamerProtocol.decodeFrame(fixture("aapl.json"));

        assertThat(tick).isNotNull();
        assertThat(tick.id()).isEqualTo("AAPL");
        assertThat(tick.price()).isEqualTo(342.4);
        assertThat(tick.time()).isEqualTo(Instant.parse("2026-10-13T07:17:36Z"));
        assertThat(tick.exchange()).isEqualTo("NMS");
        assertThat(tick.quoteType()).isEqualTo(8);
        assertThat(tick.marketHours()).isEqualTo(4);
        assertThat(tick.changePercent()).isEqualTo(0.41938958);
        assertThat(tick.change()).isEqualTo(1.4299927);
        assertThat(tick.priceHint()).isEqualTo(2);
        // fields Yahoo did not send: null strings, zero numbers
        assertThat(tick.currency()).isNull();
        assertThat(tick.shortName()).isNull();
        assertThat(tick.dayVolume()).isZero();
        assertThat(tick.bid()).isZero();
    }

    @Test
    void decodesRecordedCryptoTick() throws IOException {
        PriceTick tick = StreamerProtocol.decodeFrame(fixture("btc-usd.json"));

        assertThat(tick).isNotNull();
        assertThat(tick.id()).isEqualTo("BTC-USD");
        assertThat(tick.price()).isEqualTo(83733.99);
        assertThat(tick.currency()).isEqualTo("USD");
        assertThat(tick.exchange()).isEqualTo("CCC");
        assertThat(tick.quoteType()).isEqualTo(41);
        assertThat(tick.marketHours()).isEqualTo(1);
        assertThat(tick.dayVolume()).isEqualTo(30503794688L);
        assertThat(tick.dayHigh()).isEqualTo(83679.484);
        assertThat(tick.dayLow()).isEqualTo(81197.99);
        assertThat(tick.openPrice()).isEqualTo(81754.484);
        assertThat(tick.vol24hr()).isEqualTo(30503794688L);
        assertThat(tick.fromCurrency()).isEqualTo("BTC");
        assertThat(tick.circulatingSupply()).isEqualTo(2.009748E7);
        assertThat(tick.marketCap()).isEqualTo(1.68122135E12);
    }

    @Test
    void decodesRecordedCurrencyTick() throws IOException {
        PriceTick tick = StreamerProtocol.decodeFrame(fixture("eurusd.json"));

        assertThat(tick).isNotNull();
        assertThat(tick.id()).isEqualTo("EURUSD=X");
        assertThat(tick.price()).isEqualTo(1.11835);
        assertThat(tick.exchange()).isEqualTo("CCY");
        assertThat(tick.quoteType()).isEqualTo(14);
        assertThat(tick.priceHint()).isEqualTo(4);
    }

    @Test
    void mapsEveryField() {
        PricingData data = PricingData.newBuilder()
                .setId("AAPL260116C00200000")
                .setPrice(1.5f)
                .setTime(1_700_000_000_123L)
                .setCurrency("USD")
                .setExchange("OPR")
                .setQuoteType(13)
                .setMarketHours(1)
                .setChangePercent(-2.5f)
                .setDayVolume(1234)
                .setDayHigh(1.75f)
                .setDayLow(1.25f)
                .setChange(-0.25f)
                .setShortName("AAPL Jan 26 200 Call")
                .setExpireDate(1_768_521_600L)
                .setOpenPrice(1.6f)
                .setPreviousClose(1.75f)
                .setStrikePrice(200f)
                .setUnderlyingSymbol("AAPL")
                .setOpenInterest(5000)
                .setOptionsType(1)
                .setMiniOption(0)
                .setLastSize(10)
                .setBid(1.45f)
                .setBidSize(20)
                .setAsk(1.55f)
                .setAskSize(30)
                .setPriceHint(2)
                .setVol24Hr(99)
                .setVolAllCurrencies(98)
                .setFromCurrency("XXX")
                .setLastMarket("YYY")
                .setCirculatingSupply(1e9)
                .setMarketCap(2.5e12)
                .build();

        PriceTick tick = PricingDecoder.decode(Base64.getEncoder().encodeToString(data.toByteArray()));

        assertThat(tick)
                .isEqualTo(new PriceTick(
                        "AAPL260116C00200000",
                        1.5,
                        Instant.ofEpochMilli(1_700_000_000_123L),
                        "USD",
                        "OPR",
                        13,
                        1,
                        -2.5,
                        1234,
                        1.75,
                        1.25,
                        -0.25,
                        "AAPL Jan 26 200 Call",
                        1_768_521_600L,
                        1.6,
                        1.75,
                        200.0,
                        "AAPL",
                        5000,
                        1,
                        0,
                        10,
                        1.45,
                        20,
                        1.55,
                        30,
                        2,
                        99,
                        98,
                        "XXX",
                        "YYY",
                        1e9,
                        2.5e12));
    }

    @Test
    void widenKeepsShortestDecimal() {
        assertThat(PricingDecoder.widen(189.97f)).isEqualTo(189.97);
        assertThat(PricingDecoder.widen(0.1f)).isEqualTo(0.1);
        assertThat(PricingDecoder.widen(Float.NaN)).isNaN();
        assertThat(PricingDecoder.widen(Float.POSITIVE_INFINITY)).isInfinite();
    }

    @Test
    void frameWithoutMessageIsIgnored() {
        assertThat(StreamerProtocol.decodeFrame("{\"type\":\"heartbeat\"}")).isNull();
        assertThat(StreamerProtocol.decodeFrame("{\"message\":\"\"}")).isNull();
        assertThat(StreamerProtocol.decodeFrame("{\"message\":42}")).isNull();
    }

    @Test
    void invalidPayloadsThrowDataException() {
        assertThatThrownBy(() -> StreamerProtocol.decodeFrame("not json"))
                .isInstanceOf(YFDataException.class)
                .hasMessageContaining("not JSON");
        assertThatThrownBy(() -> PricingDecoder.decode("***"))
                .isInstanceOf(YFDataException.class)
                .hasMessageContaining("not base64");
        // 0x0A = field 1, length-delimited, length 0x7F but no bytes follow
        String truncated = Base64.getEncoder().encodeToString(new byte[] {0x0A, 0x7F});
        assertThatThrownBy(() -> PricingDecoder.decode(truncated))
                .isInstanceOf(YFDataException.class)
                .hasMessageContaining("not PricingData")
                .satisfies(
                        e -> assertThat(((YFDataException) e).payloadSnippet()).isEqualTo(truncated));
        String noId = Base64.getEncoder()
                .encodeToString(PricingData.newBuilder().setPrice(1f).build().toByteArray());
        assertThatThrownBy(() -> PricingDecoder.decode(noId))
                .isInstanceOf(YFDataException.class)
                .hasMessageContaining("no symbol");
    }

    @Test
    void subscribeAndUnsubscribeMessages() {
        assertThat(StreamerProtocol.subscribe(List.of("AAPL", "BTC-USD")))
                .isEqualTo("{\"subscribe\":[\"AAPL\",\"BTC-USD\"]}");
        assertThat(StreamerProtocol.unsubscribe(List.of("AAPL"))).isEqualTo("{\"unsubscribe\":[\"AAPL\"]}");
        assertThat(StreamerProtocol.subscribe(List.of())).isEqualTo("{\"subscribe\":[]}");
    }
}
