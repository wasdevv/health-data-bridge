package dev.wasdevv.hdb.sync.registry;

import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException;
import dev.wasdevv.hdb.core.erp.ErpCustomer;
import dev.wasdevv.hdb.core.erp.ErpLocation;
import dev.wasdevv.hdb.core.fhir.RegistryMapper;
import dev.wasdevv.hdb.core.fhir.Systems;
import dev.wasdevv.hdb.fhir.store.ResourceStore;
import dev.wasdevv.hdb.fhir.store.SearchQuery;
import dev.wasdevv.hdb.sync.erp.ErpClient;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;
import org.hl7.fhir.r4.model.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ERP to FHIR: customers become Organizations, locations become Locations, each upserted by its ERP
 * code (a conditional update on {@code identifier}), so running twice never duplicates anything.
 * Each record and the cursor move together in one transaction: a crash resumes after the last record
 * written. A record whose FHIR resource would not change is skipped, so the 15-minute schedule does
 * not bump versions for nothing.
 */
public class RegistrySync {

    private static final Logger log = LoggerFactory.getLogger(RegistrySync.class);

    public record EntityResult(String entity, int received, int written, int unchanged, int rejected, Instant cursor) {
    }

    public record Result(EntityResult customers, EntityResult locations) {
    }

    private final ErpClient erp;
    private final ResourceStore store;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final RegistryMapper mapper = new RegistryMapper();

    public RegistrySync(ErpClient erp, ResourceStore store, JdbcTemplate jdbc, TransactionTemplate tx, Clock clock) {
        this.erp = erp;
        this.store = store;
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
    }

    public Result run() {
        EntityResult customers = sync("customers", erp::customersUpdatedSince, ErpCustomer::updatedAt,
                ErpCustomer::code, mapper::toOrganization, Systems.ERP_CUSTOMER);
        EntityResult locations = sync("locations", erp::locationsUpdatedSince, ErpLocation::updatedAt,
                ErpLocation::code, mapper::toLocation, Systems.ERP_LOCATION);
        return new Result(customers, locations);
    }

    private <R> EntityResult sync(String entity, Function<Instant, List<R>> fetch, Function<R, Instant> updatedAt,
                                  Function<R, String> code, Function<R, Resource> toFhir, String system) {
        Instant cursor = cursor(entity);
        List<R> records = fetch.apply(cursor);
        int written = 0, unchanged = 0, rejected = 0;
        for (R record : records) {
            Resource resource = toFhir.apply(record);
            Instant recordCursor = updatedAt.apply(record);
            try {
                Boolean wrote = tx.execute(s -> {
                    boolean changed = upsertIfChanged(resource, system, code.apply(record));
                    saveCursor(entity, recordCursor);
                    return changed;
                });
                if (Boolean.TRUE.equals(wrote)) {
                    written++;
                } else {
                    unchanged++;
                }
            } catch (BaseServerResponseException e) {
                // invalid as FHIR: skipped and reported, so one bad record cannot stall the rest
                rejected++;
                tx.executeWithoutResult(s -> saveCursor(entity, recordCursor));
                log.warn("registry sync: {} {} rejected by FHIR validation ({})", entity, code.apply(record), e.getStatusCode());
            }
            cursor = recordCursor;
        }
        log.info("registry sync: {} received={} written={} unchanged={} rejected={}", entity, records.size(), written,
                unchanged, rejected);
        return new EntityResult(entity, records.size(), written, unchanged, rejected, cursor);
    }

    private boolean upsertIfChanged(Resource candidate, String system, String value) {
        List<? extends Resource> existing = store.search(candidate.getClass(),
                SearchQuery.of(candidate.fhirType()).token("identifier", system, value));
        if (existing.size() == 1) {
            Resource current = existing.get(0);
            Resource compare = candidate.copy();
            compare.setIdElement(current.getIdElement());
            compare.setMeta(current.getMeta());
            if (current.equalsDeep(compare)) {
                return false;
            }
        }
        store.upsertByIdentifier(candidate, system, value);
        return true;
    }

    Instant cursor(String entity) {
        List<Timestamp> rows = jdbc.queryForList("select cursor_at from sync_cursor where entity = ?", Timestamp.class, entity);
        return rows.isEmpty() ? null : rows.get(0).toInstant();
    }

    private void saveCursor(String entity, Instant at) {
        jdbc.update("""
                insert into sync_cursor (entity, cursor_at, updated_at) values (?, ?, ?)
                on conflict (entity) do update set cursor_at = greatest(sync_cursor.cursor_at, excluded.cursor_at),
                                                   updated_at = excluded.updated_at""",
                entity, Timestamp.from(at), Timestamp.from(clock.instant()));
    }
}
