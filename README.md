# Agua Viva OOP

Reimplementacao OOP em Java puro de uma distribuidora de agua mineral.
Mesmo dominio do projeto [Agua Viva VRP](https://github.com/jotavsevla/aguaVIVA) (Next.js), paradigma diferente.

## Stack

| O que     | Tecnologia        |
| --------- | ----------------- |
| Linguagem | Java 21           |
| Build     | Maven             |
| Banco     | PostgreSQL 16     |
| Driver    | JDBC (postgresql) |
| Pool      | HikariCP          |
| Container | Docker Compose    |
| Testes    | JUnit 5           |

Sem ORM. Sem Spring. Sem Lombok. Java puro, proposital.

## Pre-requisitos

- Java 21+
- Maven
- Docker e Docker Compose

## Como rodar

```bash
# 1. Subir o banco (dev + test)
docker compose up -d

# 2. Aplicar migrations no banco dev
./apply-migrations.sh

# 3. Aplicar migrations no banco test
CONTAINER_NAME=postgres-oop-test POSTGRES_DB=agua_viva_oop_test ./apply-migrations.sh

# 4. Compilar
mvn compile

# 5. Rodar health check
mvn exec:java -Dexec.mainClass="com.aguaviva.App"

# 6. Rodar testes
mvn test
```

## Estrutura

```
agua-viva/
├── pom.xml                          # Dependencias e build
├── compose.yml                      # PostgreSQL dev (5434) + test (5435)
├── apply-migrations.sh              # Aplica SQL migrations no banco
├── sql/migrations/                  # 8 migrations SQL (schema completo)
└── src/
    ├── main/java/com/aguaviva/
    │   ├── App.java                 # Entry point (health check)
    │   ├── domain/                  # Objetos de dominio (Java puro)
    │   ├── repository/              # Persistencia (JDBC puro)
    │   └── service/                 # Orquestracao
    └── test/java/com/aguaviva/
        └── repository/              # Testes de integracao
```

## Arquitetura

- **Domain**: Java puro. Zero imports de `java.sql`. Objetos com comportamento, validacoes no construtor, Value Objects imutaveis.
- **Repository**: JDBC puro com `PreparedStatement`. Converte entre `ResultSet` e objetos de dominio.
- **Service**: Orquestra domain + repository. Gerencia transacoes.

Java cuida de objetos. PostgreSQL cuida de dados. Nenhum conceito vaza para o outro lado.
