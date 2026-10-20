package dev.wasdevv.hdb.app;

import dev.wasdevv.hdb.core.demo.DemoDataset;
import dev.wasdevv.hdb.fhir.store.ResourceStore;
import dev.wasdevv.hdb.fhir.store.TransactionProcessor;
import java.time.LocalDate;
import java.util.List;
import org.hl7.fhir.r4.model.Patient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The Compose demo data: 20 fictitious patients with coverages, then 50 finished encounters from
 * 2026-10-01 to 2026-10-05, each as its own transaction Bundle, so the outbox fills exactly as it
 * would with a real clinical system posting. Runs only on an empty database.
 */
@Component
@ConditionalOnProperty("bridge.seed.enabled")
public class DemoSeed {

    public static final long SEED = 2026;
    public static final LocalDate FIRST_DAY = LocalDate.of(2026, 10, 1);
    public static final int PATIENTS = 20;
    public static final int ENCOUNTERS = 50;
    public static final int DAYS = 5;

    private static final Logger log = LoggerFactory.getLogger(DemoSeed.class);

    private final TransactionProcessor transactions;
    private final ResourceStore store;

    public DemoSeed(TransactionProcessor transactions, ResourceStore store) {
        this.transactions = transactions;
        this.store = store;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void load() {
        List<DemoDataset.DemoPatient> patients = DemoDataset.patients(PATIENTS, SEED);
        if (store.find(Patient.class, patients.get(0).id()).isPresent()) {
            log.info("demo seed: data already present, skipped");
            return;
        }
        transactions.process(DemoDataset.registryBundle(patients));
        for (DemoDataset.DemoEncounter e : DemoDataset.encounters(patients, ENCOUNTERS, FIRST_DAY, DAYS, SEED)) {
            transactions.process(DemoDataset.encounterBundle(e));
        }
        log.info("demo seed: {} patients and {} finished encounters loaded", PATIENTS, ENCOUNTERS);
    }
}
