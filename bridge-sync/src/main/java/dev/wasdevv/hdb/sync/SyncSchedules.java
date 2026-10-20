package dev.wasdevv.hdb.sync;

import dev.wasdevv.hdb.sync.erp.ErpException;
import dev.wasdevv.hdb.sync.outbox.OutboxWorker;
import dev.wasdevv.hdb.sync.reconciliation.Reconciliation;
import dev.wasdevv.hdb.sync.registry.RegistrySync;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** The three jobs, each switched on by its own property so tests drive them by hand. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SyncSchedules {

    private static final Logger log = LoggerFactory.getLogger(SyncSchedules.class);

    @Bean
    @ConditionalOnProperty("bridge.sync.worker-enabled")
    OutboxJob outboxJob(OutboxWorker worker) {
        return new OutboxJob(worker);
    }

    @Bean
    @ConditionalOnProperty("bridge.registry.enabled")
    RegistryJob registryJob(RegistrySync sync) {
        return new RegistryJob(sync);
    }

    @Bean
    @ConditionalOnProperty("bridge.reconciliation.enabled")
    ReconciliationJob reconciliationJob(Reconciliation reconciliation, Clock clock) {
        return new ReconciliationJob(reconciliation, clock);
    }

    public record OutboxJob(OutboxWorker worker) {
        @Scheduled(fixedDelayString = "${bridge.sync.poll-interval:1s}")
        void tick() {
            worker.drain();
        }
    }

    public record RegistryJob(RegistrySync sync) {
        @Scheduled(fixedDelayString = "${bridge.registry.interval:15m}", initialDelayString = "${bridge.registry.initial-delay:5s}")
        void tick() {
            try {
                sync.run();
            } catch (ErpException e) {
                log.warn("registry sync skipped, ERP unavailable: {}", e.getMessage());
            }
        }
    }

    /** Reconciles yesterday (UTC) once a day; the admin endpoint reconciles any day on demand. */
    public record ReconciliationJob(Reconciliation reconciliation, Clock clock) {
        @Scheduled(cron = "${bridge.reconciliation.cron:0 0 2 * * *}", zone = "UTC")
        void tick() {
            LocalDate yesterday = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).minusDays(1);
            try {
                reconciliation.run(yesterday);
            } catch (ErpException e) {
                log.warn("reconciliation of {} failed, ERP unavailable: {}", yesterday, e.getMessage());
            }
        }
    }
}
