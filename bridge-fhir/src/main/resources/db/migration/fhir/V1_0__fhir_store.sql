-- Current version of every resource. No history table in v1: vread is not supported.
create table fhir_resource (
    resource_type text        not null,
    id            text        not null,
    version_id    bigint      not null,
    last_updated  timestamptz not null,
    content       jsonb       not null,
    primary key (resource_type, id)
);

-- One row per indexed search value, rebuilt on every write of the resource.
create table fhir_search_index (
    resource_type text not null,
    resource_id   text not null,
    param         text not null,
    token_system  text,
    value         text,
    value_date    date,
    foreign key (resource_type, resource_id) references fhir_resource (resource_type, id) on delete cascade
);

create index fhir_search_index_value on fhir_search_index (resource_type, param, value);
create index fhir_search_index_date on fhir_search_index (resource_type, param, value_date);
create index fhir_search_index_owner on fhir_search_index (resource_type, resource_id);
