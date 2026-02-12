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
| Config    | dotenv-java       | Variaveis de ambiente via .env   |
| Container | Docker Compose    | PostgreSQL containerizado        |
| Testes    | JUnit 5           | TDD — teste antes de implementar |

**Nao usa**: ORM (Hibernate/JPA), Spring Boot, Lombok, geradores de codigo.

---

## Estrutura do Projeto

```
agua-viva/
├── pom.xml
├── compose.yml                         # PostgreSQL dev (5434) + test (5435)
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
│   │   │   └── ConnectionFactory.java  # Pool JDBC via HikariCP
│   │   └── service/
│   └── test/java/com/aguaviva/
│       ├── domain/user/
│       │   ├── PasswordTest.java       # 18 testes unitarios
│       │   └── UserTest.java           # 27 testes unitarios
│       └── repository/
│           └── ConnectionFactoryTest.java  # 2 testes de integracao
└── docs/
```

---

## Arquitetura

Tres camadas com separacao rigida:

**Domain** (`domain/`) — Java puro. Zero imports de java.sql.
Objetos com comportamento, validacoes no construtor, Value Objects imutaveis.

**Repository** (`repository/`) — JDBC puro com PreparedStatement.
Converte entre ResultSet e objetos de dominio. Sem logica de negocio.

**Service** (`service/`) — Orquestra domain + repository.
Gerencia transacoes e coordena fluxos de negocio.

---

## Como Rodar

### Pre-requisitos

- Java 21+
- Maven
- Docker + Docker Compose

### Subir o banco

```bash
# Subir PostgreSQL dev + test
docker compose up -d

# Aplicar migrations no banco de dev
./apply-migrations.sh
```

### Compilar e testar

```bash
# Rodar todos os testes (unitarios + integracao)
mvn test

# Compilar sem testes
mvn clean compile

# Gerar JAR
mvn clean package
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

## Dominio

O dominio e o mesmo do projeto Next.js — distribuidora de agua mineral:
clientes, pedidos, rotas de entrega, vales, usuarios.

### Entidades e conceitos implementados

| Classe    | Tipo         | Descricao                                    |
| --------- | ------------ | -------------------------------------------- |
| Password  | Value Object | Hash BCrypt, compare, validate. Imutavel.    |
| User      | Entidade     | Invariantes no construtor, hierarquia papeis |
| UserPapel | Enum         | SUPERVISOR > ADMIN > ATENDENTE > ENTREGADOR  |

### Banco de dados

Schema identico ao projeto Next.js (8 migrations SQL).
PostgreSQL com views, CTEs, window functions, indices parciais e constraints de negocio.

---

## Testes

47 testes — TDD (teste antes de implementar).

| Suite                 | Tipo       | Testes |
| --------------------- | ---------- | ------ |
| PasswordTest          | Unitario   | 18     |
| UserTest              | Unitario   | 27     |
| ConnectionFactoryTest | Integracao | 2      |

```bash
mvn test
```

---

## Fases de Desenvolvimento

- [x] **Fase 1** — Fundacao (pom.xml, Docker, migrations, ConnectionFactory, health check)
- [x] **Fase 2** — Domain: User + UserPapel + Password (TDD)
- [ ] **Fase 3** — Repository + Service (UserRepository, UserService, ClienteRepository...)
- [ ] **Fase 4** — Maquina de estados do Pedido (PedidoStatus, transicoes)
- [ ] **Fase 5** — Vales (debito atomico, saldo, movimentacoes)

---

## Correlacao com o Projeto Next.js

| Aspecto         | Next.js (agua-viva)  | Java (agua-viva-oop)         |
| --------------- | -------------------- | ---------------------------- |
| Dominio         | Mesmo                | Mesmo                        |
| Schema do banco | Mesmo (8 migrations) | Mesmo (8 migrations)         |
| Persistencia    | pg (SQL puro)        | JDBC (SQL puro)              |
| Models          | models/\*.js         | domain/\*_/_.java (OOP pura) |
| Testes          | Jest + TDD           | JUnit 5 + TDD                |
| ORM             | Nao                  | Nao                          |
| Framework       | Next.js              | Nenhum (Java puro)           |
| Objetivo        | SQL puro + MVC       | OOP verdadeira + SGBD puro   |

---

## Documentacao

- [Regras de Negocio](docs/docs/regras-negocio-agua-viva.md)
- [Diagrama ER](docs/docs/diagrama-er-agua-viva.md)
- [Queries CTE Documentadas](docs/docs/queries-cte-documentacao.md)
- [Organizacao Paralela ao Next.js](docs/docs/organizacaoProjetoPareloNext.md)

---

## Licenca

MIT
