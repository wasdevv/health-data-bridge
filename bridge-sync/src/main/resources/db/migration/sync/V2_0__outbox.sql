-- Transactional outbox. A row is written in the same transaction as the finished Encounter, so the
-- event exists if and only if the encounter does. DEAD rows are the dead letter queue.
create table outbox (
    id              uuid        primary key,
    event_type      text        not null,
    aggregate_type  text        not null,
    aggregate_id    text        not null,
    status          text        not null check (status in ('PENDING', 'DONE', 'DEAD')),
    attempts        integer     not null default 0,
    next_attempt_at timestamptz not null,
    created_at      timestamptz not null,
    updated_at      timestamptz not null,
    last_error      text,
    erp_invoice_id  text,
    -- first layer of idempotency: one event per encounter, however many times it is saved as finished
    constraint outbox_one_event_per_aggregate unique (event_type, aggregate_type, aggregate_id)
);

create index outbox_due on outbox (status, next_attempt_at);
