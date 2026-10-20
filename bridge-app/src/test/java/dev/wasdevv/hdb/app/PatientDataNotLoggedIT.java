package dev.wasdevv.hdb.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import dev.wasdevv.hdb.core.demo.DemoDataset;
import dev.wasdevv.hdb.core.fhir.Systems;
import dev.wasdevv.hdb.core.patient.Cpf;
import dev.wasdevv.hdb.sync.outbox.OutboxEntry;
import dev.wasdevv.hdb.testsupport.ErpMockServer;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.awaitility.Awaitility;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * Drives every path that logs (success, transient retries, the dead letter queue, a rejected resource,
 * a search by CPF, reconciliation, registry sync) with INFO logging everywhere, then looks for any
 * patient's CPF (plain and formatted) and name in what was written to the console.
 */
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = {"logging.level.root=INFO", "logging.level.ca.uhn.fhir=INFO",
        "logging.level.dev.wasdevv=DEBUG"})
class PatientDataNotLoggedIT extends BridgeIntegrationTest {

    static final LocalDate DAY = LocalDate.of(2026, 10, 2);

    @Autowired
    TestRestTemplate http;

    @Test
    void noCpfOrNameInTheLogs(CapturedOutput output) {
        List<DemoDataset.DemoPatient> patients = new ArrayList<>(DemoDataset.patients(6, 4242));
        var inactive = new DemoDataset.DemoPatient("pat-77", Cpf.generate(new java.util.Random(77)), "Rosana",
                "Quintela Brandão", LocalDate.of(1971, 5, 9), "CUST-099");
        patients.add(inactive);
        fhir.transaction().withBundle(DemoDataset.registryBundle(patients)).execute();

        ErpMockServer.faults("invoices", "{\"errorRate\":0.5,\"failAfterCommitRate\":0.2}");
        for (int i = 0; i < 6; i++) {
            post(encounter("enc-log-" + i, patients.get(i), DAY, "N39.0"));
        }
        post(encounter("enc-log-dead", inactive, DAY, "N39.0"));
        post(encounter("enc-log-nodx", patients.get(0), DAY, null));

        Patient invalid = DemoDataset.patient(patients.get(1));
        invalid.addCommunication().setPreferred(true);
        assertThatThrownBy(() -> fhir.create().resource(invalid).execute()).isInstanceOf(UnprocessableEntityException.class);
        fhir.search().byUrl("Patient?identifier=" + Systems.CPF + "|" + patients.get(2).cpf()).returnBundle(Bundle.class).execute();

        Awaitility.await().atMost(Duration.ofSeconds(60))
                .until(() -> outbox.count(OutboxEntry.Status.DONE) == 6 && outbox.count(OutboxEntry.Status.DEAD) == 2);
        http.postForObject("/admin/reconciliation/run?date=" + DAY, null, String.class);
        http.postForObject("/admin/registry/sync", null, String.class);

        String logs = output.getAll();
        assertThat(logs).as("the flows did log").contains("delivered as").contains("dead letter queue");
        for (DemoDataset.DemoPatient p : patients) {
            assertThat(logs).doesNotContain(p.cpf()).doesNotContain(Cpf.format(p.cpf()))
                    .doesNotContain(p.family()).doesNotContain(p.given() + " " + p.family());
        }
    }
}
