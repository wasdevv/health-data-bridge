package dev.wasdevv.hdb.app;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.api.ServerValidationModeEnum;
import dev.wasdevv.hdb.core.demo.DemoDataset;
import dev.wasdevv.hdb.sync.outbox.OutboxEntry;
import dev.wasdevv.hdb.sync.outbox.OutboxRepository;
import dev.wasdevv.hdb.testsupport.ErpMockServer;
import dev.wasdevv.hdb.testsupport.RequiresTestDatabase;
import dev.wasdevv.hdb.testsupport.TestDatabase;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The whole bridge as it runs in Compose: HTTP into {@code /fhir}, the scheduled worker, and the real
 * erp-mock in the same JVM. Retries and the breaker run with short timings so outages last seconds.
 */
@ExtendWith(RequiresTestDatabase.class)
@SpringBootTest(classes = BridgeApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "bridge.sync.worker-enabled=true",
                "bridge.sync.poll-interval=100ms",
                "bridge.sync.backoff-base=50ms",
                "bridge.sync.backoff-max=500ms",
                "bridge.sync.max-attempts=25",
                "bridge.sync.breaker-open-wait=2s",
                "bridge.sync.erp-read-timeout=2s",
                "bridge.registry.enabled=false",
                "bridge.reconciliation.enabled=false",
                "bridge.seed.enabled=false"})
public abstract class BridgeIntegrationTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry::add, "app_it");
        registry.add("bridge.sync.erp-base-url", ErpMockServer::baseUrl);
    }

    @LocalServerPort
    protected int port;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected OutboxRepository outbox;
    @Autowired
    protected CircuitBreaker breaker;
    @Autowired
    protected FhirContext ctx;

    protected IGenericClient fhir;

    @BeforeEach
    void cleanState() {
        jdbc.execute("truncate fhir_resource, outbox, sync_cursor, reconciliation_run cascade");
        ErpMockServer.reset(false);
        breaker.reset();
        ctx.getRestfulClientFactory().setServerValidationMode(ServerValidationModeEnum.NEVER);
        fhir = ctx.newRestfulGenericClient(baseUrl() + "/fhir");
    }

    protected String baseUrl() {
        return "http://localhost:" + port;
    }

    protected static DemoDataset.DemoEncounter encounter(String id, DemoDataset.DemoPatient patient, LocalDate day,
                                                         String icd10) {
        return new DemoDataset.DemoEncounter(id, patient, day.atTime(10, 0).toInstant(ZoneOffset.UTC), icd10,
                List.of(DemoDataset.PROCEDURES.get(0), DemoDataset.PROCEDURES.get(3)));
    }

    protected void post(DemoDataset.DemoEncounter e) {
        fhir.transaction().withBundle(DemoDataset.encounterBundle(e)).execute();
    }

    protected long count(OutboxEntry.Status status) {
        return outbox.count(status);
    }

    protected void awaitDone(long expected, Duration timeout) {
        Awaitility.await().atMost(timeout).pollInterval(Duration.ofMillis(200))
                .until(() -> count(OutboxEntry.Status.DONE) == expected);
    }
}
