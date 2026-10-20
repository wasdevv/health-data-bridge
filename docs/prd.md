# PRD - Health Data Bridge

## FHIR R4 + Integração com ERP | Java 21 + Spring Boot + Maven

---

## 0. Decisões confirmadas

Pendente. As quatro perguntas da seção 13 são respondidas aqui antes da Fase 0, e esta seção prevalece sobre o resto do documento.

---

## 1. Visão geral

**Problema:** hospitais e operadoras trocam dados de atendimento (pacientes, encontros, faturamento) em padrões de saúde como FHIR, mas o financeiro e o estoque rodam em ERPs que não falam FHIR. Quem faz a ponte perde dados, duplica lançamentos ou ficou sem saber o que falhou.

**Solução:** um serviço que expõe uma API FHIR R4 para os dados clínicos e administrativos, e sincroniza com um ERP de forma confiável. Encontros finalizados geram faturas no ERP. Cadastros do ERP (fornecedores, locais, profissionais) alimentam o lado FHIR. Toda operação é idempotente, auditável e recuperável de falhas.

**Objetivo do portfólio:** demonstrar FHIR (recursos, buscas, Bundles, validação de perfil), integração com ERP com padrões de produção (outbox, idempotência, circuit breaker, dead letter), e testes de contrato contra o ERP.

---

## 2. Stack

| Camada | Escolha | Motivo |
|---|---|---|
| Linguagem | Java 21 | Mesma base do Knowledge Hub |
| Build | Maven (multi-módulo) | Mesma base do Knowledge Hub |
| Framework | Spring Boot 3.3 | Padrão de mercado |
| FHIR | **HAPI FHIR** (R4): `hapi-fhir-structures-r4`, `hapi-fhir-validation`, `hapi-fhir-jpaserver-base` | Biblioteca de referência em Java para FHIR |
| Persistência | PostgreSQL 16 + Spring Data JPA + Flyway | Outbox e DLQ no mesmo banco |
| Resiliência | Resilience4j (circuit breaker, retry, rate limiter) | Padrão para chamadas a sistemas instáveis |
| Mapeamento | MapStruct | Conversão FHIR ↔ ERP tipada, sem reflexão |
| Testes | JUnit 5, Mockito, AssertJ, Testcontainers (Postgres), WireMock (contrato com ERP) | Requisito de mercado |
| ERP simulado | Serviço Spring separado (`erp-mock`) com API REST e injeção de falhas | Permite testar cenários de erro |
| Infra local | Docker Compose (postgres, erp-mock, bridge) | Um comando sobe o ambiente |
| CI | GitHub Actions com `mvn verify` | Pipeline padrão |

---

## 3. Arquitetura

```
health-data-bridge/                 (pom.xml pai)
├── bridge-fhir/        # HAPI FHIR server, recursos, search params, validação de perfil
├── bridge-core/        # domínio: regras de faturamento, mapeamento FHIR ↔ ERP
├── bridge-sync/        # outbox, worker, idempotência, circuit breaker, DLQ, reconciliação
├── bridge-app/         # Spring Boot app, configuração, Actuator
├── erp-mock/           # ERP simulado: clientes, itens, faturas, lançamentos, falhas injetáveis
└── bridge-test-support/# Testcontainers, fixtures FHIR, WireMock stubs
```

### 3.1 Fluxo principal: encontro finalizado vira fatura no ERP

```
1. Sistema clínico cria Encounter (status=finished) via FHIR
   PUT /fhir/Encounter/{id}
2. Na mesma transação, bridge grava linha na tabela outbox
   (tipo=CREATE_INVOICE, payload=referência ao Encounter, status=PENDING)
3. Worker lê outbox PENDING em lotes (SELECT ... FOR UPDATE SKIP LOCKED)
4. Para cada item:
   a. busca Encounter, Patient, Coverage e Claim relacionados
   b. valida o recurso contra o perfil esperado (HAPI validator)
   c. mapeia para o modelo do ERP
   d. envia ao ERP com header Idempotency-Key = id do outbox
   e. sucesso → status=DONE; falha transitória → retry com backoff;
      falha permanente ou esgotou tentativas → status=DEAD (DLQ)
5. Circuit breaker abre se o ERP falhar em série; o worker pausa e
   o outbox acumula sem perder nada
```

### 3.2 Fluxo reverso: cadastros do ERP alimentam o FHIR

- Job agendado (a cada 15 min) consulta o ERP por clientes e locais alterados desde o último cursor.
- Converte para `Organization` e `Location` FHIR e grava com `PUT` condicional (`If-Match` por identificador), evitando duplicatas.
- Guarda o cursor por entidade, para retomar de onde parou.

### 3.3 Reconciliação

