# Scripts da PoC Operacional

Este diretorio entrega a execucao minima da PoC com sequencia HTTP congelada.

## Scripts canonicos

- `scripts/poc/run-business-gate.sh`
- `scripts/poc/run-demo-m3-i0.sh`
- `scripts/poc/run-cenario.sh`
- `scripts/poc/run-suite.sh`
- `scripts/poc/run-e2e-local.sh`
- `scripts/poc/seed-montes-claros-test.sh`
- `scripts/poc/check-montes-claros-seed.sh`

Gate canonico da PoC:
1. `scripts/poc/run-business-gate.sh` (strict, evidencias oficiais)

## Atalho local (nao canonico)

- `scripts/poc/run-gate-3x.sh` (atalho para E2E local; nao substitui o business gate oficial)

Cenarios suportados:

1. `feliz`
2. `falha`
3. `cancelamento`

## Sequencia padrao (todos os cenarios)

1. `GET /health`
2. `POST /api/atendimento/pedidos`
3. `GET /api/pedidos/{pedidoId}/timeline` (inicial)
4. `POST /api/replanejamento/run` (inicial)
5. `POST /api/eventos` (`ROTA_INICIADA`)
6. `POST /api/eventos` (evento terminal do cenario)
7. `POST /api/replanejamento/run` (final)
8. `GET /api/pedidos/{pedidoId}/timeline` (final)

## Pre-requisitos

1. API Java rodando (padrao: `http://localhost:8082`).
2. PostgreSQL test acessivel (padrao: `localhost:5435`).
3. Solver acessivel para roteirizacao.
4. Comandos instalados: `curl` e `jq`.
5. Para acesso SQL:
- preferencial: `psql` local;
- fallback: Docker com container `postgres-oop-test`.

## Execucao

```bash
chmod +x scripts/poc/run-cenario.sh
chmod +x scripts/poc/run-suite.sh
chmod +x scripts/poc/run-e2e-local.sh
chmod +x scripts/poc/run-demo-m3-i0.sh

scripts/poc/run-cenario.sh feliz
scripts/poc/run-cenario.sh falha
scripts/poc/run-cenario.sh cancelamento

# Executa os 3 cenarios em lote e gera evidencias
scripts/poc/run-suite.sh

# One-command local (sobe stack, API/UI e roda gate completo)
scripts/poc/run-e2e-local.sh

# Gate oficial de negocio (strict por padrao)
scripts/poc/run-business-gate.sh

# Gate de negocio em 3 rodadas strict
scripts/poc/run-business-gate.sh --mode strict --rounds 3

# Demo guiada unica M3-I0 com evidencias consolidadas
scripts/poc/run-demo-m3-i0.sh

# Atalho local para gate E2E 3x (nao substitui o business gate oficial)
scripts/poc/run-gate-3x.sh

# Aplicar seed geografico de Montes Claros no banco de teste
scripts/poc/seed-montes-claros-test.sh

# Validar se o seed de Montes Claros esta consistente
scripts/poc/check-montes-claros-seed.sh
```

### Flags uteis do one-command

```bash
# roda so scripts PoC (sem Playwright)
scripts/poc/run-e2e-local.sh --mode observe --no-playwright

# roda so Playwright (sem scripts PoC)
scripts/poc/run-e2e-local.sh --mode observe --no-suite

# mantem API/UI no ar ao final
scripts/poc/run-e2e-local.sh --keep-running

# nao reseta estado entre rodadas (diagnostico)
scripts/poc/run-e2e-local.sh --mode observe --rounds 3 --no-reset

# desabilita check automatico de promocoes (diagnostico)
scripts/poc/run-e2e-local.sh --mode observe --rounds 3 --no-promocoes-check

# forca check estrito de promocoes (apenas cenario controlado)
scripts/poc/run-e2e-local.sh --rounds 3 --promocoes-strict

# mantem dados do Playwright para a suite (nao recomendado no gate final)
scripts/poc/run-e2e-local.sh --rounds 3 --no-reset-before-suite
```

### Evidencias da suite

Ao executar `run-suite.sh`, o script gera um diretorio com:

1. `feliz.log`, `falha.log`, `cancelamento.log` (saida completa de cada cenario)
2. `feliz.timeline.json`, `falha.timeline.json`, `cancelamento.timeline.json` (timeline final, quando disponivel)
3. `vale-positivo.log` e `vale-bloqueado.log` (gate de checkout VALE)
4. `summary.json` (consolidado da validacao dos cenarios operacionais)

Diretorio padrao:

```bash
artifacts/poc/<timestamp>
```

No `run-e2e-local.sh`, cada rodada fica em:

