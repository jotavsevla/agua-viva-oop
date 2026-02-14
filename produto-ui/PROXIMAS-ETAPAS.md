# Proximas Etapas - Time B (Produto/UI)

Data base: 2026-02-13

## Objetivo do proximo ciclo

Sair de B1 (foundation + prototipo) para B2 com uso operacional real
do fluxo de atendimento e timeline, mantendo contratos alinhados com Time A.

## Status atual

- B2 iniciado no prototipo:
  - formulario de consulta de timeline por `pedidoId`
  - integracao com `GET /api/pedidos/{pedidoId}/timeline` (quando disponivel)
  - fallback local quando A2 nao estiver disponivel
  - utilitarios de timeline cobertos por teste em `node --test`

## Sequenciamento recomendado (B2 -> B4)

### Etapa 1 - B2 MVP (2026-02-16 ate 2026-02-27)

Escopo:

- Converter prototipo atual em app frontend versionado para operacao interna.
- Entregar fluxo `atendimento -> timeline` com fallback controlado.
- Publicar estados padrao (`loading/success/empty/error`) em todas as telas B2.

Dependencias:

- A2 para timeline real (`/api/pedidos/{pedidoId}/timeline`).
- Enquanto A2 nao fechar: manter mock com contrato canonico `contracts/v1`.

Saidas esperadas:

- Backoffice de pedidos utilizavel por atendimento.
- Guia de operacao da tela de pedidos para time de negocio.

### Etapa 2 - B3 Financeiro (2026-03-02 ate 2026-03-13)

Escopo:

- Implementar saldo, extrato e cobranca no frontend com fluxos de erro.
- Unificar UX de status financeiro com pedido operacional.

Dependencias:

- A3 para endpoints de saldo/extrato/cobranca.

Saidas esperadas:

- Modulo financeiro funcional para operacao.
- Checklist de reconciliacao pedido x cobranca.

### Etapa 3 - B4 Despacho (2026-03-16 ate 2026-03-27)

Escopo:

- Trocar placeholder de mapa por Leaflet.
- Integrar stream de eventos (SSE/WS) com estrategia de reconexao.
- Implementar reconciliacao de estado (snapshot + eventos incrementais).

Dependencias:

- A4 para stream operacional.

Saidas esperadas:

- Centro de despacho v1 com visao quase real-time.
- Runbook de incidentes de stream.

## Matriz de dependencias A x B

| Dependencia | Responsavel primario | Impacto no Time B | Plano de contingencia |
| ----------- | -------------------- | ----------------- | --------------------- |
| A2 timeline | Time A | bloqueia B2 real | usar mock com contrato v1 |
| A3 financeiro | Time A | bloqueia B3 real | desenvolver UI com exemplos canonicos |
| A4 stream | Time A | bloqueia B4 real-time | fallback polling com aviso de atraso |
| A5 API produto | Time A | bloqueia B5 | preparar componente de lista paginada em modo mock |

## Documentacao obrigatoria por etapa

Para cada etapa (B2/B3/B4), atualizar:

1. `produto-ui/PROXIMAS-ETAPAS.md` com status da etapa.
2. `produto-ui/B1-design-system.md` quando houver componente novo.
3. `contracts/v1/CHANGELOG.md` se houver mudanca de contrato em v1.x.
4. `contracts/v1/examples/*.json` com exemplos de sucesso e erro.
5. `README.md` (raiz) apenas quando houver mudanca relevante para setup/uso.

## Definition of Ready (DoR) para iniciar cada etapa

- Contrato revisado (ou mock aprovado) para os endpoints da etapa.
- Criterios de aceite da tela definidos com negocio.
- Fluxos de erro mapeados.
- Plano de teste definido (unitario + integracao UI/API).

## Definition of Done (DoD) para fechar cada etapa

- Fluxo principal funcionando com dados reais (ou mock explicitamente sinalizado).
- Estados de interface completos (loading/success/empty/error).
- Documentacao de uso atualizada.
- Contratos/exemplos sincronizados.
- Validacao tecnica concluida no escopo.

## Checklist de handoff A+B

Antes de aprovar PR de contrato em `contracts/v1`:

1. Confirmar compatibilidade backward em v1.x.
2. Atualizar `CHANGELOG.md`.
3. Publicar exemplo request/response.
4. Validar teste de contrato (`ContractsV1Test`).
5. Registrar impacto de UI no plano de proximas etapas.

## Riscos imediatos e mitigacao

Risco:
Inconsistencia de naming entre endpoint real e contrato.
Mitigacao:
Manter `camelCase` como convencao do payload HTTP atual e validar em PR.

Risco:
Frontend depender de endpoint ainda nao entregue.
Mitigacao:
Feature flag + fallback mock com rotulo visivel de degradacao.

Risco:
Retrabalho de UX por escopo inflado.
Mitigacao:
Travar MVP por etapa e postergar itens de baixa prioridade.
