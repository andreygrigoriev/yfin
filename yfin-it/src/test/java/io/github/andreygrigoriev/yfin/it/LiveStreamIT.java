package io.github.andreygrigoriev.yfin.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.andreygrigoriev.yfin.YFinance;
import io.github.andreygrigoriev.yfin.live.LiveListener;
import io.github.andreygrigoriev.yfin.live.LiveStream;
import io.github.andreygrigoriev.yfin.live.PriceTick;
import io.github.andreygrigoriev.yfin.live.YFinanceLive;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Streams crypto (traded around the clock) for up to 30 seconds. */
class LiveStreamIT {

    @Test
    void receivesTicks() throws InterruptedException {
        List<PriceTick> ticks = new CopyOnWriteArrayList<>();
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        var gotTicks = new CountDownLatch(3);
        LiveListener listener = new LiveListener() {
            @Override
            public void onTick(PriceTick tick) {
                ticks.add(tick);
                gotTicks.countDown();
            }

            @Override
            public void onError(Throwable error) {
                errors.add(error);
            }
        };

        try (YFinance yf = LiveClients.create();
                LiveStream stream = YFinanceLive.connect(yf, listener)) {
            stream.subscribe("BTC-USD", "ETH-USD");
            boolean enough = gotTicks.await(30, TimeUnit.SECONDS);

            assertThat(enough).as("3 ticks within 30s, errors: %s", errors).isTrue();
            assertThat(stream.subscriptions()).containsExactlyInAnyOrder("BTC-USD", "ETH-USD");
        }

        assertThat(ticks).allSatisfy(t -> {
            assertThat(t.id()).isIn("BTC-USD", "ETH-USD");
            assertThat(t.price()).isPositive();
            assertThat(t.time()).isNotNull();
        });
    }
}
