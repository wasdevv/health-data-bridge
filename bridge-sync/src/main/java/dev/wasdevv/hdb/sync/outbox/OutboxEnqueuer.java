package dev.wasdevv.hdb.sync.outbox;

import dev.wasdevv.hdb.fhir.store.ResourceWriteListener;
import java.time.Clock;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Resource;

/**
 * Turns "Encounter saved as finished" into a CREATE_INVOICE row, inside the transaction that saved it.
 * Saving the same encounter again (finished or not) never adds a second row.
 */
public class OutboxEnqueuer implements ResourceWriteListener {

    private final OutboxRepository outbox;
    private final Clock clock;

    public OutboxEnqueuer(OutboxRepository outbox, Clock clock) {
        this.outbox = outbox;
        this.clock = clock;
    }

    @Override
    public void afterWrite(Resource resource) {
        if (resource instanceof Encounter e && e.getStatus() == Encounter.EncounterStatus.FINISHED) {
            outbox.enqueue(OutboxEntry.CREATE_INVOICE, "Encounter", e.getIdElement().getIdPart(), clock.instant());
        }
    }
}
