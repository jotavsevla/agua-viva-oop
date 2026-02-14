# Agua Viva OOP — Contexto para IA

Sistema de gestao e roteirizacao para distribuidora de agua mineral.
Stack: Java 21 + PostgreSQL 16 + Solver Python (OR-Tools/FastAPI).

## Fotografia atual

- Branch de trabalho: feature (`codex/fase7-*`)
- Fase 7 (RotaService): em andamento
- Total de testes: 168 (144 Java + 24 Python)
- Maturidade: nucleo alto, orquestracao media, entrega final baixa

## Diretriz principal

Tratar este repositorio como projeto de TDD estrito.
Sempre: RED -> GREEN -> REFACTOR.

## Regras operacionais

1. Teste antes do codigo de producao
2. Se o teste nao compilar, criar apenas assinatura minima (stub) e voltar ao RED
3. Implementar o minimo para passar
4. Refatorar sem alterar comportamento

## Guardrails tecnicos

- Nao usar Spring/Quarkus
- Nao usar JPA/Hibernate/ORM
- Nao usar Mockito/mocks framework
- JDBC puro com PreparedStatement
- Domain sem java.sql
- Validacoes no construtor (fail-fast)
- Imutabilidade quando aplicavel

## Guardrails de fluxo

- Nao executar acao de git (add/commit/push/merge) sem solicitacao explicita
- Nao criar migration sem alinhamento explicito
- Nao adicionar dependencia no pom sem alinhamento explicito

## Arquitetura esperada

```
domain/     -> regras puras
repository/ -> persistencia JDBC
solver/     -> cliente HTTP/JSON
service/    -> orquestracao transacional
```

Dependencias: `service -> repository -> domain <- solver`.

## Regras especificas de Service (Fase 7)

- Usar uma unica Connection no fluxo transacional
- `setAutoCommit(false)` e rollback em qualquer excecao
- Planejamento deve ser resiliente a retry
- Evitar duplicidade de rota/entrega em reprocessamento
- Tratar conflito de `numero_no_dia` por entregador/dia

## Cobertura atual de RotaService

Ja coberto por testes de integracao:
- fluxo feliz
- nao atendidos
- rollback por falha de persistencia
- rollback por erro HTTP do solver
- rollback por falha no lifecycle de pedido
- idempotencia sem novos pendentes
- reprocessamento sem conflito de numero_no_dia
- sem entregadores ativos
- sem pedidos elegiveis
- lock distribuido indisponivel (retorno vazio)
- retry apos lock liberado (sucesso no reprocessamento)
- concorrencia mesma instancia (2 threads, advisory lock)
- concorrencia multi-instancia (8 threads, 2 instancias, advisory lock)

Pendentes de fase:
- acoplamento correto com maquina de estados (Fase 8)

## Formatacao de codigo

Formatador: **Palantir Java Format** (4 espacos, 120 chars) via Spotless Maven Plugin.

```bash
# Verificar violacoes (usado em CI)
mvn spotless:check

# Aplicar formatacao automatica
mvn spotless:apply
```

Codigo novo deve ser formatado antes de commit.
O Spotless nao altera comportamento — apenas estilo.

## Comandos de validacao

```bash
# Java
mvn test
mvn test -Dtest=RotaServiceTest

# Formatacao
mvn spotless:check
mvn spotless:apply

# Python (usar sempre o venv local do solver)
solver/.venv/bin/python -m pytest -q solver/tests
```

## Infra de desenvolvimento

```bash
docker compose up -d postgres-oop-dev postgres-oop-test
./apply-migrations.sh
```

Se ocorrer erro de socket/permissao no PostgreSQL em execucao sandbox,
reexecutar testes com permissao de rede/local no executor.

## Padroes de nomenclatura

- Testes: `deveVerboCondicao` (portugues)
- Classes: PascalCase
- Metodos/campos: camelCase
- SQL: snake_case

## Objetivo de curto prazo

Completar Fase 7 com confiabilidade de orquestracao (concorrencia),
preparando transicao para Fase 8 (maquina de estados de pedidos).
