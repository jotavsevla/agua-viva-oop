# Scripts da PoC Operacional

Este diretorio entrega a execucao minima da PoC com sequencia HTTP congelada.

## Script principal

- `scripts/poc/run-cenario.sh`
- `scripts/poc/run-suite.sh`
- `scripts/poc/run-e2e-local.sh`
- `scripts/poc/run-gate-3x.sh`
- `scripts/poc/run-business-gate.sh`

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

scripts/poc/run-cenario.sh feliz
scripts/poc/run-cenario.sh falha
scripts/poc/run-cenario.sh cancelamento

# Executa os 3 cenarios em lote e gera evidencias
scripts/poc/run-suite.sh

# One-command local (sobe stack, API/UI e roda gate completo)
scripts/poc/run-e2e-local.sh

# Gate forte: 3 rodadas consecutivas (reseta estado a cada rodada)
scripts/poc/run-e2e-local.sh --rounds 3

# Atalho para gate 3x
scripts/poc/run-gate-3x.sh

# Gate oficial de negocio (strict por padrao)
scripts/poc/run-business-gate.sh

# Gate de negocio em 3 rodadas strict
scripts/poc/run-business-gate.sh --mode strict --rounds 3
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
