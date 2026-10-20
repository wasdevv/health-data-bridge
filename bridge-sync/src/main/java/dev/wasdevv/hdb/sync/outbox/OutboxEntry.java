package dev.wasdevv.hdb.sync.outbox;

import java.time.Instant;
import java.util.UUID;

public record OutboxEntry(
        UUID id,
        String eventType,
        String aggregateType,
        String aggregateId,
        Status status,
        int attempts,
        Instant nextAttemptAt,
        Instant createdAt,
        Instant updatedAt,
        String lastError,
        String erpInvoiceId) {

    public static final String CREATE_INVOICE = "CREATE_INVOICE";

    public enum Status {
        PENDING, DONE, DEAD
    }
}
