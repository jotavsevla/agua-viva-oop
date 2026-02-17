# GATE POC OPERACIONAL

Atualizado em: 2026-02-17
Objetivo: criterio binario de aceite para liberar PR de ciclo operacional.

## 1) Definicao de pronto (DoD) do gate

O gate e considerado verde apenas quando:

1. 3 rodadas consecutivas passam sem falha.
2. Cada rodada valida:
- Playwright PoC (`poc-m1-ui.spec.js`)
- suite de cenarios (`run-suite.sh`)
- assert de promocoes (`observe-promocoes.sh`)
3. Artefatos por rodada e resumo consolidado estao presentes.

## 2) Comando canonico

```bash
scripts/poc/run-gate-3x.sh --keep-running
```

Equivalente:

```bash
scripts/poc/run-e2e-local.sh --mode strict --rounds 3 --keep-running
```

## 2.1) Politica strict oficial

No gate oficial (aceite de PR operacional), o perfil e obrigatoriamente `strict`:

1. Playwright, suite e check de promocoes devem executar em toda rodada.
2. Etapa nao executada (exit `-1`) e tratada como falha.
3. `PROMOCOES_STRICT=1` e obrigatorio (exige `CONFIRMADO->EM_ROTA` e `PENDENTE->CONFIRMADO` em cenario elegivel).
4. Flags diagnosticas (`--no-playwright`, `--no-suite`, `--no-promocoes-check`) nao sao aceitas no strict.

## 3) Artefatos obrigatorios

Diretorio base:

```bash
artifacts/poc/e2e-<timestamp>/
```

Obrigatorio no consolidado:

1. `gate-summary.json`
2. `round-01/`
3. `round-02/`
4. `round-03/`

Obrigatorio por rodada:

1. `playwright.log`
2. `poc-suite.log`
3. `promocoes.log`
4. `promocoes-summary.json`
5. `poc-suite/summary.json`

## 4) Regras de falha automatica

Falha imediata da rodada quando qualquer item abaixo ocorre:

1. Playwright retorna exit code != 0
2. `run-suite.sh` retorna exit code != 0
3. `observe-promocoes.sh` retorna exit code != 0
4. Reset de estado falha (quando reset habilitado)

## 5) Checks de negocio cobertos pelo gate

1. loop operacional feliz/falha/cancelamento
2. checkout VALE positivo e bloqueado
3. idempotencia operacional (na suite e em script dedicado)
4. promocao observavel de fila:
- `CONFIRMADO -> EM_ROTA`
- `PENDENTE -> CONFIRMADO`

## 6) Comportamento em diagnostico

Somente fora do gate oficial, para depuracao local.

1. `--no-reset`: mantem estado entre rodadas para depuracao.
2. `--no-promocoes-check`: desliga apenas assert de promocao (nao recomendado para aceite final).
3. `--no-playwright` ou `--no-suite`: uso local de diagnostico, nao vale como gate final.
