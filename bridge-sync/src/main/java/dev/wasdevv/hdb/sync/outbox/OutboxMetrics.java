package dev.wasdevv.hdb.sync.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;

/**
 * Outbox gauges, read from the table on each scrape. With {@code bridge.outbox.deadlettered} (a counter
 * in {@link OutboxWorker}) they answer: how far behind is delivery, and how fast are rows dying.
 */
public final class OutboxMetrics {

    private OutboxMetrics() {
    }

    public static void bind(MeterRegistry registry, OutboxRepository outbox, Clock clock) {
        Gauge.builder("bridge.outbox.pending", outbox, o -> o.count(OutboxEntry.Status.PENDING))
                .description("Rows waiting to be delivered to the ERP")
                .register(registry);
        Gauge.builder("bridge.outbox.dead", outbox, o -> o.count(OutboxEntry.Status.DEAD))
                .description("Rows in the dead letter queue")
                .register(registry);
        Gauge.builder("bridge.outbox.oldest.pending.age", outbox, o -> o.oldestPendingCreatedAt()
                        .map(t -> Duration.between(t, clock.instant()).toMillis() / 1000.0)
                        .orElse(0.0))
                .baseUnit("seconds")
                .description("Age of the oldest row not yet delivered")
                .register(registry);
    }
}
