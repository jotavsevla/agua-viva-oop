# Prototipo B1

Prototipo navegavel do Time B para validar:

- fluxo de pedidos (timeline)
- modulo financeiro (saldo/extrato/cobranca)
- painel de despacho (eventos + mapa placeholder)
- integracao com endpoints reais disponiveis no backend Java

## Como usar

1. Suba um servidor estatico no diretorio do prototipo:
   - `cd produto-ui/prototipo`
   - `python3 -m http.server 4174`
2. Abra `http://localhost:4174` no navegador.
3. Use a navegacao lateral para trocar de modulo.
4. Use o seletor `Success/Empty/Error` para validar estados de interface.
5. Ajuste `API base` e clique `Testar conexao`.
6. Use os formularios para chamar:
   - `POST /api/atendimento/pedidos`
   - `GET /api/pedidos/{pedidoId}/timeline`
   - `POST /api/eventos`
   - `POST /api/replanejamento/run`
7. No modulo `Pedidos`, escolha `metodoPagamento` (inclui `VALE`) para validar regras de checkout.
8. No modulo `Pedidos`, use `Fluxo guiado E2E (usuario)` para executar:
   - atendimento
   - replanejamento inicial
   - `ROTA_INICIADA` (com `rotaId` informado)
   - evento terminal (`feliz`, `falha`, `cancelamento`)
   - replanejamento final
   - timeline final com validacao de status esperado.

## Notas

- A interface tenta API real e cai em fallback mock quando houver erro de rede/CORS.
- Contratos de payload estao em `../../contracts/v1/openapi.yaml`.
- Exemplos canonicamente em `../../contracts/v1/examples/`.
- Quando executado via servidor HTTP, o prototipo tenta carregar exemplos canonicos automaticamente.
- Apos `POST /api/atendimento/pedidos`, a interface tenta sincronizar a timeline do `pedidoId` retornado.
- No fluxo guiado E2E, se `rotaId` nao for informado, a interface pausa e exibe SQL de apoio para localizar `rota_id` e `entrega_id` do pedido.
- O mapa ainda e um placeholder visual; Leaflet entra na fase B4.
- Para executar os 3 cenarios da PoC em sequencia HTTP congelada, use `scripts/poc/run-cenario.sh`.

## Automacao com Playwright

Pre-requisitos:

1. API Java disponivel em `http://localhost:8081`
2. Solver disponivel em `http://localhost:8080`
3. PostgreSQL dev disponivel (`postgres-oop-dev`)

Instalacao (uma vez):

```bash
cd produto-ui/prototipo
npm install
npx playwright install chromium
```

Execucao:

```bash
# todos os cenarios (feliz, falha, cancelamento)
cd produto-ui/prototipo
npm run test:pw

# um cenario especifico
SCENARIO=feliz npm run test:pw
SCENARIO=falha npm run test:pw
SCENARIO=cancelamento npm run test:pw
```

O teste Playwright faz automaticamente:

1. executa o fluxo guiado ate obter `pedidoId`
2. consulta `rota_id` e `entrega_id` no PostgreSQL
3. aplica `ROTA_INICIADA` e evento terminal no modulo `Despacho`
4. roda replanejamento final
5. valida status final da timeline

## Testes (TDD)

Executar:

```bash
node --test \
  produto-ui/prototipo/tests/operational-e2e.test.js \
  produto-ui/prototipo/tests/timeline-utils.test.js \
  produto-ui/prototipo/tests/timeline-flow.test.js
```

Cobertura atual:

- geracao de path de timeline
- validacao de `pedidoId`
- normalizacao de payload de timeline
- merge de timeline no estado de pedido
- fluxo de busca de timeline com fallback e dependencias injetadas
- mapeamento de cenario terminal (`feliz/falha/cancelamento`) para payload de evento
- validacao de status esperado por cenario e SQL de apoio para descoberta de `rota_id/entrega_id`
