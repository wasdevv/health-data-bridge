-- One row per reconciliation of a day: finished encounters on the FHIR side against ERP invoices.
create table reconciliation_run (
    id                  uuid        primary key,
    day                 date        not null,
    started_at          timestamptz not null,
    finished_encounters integer     not null,
    invoices            integer     not null,
    issues              integer     not null
);

create index reconciliation_run_day on reconciliation_run (day, started_at);

create table reconciliation_issue (
    id          uuid    primary key,
    run_id      uuid    not null references reconciliation_run (id) on delete cascade,
    day         date    not null,
    kind        text    not null check (kind in ('MISSING_INVOICE', 'DUPLICATE_INVOICE', 'ORPHAN_INVOICE')),
    resource_id text    not null,
    invoice_ids text,
    reason      text    not null
);

create index reconciliation_issue_run on reconciliation_issue (run_id);
