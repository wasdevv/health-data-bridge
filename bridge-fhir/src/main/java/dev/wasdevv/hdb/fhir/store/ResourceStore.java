package dev.wasdevv.hdb.fhir.store;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.server.exceptions.PreconditionFailedException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import dev.wasdevv.hdb.fhir.validation.ResourceValidator;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * FHIR resources in PostgreSQL: current version as {@code jsonb}, search values in a side table.
 * Every write is validated first and rebuilds the resource's index rows, then runs the
 * {@link ResourceWriteListener}s in the same transaction.
 */
public class ResourceStore {

    private static final String UPSERT = """
            insert into fhir_resource (resource_type, id, version_id, last_updated, content)
            values (?, ?, ?, ?, cast(? as jsonb))
            on conflict (resource_type, id) do update
               set version_id = excluded.version_id, last_updated = excluded.last_updated, content = excluded.content
            """;

    private final JdbcTemplate jdbc;
    private final FhirContext ctx;
    private final ResourceValidator validator;
    private final List<ResourceWriteListener> listeners;
    private final Clock clock;
    private final SearchIndexer indexer = new SearchIndexer();

    public ResourceStore(JdbcTemplate jdbc, FhirContext ctx, ResourceValidator validator,
                         List<ResourceWriteListener> listeners, Clock clock) {
        this.jdbc = jdbc;
        this.ctx = ctx;
        this.validator = validator;
        this.listeners = List.copyOf(listeners);
        this.clock = clock;
    }

    @Transactional
    public <T extends Resource> T create(T resource) {
        return write(resource, UUID.randomUUID().toString(), null);
    }

    /**
     * Update, or create with the client's id when it does not exist yet (FHIR "update as create").
     * {@code expectedVersion} comes from {@code If-Match}: a stale version is a 412, not a lost update.
     */
    @Transactional
    public <T extends Resource> T update(T resource, String id, String expectedVersion) {
        return write(resource, id, expectedVersion);
    }

    /**
     * Conditional update by business identifier: no match creates, one match updates that resource,
     * more than one is a 412 because the caller's identity is ambiguous.
     */
    @Transactional
    public <T extends Resource> T upsertByIdentifier(T resource, String system, String value) {
        String id = idForIdentifier(resource.fhirType(), system, value).orElseGet(() -> UUID.randomUUID().toString());
        return write(resource, id, null);
    }

    /** The one resource carrying this identifier, empty when none does, 412 when several do. */
    @Transactional
    public Optional<String> idForIdentifier(String type, String system, String value) {
        List<String> ids = jdbc.queryForList("""
                select resource_id from fhir_search_index
                 where resource_type = ? and param = 'identifier' and token_system = ? and value = ?
                 for update""", String.class, type, system, value);
        if (ids.size() > 1) {
            throw new PreconditionFailedException(
                    "Conditional update matched " + ids.size() + " " + type + " resources for " + system + "|" + value);
        }
        return ids.stream().findFirst();
    }

    public String resourceType(Class<? extends Resource> type) {
        return ctx.getResourceType(type);
    }

    @Transactional(readOnly = true)
    public <T extends Resource> Optional<T> find(Class<T> type, String id) {
        List<String> rows = jdbc.queryForList(
                "select content::text from fhir_resource where resource_type = ? and id = ?",
                String.class, ctx.getResourceType(type), id);
        return rows.stream().findFirst().map(json -> ctx.newJsonParser().parseResource(type, json));
    }

    public <T extends Resource> T read(Class<T> type, String id) {
        return find(type, id).orElseThrow(() ->
                new ResourceNotFoundException(new IdType(ctx.getResourceType(type), id)));
    }

    @Transactional(readOnly = true)
    public <T extends Resource> List<T> search(Class<T> type, SearchQuery query) {
        StringBuilder sql = new StringBuilder(
                "select r.content::text from fhir_resource r where r.resource_type = ?");
        List<Object> args = new ArrayList<>();
        args.add(query.resourceType());
        for (SearchQuery.Criterion c : query.criteria()) {
            sql.append(" and exists (select 1 from fhir_search_index i where i.resource_type = r.resource_type")
                    .append(" and i.resource_id = r.id and i.param = ?");
            switch (c) {
                case SearchQuery.Token t -> {
                    args.add(t.param());
                    sql.append(" and i.value = ?");
                    args.add(t.value());
                    if (t.system() != null) {
                        sql.append(" and i.token_system = ?");
                        args.add(t.system());
                    }
                }
                case SearchQuery.Exact e -> {
                    args.add(e.param());
                    sql.append(" and i.value = ?");
                    args.add(e.value());
                }
                case SearchQuery.DateRange d -> {
                    args.add(d.param());
                    if (d.from() != null) {
                        sql.append(" and i.value_date >= ?");
                        args.add(d.from());
                    }
                    if (d.to() != null) {
                        sql.append(" and i.value_date <= ?");
                        args.add(d.to());
                    }
                }
            }
            sql.append(")");
        }
        sql.append(" order by r.last_updated, r.id");
        return jdbc.queryForList(sql.toString(), String.class, args.toArray()).stream()
                .map(json -> ctx.newJsonParser().parseResource(type, json))
                .toList();
    }

    private <T extends Resource> T write(T resource, String id, String expectedVersion) {
        String type = resource.fhirType();
        resource.setId(new IdType(type, id));
        // version and lastUpdated are the server's; a client-sent meta must not leak into validation
        resource.getMeta().setVersionId(null).setLastUpdated(null);
        validator.validateOrThrow(resource);

        List<Long> current = jdbc.queryForList(
                "select version_id from fhir_resource where resource_type = ? and id = ? for update",
                Long.class, type, id);
        if (expectedVersion != null) {
            if (current.isEmpty()) {
                throw new ResourceNotFoundException(new IdType(type, id));
            }
            if (!expectedVersion.equals(String.valueOf(current.get(0)))) {
                throw new PreconditionFailedException(
                        type + "/" + id + " is at version " + current.get(0) + ", not " + expectedVersion);
            }
        }
        long version = current.isEmpty() ? 1 : current.get(0) + 1;
        Instant now = clock.instant();
        resource.setId(new IdType(type, id, String.valueOf(version)));
        resource.getMeta().setVersionId(String.valueOf(version)).setLastUpdated(Date.from(now));

        jdbc.update(UPSERT, type, id, version, Timestamp.from(now), ctx.newJsonParser().encodeResourceToString(resource));
        jdbc.update("delete from fhir_search_index where resource_type = ? and resource_id = ?", type, id);
        List<Object[]> rows = indexer.index(resource).stream()
                .map(e -> new Object[] {type, id, e.param(), e.system(), e.value(), e.date()})
                .toList();
        if (!rows.isEmpty()) {
            jdbc.batchUpdate("""
                    insert into fhir_search_index (resource_type, resource_id, param, token_system, value, value_date)
                    values (?, ?, ?, ?, ?, ?)""", rows);
        }
        for (ResourceWriteListener listener : listeners) {
            listener.afterWrite(resource);
        }
        return resource;
    }
}
