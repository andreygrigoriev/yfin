package io.andreygrigoriev.yfin.internal.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.andreygrigoriev.yfin.exception.YFRateLimitException;
import io.andreygrigoriev.yfin.exception.YFTransportException;
import io.andreygrigoriev.yfin.spi.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {

    private static final Instant NOW = Instant.parse("2026-10-12T10:00:00Z");

    private final List<Duration> sleeps = new ArrayList<>();
    private double jitter = 1.0;

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    private RetryPolicy policy(int maxRetries) {
        return new RetryPolicy(
                maxRetries,
                Duration.ofSeconds(1),
                Duration.ofSeconds(30),
                sleeps::add,
                () -> jitter,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** Supplier answering the given outcomes in order; RuntimeExceptions are thrown. */
    private static final class Script implements Supplier<HttpResponse> {
        private final Deque<Object> outcomes;
        int calls;

        Script(Object... outcomes) {
            this.outcomes = new ArrayDeque<>(List.of(outcomes));
        }

        @Override
        public HttpResponse get() {
            calls++;
            Object next = outcomes.removeFirst();
            if (next instanceof RuntimeException e) {
                throw e;
            }
            return (HttpResponse) next;
        }
    }

    private static HttpResponse status(int status) {
        return HttpResponse.of(status, "s" + status);
    }

    private static HttpResponse withRetryAfter(int status, String value) {
        return new HttpResponse(status, Map.of("Retry-After", List.of(value)), "x".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void returnsFirstSuccessWithoutSleeping() {
        var script = new Script(status(200));

        assertThat(policy(2).execute(script).status()).isEqualTo(200);
        assertThat(script.calls).isEqualTo(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void succeedsAfterRetriesWithExponentialBackoff() {
        var script = new Script(status(503), status(429), new YFTransportException("reset"), status(200));

        HttpResponse response = policy(3).execute(script);

        assertThat(response.status()).isEqualTo(200);
        assertThat(script.calls).isEqualTo(4);
        assertThat(sleeps).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4));
    }

    @Test
    void fullJitterScalesDelay() {
        jitter = 0.5;
        var script = new Script(status(500), status(500), status(200));

        policy(2).execute(script);

        assertThat(sleeps).containsExactly(Duration.ofMillis(500), Duration.ofSeconds(1));
    }

    @Test
    void backoffIsCappedAtMaxDelay() {
        RetryPolicy policy = policy(100);

        assertThat(policy.backoff(10)).isEqualTo(Duration.ofSeconds(30));
        assertThat(policy.backoff(80)).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void exhausted429BecomesRateLimitException() {
        var script = new Script(status(429), status(429), status(429));

        assertThatThrownBy(() -> policy(2).execute(script))
                .isInstanceOf(YFRateLimitException.class)
                .hasMessageContaining("429")
                .hasMessageContaining("3 attempt");
        assertThat(script.calls).isEqualTo(3);
    }

    @Test
    void exhausted5xxBecomesTransportException() {
        var script = new Script(status(429), status(502));

        assertThatThrownBy(() -> policy(1).execute(script))
                .isExactlyInstanceOf(YFTransportException.class)
                .hasMessageContaining("502");
    }

    @Test
    void exhaustedTransportFailureIsRethrown() {
        var last = new YFTransportException("timeout");
        var script = new Script(new YFTransportException("reset"), last);

        assertThatThrownBy(() -> policy(1).execute(script)).isSameAs(last);
    }

    @Test
    void zeroRetriesMakesSingleAttempt() {
        var script = new Script(status(429));

        assertThatThrownBy(() -> policy(0).execute(script)).isInstanceOf(YFRateLimitException.class);
        assertThat(script.calls).isEqualTo(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void non429ClientErrorsAreNotRetried() {
        for (int status : new int[] {400, 401, 403, 404, 422}) {
            var script = new Script(status(status));

            assertThat(policy(2).execute(script).status()).isEqualTo(status);
            assertThat(script.calls).isEqualTo(1);
        }
        assertThat(sleeps).isEmpty();
    }

    @Test
    void nonTransportExceptionsAreNotRetried() {
        var script = new Script(new IllegalStateException("bug"));

        assertThatThrownBy(() -> policy(2).execute(script)).isInstanceOf(IllegalStateException.class);
        assertThat(script.calls).isEqualTo(1);
    }

    @Test
    void retryAfterSecondsIsHonored() {
        var script = new Script(withRetryAfter(429, "7"), status(200));

        policy(2).execute(script);

        assertThat(sleeps).containsExactly(Duration.ofSeconds(7));
    }

    @Test
    void retryAfterHttpDateIsHonored() {
        var script = new Script(withRetryAfter(503, "Mon, 12 Oct 2026 10:00:05 GMT"), status(200));

        policy(2).execute(script);

        assertThat(sleeps).containsExactly(Duration.ofSeconds(5));
    }

    @Test
    void retryAfterIsCappedAndClampedAtZero() {
        var script = new Script(
                withRetryAfter(429, "3600"), withRetryAfter(429, "Mon, 12 Oct 2026 09:00:00 GMT"), status(200));

        policy(2).execute(script);

        assertThat(sleeps).containsExactly(Duration.ofSeconds(30), Duration.ZERO);
    }

    @Test
    void unparseableRetryAfterFallsBackToBackoff() {
        var script = new Script(withRetryAfter(429, "soon"), status(200));

        policy(2).execute(script);

        assertThat(sleeps).containsExactly(Duration.ofSeconds(1));
    }

    @Test
    void interruptDuringBackoffBecomesTransportException() {
        var policy = new RetryPolicy(
                2,
                Duration.ofSeconds(1),
                Duration.ofSeconds(30),
                d -> {
                    throw new InterruptedException();
                },
                () -> 1.0,
                Clock.systemUTC());

        assertThatThrownBy(() -> policy.execute(new Script(status(500))))
                .isInstanceOf(YFTransportException.class)
                .hasCauseInstanceOf(InterruptedException.class);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    void rejectsNegativeRetries() {
        assertThatThrownBy(() -> RetryPolicy.of(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(RetryPolicy.of(RetryPolicy.DEFAULT_MAX_RETRIES).maxRetries()).isEqualTo(2);
    }
}
