package dev.wasdevv.hdb.sync;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import dev.wasdevv.hdb.core.demo.DemoDataset;
import dev.wasdevv.hdb.fhir.store.TransactionProcessor;
import dev.wasdevv.hdb.sync.outbox.OutboxEntry;
import dev.wasdevv.hdb.sync.outbox.OutboxRepository;
import dev.wasdevv.hdb.testsupport.RequiresTestDatabase;
import dev.wasdevv.hdb.testsupport.TestDatabase;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Real PostgreSQL, real FHIR store and worker; WireMock plays the ERP so each test scripts its failures. */
@ExtendWith(RequiresTestDatabase.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class SyncIntegrationTest {

    /** One port for the whole JVM: the Spring context is cached across test classes and keeps the ERP URL. */
    private static final int ERP_PORT = freePort();

    @RegisterExtension
    protected static WireMockExtension erp = WireMockExtension.newInstance().options(wireMockConfig().port(ERP_PORT)).build();

    private static int freePort() {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry::add, "sync_it");
        registry.add("bridge.sync.erp-base-url", () -> "http://localhost:" + ERP_PORT);
    }

    protected static final List<DemoDataset.DemoPatient> PATIENTS = DemoDataset.patients(4, 77);

    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected TransactionProcessor transactions;
    @Autowired
    protected OutboxRepository outbox;
    @Autowired
    protected CircuitBreaker breaker;

    @BeforeEach
    void cleanState() {
        jdbc.execute("truncate fhir_resource, outbox, sync_cursor, reconciliation_run cascade");
        breaker.reset();
        transactions.process(DemoDataset.registryBundle(PATIENTS));
    }

    protected static DemoDataset.DemoEncounter encounter(String id, int patient, LocalDate day, String icd10) {
        return new DemoDataset.DemoEncounter(id, PATIENTS.get(patient), day.atTime(10, 0).toInstant(ZoneOffset.UTC),
                icd10, List.of(DemoDataset.PROCEDURES.get(0), DemoDataset.PROCEDURES.get(2)));
    }

    protected void saveEncounter(DemoDataset.DemoEncounter e) {
        transactions.process(DemoDataset.encounterBundle(e));
    }

    protected OutboxEntry outboxFor(String encounterId) {
        return outbox.findByAggregate(OutboxEntry.CREATE_INVOICE, "Encounter", encounterId).orElseThrow();
    }

    protected void makeDue() {
        jdbc.update("update outbox set next_attempt_at = ? where status = 'PENDING'",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)));
    }

    protected static String invoiceJson(String invoiceId, String encounterId, String issueDate) {
        return """
                {"invoiceId":"%s","encounterId":"%s","customerCode":"CUST-001","issueDate":"%s","currency":"BRL",
                 "total":159.90,"status":"ISSUED","createdAt":"2026-10-03T15:00:00Z"}""".formatted(invoiceId, encounterId, issueDate);
    }
}
