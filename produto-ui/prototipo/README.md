# Prototipo B1

Prototipo navegavel do Time B para validar:

- fluxo de pedidos (timeline)
- modulo financeiro (saldo/extrato/cobranca)
- painel de despacho (eventos + mapa placeholder)
- integracao com endpoints reais disponiveis no backend Java

## Como usar

1. Abra `index.html` no navegador.
2. Use a navegacao lateral para trocar de modulo.
3. Use o seletor `Success/Empty/Error` para validar estados de interface.
4. Ajuste `API base` e clique `Testar conexao`.
5. Use os formularios para chamar:
   - `POST /api/atendimento/pedidos`
   - `GET /api/pedidos/{pedidoId}/timeline`
   - `POST /api/eventos`
   - `POST /api/replanejamento/run`

## Notas

- A interface tenta API real e cai em fallback mock quando houver erro de rede/CORS.
- Contratos de payload estao em `../../contracts/v1/openapi.yaml`.
- Exemplos canonicamente em `../../contracts/v1/examples/`.
- Quando executado via servidor HTTP, o prototipo tenta carregar exemplos canonicos automaticamente.
- Apos `POST /api/atendimento/pedidos`, a interface tenta sincronizar a timeline do `pedidoId` retornado.
- O mapa ainda e um placeholder visual; Leaflet entra na fase B4.

## Testes (TDD)

Executar:

```bash
node --test \
  produto-ui/prototipo/tests/timeline-utils.test.js \
  produto-ui/prototipo/tests/timeline-flow.test.js
```

Cobertura atual:

- geracao de path de timeline
- validacao de `pedidoId`
- normalizacao de payload de timeline
- merge de timeline no estado de pedido
- fluxo de busca de timeline com fallback e dependencias injetadas
