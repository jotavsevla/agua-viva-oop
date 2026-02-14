# B0 - Discovery UX, Jornadas e Blueprint

Data: 2026-02-13
Equipe: Time B (Produto/UI)

## Objetivo B0

Definir com clareza o que o frontend operacional precisa entregar nas fases B2-B5,
com base em contratos v1 e handoffs planejados de Time A.

## Escopo funcional alvo

Perfis de usuario:

- Atendente
- Dispatcher
- Financeiro
- Supervisor

Dominios de uso:

- Atendimento e ciclo de pedido
- Linha do tempo (timeline) de pedido
- Saldo/extrato/cobranca de vales
- Despacho operacional com eventos
- Consulta com filtros para produto externo

## Diferenca pratica B0 vs B1

- B0: define jornadas, telas, dados, prioridades e criterios de aceite.
- B1: materializa o sistema visual e os componentes para executar B2 em diante.

## Jornada por perfil

### Atendente

1. Recebe ligacao e cadastra pedido.
2. Confirma retorno da API (pedido criado/idempotente).
3. Acompanha timeline do pedido ate entrega/cancelamento.

Dor principal:

- Perder contexto do estado atual do pedido durante mudancas operacionais.

### Dispatcher

1. Visualiza operacao atual por rota/entregador.
2. Registra eventos operacionais (inicio, entregue, falhou, cancelado).
3. Aciona ou observa replanejamento.

Dor principal:

- Falta de visao unica em tempo quase real para decidir rapido.

### Financeiro

1. Consulta saldo do cliente.
2. Abre extrato por periodo.
3. Trata cobranca de cancelamento quando aplicavel.

Dor principal:

- Nao conseguir reconciliar rapidamente pedido x cobranca.

### Supervisor

1. Acompanha KPIs de operacao.
2. Audita casos com falha/cancelamento.
3. Define prioridade de melhoria e treinamento.

Dor principal:

- Pouca rastreabilidade centralizada para auditoria.

## Blueprint de telas (IA)

Navegacao primaria:

- Dashboard
- Pedidos
- Financeiro
- Despacho
- Clientes

Matriz de telas:

| Tela | Perfil principal | Fonte de dados | Status |
| ---- | ---------------- | -------------- | ------ |
| Dashboard operacional | Supervisor/Dispatcher | A4 + A5 | dependente |
| Novo pedido (atendimento) | Atendente | endpoint atual `/api/atendimento/pedidos` | disponivel |
| Timeline do pedido | Atendente/Dispatcher | A2 | dependente |
| Modulo financeiro | Financeiro | A3 | dependente |
| Painel de despacho com mapa | Dispatcher | A4 | dependente |
| Consulta externa de pedidos/clientes | Atendimento/Cliente | A5 | dependente |

## Mapa de dados para UI

### Dados ja disponiveis (A0/A1 parcial)

- `POST /api/atendimento/pedidos`
  - entrada: `externalCallId` (opcional), `telefone`, `quantidadeGaloes`, `atendenteId`
  - retorno: `pedidoId`, `clienteId`, `telefoneNormalizado`, `clienteCriado`, `idempotente`
- `POST /api/eventos`
  - `ROTA_INICIADA`, `PEDIDO_ENTREGUE`, `PEDIDO_FALHOU`, `PEDIDO_CANCELADO`
- `POST /api/replanejamento/run`
  - retorno de processamento de eventos e criacao de rotas/entregas

### Dados pendentes por handoff

- A2: timeline de transicoes do pedido.
- A3: saldo, extrato e cobranca.
- A4: stream de eventos operacionais (SSE/WS).
- A5: API de produto com filtros, paginacao e RBAC.

## Backlog B0 priorizado

Prioridade P0 (executar agora):

1. Fechar jornadas criticas por perfil com fluxo principal e fluxo de erro.
2. Definir blueprint de navegacao e estrutura de telas.
3. Congelar contrato de consumo v1 para endpoints ja ativos.
4. Desenhar contratos pendentes (A2-A5) em formato de expectativa UI.

Prioridade P1:

1. Definir criterios de sucesso por tela (tempo, erro, confirmacao).
2. Definir padrao de estado (loading/empty/error/success).
3. Definir dicionario de eventos operacionais para interface.

Prioridade P2:

1. Definir KPI inicial para piloto (adocao e tempo de atendimento).
2. Planejar instrumentacao de telemetria de frontend.

## Requisitos nao funcionais

- Responsividade: desktop e mobile.
- Acessibilidade: foco visivel, contraste minimo, navegacao por teclado.
- Resiliencia: fallback quando endpoint pendente/indisponivel.
- Observabilidade: pontos de log por acao operacional critica.

## Riscos e mitigacao

| Risco | Impacto | Mitigacao |
| ---- | ------- | --------- |
| Handoff A2 atrasar | bloqueio de timeline real | usar mock server com contrato congelado |
| A4 sem stream estavel | painel de despacho inconsistente | fallback polling + indicador de atraso |
| Divergencia de contrato | retrabalho alto | versionar contrato em `../contracts/v1/openapi.yaml` |
| Excesso de escopo em B2 | atraso do primeiro uso real | restringir MVP a atendimento + timeline |

## Definition of Done B0

- Jornadas aprovadas por operacao para os 4 perfis.
- Blueprint de telas fechado com prioridades.
- Contrato v1 de consumo publicado para Time B.
- Backlog B1/B2 sequenciado com dependencias explicitas A2-A5.
