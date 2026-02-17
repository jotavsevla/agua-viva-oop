# Agua Viva OOP — Contexto para IA

Sistema de gestao e roteirizacao para distribuidora de agua mineral.
Stack: Java 21 + PostgreSQL 16 + Solver Python (OR-Tools/FastAPI).

## Fotografia atual

- Branch de trabalho: feature (`codex/*`)
- Fase 7 (RotaService): avancada
- Fase 8 (PedidoLifecycle): parcial, integrada via service
- Fase 9 (Vales): iniciada (MVP checkout/entrega)
- Total de testes: 256 (210 Java + 27 Python + 14 JS unit + 5 Playwright E2E)
- Migrations: 001–013
- Maturidade: nucleo alto, orquestracao media-alta, E2E operacional iniciado

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
api/        -> HTTP endpoints (com.sun.net.httpserver)
```

Dependencias: `api -> service -> repository -> domain <- solver`.

## Regras especificas de Service (Fase 7)

- Usar uma unica Connection no fluxo transacional
- `setAutoCommit(false)` e rollback em qualquer excecao
- Planejamento deve ser resiliente a retry
- Evitar duplicidade de rota/entrega em reprocessamento
- Tratar conflito de `numero_no_dia` por entregador/dia

## Cobertura atual de RotaService (18 testes)

Ja coberto por testes de integracao:
- fluxo feliz com persistencia de rotas/entregas e atualizacao de status
- nao atendidos mantidos como PENDENTE
- rollback por falha de persistencia
- rollback por erro HTTP do solver
- rollback por falha no lifecycle de pedido
- idempotencia sem novos pendentes
- reprocessamento sem conflito de numero_no_dia
- sem entregadores ativos (short-circuit)
- sem pedidos elegiveis (short-circuit)
- pedido com pagamento nao-vale elegivel mesmo sem saldo
- reaproveitamento de rota com cancelamento sem duplicidade
- lock distribuido indisponivel (retorno vazio)
- retry apos lock liberado (sucesso no reprocessamento)
- concorrencia mesma instancia sem cancelamento indevido
- concorrencia multi-instancia sem duplicidade de rota/entrega
- alta disputa com rodadas sucessivas
- retries simultaneos apos liberacao de lock
- distribuicao de vitorias entre instancias

## Cobertura E2E (Playwright — 5 testes)

Loop operacional ponta-a-ponta via UI + API + banco real:
- cenario feliz (atendimento → rota → entrega → timeline = ENTREGUE)
- cenario falha (atendimento → rota → falha → replanejamento → timeline = CANCELADO)
- cenario cancelamento (atendimento → rota → cancelamento com cobranca → timeline = CANCELADO)
- checkout VALE com saldo suficiente (HTTP 200)
- checkout VALE sem saldo (HTTP 400)

Pendentes de fase:
- trilhas completas de auditoria (Fase 8)
- endpoints financeiros de saldo/extrato/cobranca

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
# Java (210 testes — pre-requisito: postgres-oop-test + migrations 001-013)
mvn test
mvn test -Dtest=RotaServiceTest

# Formatacao
mvn spotless:check
mvn spotless:apply

# Python (27 testes — usar sempre o venv local do solver)
solver/.venv/bin/python -m pytest -q solver/tests

# JS unit (14 testes — produto-ui)
cd produto-ui/prototipo && node --test tests/

# E2E Playwright (5 testes — requer API + banco dev + prototipo rodando)
cd produto-ui/prototipo && npx playwright test e2e/poc-m1-ui.spec.js
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

Fechar PoC operacional (milestones M0–M2) com loop E2E repetivel:
pedido → rota → evento → replanejamento → timeline.
Preparar Fase 8 (trilhas de auditoria) e Fase 9 (financeiro completo).
