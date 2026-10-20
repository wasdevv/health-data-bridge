package dev.wasdevv.hdb.sync.reconciliation;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import dev.wasdevv.hdb.fhir.store.ResourceStore;
import dev.wasdevv.hdb.sync.SyncIntegrationTest;
import dev.wasdevv.hdb.sync.outbox.OutboxWorker;
import dev.wasdevv.hdb.sync.reconciliation.Reconciliation.Kind;
import java.time.LocalDate;
import org.hl7.fhir.r4.model.Encounter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;

class ReconciliationIT extends SyncIntegrationTest {

    static final LocalDate DAY = LocalDate.of(2026, 10, 3);

    @Autowired
    Reconciliation reconciliation;
    @Autowired
    OutboxWorker worker;
    @Autowired
    ResourceStore store;
    @Autowired
    TestRestTemplate http;

    void erpInvoicesOn(LocalDate day, String... invoices) {
        erp.stubFor(get(urlPathEqualTo("/api/invoices")).withQueryParam("issueDate", equalTo(day.toString()))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("[" + String.join(",", invoices) + "]")));
    }

    @Test
    void findsMissingDuplicateAndOrphanInvoicesWithTheirReasons() {
        erp.stubFor(post("/api/invoices").willReturn(aResponse().withStatus(422)
                .withHeader("Content-Type", "application/json").withBody("{\"error\":\"unknown_customer\",\"message\":\"x\"}")));
        saveEncounter(encounter("enc-ok", 0, DAY, "I10"));
        saveEncounter(encounter("enc-dead", 1, DAY, "I10"));
        saveEncounter(encounter("enc-dup", 2, DAY, "I10"));
        saveEncounter(encounter("enc-pending", 3, DAY, "I10"));
        saveEncounter(encounter("enc-other-day", 0, DAY.plusDays(1), "I10"));
        jdbc.update("update outbox set next_attempt_at = now() + interval '1 hour' where aggregate_id <> 'enc-dead'");
        worker.runOnce(); // enc-dead dies with a 422
        Encounter inProgress = store.read(Encounter.class, "enc-ok").copy();
        inProgress.setStatus(Encounter.EncounterStatus.INPROGRESS);
        store.update(inProgress, "enc-wip", null);

        erpInvoicesOn(DAY,
                invoiceJson("INV-1", "enc-ok", "2026-10-03"),
                invoiceJson("INV-2", "enc-dup", "2026-10-03"),
                invoiceJson("INV-3", "enc-dup", "2026-10-03"),
                invoiceJson("INV-PLANTED", "enc-planted", "2026-10-03"),
                invoiceJson("INV-4", "enc-wip", "2026-10-03"));

        Reconciliation.Report report = reconciliation.run(DAY);

        assertThat(report.finishedEncounters()).isEqualTo(4);
        assertThat(report.invoices()).isEqualTo(5);
        assertThat(report.issues()).extracting(Reconciliation.Issue::kind, Reconciliation.Issue::resourceId)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(Kind.MISSING_INVOICE, "Encounter/enc-dead"),
                        org.assertj.core.groups.Tuple.tuple(Kind.MISSING_INVOICE, "Encounter/enc-pending"),
                        org.assertj.core.groups.Tuple.tuple(Kind.DUPLICATE_INVOICE, "Encounter/enc-dup"),
                        org.assertj.core.groups.Tuple.tuple(Kind.ORPHAN_INVOICE, "Encounter/enc-planted"),
                        org.assertj.core.groups.Tuple.tuple(Kind.ORPHAN_INVOICE, "Encounter/enc-wip"));
        assertThat(reason(report, "Encounter/enc-dead")).contains("dead letter queue").contains("ERP 422 unknown_customer");
        assertThat(reason(report, "Encounter/enc-pending")).contains("not delivered yet (0 attempts)");
        assertThat(reason(report, "Encounter/enc-planted")).isEqualTo("Encounter/enc-planted does not exist on the FHIR side");
        assertThat(reason(report, "Encounter/enc-wip")).contains("exists but is in-progress");
        assertThat(report.issues()).filteredOn(i -> i.kind() == Kind.DUPLICATE_INVOICE).singleElement()
                .satisfies(i -> assertThat(i.invoiceIds()).containsExactly("INV-2", "INV-3"));
    }

    @Test
    void cleanDayHasNoIssuesAndRunsArePersisted() {
        saveEncounter(encounter("enc-1", 0, DAY, "I10"));
        jdbc.update("update outbox set status = 'DONE', erp_invoice_id = 'INV-1'");
        erpInvoicesOn(DAY, invoiceJson("INV-1", "enc-1", "2026-10-03"));

        var run = http.postForEntity("/admin/reconciliation/run?date=2026-10-03", null, Reconciliation.Report.class);
        assertThat(run.getBody().issues()).isEmpty();

        var latest = http.getForEntity("/admin/reconciliation?date=2026-10-03", Reconciliation.Report.class);
        assertThat(latest.getBody().runId()).isEqualTo(run.getBody().runId());
        assertThat(latest.getBody().finishedEncounters()).isEqualTo(1);
        assertThat(http.getForEntity("/admin/reconciliation?date=2026-10-04", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void latestRunKeepsItsIssues() {
        saveEncounter(encounter("enc-1", 0, DAY, "I10"));
        erpInvoicesOn(DAY, invoiceJson("INV-9", "enc-ghost", "2026-10-03"));
        reconciliation.run(DAY);

        Reconciliation.Report latest = reconciliation.latest(DAY).orElseThrow();

        assertThat(latest.issues()).extracting(Reconciliation.Issue::kind)
                .containsExactlyInAnyOrder(Kind.MISSING_INVOICE, Kind.ORPHAN_INVOICE);
        assertThat(latest.issues()).filteredOn(i -> i.kind() == Kind.ORPHAN_INVOICE).singleElement()
                .satisfies(i -> assertThat(i.invoiceIds()).containsExactly("INV-9"));
    }

    @Test
    void erpDownIsAServiceUnavailable() {
        erp.stubFor(get(urlPathEqualTo("/api/invoices")).willReturn(aResponse().withStatus(503)));
        assertThat(http.postForEntity("/admin/reconciliation/run?date=2026-10-03", null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    static String reason(Reconciliation.Report report, String resourceId) {
        return report.issues().stream().filter(i -> i.resourceId().equals(resourceId)).findFirst().orElseThrow().reason();
    }
}
