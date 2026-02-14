# Contracts compartilhados

Este diretorio e a fonte canonica dos contratos entre backend (Time A) e produto/UI (Time B).

## Ownership

- Dono primario de implementacao backend: Time A
- Dono primario de consumo frontend: Time B
- Alteracoes em `/contracts` exigem aprovacao A+B

## Versionamento

- SemVer por major de contrato: `v1`, `v2`, ...
- Mudanca **breaking**: cria nova major (`v2`) e periodo de compatibilidade acordado
- Mudanca **non-breaking**: evolucao em `v1.x` com `CHANGELOG` atualizado
- Artefatos publicados por versao ficam em `contracts/v{major}/`

## Politica de compatibilidade

- Campos existentes nao podem mudar tipo nem semantica em `v1`
- Remocao de campo/endpoint em `v1` e proibida
- Novo campo opcional e permitido em `v1.x`
- Endpoint novo e permitido em `v1.x` sem alterar comportamento anterior

## Conteudo minimo por versao

- `openapi.yaml`
- `events/catalogo-eventos.json`
- `examples/*.json`
- `CHANGELOG.md`
