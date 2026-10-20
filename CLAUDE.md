# CLAUDE.md

Health Data Bridge is a standalone FHIR R4 ↔ ERP integration service (repo `wasdevv/health-data-bridge`, public). Swarm tasks build it, but the app **does not depend on Swarm**.

**Source of truth:** `docs/prd.md` (pt-BR). Section 0 (confirmed decisions) overrides everything else in it, and section 10 lists the acceptance criteria. README and ADRs (`docs/adr/`) are in pt-BR; code and comments in English.

## Toolchain (no sudo, no Docker)

```bash
export JAVA_HOME=$HOME/.local/jdk-21 PATH=$HOME/.local/jdk-21/bin:$HOME/.local/maven/bin:$PATH
```

- JDK 21 (Temurin) and Maven 3.9.11 are installed in `~/.local`.
- **This machine has no Docker**, so Testcontainers cannot run locally. Integration tests (`*IT`, Failsafe) use `HDB_TEST_JDBC_URL` instead. The local PostgreSQL 18 cluster is shared with knowledge-hub:
  `/usr/lib/postgresql/18/bin/pg_ctl -D ~/.local/share/kh-pg -l ~/.local/share/kh-pg/log.txt start`, then
  `HDB_TEST_JDBC_URL=jdbc:postgresql://localhost:55433/hdb_test HDB_TEST_DB_USER=postgres mvn verify`.
  Without a database the IT classes are reported as **skipped**: say so, and never call that green.
- `docker compose up` is only verified by the `compose` CI workflow. Locally, run the two jars against the same cluster (database `hdb`) and `python3 -I scripts/smoke.py <bridge> <erp>`.

## Rules

- Demo project, fictitious data only. No real patient data in any fixture; CPFs come from `Cpf.generate` with a fixed seed (`DemoDataset`).
- Patient name and CPF never reach logs or the ERP; `PatientDataNotLoggedIT` and the compose workflow enforce it. Log ids, not resources.
- Every service date is a UTC day (`BillableEncounterExtractor.toUtcDate`). Tests run in `America/Sao_Paulo` on purpose; never read a FHIR date through the JVM zone.
- Each module migrates its own Flyway folder (`db/migration/fhir`, `db/migration/sync`) and its ITs use their own schema (`TestDatabase.register(..., "<schema>")`).
- The ERP contract is `erp-mock/src/main/resources/static/openapi.yaml`; both sides test against it. Change the file and both test suites together.
- Coverage and chaos-test numbers go into the README only after they are measured, with the command and environment used.
- Commits and PRs carry no Claude attribution (no `Co-Authored-By`, no "Generated with").
