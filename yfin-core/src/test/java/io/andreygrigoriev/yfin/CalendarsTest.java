package io.andreygrigoriev.yfin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.andreygrigoriev.yfin.model.EarningsEvent;
import io.andreygrigoriev.yfin.model.EconomicEvent;
import io.andreygrigoriev.yfin.model.IpoEvent;
import io.andreygrigoriev.yfin.model.SplitEvent;
import io.andreygrigoriev.yfin.request.CalendarRequest;
import io.andreygrigoriev.yfin.request.EarningsCalendarRequest;
import io.andreygrigoriev.yfin.spi.Cache;
import io.andreygrigoriev.yfin.spi.HttpRequest;
import io.andreygrigoriev.yfin.testing.FakeTransport;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CalendarsTest {

    private static final String PATH = "/v1/finance/visualization";
    private static final String SCREENER = "/v1/finance/screener/predefined/saved";
    private static final CalendarRequest WEEK =
            CalendarRequest.between(LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 21));

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
    void earningsCalendar() {
        fake.onGet(SCREENER).respondFixture("calendar/most-actives.json");
        fake.onPost(PATH).respondFixture("calendar/earnings-week.json");

        List<EarningsEvent> events = yf.earningsCalendar(
                EarningsCalendarRequest.between(LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 21)));

        assertThat(events).hasSize(8);
        assertThat(events.getFirst().symbol()).isEqualTo("NFLX");
        assertThat(events.getLast().symbol()).isEqualTo("BAC");
    }

    @Test
    void ipoCalendar() {
        fake.onPost(PATH).respondFixture("calendar/ipo-week.json");

        List<IpoEvent> ipos = yf.ipoCalendar(WEEK);

        assertThat(ipos).hasSize(16);
        assertThat(ipos.getFirst().symbol()).isEqualTo("AVUC");
    }

    @Test
    void economicEventsCalendar() {
        fake.onPost(PATH).respondFixture("calendar/economic-week.json");

        List<EconomicEvent> events = yf.economicEventsCalendar(WEEK);

        assertThat(events).hasSize(100);
        assertThat(events.getFirst().event()).isEqualTo("CPI YY*");
    }

    @Test
    void splitsCalendar() {
        fake.onPost(PATH).respondFixture("calendar/splits-week.json");

        List<SplitEvent> splits = yf.splitsCalendar(WEEK);

        assertThat(splits).hasSize(79);
        assertThat(splits.getFirst().symbol()).isEqualTo("117670.KQ");
    }

    @ParameterizedTest
    @ValueSource(strings = {"earnings", "ipo", "economic", "splits"})
    void noArgOverloadsSendNextWeek(String type) {
        fake.onGet(SCREENER).respondFixture("calendar/most-actives.json");
        fake.onPost(PATH).respondFixture("calendar/" + type + "-empty.json");
        LocalDate today = LocalDate.now();

        assertThat(noArg(type)).isEmpty();

        HttpRequest request = fake.requests().getLast();
        assertThat(request.uri().getPath()).isEqualTo(PATH);
        String body = request.bodyAsString().orElseThrow();
        // a run across midnight may see the next day
        LocalDate start = body.contains("\"" + today + "\"") ? today : today.plusDays(1);
        assertThat(body)
                .contains("\"" + start + "\"")
                .contains("\"" + start.plusDays(CalendarRequest.DEFAULT_DAYS) + "\"")
                .contains("\"size\":" + CalendarRequest.DEFAULT_LIMIT)
                .contains("\"offset\":0");
    }

    @Test
    void nullRequestThrows() {
        assertThatNullPointerException().isThrownBy(() -> yf.earningsCalendar(null));
        assertThatNullPointerException().isThrownBy(() -> yf.ipoCalendar(null));
        assertThatNullPointerException().isThrownBy(() -> yf.economicEventsCalendar(null));
        assertThatNullPointerException().isThrownBy(() -> yf.splitsCalendar(null));
        assertThat(fake.requests()).isEmpty();
    }

    @Test
    void closedClientThrows() {
        yf.close();

        List<Function<YFinance, ?>> calls = List.of(
                YFinance::earningsCalendar,
                YFinance::ipoCalendar,
                YFinance::economicEventsCalendar,
                YFinance::splitsCalendar,
                c -> c.earningsCalendar(EarningsCalendarRequest.nextWeek()),
                c -> c.ipoCalendar(WEEK),
                c -> c.economicEventsCalendar(WEEK),
                c -> c.splitsCalendar(WEEK));
        for (Function<YFinance, ?> call : calls) {
            assertThatThrownBy(() -> call.apply(yf)).isInstanceOf(IllegalStateException.class);
        }
        assertThat(fake.requests()).isEmpty();
    }

    private List<?> noArg(String type) {
        return switch (type) {
            case "earnings" -> yf.earningsCalendar();
            case "ipo" -> yf.ipoCalendar();
            case "economic" -> yf.economicEventsCalendar();
            case "splits" -> yf.splitsCalendar();
            default -> throw new IllegalArgumentException(type);
        };
    }
}
