package io.github.andreygrigoriev.yfin.it.smoke;

import io.github.andreygrigoriev.yfin.Ticker;
import io.github.andreygrigoriev.yfin.YFinance;
import io.github.andreygrigoriev.yfin.live.LiveListener;
import io.github.andreygrigoriev.yfin.live.LiveStream;
import io.github.andreygrigoriev.yfin.live.PriceTick;
import io.github.andreygrigoriev.yfin.live.YFinanceLive;
import io.github.andreygrigoriev.yfin.model.FinancialStatement;
import io.github.andreygrigoriev.yfin.model.LineItem;
import io.github.andreygrigoriev.yfin.model.PriceHistory;
import io.github.andreygrigoriev.yfin.model.TickerInfo;
import io.github.andreygrigoriev.yfin.request.Frequency;
import io.github.andreygrigoriev.yfin.request.Period;
import io.github.andreygrigoriev.yfin.spi.Cache;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Minimal consumer app built as a native executable by {@code ./mvnw -Pnative package} in yfin-it,
 * with no yfin-specific native-image flags or metadata, and run against live Yahoo by {@code
 * NativeSmokeIT}.
 *
 * <p>It fetches a month of daily history, the info snapshot and the annual income statement of a
 * symbol over HTTPS, then streams crypto ticks over the WebSocket for up to 10 seconds. Each check
 * prints one {@code OK <check> ...} or {@code FAIL <check> ...} line; the exit status is 0 only if
 * every check passed.
 */
public final class NativeSmoke {

    static final String OK = "OK";
    static final String FAIL = "FAIL";

    private NativeSmoke() {}

    public static void main(String[] args) throws InterruptedException {
        String symbol = args.length > 0 ? args[0] : "AAPL";
        System.exit(run(symbol, System.out));
    }

    static int run(String symbol, PrintStream out) throws InterruptedException {
        List<String> failures = new ArrayList<>();
        try (YFinance yf = YFinance.builder().cache(Cache.none()).build()) {
            Ticker ticker = yf.ticker(symbol);
            check(out, failures, "history", () -> {
                PriceHistory h = ticker.history(Period.ONE_MONTH);
                require(!h.bars().isEmpty(), "no bars");
                return "bars=" + h.bars().size() + " zone=" + h.exchangeZone();
            });
            check(out, failures, "info", () -> {
                TickerInfo info = ticker.info();
                require(info.shortName() != null, "no shortName");
                return "shortName=" + info.shortName() + " fields=" + info.raw().size();
            });
            check(out, failures, "statements", () -> {
                FinancialStatement s = ticker.incomeStatement(Frequency.ANNUAL);
                require(!s.periods().isEmpty(), "no periods");
                require(!s.get(LineItem.TOTAL_REVENUE).isEmpty(), "no TotalRevenue");
                return "periods=" + s.periods().size() + " lineItems="
                        + s.lineItems().size();
            });
            String live = stream(yf);
            out.println(live);
            if (!live.startsWith(OK)) {
                failures.add("live");
            }
        }
        out.println(failures.isEmpty() ? "SMOKE PASSED" : "SMOKE FAILED " + failures);
        return failures.isEmpty() ? 0 : 1;
    }

    private static String stream(YFinance yf) throws InterruptedException {
        List<PriceTick> ticks = new CopyOnWriteArrayList<>();
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        var firstTick = new CountDownLatch(1);
        LiveListener listener = new LiveListener() {
            @Override
            public void onTick(PriceTick tick) {
                ticks.add(tick);
                firstTick.countDown();
            }

            @Override
            public void onError(Throwable error) {
                errors.add(error);
            }
        };
        try (LiveStream stream = YFinanceLive.connect(yf, listener)) {
            stream.subscribe("BTC-USD", "ETH-USD");
            if (!firstTick.await(10, TimeUnit.SECONDS)) {
                return FAIL + " live no tick within 10s, errors=" + errors;
            }
            PriceTick tick = ticks.getFirst();
            return OK + " live ticks=" + ticks.size() + " first=" + tick.id() + "@" + tick.price();
        } catch (RuntimeException e) {
            return FAIL + " live " + e;
        }
    }

    private static void check(PrintStream out, List<String> failures, String name, Check check) {
        try {
            out.println(OK + " " + name + " " + check.run());
        } catch (RuntimeException e) {
            out.println(FAIL + " " + name + " " + e);
            failures.add(name);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    @FunctionalInterface
    private interface Check {
        String run();
    }
}
