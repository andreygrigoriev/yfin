package io.andreygrigoriev.yfin.internal.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.andreygrigoriev.yfin.exception.YFTransportException;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RequestLimiterTest {

    @Test
    void neverExceedsPermitCountUnderManyVirtualThreads() throws Exception {
        var limiter = new RequestLimiter(4);
        var inFlight = new AtomicInteger();
        var maxSeen = new AtomicInteger();
        var start = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<Integer>>();
            for (int i = 0; i < 100; i++) {
                int id = i;
                futures.add(executor.submit(() -> {
                    start.await();
                    return limiter.call(() -> {
                        int now = inFlight.incrementAndGet();
                        maxSeen.accumulateAndGet(now, Math::max);
                        try {
                            Thread.sleep(2);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        inFlight.decrementAndGet();
                        return id;
                    });
                }));
            }
            start.countDown();
            int sum = 0;
            for (var future : futures) {
                sum += future.get(30, TimeUnit.SECONDS);
            }
            assertThat(sum).isEqualTo(99 * 100 / 2);
        }

        assertThat(maxSeen.get()).isBetween(1, 4);
        assertThat(limiter.availablePermits()).isEqualTo(4);
    }

    @Test
    void releasesPermitWhenCallThrows() {
        var limiter = new RequestLimiter(1);

        assertThatThrownBy(() -> limiter.call(() -> {
                    throw new IllegalStateException("boom");
                }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(limiter.availablePermits()).isEqualTo(1);
        assertThat(limiter.call(() -> "ok")).isEqualTo("ok");
    }

    @Test
    void interruptWhileWaitingBecomesTransportException() throws Exception {
        var limiter = new RequestLimiter(1);
        var holding = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        var interruptFlag = new AtomicBoolean();

        Thread holder = Thread.ofVirtual()
                .start(() -> limiter.call(() -> {
                    holding.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return null;
                }));
        holding.await();

        Thread waiter = Thread.ofVirtual().start(() -> {
            try {
                limiter.call(() -> "never");
            } catch (Throwable t) {
                failure.set(t);
                interruptFlag.set(Thread.currentThread().isInterrupted());
            }
        });
        while (waiter.getState() != Thread.State.WAITING) {
            Thread.onSpinWait();
        }
        waiter.interrupt();
        waiter.join(5_000);
        release.countDown();
        holder.join(5_000);

        assertThat(failure.get())
                .isInstanceOf(YFTransportException.class)
                .hasCauseInstanceOf(InterruptedException.class);
        assertThat(interruptFlag).isTrue();
        assertThat(limiter.availablePermits()).isEqualTo(1);
    }

    @Test
    void rejectsNonPositivePermits() {
        assertThatThrownBy(() -> new RequestLimiter(0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new RequestLimiter(RequestLimiter.DEFAULT_MAX_CONCURRENT_REQUESTS).maxConcurrentRequests())
                .isEqualTo(8);
    }
}
