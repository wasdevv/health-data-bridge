package dev.wasdevv.hdb.sync.reconciliation;

import dev.wasdevv.hdb.fhir.store.ResourceStore;
import dev.wasdevv.hdb.fhir.store.SearchQuery;
import dev.wasdevv.hdb.sync.erp.ErpClient;
import dev.wasdevv.hdb.sync.outbox.OutboxEntry;
import dev.wasdevv.hdb.sync.outbox.OutboxRepository;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.hl7.fhir.r4.model.Encounter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * For one UTC day, every finished Encounter on the FHIR side against every invoice the ERP issued.
 * Retries cover a flaky network; this covers what retries cannot: a mapping bug, an invoice issued by
 * hand in the ERP, an event that died in the DLQ and was forgotten.
 */
public class Reconciliation {

    private static final Logger log = LoggerFactory.getLogger(Reconciliation.class);

    public enum Kind {
        /** Finished encounter with no invoice. */
        MISSING_INVOICE,
        /** Finished encounter with more than one invoice. */
        DUPLICATE_INVOICE,
        /** Invoice whose encounter is not a finished encounter of that day. */
        ORPHAN_INVOICE
    }

    public record Issue(Kind kind, String resourceId, List<String> invoiceIds, String reason) {
    }

    public record Report(UUID runId, LocalDate day, Instant startedAt, int finishedEncounters, int invoices,
                         List<Issue> issues) {
    }

    private final ResourceStore store;
    private final ErpClient erp;
    private final OutboxRepository outbox;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;

    public Reconciliation(ResourceStore store, ErpClient erp, OutboxRepository outbox, JdbcTemplate jdbc,
                          TransactionTemplate tx, Clock clock) {
        this.store = store;
        this.erp = erp;
        this.outbox = outbox;
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
    }

    public Report run(LocalDate day) {
        Instant startedAt = clock.instant();
        Set<String> finished = new TreeSet<>();
        store.search(Encounter.class, SearchQuery.of("Encounter").dateRange("date", day, day).exact("status", "finished"))
                .forEach(e -> finished.add(e.getIdElement().getIdPart()));
        List<ErpClient.ErpInvoice> invoices = erp.invoicesIssuedOn(day);
        Map<String, List<String>> byEncounter = new LinkedHashMap<>();
        invoices.forEach(i -> byEncounter.computeIfAbsent(i.encounterId(), k -> new ArrayList<>()).add(i.invoiceId()));

        List<Issue> issues = new ArrayList<>();
        for (String encounterId : finished) {
            List<String> ids = byEncounter.getOrDefault(encounterId, List.of());
            if (ids.isEmpty()) {
                issues.add(new Issue(Kind.MISSING_INVOICE, "Encounter/" + encounterId, List.of(), outboxState(encounterId)));
            } else if (ids.size() > 1) {
                issues.add(new Issue(Kind.DUPLICATE_INVOICE, "Encounter/" + encounterId, ids,
                        ids.size() + " invoices for one encounter"));
            }
        }
        byEncounter.forEach((encounterId, ids) -> {
            if (!finished.contains(encounterId)) {
                issues.add(new Issue(Kind.ORPHAN_INVOICE, "Encounter/" + encounterId, ids, orphanReason(encounterId)));
            }
        });

        Report report = new Report(UUID.randomUUID(), day, startedAt, finished.size(), invoices.size(), issues);
        tx.executeWithoutResult(s -> save(report));
        log.info("reconciliation {}: finished encounters={} invoices={} issues={}", day, finished.size(),
                invoices.size(), issues.size());
        return report;
    }

    public Optional<Report> latest(LocalDate day) {
        List<Report> runs = jdbc.query("""
                select id, started_at, finished_encounters, invoices from reconciliation_run
                 where day = ? order by started_at desc limit 1""",
                (rs, n) -> new Report(rs.getObject("id", UUID.class), day, rs.getTimestamp("started_at").toInstant(),
                        rs.getInt("finished_encounters"), rs.getInt("invoices"), new ArrayList<>()),
                Date.valueOf(day));
        if (runs.isEmpty()) {
            return Optional.empty();
        }
        Report run = runs.get(0);
        run.issues().addAll(jdbc.query("""
                select kind, resource_id, invoice_ids, reason from reconciliation_issue where run_id = ? order by kind, resource_id""",
                (rs, n) -> new Issue(Kind.valueOf(rs.getString("kind")), rs.getString("resource_id"),
                        rs.getString("invoice_ids") == null ? List.of() : List.of(rs.getString("invoice_ids").split(",")),
                        rs.getString("reason")),
                run.runId()));
        return Optional.of(run);
    }

    private String outboxState(String encounterId) {
        return outbox.findByAggregate(OutboxEntry.CREATE_INVOICE, "Encounter", encounterId)
                .map(o -> switch (o.status()) {
                    case DEAD -> "outbox row " + o.id() + " is in the dead letter queue: " + o.lastError();
                    case PENDING -> "outbox row " + o.id() + " not delivered yet (" + o.attempts() + " attempts)";
                    case DONE -> "outbox row " + o.id() + " delivered as " + o.erpInvoiceId()
                            + ", but the ERP has no such invoice on this day";
                })
                .orElse("no outbox row: the encounter was never queued for billing");
    }

    private String orphanReason(String encounterId) {
        return store.find(Encounter.class, encounterId)
                .map(e -> "Encounter/" + encounterId + " exists but is " + e.getStatus().toCode()
                        + (e.getPeriod().hasStart() ? " with period.start " + e.getPeriod().getStartElement().getValueAsString() : ""))
                .orElse("Encounter/" + encounterId + " does not exist on the FHIR side");
    }

    private void save(Report r) {
        jdbc.update("""
                insert into reconciliation_run (id, day, started_at, finished_encounters, invoices, issues)
                values (?, ?, ?, ?, ?, ?)""",
                r.runId(), Date.valueOf(r.day()), Timestamp.from(r.startedAt()), r.finishedEncounters(), r.invoices(),
                r.issues().size());
        for (Issue i : r.issues()) {
            jdbc.update("""
                    insert into reconciliation_issue (id, run_id, day, kind, resource_id, invoice_ids, reason)
                    values (?, ?, ?, ?, ?, ?, ?)""",
                    UUID.randomUUID(), r.runId(), Date.valueOf(r.day()), i.kind().name(), i.resourceId(),
                    i.invoiceIds().isEmpty() ? null : String.join(",", i.invoiceIds()), i.reason());
        }
    }
}
