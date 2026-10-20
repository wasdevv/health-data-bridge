# ADR 004: Reconciliação diária além do retry

**Status:** aceita (2026-10-20)

## Contexto

Retry, idempotência e circuit breaker cobrem falhas de transporte. Não cobrem:

- bug de mapeamento que manda a fatura com a data ou o encontro errado;
- fatura lançada à mão no ERP, fora da ponte;
- evento que foi para a DLQ e ninguém reprocessou;
- encontro que foi finalizado e depois mudou de status.

Nesses casos, cada componente acredita que fez o certo. Só olhando os dois lados ao mesmo tempo dá para ver a diferença.

## Decisão

Um job diário (02:00 UTC, dia anterior) e um endpoint admin (`POST /admin/reconciliation/run?date=`) comparam, para um dia UTC, os `Encounter` finalizados do FHIR com as faturas do ERP daquele dia. As diferenças viram linhas em `reconciliation_issue`:

- `MISSING_INVOICE`: encontro sem fatura. O motivo traz o estado do outbox (na DLQ e por quê, pendente com N tentativas, ou nunca enfileirado).
- `DUPLICATE_INVOICE`: mais de uma fatura para o mesmo encontro.
- `ORPHAN_INVOICE`: fatura cujo encontro não é um encontro finalizado daquele dia, com o motivo (não existe, ou existe com outro status).

O "dia" é o mesmo nos dois lados: a data UTC de `Encounter.period.start`, que é a `issueDate` da fatura.

## Consequências

- O relatório é a prova de que nada se perdeu, e também o ponto de partida quando algo se perdeu.
- A reconciliação compara conjuntos de ids, não só contagens: uma perda e uma duplicata no mesmo dia se anulariam numa contagem.
- O `erp-mock` planta uma fatura para `enc-planted` em 2026-10-03, e o smoke test do Compose exige que a reconciliação desse dia encontre exatamente essa divergência.
- Um encontro ainda pendente no outbox aparece como `MISSING_INVOICE`. É verdade no momento da execução, e o motivo deixa claro que é atraso, não perda.
