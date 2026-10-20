# ADR 005: ERP simulado em serviço separado

**Status:** aceita (2026-10-20)

## Contexto

Os comportamentos que importam só aparecem quando o ERP falha: lentidão, 503 intermitente, 422 por dado inválido, queda total e, principalmente, a resposta que se perde depois de a fatura já ter sido gravada. Um ERP real não falha quando se pede, e usar um exigiria credenciais e ambiente de homologação.

## Decisão

`erp-mock` é uma aplicação Spring Boot separada, com:

- a API do contrato (`POST /api/invoices` com `Idempotency-Key`, `GET /api/invoices`, `GET /api/customers`, `GET /api/locations`), documentada em `static/openapi.yaml`;
- modos de falha por endpoint via `PUT /admin/faults/{endpoint}`: `latencyMs`, `errorRate` (503), `permanentErrorRate` (422, só em escrita), `failAfterCommitRate` e `down`;
- seed de clientes, locais e uma divergência plantada para a reconciliação;
- dados em memória.

O `openapi.yaml` é a fonte do contrato dos dois lados: os testes do `erp-mock` validam as respostas dele contra o arquivo, e os testes do `bridge-sync` validam contra o mesmo arquivo as requisições que a ponte envia, com WireMock no lugar do servidor.

## Consequências

- Os testes ponta a ponta e o teste de caos sobem o `erp-mock` de verdade na mesma JVM e o derrubam pela API de admin.
- Como os dados ficam em memória, reiniciar o container apaga as faturas. "ERP fora" na demo é o modo `down`, não `docker compose stop`.
- Trocar por um ERP real (Protheus, SAP) é trocar o `ErpClient` e o contrato. A decisão está registrada na seção 0 do PRD como fora da v1.
