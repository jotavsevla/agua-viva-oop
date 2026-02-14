# B1 - Design System Base e Prototipo Navegavel

Data: 2026-02-13
Equipe: Time B (Produto/UI)

## Objetivo B1

Entregar a base visual reutilizavel e um prototipo navegavel cobrindo os fluxos
principais definidos em B0.

## Direcao visual

Tema: "centro de operacao de campo".

Principios:

- Densidade alta de informacao sem perder legibilidade.
- Contraste forte para leitura em operacao.
- Sinais semaforicos para estado (ok, alerta, critico).
- Animacao util para orientar mudanca de estado.

## Tokens

Tokens vivem em `prototipo/styles.css` no bloco `:root`.

Categorias:

- Cores de superficie e texto
- Cores semanticas (`--ok`, `--warn`, `--danger`, `--info`)
- Espacamento (`--space-*`)
- Radius (`--radius-*`)
- Sombra (`--shadow-*`)
- Tipografia (`--font-heading`, `--font-body`, `--font-mono`)

## Componentes base

Catalogo inicial:

1. `AppShell`: topo + navegacao + area de conteudo.
2. `StatusPill`: etiqueta de estado operacional.
3. `MetricCard`: indicadores de topo.
4. `Panel`: bloco de conteudo com titulo e acoes.
5. `DataTable`: tabela para timeline e extrato.
6. `EventRow`: linha de evento operacional com timestamp.
7. `ActionButton`: acao primaria/secundaria/perigo.
8. `EmptyState`: fallback para ausencia de dados.
9. `Notice`: alerta de erro/aviso/informacao.

## Estados de interface padrao

Cada tela deve implementar:

- `loading`
- `success`
- `empty`
- `error`

Regra:

- Sem spinner infinito; sempre exibir contexto de recuperacao.

## Layout e breakpoints

- Desktop: `>= 1200px`
- Tablet: `768px - 1199px`
- Mobile: `< 768px`

Comportamento:

- Painel principal em grid no desktop.
- Pilha vertical com navegacao compacta em mobile.

## Acessibilidade minima

- Contraste alto em texto e status.
- Foco de teclado visivel em links e botoes.
- Elementos clicaveis com area minima de toque.
- Tabelas com cabecalho semantico.

## Prototipo navegavel entregue

Diretorio: `prototipo/`

Fluxos contemplados:

1. `Pedidos`: timeline operacional com status e idempotencia.
2. `Financeiro`: saldo, extrato e cobranca pendente.
3. `Despacho`: eventos recentes e mapa simplificado (placeholder visual de B4).
4. Integracao real com API atual:
   - `POST /api/atendimento/pedidos`
   - `POST /api/eventos`
   - `POST /api/replanejamento/run`

## Integracao com contratos

- Dados do prototipo partem de `../contracts/v1/examples/`.
- Em execucao via HTTP, o prototipo tenta carregar esses exemplos automaticamente em runtime.
- Estrutura preparada para trocar mock por API real sem mudar UI.

## Definition of Done B1

- Tokens definidos e aplicados nas telas principais.
- Componentes base reutilizaveis implementados no prototipo.
- Navegacao entre fluxos funcionando em desktop e mobile.
- Estados de sucesso/erro/vazio modelados no frontend base.
