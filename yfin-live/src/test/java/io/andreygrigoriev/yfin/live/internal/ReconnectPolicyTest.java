package io.andreygrigoriev.yfin.live.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ReconnectPolicyTest {

    private final ReconnectPolicy policy = ReconnectPolicy.defaults();

    @Test
    void capDoublesUpToMax() {
        assertThat(policy.cap(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(policy.cap(2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(policy.cap(5)).isEqualTo(Duration.ofSeconds(16));
        assertThat(policy.cap(6)).isEqualTo(Duration.ofSeconds(30));
        assertThat(policy.cap(1000)).isEqualTo(Duration.ofSeconds(30));
        assertThat(policy.cap(Integer.MAX_VALUE)).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void delayIsBetweenHalfAndCap() {
        assertThat(policy.delay(3, () -> 0.0)).isEqualTo(Duration.ofSeconds(2));
        assertThat(policy.delay(3, () -> 0.5)).isEqualTo(Duration.ofSeconds(3));
        assertThat(policy.delay(3, () -> 0.999999)).isLessThan(Duration.ofSeconds(4));
        for (int attempt = 1; attempt < 20; attempt++) {
            Duration d = policy.delay(attempt);
            assertThat(d).isBetween(policy.cap(attempt).dividedBy(2), policy.cap(attempt));
        }
    }

    @Test
    void rejectsInvalidSettings() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ReconnectPolicy(Duration.ZERO, Duration.ofSeconds(1)));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ReconnectPolicy(Duration.ofSeconds(2), Duration.ofSeconds(1)));
        assertThatIllegalArgumentException().isThrownBy(() -> policy.delay(0));
    }
}
