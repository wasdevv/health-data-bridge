package dev.wasdevv.hdb.core.erp;

import java.time.Instant;

/** A customer (health insurer or paying organization) as {@code GET /api/customers} returns it. */
public record ErpCustomer(String code, String name, String taxId, boolean active, Instant updatedAt) {
}
