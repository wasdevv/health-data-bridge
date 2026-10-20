# ADR 003: Idempotência nas duas pontas

**Status:** aceita (2026-10-20)

## Contexto

Há dois lugares onde uma fatura pode ser duplicada:

1. **Na entrada:** o mesmo encontro é salvo como `finished` mais de uma vez (correção de um campo, reenvio do sistema clínico).
2. **Na saída:** o ERP grava a fatura e a resposta se perde (timeout, conexão cortada, 503 de um proxy). Do lado da ponte, isso parece uma falha, e a ponte tenta de novo.

O retry, sozinho, transforma o caso 2 em duplicata. Sem retry, o caso 2 vira perda.

## Decisão

- **Banco:** constraint única `(event_type, aggregate_type, aggregate_id)` no outbox, com `INSERT ... ON CONFLICT DO NOTHING`. Um encontro gera no máximo um evento, não importa quantas vezes seja salvo.
- **ERP:** toda chamada leva `Idempotency-Key` igual ao id da linha do outbox, que não muda entre tentativas nem quando a linha é reprocessada da DLQ. O ERP guarda chave e requisição: repetição com o mesmo corpo devolve a fatura original (200, `Idempotent-Replayed: true`), e a mesma chave com corpo diferente é 422, porque é bug do cliente e não retry.

## Consequências

- O `erp-mock` simula exatamente o caso 2 com o modo `failAfterCommitRate`. No teste de caos, todas as respostas perdidas foram recuperadas por replay, e o número de replays bateu com o de falhas depois do commit.
- O contrato exige que o ERP real suporte uma chave de idempotência. Se não suportar, a alternativa é consultar antes de criar (`GET /api/invoices?encounterId=`), o que tem uma janela de corrida e precisa de lock.
- Reprocessar da DLQ mantém a chave. Se o ERP já tinha emitido a fatura (a falha "permanente" veio depois do commit), o reprocessamento recebe a mesma fatura em vez de criar outra.
