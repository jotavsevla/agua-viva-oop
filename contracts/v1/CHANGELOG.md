# Changelog - Contracts v1

## 1.0.2 - 2026-02-13

- Marca `GET /api/pedidos/{pedidoId}/timeline` como `x-handoff-status: ready-handoff`.
- Adiciona exemplo `pedido-timeline.response.json` para timeline de pedido.
- Mantem compatibilidade com `v1.0.0` (alteracao nao-breaking).

## 1.0.1 - 2026-02-13

- Adiciona exemplo `atendimento-pedido-manual.request.json` para fluxo sem `externalCallId`.
- Mantem compatibilidade com `v1.0.0` (alteracao nao-breaking).

## 1.0.0 - 2026-02-13

- Congelamento inicial de `contracts/v1`.
- OpenAPI com endpoints core operacionais:
  - `GET /health`
  - `POST /api/atendimento/pedidos`
  - `POST /api/eventos`
  - `POST /api/replanejamento/run`
- Catalogo de eventos operacionais e de replanejamento.
- Exemplos JSON de request/response para endpoints core.