Job diário compara, por dia, o número de Encounters finalizados no FHIR com as faturas criadas no ERP. Diferenças geram registro em tabela `reconciliation_issue` com o id do recurso e o motivo. Esse relatório é a prova de que nada se perdeu.

---

## 4. Requisitos funcionais

### 4.1 FHIR (`bridge-fhir`)

- Recursos: `Patient`, `Encounter`, `Coverage`, `Claim`, `Condition` (diagnóstico com ICD-10), `Organization`, `Location`, `Practitioner`.
- `CapabilityStatement` publicado em `/fhir/metadata`, listando recursos e operações suportadas.
- Buscas: `Patient?identifier=`, `Encounter?patient=&date=`, `Claim?status=`, com paginação `_count` e links `next`.
- Transações via `Bundle` (tipo `transaction`), atômicas: se uma entrada falha, nenhuma é gravada.
- Validação: todo recurso recebido é validado com o validador do HAPI. Recurso inválido retorna `OperationOutcome` com as mensagens, e não é gravado.
- Recurso de exemplo para o perfil de faturamento (`StructureDefinition` própria, simples, documentada), para mostrar que você sabe criar perfil, não só consumir.

### 4.2 Integração (`bridge-sync`)

- Outbox transacional, com índice em `(status, next_attempt_at)`.
- Worker com lote configurável, concorrência limitada e `SKIP LOCKED` para permitir mais de uma instância.
- Idempotência em duas camadas: chave única no outbox e `Idempotency-Key` no ERP. O ERP deduplica pelo header.
- Retry com backoff exponencial e jitter, limitado a N tentativas.
- Circuit breaker com Resilience4j: abre após 50% de falhas em janela de 20 chamadas, fica meio aberto por 30s.
- DLQ: tabela `outbox` com status `DEAD`, endpoint admin para listar e reprocessar (`POST /admin/outbox/{id}/retry`).
- Métricas: tamanho do outbox pendente, idade da mensagem mais antiga, taxa de DLQ.

### 4.3 ERP mock (`erp-mock`)

- API REST: `POST /api/invoices` (aceita `Idempotency-Key` e responde a repetição com o mesmo resultado), `GET /api/customers?updatedSince=`, `GET /api/locations`.
- Modos de falha configuráveis por endpoint: latência, erro 503 intermitente, erro 422 permanente (dados inválidos).
- Documentação do contrato em OpenAPI, que é a fonte usada nos testes de contrato.

---

## 5. Requisitos não funcionais

- **Confiabilidade:** nenhum evento de encontro finalizado pode ser perdido. Provado por teste: derrubar o ERP durante 1000 eventos e verificar que todos chegam depois da recuperação, sem duplicata.
- **Testes unitários (JUnit + Mockito):** mapeadores, política de retry, decisão do circuit breaker (com `Clock` injetado), validação de perfil.
- **Testes de integração (Testcontainers):** outbox com concorrência real (duas threads lendo o mesmo lote), transações do Bundle, buscas FHIR.
- **Testes de contrato:** WireMock com as respostas do OpenAPI do ERP; teste que falha se o mapeador mudar o formato enviado.
- **Testes FHIR:** recurso inválido é rejeitado; recurso válido passa; `CapabilityStatement` lista os recursos esperados.
- **Cobertura:** JaCoCo. Meta de 75% em `bridge-core` e `bridge-sync`. Número real só depois de `mvn verify`.
- **Segurança:** dados de paciente não aparecem nos logs (teste que procura CPF e nome no output). Autenticação fora da v1, documentada, com nota sobre SMART on FHIR como próximo passo.
- **Conformidade:** o projeto é de demonstração, com dados fictícios. Documentar isso no README e não usar dado real de paciente em nenhum fixture.

---

## 6. Estrutura de decisões (ADRs a escrever)

1. **HAPI FHIR em vez de implementar FHIR do zero:** é a biblioteca usada na prática; o valor está em modelar o perfil e as regras, não em serializar JSON.
2. **Outbox em vez de chamada direta ao ERP na requisição:** a requisição do FHIR não depende da disponibilidade do ERP. Trade-off: entrega é eventual, e isso precisa ser explicado ao usuário.
3. **Idempotência nas duas pontas:** o banco evita enfileirar o mesmo evento duas vezes, e o ERP evita lançar a mesma fatura duas vezes.
4. **Reconciliação diária além do retry:** retry cobre falha de rede, mas não cobre bug de mapeamento. Só a reconciliação pega isso.
5. **ERP simulado em serviço separado:** permite falhar o ERP de propósito nos testes e na demo, sem depender de um sistema real.

---

## 7. Trilha paralela: GeneXus (fora deste repositório)

GeneXus é uma ferramenta proprietária de low-code. Não dá pra simular isso com código Java: a vaga pede o domínio da ferramenta, e isso só se constrói usando a ferramenta. Por isso a trilha é separada:

