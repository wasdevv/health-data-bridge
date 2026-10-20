package dev.wasdevv.hdb.fhir.rest;

import ca.uhn.fhir.rest.annotation.Transaction;
import ca.uhn.fhir.rest.annotation.TransactionParam;
import dev.wasdevv.hdb.fhir.store.TransactionProcessor;
import org.hl7.fhir.r4.model.Bundle;

/** {@code POST /fhir} with a transaction Bundle. */
public class TransactionProvider {

    private final TransactionProcessor processor;

    public TransactionProvider(TransactionProcessor processor) {
        this.processor = processor;
    }

    @Transaction
    public Bundle transaction(@TransactionParam Bundle bundle) {
        return processor.process(bundle);
    }
}
