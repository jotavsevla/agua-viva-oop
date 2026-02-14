# Roadmap — Agua Viva OOP

Plano de desenvolvimento em 10 fases.
Fases 1-6 concluidas, 7 avancada, 8 parcial, 9 pendente e 10 iniciada (API base).

---

## Dependencias Criticas (estado atual)

Para executar com consistencia o que ja foi entregue:

- Migracoes `001` a `011` aplicadas em dev e test
- `postgres-oop-test` ativo na porta `5435` para suites Java de integracao
- Solver Python acessivel em `SOLVER_URL` para execucao da API de roteirizacao
- `.env` com credenciais e portas alinhadas (`POSTGRES_*`, `SOLVER_URL`, `API_PORT`)
- `contracts/v1` congelado como baseline de handoff A->B (`openapi.yaml`, `events/`, `examples/`)

---

## Fases Concluidas

### Fase 1 — Fundacao

- [x] pom.xml com Java 21 + JUnit 5
- [x] Docker Compose (PostgreSQL dev + test)
- [x] 11 migrations SQL
- [x] ConnectionFactory (HikariCP)
- [x] App.java (entrypoint + modo API)
- [x] apply-migrations.sh

### Fase 2 — Domain: User

- [x] Password (Value Object — hash, compare, validate)
- [x] User (Entidade com comportamento)
- [x] UserPapel (Enum com hierarquia)
- [x] 45 testes unitarios (PasswordTest + UserTest)

### Fase 3 — Repository: UserRepository

- [x] UserRepository (CRUD JDBC puro)
- [x] 21 testes de integracao (banco real)
- [x] Soft delete (desativar)
- [x] Constraints de email unico

### Fase 4 — Solver Python

- [x] FastAPI (POST /solve, POST /solve/async, POST /cancel/{job_id}, GET /result/{job_id}, POST /map, GET /demo)
- [x] OR-Tools CVRPTW (capacidade + time windows + multiplas viagens)
- [x] OSRM (distancias reais por vias)
- [x] Nominatim (geocoding)
- [x] Folium (visualizacao)
- [x] 27 testes pytest

### Fase 5 — Integracao Java-Solver

- [x] SolverClient (HTTP client)
- [x] SolverRequest / SolverResponse (DTOs imutaveis)
- [x] SolverAsyncAccepted / SolverJobResult (contrato assincrono)
- [x] Coordenada, PedidoSolver, Parada, RotaSolver
- [x] Gson com snake_case
- [x] 12 testes (serializacao/deserializacao + metadados async)

### Fase 6 — Repository: Cliente + Pedido

Objetivo: persistencia de clientes e pedidos via JDBC.

- [x] `Cliente.java` (entidade de dominio)
- [x] `ClienteRepository.java` (CRUD)
- [x] `Pedido.java` (entidade com invariantes de janela/status)
- [x] `PedidoRepository.java` (CRUD)
- [x] `ClienteTipo`, `PedidoStatus`, `JanelaTipo` (enums de dominio)
- [x] Testes unitarios (`ClienteTest` + `PedidoTest`)
- [x] Testes de integracao (`ClienteRepositoryTest` + `PedidoRepositoryTest`)

**Dependencias atendidas:** migration 003 (clientes) e 005 (pedidos).

---

## Fases em Andamento

### Fase 7 — Service: Orquestracao de Operacao

Objetivo: orquestrar domain + repository + solver.

- [x] `RotaService.java` — fluxo principal:
  1. Busca pedidos pendentes
  2. Busca entregadores ativos
  3. Monta SolverRequest
  4. Chama solver (POST /solve)
  5. Persiste rotas e entregas no banco
