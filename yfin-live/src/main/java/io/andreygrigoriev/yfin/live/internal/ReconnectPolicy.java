package io.andreygrigoriev.yfin.live.internal;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * Exponential reconnect backoff with jitter: attempt {@code n} (from 1) waits between half and all
 * of {@code min(maxDelay, initialDelay * 2^(n-1))}. The lower half bound keeps a flapping
 * connection from reconnecting in a tight loop.
 *
 * @param initialDelay delay cap of the first attempt
 * @param maxDelay upper bound of every delay
 */
public record ReconnectPolicy(Duration initialDelay, Duration maxDelay) {

    /** Initial delay of the default policy. */
    public static final Duration DEFAULT_INITIAL_DELAY = Duration.ofSeconds(1);
    /** Maximum delay of the default policy. */
    public static final Duration DEFAULT_MAX_DELAY = Duration.ofSeconds(30);

    public ReconnectPolicy {
        Objects.requireNonNull(initialDelay, "initialDelay");
        Objects.requireNonNull(maxDelay, "maxDelay");
        if (initialDelay.isNegative() || initialDelay.isZero()) {
            throw new IllegalArgumentException("initialDelay must be positive: " + initialDelay);
        }
        if (maxDelay.compareTo(initialDelay) < 0) {
            throw new IllegalArgumentException("maxDelay " + maxDelay + " is below initialDelay " + initialDelay);
        }
    }

    /** 1s initial, 30s maximum. */
    public static ReconnectPolicy defaults() {
        return new ReconnectPolicy(DEFAULT_INITIAL_DELAY, DEFAULT_MAX_DELAY);
    }

    /** Delay before reconnect attempt {@code attempt} (1-based). */
    public Duration delay(int attempt) {
        return delay(attempt, () -> ThreadLocalRandom.current().nextDouble());
    }

    /** Delay before {@code attempt} with {@code random} supplying values in {@code [0, 1)}. */
    public Duration delay(int attempt, DoubleSupplier random) {
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be >= 1: " + attempt);
        }
        long cap = cap(attempt).toNanos();
        long half = cap / 2;
        return Duration.ofNanos(half + (long) (random.getAsDouble() * (cap - half)));
    }

    /** {@code min(maxDelay, initialDelay * 2^(attempt-1))}, without overflow. */
    Duration cap(int attempt) {
        int shift = Math.min(attempt - 1, 62);
        long initial = initialDelay.toNanos();
        long max = maxDelay.toNanos();
        if (initial > (max >> shift)) {
            return maxDelay;
        }
        return Duration.ofNanos(Math.min(max, initial << shift));
    }
}
