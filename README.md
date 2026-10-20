# Health Data Bridge

FHIR R4 API for clinical and administrative data, synchronized reliably with an ERP: finished encounters become invoices, ERP registries feed FHIR `Organization`/`Location`. Transactional outbox, two-sided idempotency, circuit breaker, dead letter queue and daily reconciliation.

Java 21 · Spring Boot 3.3 · HAPI FHIR · PostgreSQL · Resilience4j · Testcontainers · WireMock

**Status:** planning. The spec is in [`docs/prd.md`](docs/prd.md) (pt-BR).

> Demonstration project. All data is fictitious; no real patient data is used anywhere.
