# Agua Viva OOP — Java Puro + PostgreSQL

Sistema de gestao e otimizacao de rotas para distribuidora de agua mineral.
Reimplementacao OOP do [Agua Viva VRP](https://github.com/jotavsevla/aguaVIVA) (Next.js) — mesmo dominio, paradigma diferente.

O objetivo e estudar e aplicar **Orientacao a Objetos verdadeira** em Java,
com **PostgreSQL isolado em Docker** (SQL puro, sem ORM) e um **solver Python**
que resolve roteamento de veiculos com restricoes reais.

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

Isso vale para domain, repository e solver. Nenhuma classe existe sem teste.

**102 testes** (78 Java + 24 Python), zero falhas. Os testes nao sao formais —
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

```
┌─────────────────────────────────────────────────────────────┐
│                        Docker Compose                       │
│                                                             │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌────────────┐  │
│  │PostgreSQL│  │  OSRM    │  │Nominatim │  │  Solver    │  │
│  │  :5434   │  │  :5000   │  │  :8088   │  │  :8080     │  │
│  │          │  │ distancias│  │ geocoding│  │ OR-Tools   │  │
│  │          │  │ reais    │  │ endereco │  │ CVRPTW     │  │
│  │          │  │ por vias │  │ → lat/lon│  │            │  │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘  └─────┬──────┘  │
│       │              │             │              │          │
└───────┼──────────────┼─────────────┼──────────────┼──────────┘
        │              │             │              │
   ┌────┴──────────────┴─────────────┴──────────────┴──────┐
   │                    Java Backend                        │
   │                                                        │
   │  domain/     → Regras de negocio (OOP pura)            │
   │  repository/ → JDBC (leitura/gravacao)                 │
   │  solver/     → SolverClient (HTTP + JSON)              │
   │  service/    → Orquestracao (a construir)              │
   └───────────────────────┬────────────────────────────────┘
                           │
                    ┌──────┴──────┐
                    │   Browser   │
                    │ Leaflet.js  │
                    │ (mapa)      │
                    └─────────────┘
```

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

**Service** (`service/`) — A construir. Orquestra domain + repository + solver.
Gerencia transacoes e coordena fluxos de negocio.

Dependencias apontam sempre para o centro: `service → repository → domain ← solver`.

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

Schema com **8 migrations SQL** — PostgreSQL com enums nativos, indices parciais,
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

### Status do pedido

```
PENDENTE → CONFIRMADO → EM_ROTA → ENTREGUE
                                 → CANCELADO
```

Validado por constraints no banco e (futuro) por maquina de estados no dominio.

---

## Testes

**102 testes** — TDD (teste escrito antes da implementacao).

### Testes unitarios (55 Java + 24 Python)

Testam logica pura sem dependencias externas (sem banco, sem rede, sem Docker).

| Suite            | Testes | O que valida                                                    |
| ---------------- | ------ | --------------------------------------------------------------- |
| PasswordTest     | 18     | Politica de senha, BCrypt, matching, reconstrucao, Value Object |
| UserTest         | 27     | Invariantes, hierarquia (10 combinacoes), identidade, email     |
| SolverClientTest | 10     | Serializacao/deserializacao JSON, roundtrip, construcao         |
| test_vrp         | 14     | Solver CVRPTW, capacidade, time windows, multiplas viagens      |
| test_models      | 6      | Validacao Pydantic, defaults, galoes >= 1                       |
| test_matrix      | 5      | Haversine, simetria, fallback OSRM → Haversine                 |

### Testes de integracao (23 Java)

Testam interacao com PostgreSQL real (porta 5435, tmpfs, dados em memoria).

| Suite                 | Testes | O que valida                                                 |
| --------------------- | ------ | ------------------------------------------------------------ |
| ConnectionFactoryTest | 2      | Conexao valida, versao do PostgreSQL                         |
| UserRepositoryTest    | 21     | CRUD completo, email unico, todos os enum, soft delete, hash persistido |

Isolamento entre testes: `DELETE FROM users` + `ALTER SEQUENCE ... RESTART` no `@BeforeEach`.
Sem mocks. Banco real em tmpfs.

### Rodando os testes

```bash
# Java (78 testes — pre-requisito: postgres-oop-test rodando)
mvn test

# Python (24 testes — dentro do venv)
cd solver && pytest tests/ -v
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
│       └── 008_create_views_cte.sql
├── src/
│   ├── main/java/com/aguaviva/
│   │   ├── App.java                    # Entry point (health check)
│   │   ├── domain/
│   │   │   └── user/
│   │   │       ├── Password.java       # Value Object — hash, compare, validate
│   │   │       ├── User.java           # Entidade com comportamento
│   │   │       └── UserPapel.java      # Enum com hierarquia de papeis
│   │   ├── repository/
│   │   │   ├── ConnectionFactory.java  # Pool JDBC via HikariCP
│   │   │   └── UserRepository.java     # CRUD de usuarios
│   │   └── solver/
│   │       ├── SolverClient.java       # HTTP client pro solver Python
│   │       ├── SolverRequest.java      # Request — deposito, pedidos, entregadores
│   │       ├── SolverResponse.java     # Response — rotas otimizadas
│   │       ├── Coordenada.java         # Value Object — lat/lon
│   │       ├── PedidoSolver.java       # Pedido formatado pro solver
│   │       ├── Parada.java             # Parada na rota (ordem, hora prevista)
│   │       └── RotaSolver.java         # Rota com entregador e paradas
│   └── test/java/com/aguaviva/
│       ├── domain/user/
│       │   ├── PasswordTest.java       # 18 testes unitarios
│       │   └── UserTest.java           # 27 testes unitarios
│       ├── repository/
│       │   ├── ConnectionFactoryTest.java
│       │   └── UserRepositoryTest.java # 21 testes de integracao
│       └── solver/
│           └── SolverClientTest.java   # 10 testes (serializacao/deserializacao)
├── solver/                             # Solver Python (segregado)
│   ├── main.py                         # FastAPI — POST /solve, POST /map, GET /demo
│   ├── vrp.py                          # OR-Tools CVRPTW
│   ├── matrix.py                       # OSRM + fallback Haversine
│   ├── models.py                       # Pydantic — contratos entrada/saida
│   ├── visualize.py                    # Folium — mapa interativo
│   ├── requirements.txt
│   ├── Dockerfile
│   ├── osrm/
│   │   └── prepare.sh                  # Baixa e processa mapa OSM (rodar 1x)
│   └── tests/                          # 24 testes pytest
└── docs/                               # Documentacao interna detalhada
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

### Subir o banco

```bash
docker compose up -d postgres-oop-dev postgres-oop-test
./apply-migrations.sh
```

### Compilar e testar (Java)

```bash
mvn test           # 78 testes (unitarios + integracao)
mvn clean compile  # compilar sem testes
```

### Preparar e subir o solver

```bash
# 1. Baixar e processar mapa OSM (rodar uma vez, ~15min)
cd solver/osrm && ./prepare.sh

# 2. Subir OSRM + Nominatim + Solver
docker compose up -d osrm nominatim solver

# 3. Testar solver localmente (opcional)
cd solver && python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
pytest tests/ -v   # 24 testes
```

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

O banco de teste roda na porta **5435** com dados em memoria (tmpfs).

---

## Fases de Desenvolvimento

- [x] **Fase 1** — Fundacao (pom.xml, Docker, migrations, ConnectionFactory, health check)
- [x] **Fase 2** — Domain: User + UserPapel + Password (TDD, 45 testes)
- [x] **Fase 3** — Repository: UserRepository (TDD, 23 testes de integracao)
- [x] **Fase 4** — Solver Python: OR-Tools CVRPTW + OSRM + Nominatim (24 testes)
- [x] **Fase 5** — Integracao Java-Solver: SolverClient + Gson (10 testes)
- [ ] **Fase 6** — Repository: ClienteRepository + PedidoRepository
- [ ] **Fase 7** — Service: RotaService (orquestra solver + repositorios)
- [ ] **Fase 8** — Maquina de estados do Pedido (transicoes de status)
- [ ] **Fase 9** — Vales (debito atomico, saldo, movimentacoes)
- [ ] **Fase 10** — Frontend: Leaflet.js (mapa com rotas)

---

## Correlacao com o Projeto Next.js

| Aspecto         | Next.js (agua-viva)  | Java (agua-viva-oop)          |
| --------------- | -------------------- | ----------------------------- |
| Dominio         | Mesmo                | Mesmo                         |
| Schema do banco | Mesmo (8 migrations) | Mesmo (8 migrations)          |
| Persistencia    | pg (SQL puro)        | JDBC (SQL puro)               |
| Models          | models/\*.js         | domain/\*/\*.java (OOP pura)  |
| Testes          | Jest + TDD           | JUnit 5 + TDD                 |
| Solver          | —                    | Python (OR-Tools, segregado)  |
| ORM             | Nao                  | Nao                           |
| Framework       | Next.js              | Nenhum (Java puro)            |

---

## Licenca

MIT
