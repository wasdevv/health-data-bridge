package dev.wasdevv.hdb.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.wasdevv.hdb.core.demo.DemoDataset;
import dev.wasdevv.hdb.sync.outbox.OutboxEntry;
import dev.wasdevv.hdb.sync.outbox.OutboxWorker;
import dev.wasdevv.hdb.testsupport.ErpMockServer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The PRD's reliability proof: 1000 finished encounters posted over HTTP while the ERP misbehaves in
 * every way erp-mock can: 20% of calls answer 503, 10% store the invoice and then answer 503 anyway
 * (the lost-response case), every call is slow, and twice the ERP goes fully down for 4 seconds.
 * Two workers drain the outbox at once (the scheduled one, plus a second loop standing in for another
 * instance). The test passes only with zero losses, zero duplicates and an empty dead letter queue.
 */
class ChaosIT extends BridgeIntegrationTest {

    static final int EVENTS = 1000;
    static final int PRODUCERS = 4;

    @Autowired
    OutboxWorker worker;

    @Test
    void thousandEventsWithAnUnstableErpEndWithNoLossAndNoDuplicate() throws Exception {
        List<DemoDataset.DemoPatient> patients = DemoDataset.patients(20, 99);
        fhir.transaction().withBundle(DemoDataset.registryBundle(patients)).execute();
        List<DemoDataset.DemoEncounter> encounters = DemoDataset.encounters(patients, EVENTS, LocalDate.of(2026, 9, 1), 30, 99);
        ErpMockServer.faults("invoices", "{\"latencyMs\":5,\"errorRate\":0.2,\"failAfterCommitRate\":0.1}");

        long t0 = System.nanoTime();
        AtomicBoolean producing = new AtomicBoolean(true);
        AtomicInteger posted = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(PRODUCERS + 2);

        // a second worker, as if a second instance were running: SKIP LOCKED keeps them apart
        Future<?> secondInstance = pool.submit(() -> {
            while (producing.get() || outbox.count(OutboxEntry.Status.PENDING) > 0) {
                worker.drain();
                sleep(50);
            }
        });
        Future<?> outages = pool.submit(() -> {
            for (int round = 0; round < 2; round++) {
                while (posted.get() < (round + 1) * EVENTS / 3) {
                    sleep(20);
                }
                ErpMockServer.faults("invoices", "{\"down\":true}");
                sleep(4000);
                ErpMockServer.faults("invoices", "{\"latencyMs\":5,\"errorRate\":0.2,\"failAfterCommitRate\":0.1}");
            }
        });
        List<Future<?>> producers = new ArrayList<>();
        for (int p = 0; p < PRODUCERS; p++) {
            int offset = p;
            producers.add(pool.submit(() -> {
                var client = ctx.newRestfulGenericClient(baseUrl() + "/fhir");
                for (int i = offset; i < EVENTS; i += PRODUCERS) {
                    client.transaction().withBundle(DemoDataset.encounterBundle(encounters.get(i))).execute();
                    posted.incrementAndGet();
                }
            }));
        }
        for (Future<?> f : producers) {
            f.get(5, TimeUnit.MINUTES);
        }
        producing.set(false);
        outages.get(1, TimeUnit.MINUTES);

        // the ERP stays unstable until the last event is in: the backlog drains under the same faults
        awaitDone(EVENTS, Duration.ofMinutes(5));
        secondInstance.get(1, TimeUnit.MINUTES);
        pool.shutdown();
        ErpMockServer.clearFaults();
        long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - t0);

        JsonNode stats = ErpMockServer.stats();
        Integer attempts = jdbc.queryForObject("select sum(attempts) from outbox", Integer.class);
        Integer maxAttempts = jdbc.queryForObject("select max(attempts) from outbox", Integer.class);
        report(seconds, stats, attempts, maxAttempts);

        assertThat(count(OutboxEntry.Status.DONE)).isEqualTo(EVENTS);
        assertThat(count(OutboxEntry.Status.DEAD)).isZero();
        assertThat(stats.get("invoices").asInt()).as("no losses").isEqualTo(EVENTS);
        assertThat(stats.get("distinctEncounters").asInt()).isEqualTo(EVENTS);
        assertThat(stats.get("duplicatedEncounters")).as("no duplicates").isEmpty();
        assertThat(stats.get("replays").asLong()).as("lost responses were recovered by Idempotency-Key").isPositive();
        assertThat(stats.get("faultsInjected").asLong()).isPositive();
        assertThat(attempts).as("failures really happened").isGreaterThan(EVENTS);
    }

    /** Numbers for the README, from the run that produced them. */
    private static void report(long seconds, JsonNode stats, int attempts, int maxAttempts) throws IOException {
        String text = """
                chaos run: %d encounters in %d s
                ERP requests: %d, faults injected: %d, idempotent replays: %d
                invoices: %d, distinct encounters: %d, duplicated: %s
                outbox attempts: %d total, %d max for one event
                """.formatted(EVENTS, seconds, stats.get("requests").asLong(), stats.get("faultsInjected").asLong(),
                stats.get("replays").asLong(), stats.get("invoices").asInt(), stats.get("distinctEncounters").asInt(),
                stats.get("duplicatedEncounters"), attempts, maxAttempts);
        Files.writeString(Path.of("target", "chaos-report.txt"), text);
        System.out.print(text);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
