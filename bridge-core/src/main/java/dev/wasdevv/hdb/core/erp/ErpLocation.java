package dev.wasdevv.hdb.core.erp;

import java.time.Instant;

/** A cost center or physical unit as {@code GET /api/locations} returns it. */
public record ErpLocation(String code, String name, String city, boolean active, Instant updatedAt) {
}
