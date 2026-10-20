# CLAUDE.md

Health Data Bridge is a standalone FHIR R4 ↔ ERP integration service (repo `wasdevv/health-data-bridge`, public). Swarm tasks build it, but the app **does not depend on Swarm**.

**Source of truth:** `docs/prd.md` (pt-BR). Section 0 (confirmed decisions) overrides everything else in it, and section 10 lists the acceptance criteria. Section 0 is still pending: answer the four questions of section 13 there before Phase 0. Build one phase from section 9 per task, in order.

## Toolchain (no sudo, no Docker)

```bash
export JAVA_HOME=$HOME/.local/jdk-21 PATH=$HOME/.local/jdk-21/bin:$HOME/.local/maven/bin:$PATH
```

- JDK 21 (Temurin) and Maven 3.9.11 are installed in `~/.local`.
- **This machine has no Docker**, so Testcontainers cannot run locally. Integration tests need a JDBC-URL fallback (same pattern as `~/projetos/knowledge-hub`); without a database they are reported as **skipped**: say so, and never call that green. `docker compose up` is only verified in CI.

## Rules

- Demo project, fictitious data only. No real patient data in any fixture; CPFs are generated with valid check digits.
- Patient data (CPF, name) never reaches logs; a test enforces it.
- Coverage and chaos-test numbers go into the README only after they are measured, with the command and environment used.
- Commits carry no Claude attribution (no `Co-Authored-By`, no "Generated with").
