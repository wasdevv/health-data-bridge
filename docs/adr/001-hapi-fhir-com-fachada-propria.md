# ADR 001: HAPI FHIR, com fachada própria em vez do JPA Server

**Status:** aceita (2026-10-20)

## Contexto

O serviço precisa falar FHIR R4 de verdade: parse e serialização dos recursos, `CapabilityStatement`, buscas com paginação, `Bundle` de transação, `OperationOutcome` e validação contra perfil. Implementar isso do zero é semanas de trabalho sem valor nenhum para o problema, que é a ponte com o ERP.

O HAPI FHIR é a biblioteca de referência em Java e oferece dois níveis: o `RestfulServer` (servidor REST com providers anotados) e o `hapi-fhir-jpaserver-base` (um servidor completo, com schema e transações próprias).

## Decisão

Usar o HAPI para tudo o que é padrão FHIR (`hapi-fhir-structures-r4`, `hapi-fhir-server`, `hapi-fhir-validation`) e escrever a camada de armazenamento: o recurso atual em `jsonb` e uma tabela de índice só com os parâmetros que o `CapabilityStatement` anuncia.

O JPA Server ficou de fora por um motivo concreto: o outbox tem que ser gravado na **mesma transação** que o `Encounter` (ver ADR 002). Com o JPA Server, essa transação pertence ao HAPI, e o gancho teria que passar pelos interceptors de storage dele. Com a fachada, `ResourceStore.write` abre a transação, grava, reindexa e chama os `ResourceWriteListener`, e o outbox é um desses listeners.

## Consequências

- O valor do projeto fica onde deve estar: no perfil `billable-encounter`, nas regras de faturamento e na integração.
- Funcionalidade FHIR limitada ao que foi implementado: read, create, update (inclusive condicional por `identifier`), search-type com os parâmetros indexados, transaction. Sem histórico (`vread`), sem `_include`, sem delete. Está declarado no `CapabilityStatement`, então um cliente sabe o que esperar.
- A paginação usa o `FifoMemoryPagingProvider` do HAPI: os links `next` vivem na memória de uma instância. Com várias instâncias atrás de um balanceador, a paginação precisaria de sessão fixa ou de um paging provider em banco.
- A busca por data usa o dia UTC do texto do parâmetro, não `getLowerBoundAsInstant()`, que resolve a data no fuso da JVM. Os testes rodam em `America/Sao_Paulo` para pegar esse tipo de erro.