- Verificar no site oficial da GeneXus se há versão de avaliação ou de estudo disponível, e as condições de uso. Não assumir que é gratuita sem checar.
- Construir uma aplicação pequena (ex: cadastro de pacientes com consulta de encontros) na ferramenta, e documentar o que foi feito, o que é diferente de Java e onde a abordagem low-code ajuda ou atrapalha.
- Resultado: uma pasta `genexus/` com a aplicação exportada e um README curto. Isso mostra experiência real, não conhecimento de leitura.
- Essa trilha tem uma curva própria e pode levar mais tempo que o projeto Java. Por isso não entra na estimativa abaixo.

---

## 8. Dados de demonstração

- 20 pacientes fictícios com CPF gerado para teste (dígitos verificadores válidos, sem correspondência a pessoas reais).
- 50 encontros finalizados com diagnósticos ICD-10 de exemplo.
- Coberturas e faturas de exemplo.
- Seed carregado pelo Compose.

---

## 9. Fases e entregas

| Fase | Entrega | Estimativa |
|---|---|---|
| 0 | Multi-módulo Maven, Compose com postgres e erp-mock, CI com teste trivial | 1.5h |
| 1 | `bridge-core`: mapeadores e regras de faturamento com testes unitários | 2.5h |
| 2 | `bridge-fhir`: recursos, `CapabilityStatement`, buscas, validação, perfil próprio | 4h |
| 3 | `erp-mock` com OpenAPI e modos de falha | 2h |
| 4 | `bridge-sync`: outbox, worker com `SKIP LOCKED`, retry, circuit breaker, DLQ | 4h |
| 5 | Fluxo reverso (ERP → FHIR) com cursor e `PUT` condicional | 2h |
| 6 | Reconciliação diária e tabela de divergências | 1.5h |
| 7 | Testes: Testcontainers (concorrência), WireMock (contrato), teste de caos (ERP fora) | 3h |
| 8 | README, ADRs, diagramas, CI verde | 1.5h |

**Total estimado:** cerca de 22h para o projeto Java. A trilha GeneXus é separada.

---

## 10. Critérios de aceite

- [ ] `docker compose up` sobe tudo com dados de seed.
- [ ] `GET /fhir/metadata` retorna um `CapabilityStatement` válido.
- [ ] Recurso FHIR inválido é rejeitado com `OperationOutcome` explicando o erro.
- [ ] Encontro finalizado gera exatamente uma fatura no ERP.
- [ ] Com o ERP fora por alguns minutos, os eventos ficam no outbox e chegam depois, sem duplicata.
- [ ] Evento que falha de forma permanente aparece na DLQ e pode ser reprocessado pelo endpoint admin.
- [ ] Teste de caos (1000 eventos com ERP instável) termina com zero perdas e zero duplicatas.
- [ ] Reconciliação identifica uma divergência plantada de propósito nos dados.
- [ ] Logs não contêm CPF nem nome de paciente.
- [ ] `mvn verify` passa no CI.

---

## 11. Fora de escopo (v1)

- Autenticação SMART on FHIR e OAuth2 de produção.
- Integração com dados reais de qualquer sistema de saúde.
- Padrões específicos do Brasil como RNDS e TISS. Ficam como próximo passo, com nota de que são padrões relevantes para o mercado nacional.
- Integração com um ERP real. O `erp-mock` simula o comportamento. Se quiser algo mais próximo do mercado brasileiro, dá pra trocar por um contrato de API do Protheus ou SAP, mas isso exige ler documentação específica e fica como decisão a confirmar.

---

## 12. Narrativa para entrevista

> "Construí uma ponte entre um sistema de saúde com API FHIR R4 e um ERP. Quando um encontro é finalizado, a gravação no FHIR e o registro no outbox acontecem na mesma transação. Um worker lê o outbox com SKIP LOCKED, valida o recurso contra o perfil, envia ao ERP com Idempotency-Key e aplica retry com backoff e circuit breaker. Falhas permanentes vão para uma DLQ com reprocessamento. Como garantia extra, um job diário reconcilia encontros com faturas. Testei a entrega com um teste de caos: derrubei o ERP durante a carga e verifiquei zero perdas e zero duplicatas."

---

## 13. Decisões a confirmar antes de codar

1. **ERP alvo:** `erp-mock` genérico (recomendado para começar) ou contrato específico de um ERP brasileiro?
2. **Servidor FHIR:** HAPI JPA Server completo (mais pesado, mais realista) ou fachada própria sobre HAPI (mais leve)?
3. **Escopo:** projeto Java completo (22h) ou corte mínimo (fases 0, 1, 2, 4 e 7)?
4. **Trilha GeneXus:** começar agora em paralelo, ou depois de fechar o projeto Java?
