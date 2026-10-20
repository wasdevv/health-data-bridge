package dev.wasdevv.hdb.sync.outbox;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** SQL for the {@code outbox} table. Callers own the transactions. */
public class OutboxRepository {

    private static final String COLUMNS = """
            id, event_type, aggregate_type, aggregate_id, status, attempts, next_attempt_at, created_at,
            updated_at, last_error, erp_invoice_id""";

    private static final RowMapper<OutboxEntry> ROW = OutboxRepository::map;

    private final JdbcTemplate jdbc;

    public OutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** False when the event already exists: the unique constraint is the first idempotency layer. */
    public boolean enqueue(String eventType, String aggregateType, String aggregateId, Instant now) {
        return jdbc.update("""
                insert into outbox (id, event_type, aggregate_type, aggregate_id, status, attempts, next_attempt_at,
                                    created_at, updated_at)
                values (?, ?, ?, ?, 'PENDING', 0, ?, ?, ?)
                on conflict on constraint outbox_one_event_per_aggregate do nothing""",
                UUID.randomUUID(), eventType, aggregateType, aggregateId, ts(now), ts(now), ts(now)) == 1;
    }

    /**
     * Due rows, locked until the caller's transaction ends. {@code SKIP LOCKED} lets another worker
     * (thread or instance) take the next rows instead of waiting for these.
     */
    public List<OutboxEntry> claimDue(int limit, Instant now) {
        return jdbc.query("select " + COLUMNS + """
                 from outbox
                where status = 'PENDING' and next_attempt_at <= ?
                order by next_attempt_at, created_at
                limit ?
                for update skip locked""", ROW, ts(now), limit);
    }

    public void markDone(UUID id, int attempts, String erpInvoiceId, Instant now) {
        jdbc.update("""
                update outbox set status = 'DONE', attempts = ?, erp_invoice_id = ?, last_error = null, updated_at = ?
                 where id = ?""", attempts, erpInvoiceId, ts(now), id);
    }

    public void reschedule(UUID id, int attempts, Instant nextAttemptAt, String error, Instant now) {
        jdbc.update("""
                update outbox set attempts = ?, next_attempt_at = ?, last_error = ?, updated_at = ? where id = ?""",
                attempts, ts(nextAttemptAt), error, ts(now), id);
    }

    public void markDead(UUID id, int attempts, String error, Instant now) {
        jdbc.update("""
                update outbox set status = 'DEAD', attempts = ?, last_error = ?, updated_at = ? where id = ?""",
                attempts, error, ts(now), id);
    }

    /** DEAD back to PENDING with a fresh attempt budget. False when the row is not DEAD. */
    public boolean revive(UUID id, Instant now) {
        return jdbc.update("""
                update outbox set status = 'PENDING', attempts = 0, next_attempt_at = ?, updated_at = ?
                 where id = ? and status = 'DEAD'""", ts(now), ts(now), id) == 1;
    }

    public Optional<OutboxEntry> find(UUID id) {
        return jdbc.query("select " + COLUMNS + " from outbox where id = ?", ROW, id).stream().findFirst();
    }

    public Optional<OutboxEntry> findByAggregate(String eventType, String aggregateType, String aggregateId) {
        return jdbc.query("select " + COLUMNS + " from outbox where event_type = ? and aggregate_type = ? and aggregate_id = ?",
                ROW, eventType, aggregateType, aggregateId).stream().findFirst();
    }

    public List<OutboxEntry> list(OutboxEntry.Status status, int limit) {
        return jdbc.query("select " + COLUMNS + " from outbox where status = ? order by updated_at desc limit ?",
                ROW, status.name(), limit);
    }

    public long count(OutboxEntry.Status status) {
        return jdbc.queryForObject("select count(*) from outbox where status = ?", Long.class, status.name());
    }

    public Optional<Instant> oldestPendingCreatedAt() {
        Timestamp t = jdbc.queryForObject("select min(created_at) from outbox where status = 'PENDING'", Timestamp.class);
        return Optional.ofNullable(t).map(Timestamp::toInstant);
    }

    private static OutboxEntry map(ResultSet rs, int row) throws SQLException {
        return new OutboxEntry(
                rs.getObject("id", UUID.class),
                rs.getString("event_type"),
                rs.getString("aggregate_type"),
                rs.getString("aggregate_id"),
                OutboxEntry.Status.valueOf(rs.getString("status")),
                rs.getInt("attempts"),
                rs.getTimestamp("next_attempt_at").toInstant(),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getString("last_error"),
                rs.getString("erp_invoice_id"));
    }

    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }
}
