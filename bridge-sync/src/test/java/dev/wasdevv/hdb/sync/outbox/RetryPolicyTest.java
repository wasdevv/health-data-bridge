package dev.wasdevv.hdb.sync.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {

    final RetryPolicy policy = new RetryPolicy(Duration.ofSeconds(1), Duration.ofSeconds(60), 5, new Random(3));

    @Test
    void delayDoublesPerAttemptWithinEqualJitterBounds() {
        for (int attempt = 1; attempt <= 6; attempt++) {
            long ceiling = Math.min(1000L << (attempt - 1), 60_000);
            for (int i = 0; i < 200; i++) {
                assertThat(policy.delayAfter(attempt).toMillis()).as("attempt %d", attempt)
                        .isBetween(ceiling / 2, ceiling);
            }
        }
    }

    @Test
    void delayIsCappedAtMax() {
        for (int i = 0; i < 100; i++) {
            assertThat(policy.delayAfter(40).toMillis()).isBetween(30_000L, 60_000L);
        }
    }

    @Test
    void jitterSpreadsRowsThatFailedTogether() {
        Set<Long> delays = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            delays.add(policy.delayAfter(4).toMillis());
        }
        assertThat(delays).hasSizeGreaterThan(40);
    }

    @Test
    void exhaustedAtMaxAttempts() {
        assertThat(policy.exhausted(4)).isFalse();
        assertThat(policy.exhausted(5)).isTrue();
        assertThat(policy.maxAttempts()).isEqualTo(5);
        assertThatThrownBy(() -> new RetryPolicy(Duration.ofSeconds(1), Duration.ofSeconds(2), 0, new Random()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
