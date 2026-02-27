# Agua Viva OOP

Sistema de operacao e despacho para distribuidora de agua mineral, com backend Java, PostgreSQL e solver Python.

## Escopo Publico

Este `README.md` passa a ser a documentacao publica minima do projeto.
O foco e somente no que esta em uso real hoje: operacao, regras de negocio e comandos praticos.
A visibilidade do repositorio permanece inalterada.

## Stack em Uso

- Java 25.x (obrigatorio no build Maven)
- Maven
- PostgreSQL 16 (Docker)
- Solver Python (FastAPI + OR-Tools)
- UI prototipo (Node)

## Regras de Negocio Vigentes

Fonte de verdade: comportamento validado em testes de integracao e contratos em `contracts/v1`.

### Atendimento Omnichannel

1. Canais aceitos: `MANUAL`, `WHATSAPP`, `BINA_FIXO`, `TELEFONIA_FIXO`.
2. `sourceEventId` e obrigatorio para canal automatico (`WHATSAPP`, `BINA_FIXO`, `TELEFONIA_FIXO`).
3. `manualRequestId` so pode ser usado com `origemCanal=MANUAL`.
4. Headers `Idempotency-Key` e `X-Idempotency-Key` sao aceitos; se ambos forem enviados, devem ter o mesmo valor.
5. Idempotencia e aplicada por canal + chave:
- Mesmo payload com mesma chave retorna o mesmo pedido (`idempotente=true`).
- Mesma chave com payload divergente retorna conflito (`409`).
6. Se o telefone nao existir, o cadastro do cliente e capturado. Se o pedido for rejeitado por regra (endereco/geolocalizacao/cobertura/vale), o cadastro permanece salvo.
7. Em `MANUAL`, se ja existir pedido aberto para o cliente, o atendimento retorna o pedido existente (sem duplicar).

### Pedido e Execucao

1. Fluxo de status: `PENDENTE -> CONFIRMADO -> EM_ROTA -> ENTREGUE` ou `CANCELADO`.
2. Evento terminal (`PEDIDO_ENTREGUE`, `PEDIDO_FALHOU`, `PEDIDO_CANCELADO`) so e aceito quando a entrega esta em `EM_EXECUCAO`; fora disso retorna `409`.
3. Eventos operacionais respeitam idempotencia por `externalEventId`.
4. Quando a ultima entrega da rota termina, o backend publica `ROTA_CONCLUIDA` no outbox (`dispatch_events`).

### Operacao / Despacho

1. `GET /api/operacao/painel`, `GET /api/operacao/eventos` e `GET /api/operacao/mapa` sao os endpoints de leitura operacional ativa.
2. `GET /api/operacao/mapa` retorna `rotas[].trajeto` (`DEPOSITO -> PARADAS -> DEPOSITO`) e mantem `rotas[].paradas` por compatibilidade.

## Contratos Oficiais

Pacote canonico em `contracts/v1`:

- `contracts/v1/openapi.yaml`
- `contracts/v1/events/catalogo-eventos.json`
- `contracts/v1/examples/*.json`

## Comandos Praticos

### 1) Bootstrap de dependencias

Linux/macOS:

```bash
scripts/bootstrap-dev.sh
```

Windows (PowerShell):

```powershell
.\scripts\bootstrap-dev.ps1
```

### 2) Subir ambiente de teste operacional (recomendado)

```bash
scripts/poc/start-test-env.sh
```

Saida esperada:

- API em `http://localhost:8082`
- UI em `http://localhost:4174`
- Solver em `http://localhost:8080`

Smoke rapido:

```bash
curl -sS http://localhost:8082/health
curl -sS http://localhost:8082/api/operacao/painel | jq '.ambiente'
curl -sS http://localhost:8082/api/operacao/mapa | jq '{rotas: (.rotas | length), deposito: .deposito}'
```

### 3) Validar onboarding em Linux limpo (simulacao de VM)

```bash
scripts/poc/smoke-clean-linux.sh
```

Esse smoke sobe um Ubuntu descartavel, instala Java/Maven/Python/Node e executa:

- `scripts/bootstrap-dev.sh`
- `scripts/poc/start-test-env.sh`

Para rodar em paralelo com um ambiente local ja ativo, personalize portas e project name:

```bash
COMPOSE_PROJECT_NAME=av_vmtest_ana POSTGRES_TEST_PORT=55435 SOLVER_PORT=18080 API_PORT=18082 UI_PORT=14174 \
  scripts/poc/smoke-clean-linux.sh
```

### 4) Rodar API manualmente (se necessario)

```bash
SOLVER_URL=http://localhost:8080 API_PORT=8081 \
  mvn -DskipTests exec:java -Dexec.mainClass=com.aguaviva.App -Dexec.args=api
```

## Testes (container-first)

Suite padrao (Java + solver + UI smoke):

```bash
scripts/tests/run-container-tests.sh
```

Atalhos:

```bash
# pular suites especificas
scripts/tests/run-container-tests.sh --skip-ui
scripts/tests/run-container-tests.sh --skip-solver
```

Mutacao (dominio):

```bash
docker compose --profile test run --rm test-java sh -lc 'mvn -Pmutation-tests pitest:mutationCoverage'
```

## Gate Operacional (PoC)

```bash
# rodada diaria
scripts/poc/run-business-gate.sh

# gate forte para PR/nightly
scripts/poc/run-business-gate.sh --mode strict --rounds 3
```

Evidencias em:

- `artifacts/poc/business-gate-<timestamp>/business-summary.json`

## Servicos Docker

`compose.yml` define:

- `postgres-oop-dev` (5434)
- `postgres-oop-test` (5435)
- `nominatim` (8088)
- `osrm` (5000)
- `solver` (8080)
- `api` (8082)
- `migrations-test` (profile `test`)
- `test-java` (profile `test`)
- `test-solver` (profile `test`)
- `test-ui-node` (profile `test`)

## Variaveis de Ambiente Principais

- `POSTGRES_HOST`, `POSTGRES_PORT`, `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`
- `API_POSTGRES_HOST`, `API_POSTGRES_DB`, `API_POSTGRES_USER`, `API_SOLVER_URL`
- `API_PORT`
- `OSRM_DATASET`
- `NOMINATIM_PBF_URL`, `NOMINATIM_PASSWORD`

Resolucao de configuracao de banco no runtime:

1. Variaveis de ambiente do processo (`POSTGRES_*`)
2. `.env` local
3. Fallback padrao do codigo
