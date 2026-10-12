package io.andreygrigoriev.yfin.internal.http;

import io.andreygrigoriev.yfin.exception.YFRateLimitException;
import io.andreygrigoriev.yfin.exception.YFTransportException;
import io.andreygrigoriev.yfin.spi.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Retries a request on 429, 5xx and transport failures with exponential backoff and full jitter.
 *
 * <p>Attempt {@code n} (0-based) waits a uniformly random delay in {@code [0, min(maxDelay, base *
 * 2^n))}. A {@code Retry-After} header (delta-seconds or HTTP-date) on a 429/503 response replaces
 * the computed delay, capped at {@code maxDelay}. Other statuses, including non-429 4xx, are
 * returned immediately. After {@code maxRetries} retries a final 429 becomes {@link
 * YFRateLimitException}, a final 5xx or transport failure becomes {@link YFTransportException}.
 *
 * <p>Thread-safe: holds no mutable state.
 */
public final class RetryPolicy {

    private static final Logger LOG = LoggerFactory.getLogger(RetryPolicy.class);

    /** Default number of retries after the first attempt. */
    public static final int DEFAULT_MAX_RETRIES = 2;

    static final Duration DEFAULT_BASE_DELAY = Duration.ofSeconds(1);
    static final Duration DEFAULT_MAX_DELAY = Duration.ofSeconds(30);

    /** Blocks the calling thread; injectable so tests do not sleep. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final int maxRetries;
    private final Duration baseDelay;
    private final Duration maxDelay;
    private final Sleeper sleeper;
    private final DoubleSupplier random;
    private final Clock clock;

    RetryPolicy(
            int maxRetries,
            Duration baseDelay,
            Duration maxDelay,
            Sleeper sleeper,
            DoubleSupplier random,
            Clock clock) {
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be >= 0: " + maxRetries);
        }
        if (baseDelay.isNegative() || maxDelay.isNegative()) {
            throw new IllegalArgumentException("delays must not be negative");
        }
        this.maxRetries = maxRetries;
        this.baseDelay = baseDelay;
        this.maxDelay = maxDelay;
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.random = Objects.requireNonNull(random, "random");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Policy with base delay 1s, max delay 30s, real sleeping and system clock. */
    public static RetryPolicy of(int maxRetries) {
        return new RetryPolicy(
                maxRetries,
                DEFAULT_BASE_DELAY,
                DEFAULT_MAX_DELAY,
                d -> Thread.sleep(d),
                () -> ThreadLocalRandom.current().nextDouble(),
                Clock.systemUTC());
    }

    public int maxRetries() {
        return maxRetries;
    }

    /**
     * Runs {@code call} until it returns a non-retryable response or retries are exhausted.
     *
     * @throws YFRateLimitException when the last attempt answered 429
     * @throws YFTransportException when the last attempt answered 5xx or failed, or the thread was
     *     interrupted while waiting (interrupt flag restored)
     */
    public HttpResponse execute(Supplier<HttpResponse> call) {
        for (int attempt = 0; ; attempt++) {
            HttpResponse response;
            try {
                response = call.get();
            } catch (YFTransportException e) {
                if (attempt >= maxRetries) {
                    throw e;
                }
                LOG.debug("transport failure, retry {}/{}: {}", attempt + 1, maxRetries, e.getMessage());
                sleep(backoff(attempt), e);
                continue;
            }
            if (!isRetryable(response.status())) {
                return response;
            }
            if (attempt >= maxRetries) {
                throw exhausted(response, attempt + 1);
            }
            Optional<Duration> requested = retryAfter(response);
            Duration delay = requested.isPresent() ? requested.get() : backoff(attempt);
            LOG.debug("status {}, retry {}/{} in {}", response.status(), attempt + 1, maxRetries, delay);
            sleep(delay, null);
        }
    }

    static boolean isRetryable(int status) {
        return status == 429 || (status >= 500 && status < 600);
    }

    /** Full-jitter exponential backoff delay for 0-based {@code attempt}. */
    Duration backoff(int attempt) {
        double ceiling = Math.min(maxDelay.toMillis(), baseDelay.toMillis() * Math.pow(2, attempt));
        return Duration.ofMillis((long) (random.getAsDouble() * ceiling));
    }

    /** Delay requested by a {@code Retry-After} header, capped at {@code maxDelay}. */
    Optional<Duration> retryAfter(HttpResponse response) {
        Optional<String> header = response.header("Retry-After").map(String::strip);
        if (header.isEmpty() || header.get().isEmpty()) {
            return Optional.empty();
        }
        String value = header.get();
        Duration delay;
        try {
            delay = Duration.ofSeconds(Long.parseLong(value));
        } catch (NumberFormatException notSeconds) {
            try {
                var at = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME);
                delay = Duration.between(clock.instant(), at.toInstant());
            } catch (DateTimeParseException notDate) {
                LOG.debug("ignoring unparseable Retry-After: {}", value);
                return Optional.empty();
            }
        }
        if (delay.isNegative()) {
            delay = Duration.ZERO;
        }
        return Optional.of(delay.compareTo(maxDelay) > 0 ? maxDelay : delay);
    }

    private void sleep(Duration delay, @Nullable YFTransportException cause) {
        try {
            sleeper.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            var ex = new YFTransportException("interrupted while waiting to retry", e);
            if (cause != null) {
                ex.addSuppressed(cause);
            }
            throw ex;
        }
    }

    private static RuntimeException exhausted(HttpResponse response, int attempts) {
        String message = "HTTP " + response.status() + " after " + attempts + " attempt(s)";
        return response.status() == 429 ? new YFRateLimitException(message) : new YFTransportException(message);
    }
}
