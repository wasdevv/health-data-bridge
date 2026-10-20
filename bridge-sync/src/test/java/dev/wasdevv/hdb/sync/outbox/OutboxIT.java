package dev.wasdevv.hdb.sync.outbox;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.stubbing.Scenario;
import dev.wasdevv.hdb.core.demo.DemoDataset;
import dev.wasdevv.hdb.fhir.store.ResourceStore;
import dev.wasdevv.hdb.sync.SyncIntegrationTest;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.hl7.fhir.r4.model.Encounter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;

class OutboxIT extends SyncIntegrationTest {

    static final LocalDate DAY = LocalDate.of(2026, 10, 3);

    @Autowired
    OutboxWorker worker;
    @Autowired
    ResourceStore store;
    @Autowired
    TestRestTemplate http;
    @Autowired
    MeterRegistry meters;

    void erpAccepts() {
        erp.stubFor(post("/api/invoices").willReturn(aResponse().withStatus(201)
                .withHeader("Content-Type", "application/json")
                .withTransformers("response-template")
                .withBody("{\"invoiceId\":\"INV-{{request.headers.Idempotency-Key}}\",\"encounterId\":\"x\"}")));
    }

    @Test
    void finishedEncounterIsQueuedInItsOwnTransactionOnlyOnce() {
        saveEncounter(encounter("enc-1", 0, DAY, "I10"));
        saveEncounter(encounter("enc-1", 0, DAY, "I10"));
        Encounter planned = store.read(Encounter.class, "enc-1").copy();
        planned.setId("enc-2");
        planned.setStatus(Encounter.EncounterStatus.INPROGRESS);
        store.update(planned, "enc-2", null);

        assertThat(jdbc.queryForObject("select count(*) from outbox", Integer.class)).isEqualTo(1);
        OutboxEntry row = outboxFor("enc-1");
        assertThat(row.status()).isEqualTo(OutboxEntry.Status.PENDING);
        assertThat(row.attempts()).isZero();
    }

    @Test
    void deliversWithTheOutboxIdAsIdempotencyKey() {
        erpAccepts();
        saveEncounter(encounter("enc-1", 0, DAY, "I10"));
        OutboxEntry queued = outboxFor("enc-1");

        OutboxWorker.RunResult result = worker.runOnce();

        assertThat(result.delivered()).isEqualTo(1);
        OutboxEntry done = outboxFor("enc-1");
        assertThat(done.status()).isEqualTo(OutboxEntry.Status.DONE);
        assertThat(done.attempts()).isEqualTo(1);
        assertThat(done.erpInvoiceId()).isEqualTo("INV-" + queued.id());
        erp.verify(1, postRequestedFor(urlPathEqualTo("/api/invoices")).withHeader("Idempotency-Key",
                com.github.tomakehurst.wiremock.client.WireMock.equalTo(queued.id().toString())));
        assertThat(worker.runOnce().claimed()).as("DONE rows are not claimed again").isZero();
    }

