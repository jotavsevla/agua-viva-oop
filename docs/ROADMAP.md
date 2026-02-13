# Roadmap — Agua Viva OOP

Plano de desenvolvimento em 10 fases.
Fases 1-6 concluidas, 7 em andamento, 8-10 pendentes.

---

## Fases Concluidas

### Fase 1 — Fundacao

- [x] pom.xml com Java 21 + JUnit 5
- [x] Docker Compose (PostgreSQL dev + test)
- [x] 8 migrations SQL
- [x] ConnectionFactory (HikariCP)
- [x] App.java (health check)
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

- [x] FastAPI (POST /solve, POST /map, GET /demo)
- [x] OR-Tools CVRPTW (capacidade + time windows + multiplas viagens)
- [x] OSRM (distancias reais por vias)
- [x] Nominatim (geocoding)
- [x] Folium (visualizacao)
- [x] 24 testes pytest

### Fase 5 — Integracao Java-Solver

- [x] SolverClient (HTTP client)
- [x] SolverRequest / SolverResponse (DTOs imutaveis)
- [x] Coordenada, PedidoSolver, Parada, RotaSolver
- [x] Gson com snake_case
- [x] 10 testes (serializacao/deserializacao)

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

## Fase em Andamento

### Fase 7 — Service: RotaService

Objetivo: orquestrar domain + repository + solver.

- [x] `RotaService.java` — fluxo principal:
  1. Busca pedidos pendentes
  2. Busca entregadores ativos
  3. Monta SolverRequest
  4. Chama solver (POST /solve)
  5. Persiste rotas e entregas no banco
- [x] Gerenciamento de transacoes JDBC
- [x] Testes de integracao (`RotaServiceTest` com solver stub HTTP + banco real)
- [ ] Cobrir cenarios de rollback/erro (falha ao persistir entrega, duplicidade de rota, indisponibilidade do solver)

**Dependencias:** Fase 6 (repositorios de cliente/pedido).

---

## Fases Pendentes

### Fase 8 — Maquina de Estados do Pedido

Objetivo: controlar transicoes de status do pedido.

```
PENDENTE → CONFIRMADO → EM_ROTA → ENTREGUE
                                 → CANCELADO
```

- [ ] Transicoes validas encapsuladas no dominio
- [ ] Validacao: transicao invalida lanca excecao
- [ ] Registro de timestamp em cada transicao
- [ ] Testes unitarios para todas as transicoes

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

- [ ] Mapa com rotas (Leaflet.js)
- [ ] Marcadores de clientes com informacoes
- [ ] Rotas coloridas por entregador
- [ ] Painel de status das entregas
- [ ] Integracao com API Java (a definir: servlets, Javalin, etc.)

---

## Resumo de Progresso

```
Fase 1  ██████████ Fundacao
Fase 2  ██████████ Domain User
Fase 3  ██████████ Repository User
Fase 4  ██████████ Solver Python
Fase 5  ██████████ Integracao Java-Solver
Fase 6  ██████████ Repository Cliente + Pedido
Fase 7  ██████░░░░ Service RotaService
Fase 8  ░░░░░░░░░░ Maquina de Estados
Fase 9  ░░░░░░░░░░ Sistema de Vales
Fase 10 ░░░░░░░░░░ Frontend Leaflet.js
```

**Status:** 6/10 fases concluidas + Fase 7 em andamento — 161 testes mapeados (137 Java + 24 Python).
