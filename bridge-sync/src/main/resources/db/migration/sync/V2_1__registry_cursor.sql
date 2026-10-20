-- Where the ERP to FHIR registry sync stopped, per entity. The ERP's updatedSince is inclusive, so
-- resuming from here re-reads at most the records changed at this exact instant, never skips one.
create table sync_cursor (
    entity     text        primary key,
    cursor_at  timestamptz not null,
    updated_at timestamptz not null
);
