# Time B - Produto/UI

Este diretorio concentra os entregaveis de Time B (Produto/UI) para as fases B0 e B1.

## Conteudo

- `B0-discovery.md`: discovery, jornadas, blueprint, mapa de dados e backlog.
- `B1-design-system.md`: foundation de design system e regras de uso.
- `PROXIMAS-ETAPAS.md`: plano executavel B2-B4 com dependencias A2-A5 e checklist de handoff.
- `contracts/README.md`: ponte para contratos canonicos em `../contracts/v1`.
- `prototipo/`: prototipo navegavel responsivo (HTML/CSS/JS puro).
  - inclui chamadas reais para endpoints de atendimento/eventos/replanejamento.

## Execucao do prototipo

Opcao 1:

- abrir `prototipo/index.html` no navegador.

Opcao 2:

- servir localmente com qualquer server estatico (ex.: `python3 -m http.server`).

Campos de request para endpoints atuais usam `camelCase` conforme `ApiServer`.

## Fonte canonica de contrato

Usar sempre:

- `../contracts/v1/openapi.yaml`
- `../contracts/v1/examples/*.json`

## Fronteira com Time A

- Sem alteracao de migrations, regras transacionais ou services Java.
- Contratos devem ser revisados com aprovacao A+B quando houver mudanca.
