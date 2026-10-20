package dev.wasdevv.hdb.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.wasdevv.hdb.core.demo.DemoDataset;
import dev.wasdevv.hdb.core.fhir.Systems;
import dev.wasdevv.hdb.erpmock.Seed;
import dev.wasdevv.hdb.sync.outbox.OutboxEntry;
import dev.wasdevv.hdb.sync.reconciliation.Reconciliation;
import dev.wasdevv.hdb.testsupport.ErpMockServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import org.awaitility.Awaitility;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CapabilityStatement;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;

class EndToEndIT extends BridgeIntegrationTest {

    static final LocalDate DAY = LocalDate.parse(Seed.PLANTED_DATE);
    static final List<DemoDataset.DemoPatient> PATIENTS = DemoDataset.patients(4, 31);

    @Autowired
    TestRestTemplate http;

    void registry(List<DemoDataset.DemoPatient> patients) {
        fhir.transaction().withBundle(DemoDataset.registryBundle(patients)).execute();
    }

    @Test
    void metadataIsACapabilityStatement() {
        CapabilityStatement cs = fhir.capabilities().ofType(CapabilityStatement.class).execute();
        assertThat(cs.getRestFirstRep().getResource()).extracting(r -> r.getType()).contains("Patient", "Encounter",
                "Coverage", "Claim", "Condition", "Organization", "Location", "Practitioner");
    }

    @Test
    void finishedEncounterBecomesExactlyOneInvoice() throws InterruptedException {
        registry(PATIENTS);
        var e = encounter("enc-e2e-1", PATIENTS.get(0), DAY, "I10");
        post(e);

        awaitDone(1, Duration.ofSeconds(20));
        post(e); // saved again as finished: same event, no second invoice
        Thread.sleep(1000); // several worker polls

        JsonNode invoices = ErpMockServer.invoices("encounterId=enc-e2e-1");
        assertThat(invoices).hasSize(1);
        assertThat(invoices.get(0).get("total").decimalValue()).isEqualByComparingTo(e.total());
        assertThat(invoices.get(0).get("issueDate").asText()).isEqualTo(DAY.toString());
        assertThat(invoices.get(0).get("customerCode").asText()).isEqualTo(PATIENTS.get(0).customerCode());
        assertThat(outbox.findByAggregate(OutboxEntry.CREATE_INVOICE, "Encounter", "enc-e2e-1").orElseThrow().erpInvoiceId())
                .isEqualTo(invoices.get(0).get("invoiceId").asText());
    }

    @Test
    void erpOutageDelaysDeliveryButLosesNothing() {
        registry(PATIENTS);
        ErpMockServer.faults("invoices", "{\"down\":true}");
        for (int i = 1; i <= 30; i++) {
            post(encounter("enc-out-%02d".formatted(i), PATIENTS.get(i % PATIENTS.size()), DAY, "J45.9"));
        }

        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> breaker.getState() == CircuitBreaker.State.OPEN);
        assertThat(count(OutboxEntry.Status.PENDING)).isEqualTo(30);
        assertThat(count(OutboxEntry.Status.DEAD)).isZero();
        assertThat(ErpMockServer.stats().get("invoices").asInt()).isZero();

        ErpMockServer.clearFaults();
        awaitDone(30, Duration.ofSeconds(60));

        JsonNode stats = ErpMockServer.stats();
        assertThat(stats.get("invoices").asInt()).isEqualTo(30);
        assertThat(stats.get("duplicatedEncounters")).isEmpty();
    }

    @Test
    void permanentFailureLandsInTheDlqAndIsReprocessedAfterTheFix() {
        var closed = new DemoDataset.DemoPatient("pat-99", PATIENTS.get(0).cpf(), "Teste", "Fictício",
                LocalDate.of(1980, 1, 1), "CUST-099");
        registry(List.of(closed));
        post(encounter("enc-dlq", closed, DAY, "M54.5"));

        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> count(OutboxEntry.Status.DEAD) == 1);
        OutboxEntry[] dead = http.getForObject("/admin/outbox?status=DEAD", OutboxEntry[].class);
        assertThat(dead).singleElement().satisfies(o -> {
            assertThat(o.aggregateId()).isEqualTo("enc-dlq");
            assertThat(o.lastError()).isEqualTo("permanent: ERP 422 inactive_customer");
        });

        // the fix happens in the ERP: the insurer's contract is reactivated
        ErpMockServer.customer("CUST-099", "{\"name\":\"Operadora Reativada\",\"active\":true}");
        http.postForObject("/admin/outbox/" + dead[0].id() + "/retry", null, OutboxEntry.class);

        awaitDone(1, Duration.ofSeconds(20));
        assertThat(ErpMockServer.invoices("encounterId=enc-dlq")).hasSize(1);
    }

    @Test
    void reconciliationFindsThePlantedDivergenceAndNothingElse() {
        ErpMockServer.reset(true);
        registry(PATIENTS);
        for (int i = 1; i <= 5; i++) {
            post(encounter("enc-rec-" + i, PATIENTS.get(i % PATIENTS.size()), DAY, "A09"));
        }
        post(encounter("enc-rec-other-day", PATIENTS.get(0), DAY.plusDays(1), "A09"));
        awaitDone(6, Duration.ofSeconds(20));

        Reconciliation.Report report = http.postForObject("/admin/reconciliation/run?date=" + DAY, null,
                Reconciliation.Report.class);

        assertThat(report.finishedEncounters()).isEqualTo(5);
        assertThat(report.invoices()).isEqualTo(6);
        assertThat(report.issues()).singleElement().satisfies(i -> {
            assertThat(i.kind()).isEqualTo(Reconciliation.Kind.ORPHAN_INVOICE);
            assertThat(i.resourceId()).isEqualTo("Encounter/" + Seed.PLANTED_ENCOUNTER);
            assertThat(i.invoiceIds()).containsExactly("INV-PLANTED");
        });
    }

    @Test
    void registrySyncBringsErpCustomersAndLocationsIntoFhir() {
        http.postForObject("/admin/registry/sync", null, String.class);

        Bundle orgs = fhir.search().byUrl("Organization?identifier=" + Systems.ERP_CUSTOMER + "|CUST-001")
                .returnBundle(Bundle.class).execute();
        assertThat(orgs.getEntry()).hasSize(1);
        Bundle all = fhir.search().byUrl("Organization").returnBundle(Bundle.class).execute();
        assertThat(all.getTotal()).isEqualTo(5);
        assertThat(fhir.search().byUrl("Location").returnBundle(Bundle.class).execute().getTotal()).isEqualTo(3);

        http.postForObject("/admin/registry/sync", null, String.class);
        assertThat(fhir.search().byUrl("Organization").returnBundle(Bundle.class).execute().getTotal()).isEqualTo(5);
    }

    /** Spring Boot tests run without the Prometheus registry; the compose smoke test scrapes /actuator/prometheus. */
    @Test
    void actuatorExposesOutboxAndBreakerMetrics() {
        for (String name : List.of("bridge.outbox.pending", "bridge.outbox.dead", "bridge.outbox.oldest.pending.age",
                "resilience4j.circuitbreaker.state")) {
            assertThat(http.getForEntity("/actuator/metrics/" + name, String.class).getStatusCode().is2xxSuccessful())
                    .as(name).isTrue();
        }
    }
}