    @Test
    void transientFailureIsRetriedWithBackoffThenDelivered() throws Exception {
        erp.stubFor(post("/api/invoices").inScenario("flaky").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503).withBody("{\"error\":\"unavailable\",\"message\":\"x\"}"))
                .willSetStateTo("up"));
        erp.stubFor(post("/api/invoices").inScenario("flaky").whenScenarioStateIs("up")
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody("{\"invoiceId\":\"INV-9\"}")));
        saveEncounter(encounter("enc-1", 0, DAY, "I10"));

        assertThat(worker.runOnce().retried()).isEqualTo(1);
        OutboxEntry failed = outboxFor("enc-1");
        assertThat(failed.status()).isEqualTo(OutboxEntry.Status.PENDING);
        assertThat(failed.attempts()).isEqualTo(1);
        assertThat(failed.lastError()).isEqualTo("ERP 503 unavailable");
        assertThat(failed.nextAttemptAt()).isAfter(failed.updatedAt());
        assertThat(worker.runOnce().claimed()).as("not due yet").isZero();

        Thread.sleep(60);
        assertThat(worker.runOnce().delivered()).isEqualTo(1);
        assertThat(outboxFor("enc-1").attempts()).isEqualTo(2);
        assertThat(outboxFor("enc-1").lastError()).isNull();
    }

    @Test
    void exhaustedRetriesGoToTheDeadLetterQueue() throws Exception {
        erp.stubFor(post("/api/invoices").willReturn(aResponse().withStatus(503)));
        saveEncounter(encounter("enc-1", 0, DAY, "I10"));

        for (int i = 0; i < 3; i++) {
            makeDue();
            worker.runOnce();
        }

        OutboxEntry dead = outboxFor("enc-1");
        assertThat(dead.status()).isEqualTo(OutboxEntry.Status.DEAD);
        assertThat(dead.attempts()).isEqualTo(3);
        assertThat(dead.lastError()).startsWith("gave up after 3 attempts: ERP 503");
        assertThat(meters.get("bridge.outbox.dead.total").counter().count()).isPositive();
    }

    @Test
    void permanentRejectionIsDeadAtOnceAndTheAdminEndpointRevivesIt() {
        erp.stubFor(post("/api/invoices").inScenario("fix").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(422).withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"inactive_customer\",\"message\":\"customer CUST-001 is inactive\"}"))
                .willSetStateTo("fixed"));
        erp.stubFor(post("/api/invoices").inScenario("fix").whenScenarioStateIs("fixed")
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody("{\"invoiceId\":\"INV-7\"}")));
        saveEncounter(encounter("enc-1", 0, DAY, "I10"));

        assertThat(worker.runOnce().dead()).isEqualTo(1);
        OutboxEntry dead = outboxFor("enc-1");
        assertThat(dead.lastError()).isEqualTo("permanent: ERP 422 inactive_customer");

        var listed = http.getForEntity("/admin/outbox?status=DEAD", OutboxEntry[].class);
        assertThat(listed.getBody()).extracting(OutboxEntry::id).containsExactly(dead.id());

        var revived = http.postForEntity("/admin/outbox/" + dead.id() + "/retry", null, OutboxEntry.class);
        assertThat(revived.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(revived.getBody().status()).isEqualTo(OutboxEntry.Status.PENDING);
        assertThat(revived.getBody().attempts()).isZero();

        assertThat(worker.runOnce().delivered()).isEqualTo(1);
        assertThat(outboxFor("enc-1").erpInvoiceId()).isEqualTo("INV-7");
        assertThat(http.postForEntity("/admin/outbox/" + dead.id() + "/retry", null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(http.postForEntity("/admin/outbox/" + java.util.UUID.randomUUID() + "/retry", null, String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void encounterOutsideTheBillingProfileIsDeadUntilFixed() {
        erpAccepts();
        saveEncounter(encounter("enc-1", 0, DAY, null));

        assertThat(worker.runOnce().dead()).isEqualTo(1);
        assertThat(outboxFor("enc-1").lastError()).startsWith("permanent: not a billable-encounter")
                .contains("Encounter.diagnosis");
        erp.verify(0, postRequestedFor(urlPathEqualTo("/api/invoices")));

        var fix = DemoDataset.encounterBundle(encounter("enc-1", 0, DAY, "K29.7"));
        fix.getEntry().remove(1); // the Claim is already there; a second one would make billing ambiguous
        transactions.process(fix);
        http.postForEntity("/admin/outbox/" + outboxFor("enc-1").id() + "/retry", null, OutboxEntry.class);
        assertThat(worker.runOnce().delivered()).isEqualTo(1);
    }

    @Test
    void missingClaimIsRetriedNotKilled() {
        erpAccepts();
        var noClaim = DemoDataset.encounterBundle(encounter("enc-1", 0, DAY, "I10"));
        noClaim.getEntry().remove(1);
        transactions.process(noClaim);

        assertThat(worker.runOnce().retried()).isEqualTo(1);
        assertThat(outboxFor("enc-1").status()).isEqualTo(OutboxEntry.Status.PENDING);
        assertThat(outboxFor("enc-1").lastError()).isEqualTo("no active Claim for Encounter/enc-1 yet");

        saveEncounter(encounter("enc-1", 0, DAY, "I10")); // the Claim arrives
        makeDue();
        assertThat(worker.runOnce().delivered()).isEqualTo(1);
    }

    @Test
    void twoWorkersNeverSendTheSameRow() throws Exception {
        erp.stubFor(post("/api/invoices").willReturn(aResponse().withStatus(201).withFixedDelay(30)
                .withHeader("Content-Type", "application/json")
                .withTransformers("response-template")
                .withBody("{\"invoiceId\":\"INV-{{request.headers.Idempotency-Key}}\"}")));
        for (int i = 1; i <= 40; i++) {
            saveEncounter(encounter("enc-%02d".formatted(i), i % PATIENTS.size(), DAY, "I10"));
        }

        CountDownLatch start = new CountDownLatch(1);
        Callable<Integer> drain = () -> {
            start.await();
            int n = 0;
            OutboxWorker.RunResult r;
            while ((r = worker.runOnce()).claimed() > 0) {
                n += r.delivered();
            }
            return n;
        };
        var pool = Executors.newFixedThreadPool(2);
        var a = pool.submit(drain);
        var b = pool.submit(drain);
        start.countDown();
        int deliveredA = a.get();
        int deliveredB = b.get();
        pool.shutdown();

        assertThat(deliveredA + deliveredB).isEqualTo(40);
        assertThat(deliveredA).as("both workers took rows").isPositive();
        assertThat(deliveredB).as("both workers took rows").isPositive();
        Map<String, Integer> perKey = new HashMap<>();
        erp.getAllServeEvents().forEach(ev -> perKey.merge(ev.getRequest().getHeader("Idempotency-Key"), 1, Integer::sum));
        assertThat(perKey).hasSize(40).allSatisfy((key, count) -> assertThat(count).as(key).isEqualTo(1));
        assertThat(outbox.count(OutboxEntry.Status.DONE)).isEqualTo(40);
    }

    @Test
    void openBreakerPausesTheWorkerWithoutSpendingAttempts() throws Exception {
        erp.stubFor(post("/api/invoices").willReturn(aResponse().withStatus(503)));
        for (int i = 1; i <= 12; i++) {
            saveEncounter(encounter("enc-%02d".formatted(i), i % PATIENTS.size(), DAY, "I10"));
        }

        worker.runOnce(); // 10 transient failures: a full window at 100%
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        makeDue();
        Integer attemptsBefore = jdbc.queryForObject("select sum(attempts) from outbox", Integer.class);

        assertThat(worker.runOnce()).isEqualTo(new OutboxWorker.RunResult(0, 0, 0, 0, 0));
        assertThat(jdbc.queryForObject("select sum(attempts) from outbox", Integer.class)).isEqualTo(attemptsBefore);
        assertThat(outbox.count(OutboxEntry.Status.PENDING)).isEqualTo(12);

        erpAccepts();
        Thread.sleep(1100); // breaker-open-wait in the test properties
        // half-open lets 2 calls through; the rest of that batch waits for the breaker to close
        for (int i = 0; i < 5 && outbox.count(OutboxEntry.Status.PENDING) > 0; i++) {
            makeDue();
            worker.drain();
        }
        assertThat(outbox.count(OutboxEntry.Status.DONE)).isEqualTo(12);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void metricsShowBacklogAndDeadLetters() {
        saveEncounter(encounter("enc-1", 0, DAY, "I10"));
        saveEncounter(encounter("enc-2", 1, DAY, null));
        erpAccepts();

        assertThat(meters.get("bridge.outbox.pending").gauge().value()).isEqualTo(2);
        assertThat(meters.get("bridge.outbox.oldest.pending.age").gauge().value()).isGreaterThanOrEqualTo(0);
        worker.runOnce();
        assertThat(meters.get("bridge.outbox.pending").gauge().value()).isZero();
        assertThat(meters.get("bridge.outbox.dead").gauge().value()).isEqualTo(1);
        assertThat(meters.get("resilience4j.circuitbreaker.state").tag("name", "erp").tag("state", "closed").gauge().value())
                .isEqualTo(1);
    }
}
