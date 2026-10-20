# ADR 006: JdbcTemplate em vez de Spring Data JPA

**Status:** aceita (2026-10-20)

## Contexto

O PRD listava Spring Data JPA. Na implementação, todas as consultas que importam são específicas do PostgreSQL:

- recursos FHIR em `jsonb`, gravados com `INSERT ... ON CONFLICT DO UPDATE`;
- a reivindicação do lote com `FOR UPDATE SKIP LOCKED`;
- o enfileiramento idempotente com `ON CONFLICT ON CONSTRAINT ... DO NOTHING`;
- o cursor com `greatest(...)` para nunca andar para trás.

Com JPA, cada uma delas seria uma `@Query(nativeQuery = true)`, e as entidades existiriam só para mapear o resultado.

## Decisão

`JdbcTemplate` com SQL explícito nas classes de repositório (`ResourceStore`, `OutboxRepository`, `RegistrySync`, `Reconciliation`). Flyway cuida do schema, com as migrações de cada módulo em `db/migration/<módulo>`.

## Consequências

- O SQL que roda é o SQL que está no código, sem uma camada de tradução no meio. A transação é a do Spring (`@Transactional` e `TransactionTemplate`), igual ao que seria com JPA.
- Sem cache de primeiro nível nem dirty checking: cada atualização é um `UPDATE` explícito.
- O mapeamento FHIR ↔ ERP continua com MapStruct onde o formato é tipado (`InvoiceRequestMapper`); a extração dos recursos HAPI é escrita à mão, porque o modelo do HAPI não se presta a mapeamento por reflexão.
