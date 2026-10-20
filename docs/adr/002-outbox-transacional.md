# ADR 002: Outbox transacional em vez de chamar o ERP na requisição

**Status:** aceita (2026-10-20)

## Contexto

Quando um `Encounter` é finalizado, o ERP precisa emitir uma fatura. A forma ingênua é chamar o ERP dentro do `PUT /fhir/Encounter/{id}`. Ela tem dois problemas:

1. A disponibilidade do FHIR passa a depender da do ERP. ERP fora significa que o sistema clínico não consegue finalizar atendimentos.
2. Não existe forma atômica de "gravar no banco e chamar um serviço HTTP". Se o ERP responde e o commit falha, a fatura existe sem o encontro. Se o commit acontece e a chamada falha, o encontro existe sem fatura, e ninguém fica sabendo.

## Decisão

O `OutboxEnqueuer` é um `ResourceWriteListener`: ao gravar um `Encounter` com `status = finished`, ele insere uma linha `CREATE_INVOICE` na tabela `outbox`, **na mesma transação**. O evento existe se, e somente se, o encontro existe.

O `OutboxWorker` entrega de forma assíncrona:

- Reivindica um lote com `SELECT ... FOR UPDATE SKIP LOCKED`, o que permite mais de uma instância ou thread sem coordenação.
- Prepara cada linha (perfil, recursos relacionados, regras) e envia em paralelo, com limite de concorrência.
- Registra o resultado: `DONE`, retry com backoff exponencial e jitter, ou `DEAD` (falha permanente ou tentativas esgotadas).
- Faz tudo numa transação por lote. Se o processo morre antes do commit, as linhas voltam a `PENDING` e são reenviadas com a mesma `Idempotency-Key` (ADR 003).

Com o circuit breaker aberto, o worker não reivindica nada: o outbox cresce, nenhuma tentativa é gasta e nada se perde.

## Consequências

- A entrega é **eventual**: o `PUT` responde antes de a fatura existir. Quem consulta o ERP logo depois pode não ver a fatura ainda. Isso precisa estar claro para o usuário do sistema clínico; as métricas `bridge.outbox.pending` e `bridge.outbox.oldest.pending.age` mostram o atraso.
- A transação do lote fica aberta durante as chamadas HTTP. Com `batch-size` 50, concorrência 4 e timeout de leitura de 5 s, o pior caso é algumas dezenas de segundos de locks em linhas que nenhum outro worker quer (`SKIP LOCKED`). Se isso virar problema, o próximo passo é lease com coluna `locked_until` e commits por linha.
- Recurso que ainda não chegou (o `Claim` depois do `Encounter`) é falha transitória. Perfil inválido e regra de faturamento violada são permanentes: o mesmo dado falha do mesmo jeito.
