# Agua Viva OOP — Java Puro + PostgreSQL

Reimplementacao OOP do sistema Agua Viva (distribuidora de agua mineral).
Repositorio paralelo ao [Agua Viva VRP](https://github.com/jotavsevla/aguaVIVA) (Next.js) — mesmo dominio, paradigma diferente.

O objetivo e estudar e aplicar **Orientacao a Objetos verdadeira** em Java,
com **PostgreSQL isolado em Docker** (SQL puro, sem ORM).

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

**Nao usa**: ORM (Hibernate/JPA), Spring Boot, Lombok, geradores de codigo.

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
│       │   └── UserRepositoryTest.java
│       └── solver/
│           └── SolverClientTest.java   # 10 testes (serializacao/deserializacao)
├── solver/                             # Solver Python (segregado)
│   ├── main.py                         # FastAPI — POST /solve
│   ├── vrp.py                          # OR-Tools CVRPTW
│   ├── matrix.py                       # Cliente OSRM + fallback Haversine
│   ├── models.py                       # Pydantic — contratos entrada/saida
│   ├── visualize.py                    # Folium — mapa de debug
│   ├── requirements.txt
│   ├── Dockerfile
│   ├── osrm/
│   │   └── prepare.sh                  # Baixa e processa mapa OSM (rodar 1x)
│   └── tests/                          # 24 testes pytest
└── docs/
```

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

**Tres camadas Java com separacao rigida:**

**Domain** (`domain/`) — Java puro. Zero imports de java.sql.
Objetos com comportamento, validacoes no construtor, Value Objects imutaveis.

**Repository** (`repository/`) — JDBC puro com PreparedStatement.
Converte entre ResultSet e objetos de dominio. Sem logica de negocio.

**Solver** (`solver/`) — Integracao com o solver Python via HTTP + Gson.
Classes imutaveis que espelham o contrato JSON do solver.

**Service** (`service/`) — Orquestra domain + repository + solver.
Gerencia transacoes e coordena fluxos de negocio.

---

## Solver Python (segregado)

O solver e um servico **independente** que resolve o problema de roteamento
de veiculos (CVRPTW — Capacitated Vehicle Routing Problem with Time Windows).

Recebe JSON com pedidos, devolve JSON com rotas otimizadas. Stateless.

| Restricao | Como trata |
| --------- | ---------- |
| Capacidade | Max 5 galoes por viagem |
| Time windows | Pedidos HARD com horario obrigatorio |
| Multiplas viagens | Ate 3 viagens por entregador por dia |
| Pedidos inviaveis | Devolvidos em `nao_atendidos` |

**Comunicacao**: Java monta `SolverRequest` → `POST /solve` → recebe `SolverResponse`.

O OSRM fornece distancias reais por vias (nao linha reta).
O Nominatim converte endereco texto em lat/lon (geocoding).

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

## Servicos Docker

| Servico    | Porta | Funcao                                   |
| ---------- | ----- | ---------------------------------------- |
| PostgreSQL | 5434  | Banco de dados (dev)                     |
| PostgreSQL | 5435  | Banco de dados (test, tmpfs)             |
| OSRM       | 5000  | Distancias reais por vias (OpenStreetMap)|
| Nominatim  | 8088  | Geocoding — endereco para lat/lon        |
| Solver     | 8080  | Otimizador de rotas (OR-Tools + FastAPI) |

---

## Dominio

Distribuidora de agua mineral — clientes, pedidos, rotas de entrega, vales, usuarios.

### Entidades implementadas

| Classe    | Tipo         | Descricao                                    |
| --------- | ------------ | -------------------------------------------- |
| Password  | Value Object | Hash BCrypt, compare, validate. Imutavel.    |
| User      | Entidade     | Invariantes no construtor, hierarquia papeis |
| UserPapel | Enum         | SUPERVISOR > ADMIN > ATENDENTE > ENTREGADOR  |

### Banco de dados

Schema com 8 migrations SQL.
PostgreSQL com views, CTEs, window functions, indices parciais e constraints de negocio.

---

## Testes

78 testes — TDD (teste antes de implementar).

| Suite                 | Linguagem | Tipo       | Testes |
| --------------------- | --------- | ---------- | ------ |
| PasswordTest          | Java      | Unitario   | 18     |
| UserTest              | Java      | Unitario   | 27     |
| ConnectionFactoryTest | Java      | Integracao | 2      |
| UserRepositoryTest    | Java      | Integracao | 21     |
| SolverClientTest      | Java      | Unitario   | 10     |
| test_vrp              | Python    | Unitario   | 14     |
| test_models           | Python    | Unitario   | 6      |
| test_matrix           | Python    | Unitario   | 5      |

```bash
mvn test                              # Java (78 testes)
cd solver && pytest tests/ -v         # Python (24 testes, dentro do venv)
```

---

## Fases de Desenvolvimento

- [x] **Fase 1** — Fundacao (pom.xml, Docker, migrations, ConnectionFactory, health check)
- [x] **Fase 2** — Domain: User + UserPapel + Password (TDD)
- [x] **Fase 3** — Repository: UserRepository (TDD)
- [x] **Fase 4** — Solver Python: OR-Tools CVRPTW + OSRM + Nominatim
- [x] **Fase 5** — Integracao Java-Solver: SolverClient + Gson
- [ ] **Fase 6** — Repository: ClienteRepository + PedidoRepository
- [ ] **Fase 7** — Service: RotaService (orquestra solver + repositorios)
- [ ] **Fase 8** — Maquina de estados do Pedido (transicoes de status)
- [ ] **Fase 9** — Vales (debito atomico, saldo, movimentacoes)
- [ ] **Fase 10** — Frontend: Leaflet.js (mapa com rotas)

---

## Correlacao com o Projeto Next.js

| Aspecto         | Next.js (agua-viva)  | Java (agua-viva-oop)         |
| --------------- | -------------------- | ---------------------------- |
| Dominio         | Mesmo                | Mesmo                        |
| Schema do banco | Mesmo (8 migrations) | Mesmo (8 migrations)         |
| Persistencia    | pg (SQL puro)        | JDBC (SQL puro)              |
| Models          | models/\*.js         | domain/\*_/_.java (OOP pura) |
| Testes          | Jest + TDD           | JUnit 5 + TDD                |
| Solver          | —                    | Python (OR-Tools, segregado) |
| ORM             | Nao                  | Nao                          |
| Framework       | Next.js              | Nenhum (Java puro)           |

---

## Documentacao

- [Regras de Negocio](docs/docs/regras-negocio-agua-viva.md)
- [Diagrama ER](docs/docs/diagrama-er-agua-viva.md)
- [Queries CTE Documentadas](docs/docs/queries-cte-documentacao.md)
- [Organizacao Paralela ao Next.js](docs/docs/organizacaoProjetoPareloNext.md)

---

## Licenca

MIT
