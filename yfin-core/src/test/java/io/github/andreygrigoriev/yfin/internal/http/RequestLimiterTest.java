package io.github.andreygrigoriev.yfin.internal.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.andreygrigoriev.yfin.exception.YFTransportException;
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
    void admitsExactlyPermitCountConcurrentlyAndBlocksTheNext() throws Exception {
        var limiter = new RequestLimiter(4);
        var inside = new CountDownLatch(4);
        var release = new CountDownLatch(1);
        var fifthEntered = new AtomicBoolean();
        var holders = new ArrayList<Thread>();
        for (int i = 0; i < 4; i++) {
            holders.add(Thread.ofVirtual()
                    .start(() -> limiter.call(() -> {
                        inside.countDown();
                        await(release);
                        return null;
                    })));
        }
        assertThat(inside.await(5, TimeUnit.SECONDS))
                .as("4 calls inside at once")
                .isTrue();
        assertThat(limiter.availablePermits()).isZero();

        Thread fifth = Thread.ofVirtual()
                .start(() -> limiter.call(() -> {
                    fifthEntered.set(true);
                    return null;
                }));
        awaitWaiting(fifth);
        assertThat(fifthEntered).isFalse();

        release.countDown();
        fifth.join(5_000);
        for (Thread t : holders) {
            t.join(5_000);
        }
        assertThat(fifthEntered).isTrue();
        assertThat(limiter.availablePermits()).isEqualTo(4);
    }

    @Test
    void waitersAreServedInArrivalOrder() throws Exception {
        var limiter = new RequestLimiter(1);
        var holding = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var order = new java.util.concurrent.ConcurrentLinkedQueue<String>();
        Thread holder = Thread.ofVirtual()
                .start(() -> limiter.call(() -> {
                    holding.countDown();
                    await(release);
                    return null;
                }));
        holding.await();
        var waiters = new ArrayList<Thread>();
        for (String name : new String[] {"a", "b", "c"}) {
            Thread t = Thread.ofVirtual().start(() -> limiter.call(() -> order.add(name)));
            awaitWaiting(t); // queued before the next one starts
            waiters.add(t);
        }

        release.countDown();
        holder.join(5_000);
        for (Thread t : waiters) {
            t.join(5_000);
        }
        assertThat(order).containsExactly("a", "b", "c");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitWaiting(Thread t) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (t.getState() != Thread.State.WAITING) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(t + " never blocked");
            }
            Thread.onSpinWait();
        }
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