```bash
artifacts/poc/e2e-<timestamp>/round-01
artifacts/poc/e2e-<timestamp>/round-02
artifacts/poc/e2e-<timestamp>/round-03
```

Resumo consolidado do gate:

```bash
artifacts/poc/e2e-<timestamp>/gate-summary.json
```

Resumo consolidado da demo guiada M3-I0:

```bash
artifacts/poc/demo-m3-i0-<timestamp>/demo-summary.json
artifacts/poc/demo-m3-i0-<timestamp>/demo-summary.txt
```

Por rodada, tambem sao gerados:

1. `round-0X/playwright.log`
2. `round-0X/poc-suite.log`
3. `round-0X/promocoes.log`
4. `round-0X/promocoes-summary.json`

Status esperado por cenario na validacao automatica:

1. `feliz` -> `ENTREGUE`
2. `falha` -> `CANCELADO`
3. `cancelamento` -> `CANCELADO`

Gate adicional da suite:

1. `VALE` positivo: checkout deve funcionar com saldo suficiente.
2. `VALE` bloqueado: checkout deve falhar sem saldo (mensagem contendo `cliente nao possui vale`).

## Parametros uteis

Variaveis mais usadas:

- `API_BASE`
- `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`
- `DB_CONTAINER` (quando usar fallback via Docker)
- `SOLVER_REBUILD` (`1` por padrao; recompila solver antes do gate para evitar container defasado)
- `PROMOCOES_STRICT` (`0` por padrao; `1` para exigir transicoes no check de promocoes)
- `DEBOUNCE_SEGUNDOS` (padrao do `run-cenario.sh`: `0`)
- `SUITE_DEBOUNCE_SEGUNDOS` (padrao do `run-suite.sh`: `0`)
- `SEED_MONTES_CLAROS` (`1` por padrao no reset; `0` para base minima sem clientes seedados)
- `METODO_PAGAMENTO` (`PIX`, `DINHEIRO`, `CARTAO`, `VALE`, `NAO_INFORMADO`)
- `TELEFONE`
- `QUANTIDADE_GALOES`

Exemplo com `VALE`:

```bash
METODO_PAGAMENTO=VALE VALE_SALDO=10 scripts/poc/run-cenario.sh feliz
```

## Seed tecnico

O script cria/atualiza automaticamente:

1. usuario atendente de PoC;
2. usuario entregador de PoC;
3. cliente de PoC com coordenada valida para roteirizacao.

Se `METODO_PAGAMENTO=VALE`, o script tambem ajusta `saldo_vales` para o cliente.

## Seed geografico de Montes Claros (teste)

O seed `sql/seeds/001_seed_clientes_montes_claros_test.sql` injeta clientes com coordenadas reais/plausiveis de bairros de Montes Claros (Centro, Major Prates, Todos os Santos, Sao Jose, Ibituruna e Jardim Panorama), sem criar pedidos.

Uso direto:

```bash
scripts/poc/seed-montes-claros-test.sh
```

No fluxo de reset, ele roda automaticamente por padrao:

```bash
scripts/poc/reset-test-state.sh
```

Para desabilitar:

```bash
SEED_MONTES_CLAROS=0 scripts/poc/reset-test-state.sh
```

## Ancoragem rapida (PoC-MVP)

Fluxo recomendado para provar "acontecendo" no banco real de teste:

```bash
# 1) subir stack de teste
scripts/poc/start-test-env.sh

# 2) resetar estado + seed de Montes Claros
scripts/poc/reset-test-state.sh

# 3) validar massa geografica seedada
scripts/poc/check-montes-claros-seed.sh

# 4) rodar cenarios canonicos
scripts/poc/run-cenario.sh feliz
scripts/poc/run-cenario.sh falha
scripts/poc/run-cenario.sh cancelamento

# 5) fechar gate oficial (1x e depois 3x)
scripts/poc/run-business-gate.sh --mode strict --rounds 1
scripts/poc/run-business-gate.sh --mode strict --rounds 3
```

Evidencia final esperada:

1. `artifacts/poc/business-gate-<timestamp>/business-summary.json`
2. checks obrigatorios (`R01..R27`) em `PASS`
3. cenarios `feliz/falha/cancelamento` com status finais esperados

## Demo guiada unica (M3-I0)

Fluxo oficial para narrativa executiva unica, com pacote de evidencia por cenario:

```bash
scripts/poc/run-demo-m3-i0.sh
```

Saidas obrigatorias:

1. `demo-summary.json`
2. `demo-summary.txt`
3. `scenarios/<feliz|falha|cancelamento>/timeline-final.json`
4. `scenarios/<feliz|falha|cancelamento>/execucao-final.json`
5. `scenarios/<falha|cancelamento>/job-detail-*.json` (quando houver novo job no cenario)
