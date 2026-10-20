package dev.wasdevv.hdb.sync.outbox;

import java.time.Duration;
import java.util.random.RandomGenerator;

/**
 * Exponential backoff with "equal jitter": after attempt n the next one waits between half and all of
 * {@code base * 2^(n-1)}, capped at {@code max}. Half is fixed so retries never bunch up at zero, half
 * is random so a crowd of rows that failed together does not come back together.
 */
public final class RetryPolicy {

    private final Duration base;
    private final Duration max;
    private final int maxAttempts;
    private final RandomGenerator random;

    public RetryPolicy(Duration base, Duration max, int maxAttempts, RandomGenerator random) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        this.base = base;
        this.max = max;
        this.maxAttempts = maxAttempts;
        this.random = random;
    }

    /** True when {@code attemptsMade} used up the budget: the row goes to the dead letter queue. */
    public boolean exhausted(int attemptsMade) {
        return attemptsMade >= maxAttempts;
    }

    public Duration delayAfter(int attemptsMade) {
        long ceiling = base.toMillis();
        for (int i = 1; i < attemptsMade && ceiling < max.toMillis(); i++) {
            ceiling *= 2;
        }
        ceiling = Math.min(ceiling, max.toMillis());
        long half = ceiling / 2;
        return Duration.ofMillis(half + random.nextLong(ceiling - half + 1));
    }

    public int maxAttempts() {
        return maxAttempts;
    }
}
