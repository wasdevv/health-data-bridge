package dev.wasdevv.hdb.fhir.store;

import org.hl7.fhir.r4.model.Resource;

/**
 * Called inside the transaction that writes the resource, after the row is in place. Whatever a
 * listener writes commits or rolls back together with the resource: this is how the outbox row and
 * the finished Encounter end up in the same transaction.
 */
public interface ResourceWriteListener {

    void afterWrite(Resource resource);
}