- [x] Gerenciamento de transacoes JDBC
- [x] Testes de integracao (`RotaServiceTest` com solver stub HTTP + banco real)
- [x] Cobrir cenarios de rollback/erro (falha ao persistir entrega e indisponibilidade do solver)
- [x] Cobrir idempotencia/reprocessamento de roteirizacao (sem duplicar entrega/rota em retries)
- [x] Incluir `plan_version`/`job_id` para evitar aplicacao de plano obsoleto e habilitar cancelamento cooperativo
- [x] Reaproveitar slots de entregas canceladas/falhas antes de chamar solver (insercao local)
- [x] Ordenacao de elegiveis por FIFO com filtro operacional de capacidade/saldo
- [x] `AtendimentoTelefonicoService` com idempotencia por `external_call_id`
- [x] `ExecucaoEntregaService` para eventos operacionais (`ROTA_INICIADA`, `PEDIDO_ENTREGUE`, `PEDIDO_FALHOU`, `PEDIDO_CANCELADO`)
- [x] `ReplanejamentoWorkerService` com debounce e lock distribuido (`pg_try_advisory_xact_lock`)
- [x] Lock distribuido no `RotaService` para impedir planejamento concorrente entre instancias
- [x] Testes de alta disputa no `RotaService` para evitar duplicacao em chamadas concorrentes multi-instancia
- [x] API HTTP minima (`ApiServer`) para atendimento, eventos e disparo de replanejamento
- [ ] Endurecer concorrencia para multiplas instancias (cenarios de alta disputa, retries simultaneos e fairness)

**Dependencias:** Fase 6 (repositorios de cliente/pedido).

---

## Fases Pendentes

### Fase 8 — Maquina de Estados do Pedido (parcial)

Objetivo: controlar transicoes de status do pedido.

```
PENDENTE → CONFIRMADO → EM_ROTA → ENTREGUE
                                 → CANCELADO
```

- [x] Transicoes validas encapsuladas no dominio (`PedidoStateMachine`)
- [x] Validacao: transicao invalida lanca excecao
- [x] Testes unitarios para transicoes e regra de cobranca (`PedidoStateMachineTest`)
- [x] Integracao obrigatoria via `PedidoLifecycleService` (lock pessimista + transicao centralizada)
- [x] Uso do lifecycle no `RotaService` e no fluxo de eventos operacionais
- [ ] Registro de timestamp por transicao e trilha de auditoria detalhada de status
- [ ] Politica completa de compensacao financeira para cancelamentos tardios integrada com vales

**Regras:**
- PENDENTE → CONFIRMADO: pedido aceito
- CONFIRMADO → EM_ROTA: atribuido a uma rota
- EM_ROTA → ENTREGUE: entrega concluida
- EM_ROTA → CANCELADO: falha na entrega
- PENDENTE → CANCELADO: cancelamento antes de confirmar

### Fase 9 — Sistema de Vales

Objetivo: credito pre-pago (vale-agua).

- [ ] `ValeService.java` — operacoes atomicas:
  - Creditar vales (compra)
  - Debitar vales (entrega realizada)
  - Consultar saldo
  - Extrato de movimentacoes
- [ ] Debito atomico: `UPDATE saldo_vales SET quantidade = quantidade - ? WHERE quantidade >= ?`
- [ ] Integracao com pedido: debita ao confirmar entrega
- [ ] Testes com cenarios de concorrencia

**Tabelas ja existem:** `saldo_vales` + `movimentacao_vales` (migration 004).

### Fase 10 — Frontend: Leaflet.js

Objetivo: visualizacao de rotas em mapa interativo.

- [x] Base de API HTTP para alimentar UI e app operacional
- [x] Endpoint HTTP `GET /api/pedidos/{pedidoId}/timeline` (A2 inicial para handoff com Time B)
- [ ] Mapa com rotas (Leaflet.js)
- [ ] Marcadores de clientes com informacoes
- [ ] Rotas coloridas por entregador
- [ ] Painel de status das entregas
- [ ] Integracao de UI com API Java e fluxo de eventos em tempo quase real

---

## Resumo de Progresso

```
Fase 1  ██████████ Fundacao
Fase 2  ██████████ Domain User
Fase 3  ██████████ Repository User
Fase 4  ██████████ Solver Python
Fase 5  ██████████ Integracao Java-Solver
Fase 6  ██████████ Repository Cliente + Pedido
Fase 7  █████████░ Service Orquestracao
Fase 8  ██████░░░░ Maquina de Estados
Fase 9  ░░░░░░░░░░ Sistema de Vales
Fase 10 ██░░░░░░░░ Frontend/API Produto
```

**Status:** 6/10 fases concluidas + Fase 7 avancada + Fase 8 parcial + Fase 10 iniciada — 211 testes mapeados (184 Java + 27 Python).
