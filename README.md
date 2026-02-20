# Agua Viva OOP — Java Puro + PostgreSQL

Sistema de gestao e otimizacao de rotas para distribuidora de agua mineral.
Reimplementacao OOP do [Agua Viva VRP](https://github.com/jotavsevla/aguaVIVA) (Next.js) — mesmo dominio, paradigma diferente.

O objetivo e estudar e aplicar **Orientacao a Objetos verdadeira** em Java,
com **PostgreSQL isolado em Docker** (SQL puro, sem ORM) e um **solver Python**
que resolve roteamento de veiculos com restricoes reais.

## Escopo Publico

Este `README.md` e a referencia publica para terceiros.
Ele resume contexto, arquitetura, contratos e como executar o projeto.
Guia operacional detalhado, testes por tipo, padroes de docs e estilo ficam em `docs/`.
Politica completa de publico x interno: `docs/DOCUMENTACAO-PADROES.md`.

## Mapa de documentacao interna (temas exclusivos)

Documentos abaixo sao internos (uso local da equipe) e seguem padrao tematico.

- Padroes de documentacao: `docs/DOCUMENTACAO-PADROES.md`
- Status tecnico interno: `docs/ESTADO-ATUAL.md`
- Gate operacional PoC: `docs/GATE_POC_OPERACIONAL.md`
- Runbook do ambiente de teste: `docs/RUNBOOK_OPERACAO_TESTE.md`
- Testes Java: `docs/TESTES-JAVA.md`
- Testes Solver (Python): `docs/TESTES-SOLVER-PYTHON.md`
- Testes UI unitarios (JS): `docs/TESTES-UI-JS.md`
- Testes E2E (Playwright): `docs/TESTES-E2E-PLAYWRIGHT.md`
- Estilo e formatacao (`spotless:check`): `docs/ESTILO-E-FORMATACAO.md`
- Versionamento, branch, commit e PR: `GIT-E-PADROES.md`

## Memoria Tecnica Recente (PoC Operacional)

Contexto consolidado desta rodada (M1/M2), com foco em robustez operacional e visibilidade real de frota.

### Questoes sanadas de regra de negocio

1. Evento terminal (`PEDIDO_ENTREGUE`, `PEDIDO_FALHOU`, `PEDIDO_CANCELADO`) so entra quando a entrega esta em `EM_EXECUCAO`; fora disso retorna `409` (bloqueio de transicao invalida).
2. Repeticao de evento operacional com mesma chave (`externalEventId`) e mesmo payload nao duplica efeito; resposta volta `200` com marcacao idempotente.
3. Reuso da mesma chave com payload diferente e bloqueado com `409`, impedindo divergencia logica para o mesmo evento externo.
4. Fluxo operacional no prototipo roda sem fallback silencioso para mock; o estado visivel vem do banco real de teste.
5. Leitura operacional para despacho consolidada com endpoints reais (`/api/operacao/painel`, `/api/operacao/eventos`, `/api/operacao/mapa`) para evidenciar fila `PENDENTE/CONFIRMADO/EM_ROTA` e rotas de trabalho.

### Questoes tecnicas sanadas que afetavam a regra

1. `ConnectionFactory` passou a resolver config por prioridade `runtime env > .env > default`, eliminando mismatch entre ambiente `dev` e `test`.
2. `scripts/poc/start-test-env.sh` passou a subir API/UI com `nohup`, evitando queda de processo apos encerramento do shell.
3. Incidente recorrente de "API offline" com dados inconsistentes foi rastreado para desalinhamento de ambiente e esta mitigado no fluxo padrao de teste.

Validacao rapida recomendada:

```bash
scripts/poc/start-test-env.sh
curl -sS http://localhost:8082/api/operacao/painel | jq '.ambiente'
curl -sS http://localhost:8082/api/operacao/mapa | jq '{rotas: (.rotas | length), deposito: .deposito}'
```

Esperado: `ambiente = "test"` e retorno de mapa sem fallback local.

### Gate Oficial de Negocio (PoC)

Fluxo oficial de aceite operacional:

```bash
# 1 rodada strict (padrao de trabalho diario)
scripts/poc/run-business-gate.sh

# gate forte 3x (PR/nightly)
scripts/poc/run-business-gate.sh --mode strict --rounds 3
```

Regras do gate oficial:

1. Nao aceita verde parcial por etapa nao executada.
2. Em `strict`, etapas obrigatorias do E2E (Playwright + suite + promocoes) sao mandatórias.
3. Evidencias sao consolidadas em `artifacts/poc/business-gate-<timestamp>/business-summary.json`.

### Demo Guiada Unica (M3-I0)

Roteiro unico para demonstracao executiva do loop operacional, com pacote fechado de evidencias por cenario:

```bash
scripts/poc/run-demo-m3-i0.sh
```

Saidas principais:

1. `artifacts/poc/demo-m3-i0-<timestamp>/demo-summary.json`
2. `artifacts/poc/demo-m3-i0-<timestamp>/demo-summary.txt`

---

## Por que este projeto existe

O projeto Next.js resolve o mesmo problema, mas com paradigma funcional/procedural.
Este repositorio reimplementa tudo em Java para explorar o que muda quando
se leva OOP a serio: objetos com comportamento, imutabilidade, Value Objects,
entidades com identidade, separacao rigida de camadas e TDD como disciplina.

**Nao e um port.** E a mesma distribuidora, o mesmo schema de banco, mas com
decisoes de design que so fazem sentido em OOP.

---

## Filosofia

### TDD — Test-Driven Development

Todo codigo de producao foi escrito **depois** do teste que o exige.
O ciclo e sempre o mesmo:

1. **Red** — escreve o teste, roda, ve falhar
2. **Green** — implementa o minimo para passar
3. **Refactor** — limpa sem quebrar testes

Isso vale para domain, repository, service e solver. Nenhuma classe existe sem teste.

Suite de testes mapeada em Java/Python/JS/Playwright, com TDD estrito e sem mocks. Os testes nao sao formais —
eles documentam o comportamento esperado do sistema.

### Objetos com comportamento, nao DTOs

Classes de dominio nao sao sacos de getters/setters. `User` sabe verificar senha
e validar hierarquia. `Password` sabe fazer hash e comparar. O construtor rejeita
estado invalido — um objeto invalido **nunca existe**.

```java
// Password — Value Object imutavel
public final class Password {
    private final String hash;

    public static Password fromPlainText(String plainText) {
        validate(plainText);  // fail-fast: senha fraca nao gera hash
        return new Password(BCrypt.hashpw(plainText, BCrypt.gensalt(12)));
    }

    public boolean matches(String plainText) {
        return BCrypt.checkpw(plainText, this.hash);
    }

    @Override
    public String toString() {
        return "Password[****]";  // nunca expoe o hash
    }
}
```

```java
// User — Entidade com comportamento
public final class User {
    public User(String nome, String email, Password password, UserPapel papel) {
        validarNome(nome);       // null, vazio, espacos → IllegalArgumentException
        validarEmail(email);     // null, sem @ → IllegalArgumentException
        Objects.requireNonNull(password);
        Objects.requireNonNull(papel);
        // ... se chegou aqui, o objeto e valido
    }

    public boolean podeGerenciar(User outro) {
        return this.papel.podeGerenciar(outro.papel);
    }

    public boolean verificarSenha(String senhaPlana) {
        return this.password.matches(senhaPlana);
    }

    // Identidade baseada em ID (entidade), nao em valores
    @Override
    public boolean equals(Object o) {
        if (this.id == 0 || ((User) o).id == 0) return false;
        return this.id == ((User) o).id;
    }
}
```

### Sem framework, sem ORM

Nao usa Spring Boot, Hibernate, JPA, Lombok nem geradores de codigo.
Cada camada e construida manualmente para entender o que acontece por baixo.

Repository usa JDBC puro com `PreparedStatement` — queries parametrizadas,
mapeamento manual de `ResultSet`, constraints do banco traduzidas para excecoes de dominio:

```java
public User save(User user) throws SQLException {
    try (Connection conn = connectionFactory.getConnection();
         PreparedStatement stmt = conn.prepareStatement(sql, RETURN_GENERATED_KEYS)) {
        stmt.setString(1, user.getNome());
        stmt.setString(2, user.getEmail());
        stmt.setString(3, user.toSenhaHash());
        // ...
    } catch (SQLException e) {
        if ("23505".equals(e.getSQLState())) {  // unique violation
            throw new IllegalArgumentException("Email ja cadastrado: " + user.getEmail());
        }
        throw e;
    }
}
```

### Sem mocking

Os testes **nao usam Mockito**. Testes unitarios testam objetos puros (sem dependencias).
Testes de integracao usam um PostgreSQL real rodando em Docker com tmpfs (dados em memoria).

Limpeza entre testes e explicita:

```java
@BeforeEach
void limparTabela() throws Exception {
    try (Connection conn = factory.getConnection();
         Statement stmt = conn.createStatement()) {
        stmt.execute("DELETE FROM users");
        stmt.execute("ALTER SEQUENCE users_id_seq RESTART WITH 1");
    }
}
```

### SQL como cidadao de primeira classe

PostgreSQL nao e um detalhe de implementacao — e parte do design.
O schema usa enums nativos, indices parciais, constraints de negocio,
views com CTEs, window functions e funcoes SQL:

```sql
-- Indice parcial: so entregadores ativos (evita full scan)
CREATE INDEX idx_users_entregadores_ativos ON users(papel, ativo)
    WHERE papel = 'entregador' AND ativo = true;

-- Constraint: pedido HARD exige janela de tempo completa
CONSTRAINT chk_janela_hard_completa CHECK (
    janela_tipo = 'ASAP'
    OR (janela_inicio IS NOT NULL AND janela_fim IS NOT NULL)
);

-- View com CTE: relatorio de performance do entregador
CREATE OR REPLACE VIEW vw_relatorio_entregador AS
WITH entregas_periodo AS ( ... ),
     metricas_brutas AS ( ... )
SELECT ..., COUNT(*) FILTER (WHERE status = 'ENTREGUE') AS entregas_sucesso,
       ROUND(100.0 * mb.entregas_sucesso / NULLIF(mb.total_entregas, 0), 1) AS taxa_sucesso_pct
FROM metricas_brutas mb JOIN users u ON u.id = mb.entregador_id;
```

---

## Stack

| Camada    | Tecnologia        | Motivo                           |
| --------- | ----------------- | -------------------------------- |
| Linguagem | Java 21 (LTS)     | OOP pura, tipos fortes           |
| Build     | Maven             | Padrao da industria              |
| SGBD      | PostgreSQL 16     | SQL puro, views, CTEs, functions |
| Driver    | JDBC (postgresql) | Queries parametrizadas diretas   |
| Pool      | HikariCP          | Pool de conexoes leve            |
| Hash      | BCrypt (jBCrypt)  | Hash de senhas com salt          |
| JSON      | Gson              | Comunicacao com solver Python    |
| Config    | dotenv-java       | Variaveis de ambiente via .env   |
| Container | Docker Compose    | PostgreSQL + OSRM + Solver       |
| Testes    | JUnit 5           | TDD — teste antes de implementar |

**Nao usa**: ORM (Hibernate/JPA), Spring Boot, Lombok, geradores de codigo, mocks.

---

## Arquitetura

![Diagrama de arquitetura](docs/.assets/arquitetura.svg)

### Camadas com separacao rigida

**Domain** (`domain/`) — Java puro. Zero imports de `java.sql`.
Objetos com comportamento, validacoes no construtor, Value Objects imutaveis.
Nao depende de nenhuma outra camada.

**Repository** (`repository/`) — JDBC puro com `PreparedStatement`.
Converte entre `ResultSet` e objetos de dominio. Sem logica de negocio.
Constraints do banco viram excecoes semanticas (`IllegalArgumentException`).

**Solver** (`solver/`) — Integracao com o solver Python via HTTP + Gson.
Classes imutaveis que espelham o contrato JSON do solver.
Gson com `LOWER_CASE_WITH_UNDERSCORES` converte `capacidadeVeiculo` → `capacidade_veiculo`.

**Service** (`service/`) — Orquestra domain + repository + solver.
Gerencia transacoes e coordena fluxos de negocio:
- `RotaService` com planejamento transacional, `plan_version`, cancelamento best-effort, preempcao distribuida via `solver_jobs` e trilha de `request_payload`/`response_payload`
- `PedidoLifecycleService` como porta unica de transicao de status (state machine + lock pessimista)
- `AtendimentoTelefonicoService` com idempotencia por `external_call_id` e regra de vale no checkout
- `ExecucaoEntregaService` para eventos operacionais (`ROTA_INICIADA`, `PEDIDO_ENTREGUE`, `PEDIDO_FALHOU`, `PEDIDO_CANCELADO`) + debito de vale + regra terminal so em `EM_EXECUCAO`
- `EventoOperacionalIdempotenciaService` com deduplicacao por `external_event_id` + hash de payload
- `ReplanejamentoWorkerService` com debounce e lock via `pg_try_advisory_xact_lock`

**API** (`api/`) — Endpoints HTTP via `com.sun.net.httpserver` (JDK built-in).
`ApiServer` registra rotas, CORS e despacha para services.

Dependencias apontam sempre para o centro: `api → service → repository → domain ← solver`.

---

## Dominio

Distribuidora de agua mineral — clientes, pedidos, rotas de entrega, vales, usuarios.

### Entidades e Value Objects

| Classe    | Tipo         | Descricao                                    |
| --------- | ------------ | -------------------------------------------- |
| User      | Entidade     | Invariantes no construtor, hierarquia papeis, verificacao de senha |
| Password  | Value Object | Hash BCrypt, compare, validate. Imutavel. `toString` nunca expoe hash. |
| UserPapel | Enum         | SUPERVISOR > ADMIN > ATENDENTE > ENTREGADOR (hierarquia com `podeGerenciar`) |

### Hierarquia de papeis

```
SUPERVISOR (nivel 4) → pode gerenciar todos abaixo
ADMIN      (nivel 3) → pode gerenciar ATENDENTE e ENTREGADOR
ATENDENTE  (nivel 2) → pode gerenciar ENTREGADOR
ENTREGADOR (nivel 1) → nao gerencia ninguem
```

Implementado como comportamento no enum, nao como if/else espalhado:

```java
public enum UserPapel {
    ENTREGADOR(1), ATENDENTE(2), ADMIN(3), SUPERVISOR(4);

    private final int nivel;

    public boolean podeGerenciar(UserPapel outro) {
        return this.nivel > outro.nivel;
    }
}
```

---

## Solver Python (segregado)

Microservico **independente** que resolve o problema de roteamento
de veiculos (CVRPTW — Capacitated Vehicle Routing Problem with Time Windows).

Recebe JSON com pedidos, devolve JSON com rotas otimizadas. Stateless.

| Restricao         | Como trata                           |
| ----------------- | ------------------------------------ |
| Capacidade        | Max 5 galoes por viagem              |
| Time windows      | Pedidos HARD com horario obrigatorio |
| Multiplas viagens | Ate 3 viagens por entregador por dia |
| Pedidos inviaveis | Devolvidos em `nao_atendidos`        |

**Comunicacao**: Java monta `SolverRequest` → `POST /solve` → recebe `SolverResponse`.

O OSRM fornece distancias reais por vias (nao linha reta).
O Nominatim converte endereco texto em lat/lon (geocoding).

### Veiculos virtuais

OR-Tools modela multiplas viagens criando veiculos virtuais:
3 entregadores × 3 viagens = 9 veiculos. O solver otimiza todos de uma vez,
e o mapeamento `driver_index = vehicle_id // MAX_TRIPS_PER_DRIVER`
reconstroi quem e quem na resposta.

---

## Banco de Dados

Schema com **15 migrations SQL** — PostgreSQL com enums nativos, indices parciais,
constraints de negocio, views com CTEs, window functions e funcoes SQL.

| Migration | Tabela                | Descricao                                 |
| --------- | --------------------- | ----------------------------------------- |
| 001       | users                 | Usuarios com hierarquia de papeis         |
| 002       | sessions              | Sessoes token-based                       |
| 003       | clientes              | Cadastro PF/PJ com geocoding             |
| 004       | saldo_vales, movimentacao_vales | Sistema de credito pre-pago     |
| 005       | pedidos               | Pedidos com time windows e status         |
| 006       | rotas, entregas       | Rotas otimizadas e atribuicao de pedidos  |
| 007       | configuracoes         | Parametros do sistema (capacidade, horarios) |
| 008       | views, funcoes        | Dashboard entregador, pedidos pro solver, performance, extrato |
| 009       | controles dinamicos   | `plan_version`, `solver_jobs`, cancelamento/cobranca em operacao dinamica |
| 010       | idempotencia telefonica | `pedidos.external_call_id` (UNIQUE) para retries sem duplicacao |
| 011       | outbox de despacho    | `dispatch_events` para coalescencia/debounce de replanejamento |
| 012       | metodo de pagamento   | Enum `pedido_metodo_pagamento`, coluna em `pedidos`, idempotencia de debito de vale |
| 013       | idempotencia de eventos | Tabela `eventos_operacionais_idempotencia` com hash de payload |
| 014       | camada secundaria     | Unicidade de rota `PLANEJADA` por entregador/dia |
| 015       | camada primaria       | Unicidade de rota `EM_ANDAMENTO` por entregador/dia |

### Status do pedido

```
PENDENTE → CONFIRMADO → EM_ROTA → ENTREGUE
                                 → CANCELADO
```

Validado por constraints no banco e por `PedidoStateMachine` no dominio;
a integracao completa desse fluxo no service segue em andamento.

---

## Testes

O projeto usa suites separadas por tipo de teste para evitar mistura de contexto.

- Java (unitario + integracao): `docs/TESTES-JAVA.md`
- Solver Python (pytest): `docs/TESTES-SOLVER-PYTHON.md`
- UI JS unitario (node:test): `docs/TESTES-UI-JS.md`
- E2E Playwright: `docs/TESTES-E2E-PLAYWRIGHT.md`

Comando rapido (rodar Java no projeto raiz):

```bash
mvn test
```

---

## Estrutura do Projeto

```
agua-viva/
├── pom.xml
├── compose.yml                         # PostgreSQL + OSRM + Nominatim + Solver
├── .env.example
├── apply-migrations.sh
├── sql/
│   └── migrations/
│       ├── 001_create_users.sql
│       ├── 002_create_sessions.sql
│       ├── 003_create_clientes.sql
│       ├── 004_create_vales.sql
│       ├── 005_create_pedidos.sql
│       ├── 006_create_rotas_entregas.sql
│       ├── 007_create_configuracoes.sql
│       ├── 008_create_views_cte.sql
│       ├── 009_add_dynamic_dispatch_controls.sql
│       ├── 010_add_external_call_id_to_pedidos.sql
│       ├── 011_create_dispatch_events.sql
│       ├── 012_add_pedido_metodo_pagamento_and_vale_debito_idempotency.sql
│       ├── 013_create_eventos_operacionais_idempotencia.sql
│       ├── 014_add_unique_planejada_por_entregador_dia.sql
│       └── 015_add_unique_em_andamento_por_entregador_dia.sql
├── contracts/
│   └── v1/
│       ├── openapi.yaml                # Contrato OpenAPI congelado (A0)
│       ├── events/catalogo-eventos.json
│       └── examples/*.json
├── src/
│   ├── main/java/com/aguaviva/
│   │   ├── App.java                    # Entry point (health check)
│   │   ├── api/
│   │   │   └── ApiServer.java          # API HTTP nativa (atendimento/eventos + operacao one-click)
│   │   ├── domain/
│   │   │   ├── user/
│   │   │   │   ├── Password.java       # Value Object — hash, compare, validate
│   │   │   │   ├── User.java           # Entidade com comportamento
│   │   │   │   └── UserPapel.java      # Enum com hierarquia de papeis
│   │   │   ├── cliente/
│   │   │   │   ├── Cliente.java        # Entidade com invariantes de cadastro
│   │   │   │   └── ClienteTipo.java    # Enum PF/PJ
│   │   │   └── pedido/
│   │   │       ├── Pedido.java         # Entidade com invariantes de janela/status
│   │   │       ├── PedidoStatus.java   # Enum de status do pedido
│   │   │       ├── JanelaTipo.java     # Enum HARD/ASAP
│   │   │       ├── PedidoStateMachine.java      # Regras de transicao de status
│   │   │       └── PedidoTransitionResult.java  # Resultado de transicao + regra de cobranca
│   │   ├── repository/
│   │   │   ├── ConnectionFactory.java  # Pool JDBC via HikariCP
│   │   │   ├── UserRepository.java     # CRUD de usuarios
│   │   │   ├── ClienteRepository.java  # CRUD de clientes
│   │   │   └── PedidoRepository.java   # CRUD de pedidos
│   │   ├── service/
│   │   │   ├── RotaService.java        # Orquestra solver + persistencia de rotas/entregas
│   │   │   ├── PedidoLifecycleService.java   # Porta unica de transicao de status
│   │   │   ├── AtendimentoTelefonicoService.java # Entrada telefonica idempotente + regra de vale
│   │   │   ├── ExecucaoEntregaService.java   # Eventos operacionais + debito de vale
│   │   │   ├── EventoOperacionalIdempotenciaService.java # Deduplicacao por external_event_id
│   │   │   ├── DispatchEventService.java     # Outbox em `dispatch_events`
│   │   │   ├── ReplanejamentoWorkerService.java # Worker de replanejamento com debounce
│   │   │   ├── OperacaoPainelService.java # Read model operacional para cards/listas
│   │   │   ├── OperacaoEventosService.java # Feed operacional de `dispatch_events`
│   │   │   ├── OperacaoMapaService.java # Read model geografico real para Despacho
│   │   │   └── PlanejamentoResultado.java # Resultado da execucao de roteirizacao
│   │   └── solver/
│   │       ├── SolverClient.java       # HTTP client pro solver Python
│   │       ├── SolverRequest.java      # Request — deposito, pedidos, entregadores
│   │       ├── SolverResponse.java     # Response — rotas otimizadas
│   │       ├── SolverAsyncAccepted.java # Ack do endpoint /solve/async
│   │       ├── SolverJobResult.java     # Resultado consultavel por job_id
│   │       ├── Coordenada.java         # Value Object — lat/lon
│   │       ├── PedidoSolver.java       # Pedido formatado pro solver
│   │       ├── Parada.java             # Parada na rota (ordem, hora prevista)
│   │       └── RotaSolver.java         # Rota com entregador e paradas
│   └── test/java/com/aguaviva/
│       ├── domain/
│       │   ├── user/
│       │   │   ├── PasswordTest.java   # 18 testes unitarios
│       │   │   └── UserTest.java       # 27 testes unitarios
│       │   ├── cliente/
│       │   │   └── ClienteTest.java    # 16 testes unitarios
│       │   └── pedido/
│       │       ├── PedidoTest.java     # 16 testes unitarios
│       │       └── PedidoStateMachineTest.java # 8 testes unitarios
│       ├── repository/
│       │   ├── ConnectionFactoryTest.java
│       │   ├── UserRepositoryTest.java    # 21 testes de integracao
│       │   ├── ClienteRepositoryTest.java # 13 testes de integracao
│       │   └── PedidoRepositoryTest.java  # 12 testes de integracao
│       ├── service/
│       │   ├── RotaServiceTest.java    # 24 testes de integracao (inclui lock distribuido e alta disputa)
│       │   ├── PedidoLifecycleServiceTest.java  # 3 testes
│       │   ├── AtendimentoTelefonicoServiceTest.java  # 11 testes
│       │   ├── ExecucaoEntregaServiceTest.java  # 12 testes
│       │   └── ReplanejamentoWorkerServiceTest.java  # 5 testes
│       ├── contracts/
│       │   └── ContractsV1Test.java    # 3 testes de contrato para /contracts/v1
│       └── solver/
│           └── SolverClientTest.java   # 14 testes (serializacao/deserializacao + async)
├── solver/                             # Solver Python (segregado)
│   ├── main.py                         # FastAPI — /solve, /solve/async, /cancel/{job_id}, /result/{job_id}
│   ├── vrp.py                          # OR-Tools CVRPTW
│   ├── matrix.py                       # OSRM + fallback Haversine
│   ├── models.py                       # Pydantic — contratos entrada/saida
│   ├── visualize.py                    # Folium — mapa interativo
│   ├── requirements.txt
│   ├── Dockerfile
│   ├── osrm/
│   │   └── prepare.sh                  # Baixa e processa mapa OSM (rodar 1x)
│   └── tests/                          # 29 testes pytest
├── produto-ui/
│   └── prototipo/
│       ├── index.html                 # Prototipo navegavel
│       ├── app.js                     # Logica principal do prototipo
│       ├── styles.css
│       ├── lib/
│       │   ├── timeline-flow.js       # Fetch de timeline com fallback
│       │   ├── timeline-utils.js      # Helpers de path/normalizacao
│       │   └── operational-e2e.js     # Helpers de payloads E2E
│       ├── tests/                     # 15 testes JS unit (node:test)
│       ├── e2e/
│       │   └── poc-m1-ui.spec.js      # 5 testes Playwright E2E
│       ├── package.json
│       └── playwright.config.js
```

---

## Servicos Docker

| Servico    | Porta | Funcao                                    |
| ---------- | ----- | ----------------------------------------- |
| PostgreSQL | 5434  | Banco de dados (dev, volume persistente)  |
| PostgreSQL | 5435  | Banco de dados (test, tmpfs em memoria)   |
| OSRM       | 5000  | Distancias reais por vias (OpenStreetMap) |
| Nominatim  | 8088  | Geocoding — endereco para lat/lon         |
| Solver     | 8080  | Otimizador de rotas (OR-Tools + FastAPI)  |

---

## Como Rodar

### Pre-requisitos

- Java 21+
- Maven
- Docker + Docker Compose
- Python 3.12+ (para testes locais do solver)

### Subir banco e aplicar schema completo (001-015)

```bash
docker compose up -d postgres-oop-dev postgres-oop-test
./apply-migrations.sh

# aplicar tambem no banco de teste (porta 5435)
CONTAINER_NAME=postgres-oop-test POSTGRES_DB=agua_viva_oop_test ./apply-migrations.sh
```

### Compilar e testar (Java)

```bash
mvn test
mvn clean compile  # compilar sem testes
```

### Gerar diagramas da arquitetura (sobrescrevendo sempre)

```bash
./scripts/gerar-diagramas.sh
```

Saidas geradas/atualizadas automaticamente em `target/diagramas`:
- `target/diagramas/camadas.dot` (visao limpa por camada)
- `target/diagramas/pacotes.dot` (visao limpa por pacote interno)
- `target/diagramas/jdeps/classes.dot`
- `target/diagramas/jdeps/summary.dot`
- `target/diagramas/dependencies.dot`
- `target/diagramas/pacotes.mmd`
- `target/diagramas/camadas.mmd`
- `target/diagramas/camadas.svg` (quando `dot`/Graphviz estiver instalado)
- `target/diagramas/pacotes.svg` (quando `dot`/Graphviz estiver instalado)
- `target/diagramas/dependencies.svg` (quando `dot`/Graphviz estiver instalado)

O script limpa a pasta de saida antes de gerar, entao os arquivos antigos sempre sao substituidos pelos novos.
Observacao: `target/diagramas/jdeps/classes.dot` e o dump bruto do `jdeps` (mais poluido); para analise visual, use primeiro `camadas.dot` e `pacotes.dot`.

### Preparar e subir o solver

```bash
# 1. Baixar e processar mapa OSM (rodar uma vez, ~15min)
cd solver/osrm
./prepare.sh
# ou com recorte custom (menor):
# PBF_URL=https://seu-servidor/recorte.osm.pbf ./prepare.sh

# 2. Subir OSRM + Nominatim + Solver
OSRM_DATASET=sudeste-latest \
NOMINATIM_PBF_URL=https://download.geofabrik.de/south-america/brazil/sudeste-latest.osm.pbf \
docker compose up -d osrm nominatim solver

# 3. Testar solver localmente (opcional)
cd solver && python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
pytest tests/ -v
```

### Poda de mapa (recomendado para recuperacao mais rapida)

- Objetivo: reduzir tempo de bootstrap e melhorar recovery do stack de navegacao.
- Iniciativa: tornar dataset configuravel (`OSRM_DATASET` e `NOMINATIM_PBF_URL`) e flexibilizar preprocessamento no `prepare.sh`.
- Metodologia: mapear hardcodes, parametrizar compose/script, validar sintaxe/startup e manter fallback no solver.
- O OSRM sobe com o dataset definido em `OSRM_DATASET` (default: `sudeste-latest`).
- O script `solver/osrm/prepare.sh` aceita `PBF_URL` custom para recortes menores.
- Para reduzir tempo de bootstrap pos-queda, use o menor recorte que cubra a operacao real.

Exemplo:

```bash
# gera dataset a partir de um recorte menor
cd solver/osrm
PBF_URL=https://seu-storage/operacao-zona-sul.osm.pbf ./prepare.sh

# o script imprime "Dataset gerado: <nome>"
OSRM_DATASET=operacao-zona-sul docker compose up -d osrm solver
```

### Subir API Java (atendimento/eventos + operacao one-click)

```bash
# com solver ja disponivel em http://localhost:8080
SOLVER_URL=http://localhost:8080 API_PORT=8081 \
  mvn -DskipTests exec:java -Dexec.mainClass=com.aguaviva.App -Dexec.args=api
```

Endpoints:
- `GET /health`
- `POST /api/atendimento/pedidos`
- `POST /api/eventos`
- `POST /api/replanejamento/run` (desativado, retorna `409`)
- `POST /api/operacao/rotas/prontas/iniciar`
- `GET /api/pedidos/{pedidoId}/timeline`
- `GET /api/pedidos/{pedidoId}/execucao`
- `GET /api/entregadores/{entregadorId}/roteiro`
- `GET /api/operacao/painel`
- `GET /api/operacao/eventos`
- `GET /api/operacao/mapa`
- `GET /api/operacao/replanejamento/jobs`
- `GET /api/operacao/replanejamento/jobs/{jobId}`

### Contratos compartilhados (A0)

Pacote canonico de handoff A->B em `/contracts/v1`:
- `openapi.yaml`
- `events/catalogo-eventos.json`
- `examples/*.json`

### Variaveis de ambiente

Copie `.env.example` para `.env`:

```bash
cp .env.example .env
```

| Variavel          | Padrao            | Descricao          |
| ----------------- | ----------------- | ------------------ |
| POSTGRES_HOST     | localhost         | Host do banco dev  |
| POSTGRES_PORT     | 5434              | Porta do banco dev |
| POSTGRES_USER     | postgres          | Usuario            |
| POSTGRES_PASSWORD | postgres          | Senha              |
| POSTGRES_DB       | agua_viva_oop_dev | Nome do banco dev  |
| OSRM_DATASET      | sudeste-latest    | Dataset preprocessado do OSRM (sem `.osrm`) |
| NOMINATIM_PBF_URL | https://download.geofabrik.de/south-america/brazil/sudeste-latest.osm.pbf | URL do `.osm.pbf` importado pelo Nominatim |
| SOLVER_URL        | http://localhost:8080 | URL do solver Python |
| API_PORT          | 8081              | Porta da API Java  |

O banco de teste roda na porta **5435** com dados em memoria (tmpfs).

Resolucao de configuracao de banco no runtime:

1. Variavel de ambiente do processo (`POSTGRES_*`)
2. `.env` local
3. fallback padrao do codigo

Isso permite subir API para `test` sem editar `.env`, por exemplo:

```bash
POSTGRES_HOST=localhost POSTGRES_PORT=5435 POSTGRES_DB=agua_viva_oop_test \
POSTGRES_USER=postgres POSTGRES_PASSWORD=postgres \
SOLVER_URL=http://localhost:8080 API_PORT=8082 \
mvn -DskipTests exec:java -Dexec.mainClass=com.aguaviva.App -Dexec.args=api
```

---

## Fases de Desenvolvimento

- [x] **Fase 1** — Fundacao (pom.xml, Docker, migrations, ConnectionFactory, health check)
- [x] **Fase 2** — Domain: User + UserPapel + Password (TDD, 45 testes)
- [x] **Fase 3** — Repository: UserRepository (TDD, 21 testes de integracao)
- [x] **Fase 4** — Solver Python: OR-Tools CVRPTW + OSRM + Nominatim + fluxo async/cancel (29 testes)
- [x] **Fase 5** — Integracao Java-Solver: SolverClient + Gson + contrato async/cancel (14 testes)
- [x] **Fase 6** — Repository: ClienteRepository + PedidoRepository
- [ ] **Fase 7** — Service: RotaService (avancada: 24 testes, concorrencia multi-instancia coberta)
- [ ] **Fase 8** — Maquina de estados do Pedido (parcial: integrada via `PedidoLifecycleService`; faltam trilhas completas de auditoria)
- [ ] **Fase 9** — Vales (iniciada: MVP checkout/entrega com debito idempotente)
- [ ] **Fase 10** — Frontend (iniciada: API base pronta, prototipo navegavel, 5 testes E2E Playwright)

---

## Correlacao com o Projeto Next.js

| Aspecto         | Next.js (agua-viva)  | Java (agua-viva-oop)          |
| --------------- | -------------------- | ----------------------------- |
| Dominio         | Mesmo                | Mesmo                         |
| Schema do banco | Mesmo (11 migrations) | Mesmo (15 migrations)          |
| Persistencia    | pg (SQL puro)        | JDBC (SQL puro)               |
| Models          | models/\*.js         | domain/\*/\*.java (OOP pura)  |
| Testes          | Jest + TDD           | JUnit 5 + TDD                 |
| Solver          | —                    | Python (OR-Tools, segregado)  |
| ORM             | Nao                  | Nao                           |
| Framework       | Next.js              | Nenhum (Java puro)            |

---

## Licenca

MIT
