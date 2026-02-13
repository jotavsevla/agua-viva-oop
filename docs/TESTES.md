# Testes — Agua Viva OOP

Guia completo da suite de testes do projeto.
Metodologia TDD: teste escrito **antes** da implementacao.

---

## Resumo

| Suite                 | Linguagem | Tipo       | Testes | Camada     |
| --------------------- | --------- | ---------- | ------ | ---------- |
| PasswordTest          | Java      | Unitario   | 18     | Domain     |
| UserTest              | Java      | Unitario   | 27     | Domain     |
| ClienteTest           | Java      | Unitario   | 16     | Domain     |
| PedidoTest            | Java      | Unitario   | 16     | Domain     |
| PedidoStateMachineTest| Java      | Unitario   | 8      | Domain     |
| SolverClientTest      | Java      | Unitario   | 12     | Solver     |
| ConnectionFactoryTest | Java      | Integracao | 2      | Repository |
| UserRepositoryTest    | Java      | Integracao | 21     | Repository |
| ClienteRepositoryTest | Java      | Integracao | 13     | Repository |
| PedidoRepositoryTest  | Java      | Integracao | 12     | Repository |
| RotaServiceTest       | Java      | Integracao | 9      | Service    |
| PedidoLifecycleServiceTest | Java | Integracao | 3      | Service    |
| AtendimentoTelefonicoServiceTest | Java | Integracao | 5      | Service    |
| ExecucaoEntregaServiceTest | Java | Integracao | 4      | Service    |
| ReplanejamentoWorkerServiceTest | Java | Integracao | 3      | Service    |
| test_vrp              | Python    | Unitario   | 14     | Solver     |
| test_models           | Python    | Unitario   | 6      | Solver     |
| test_matrix           | Python    | Unitario   | 5      | Solver     |
| test_main_async       | Python    | Unitario   | 2      | Solver     |

**Total: 196 testes** (169 Java + 27 Python)

---

## Como Rodar

### Java (JUnit 5)

```bash
# 0) preparar ambiente local (uma vez)
cp .env.example .env

# 1) banco de teste
docker compose up -d postgres-oop-test
CONTAINER_NAME=postgres-oop-test POSTGRES_DB=agua_viva_oop_test ./apply-migrations.sh

# 2) todos os testes Java (unitarios + integracao)
mvn test

# Apenas uma classe de teste
mvn test -Dtest=PasswordTest
mvn test -Dtest=UserRepositoryTest
mvn -Dtest=AtendimentoTelefonicoServiceTest,ExecucaoEntregaServiceTest,ReplanejamentoWorkerServiceTest test

# Com output detalhado
mvn test -Dsurefire.useFile=false
```

**Pre-requisito para testes de integracao:** `postgres-oop-test` rodando e migracoes `001-011` aplicadas no banco de teste.

```bash
docker compose up -d postgres-oop-test
CONTAINER_NAME=postgres-oop-test POSTGRES_DB=agua_viva_oop_test ./apply-migrations.sh
```

### Python (pytest)

```bash
cd solver
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
pytest tests/ -v

# alternativa sem ativar venv (mais robusta)
.venv/bin/python -m pytest tests/ -v

# alternativa quando python do sistema nao enxerga dependencias do projeto
python3 -m pytest tests/ -v
```

Se aparecer `ModuleNotFoundError` ou `No module named pytest`, rode o pytest usando o interpretador do proprio venv:

```bash
cd solver
.venv/bin/python -m pytest tests/ -v
```

---

## Testes Unitarios vs Integracao

### Testes Unitarios

Testam logica pura **sem dependencias externas** (sem banco, sem rede, sem Docker).

| Classe            | O que testa                                                  |
| ----------------- | ------------------------------------------------------------ |
| `PasswordTest`    | Validacao de politica de senha, hashing BCrypt, matching, reconstrucao de hash, Value Object (equals/hashCode/toString) |
| `UserTest`        | Invariantes do construtor, hierarquia de papeis (`podeGerenciar`), composicao com Password, identidade de entidade, normalizacao de email |
| `ClienteTest`     | Invariantes de cadastro, tipo de cliente, coordenadas e identidade da entidade |
| `PedidoTest`      | Invariantes de quantidade/janela/status, regras de construcao e identidade da entidade |
| `PedidoStateMachineTest` | Transicoes de status validas/invalidas e regra de cobranca por cancelamento em rota |
| `SolverClientTest`| Serializacao Java→JSON (snake_case via Gson), deserializacao JSON→Java, roundtrip request/response e contratos async |

**Caracteristicas:**
- Executam em milissegundos
- Nao precisam de Docker/banco
- Testam regras de negocio isoladamente
- Estao em `src/test/java/com/aguaviva/domain/` e `solver/`

### Testes de Integracao

Testam a **interacao com o banco PostgreSQL real** (porta 5435, tmpfs).

| Classe                 | O que testa                                                      |
| ---------------------- | ---------------------------------------------------------------- |
| `ConnectionFactoryTest`| Conexao com banco de teste, versao do PostgreSQL                 |
| `UserRepositoryTest`   | CRUD completo (save, findById, findByEmail, findAll, update, desativar), constraints de email unico, mapeamento de todos os enum `UserPapel`, campos opcionais (telefone), hash de senha persistido corretamente |
| `ClienteRepositoryTest`| CRUD completo (save, findById, findByTelefone, findAll, update), constraint de telefone unico, mapeamento de enum `ClienteTipo` e coordenadas |
| `PedidoRepositoryTest` | CRUD completo (save, findById, findByCliente, findPendentes, update), mapeamento de `JanelaTipo`/`PedidoStatus`, validacao de FKs (`cliente_id`, `criado_por`) |
| `RotaServiceTest`      | Orquestracao fim-a-fim da roteirizacao (solver stub HTTP + PostgreSQL real), persistencia de `rotas`/`entregas`, rollback transacional, idempotencia, reprocessamento sem conflito de `numero_no_dia` e short-circuit quando nao ha elegiveis |
| `PedidoLifecycleServiceTest` | Porta unica de transicao de status com lock pessimista (`SELECT ... FOR UPDATE`) e efeitos financeiros de cancelamento |
| `AtendimentoTelefonicoServiceTest` | Intake telefonico transacional com normalizacao de telefone e idempotencia por `external_call_id` |
| `ExecucaoEntregaServiceTest` | Aplicacao dos eventos operacionais (`ROTA_INICIADA`, `PEDIDO_ENTREGUE`, `PEDIDO_FALHOU`, `PEDIDO_CANCELADO`) |
| `ReplanejamentoWorkerServiceTest` | Debounce de eventos no outbox `dispatch_events`, lock distribuido e marcacao de processamento |

**Caracteristicas:**
- Precisam de `postgres-oop-test` rodando (Docker)
- Banco de teste usa **tmpfs** (dados em memoria, sem persistencia)
- Limpeza automatica entre testes (delecao ou `TRUNCATE ... RESTART IDENTITY CASCADE`, dependendo da suite)
- Lifecycle JUnit 5: `@BeforeAll` (cria pool), `@BeforeEach` (limpa dados), `@AfterEach` (quando necessario) e `@AfterAll` (fecha pool)
- Tempo de execucao: ~25-30s (inclui startup do HikariCP)

---

## Detalhamento por Suite

### PasswordTest (18 testes)

```
Validacao (politica de senha)
  deveRejeitarSenhaNula
  deveRejeitarSenhaVazia
  deveRejeitarSenhaComEspacosEmBranco
  deveRejeitarSenhaCurta
  deveAceitarSenhaValida

Criacao via fromPlainText
  deveCriarPasswordAPartirDePlainText
  fromPlainTextDeveRejeitarSenhaInvalida
  deveGerarHashDiferenteDaSenhaOriginal
  deveGerarHashesDiferentesParaMesmaSenha   (salt aleatorio)

Comparacao (matches)
  deveCompararCorretamenteComSenhaCorreta
  deveRejeitarSenhaIncorreta

Reconstrucao via fromHash
  deveCriarPasswordAPartirDeHash
  deveLancarExcecaoParaHashNulo
  deveLancarExcecaoParaHashVazio

Persistencia (toHash)
  deveExporHashParaPersistencia

Value Object (equals/hashCode/toString)
  passwordsComMesmoHashDevemSerIguais
  passwordsComHashesDiferentesNaoDevemSerIguais
  toStringNaoDeveExporOHash                (seguranca)
```

### UserTest (27 testes)

```
Criacao valida
  deveCriarUsuarioValidoComDadosObrigatorios
  deveCriarUsuarioValidoComTodosOsCampos

Invariantes do construtor
  deveRejeitarNomeNulo / Vazio / EspacosEmBranco
  deveRejeitarEmailNulo / Vazio / SemArroba
  deveRejeitarPasswordNulo
  deveRejeitarPapelNulo

Hierarquia de papeis (10 testes)
  supervisorPodeGerenciar → Admin, Atendente, Entregador
  adminPodeGerenciar → Atendente, Entregador
  adminNaoPodeGerenciar → Supervisor, OutroAdmin
  atendentePodeGerenciar → Entregador
  atendenteNaoPodeGerenciar → Admin
  entregadorNaoPodeGerenciarNinguem

Composicao com Password
  deveVerificarSenhaCorreta
  deveRejeitarSenhaIncorreta

Identidade (equals/hashCode/toString)
  usuariosComMesmoIdDevemSerIguais
  usuariosComIdsDiferentesNaoDevemSerIguais
  usuariosSemIdNaoDevemSerIguaisEntreSi
  toStringNaoDeveExporSenha

Normalizacao
  deveNormalizarEmailParaMinusculo
```

### UserRepositoryTest (21 testes)

```
save (inserir)
  deveSalvarUsuarioERetornarComIdGerado
  deveSalvarUsuarioERecuperarPorId
  deveSalvarUsuarioComSenhaHashEConseguirVerificar
  deveRetornarOptionalVazioQuandoIdNaoExiste

Mapeamento de enum
  deveSalvarERecuperarTodosOsPapeis       (4 valores do enum)

findByEmail
  deveEncontrarUsuarioPorEmail
  deveEncontrarUsuarioPorEmailIgnorandoCase
  deveRetornarOptionalVazioQuandoEmailNaoExiste

Restricao de email unico
  deveLancarExcecaoAoSalvarEmailDuplicado
  deveLancarExcecaoComMensagemInformativaParaEmailDuplicado

findAll
  deveRetornarListaVaziaQuandoNaoHaUsuarios
  deveRetornarTodosOsUsuarios
  deveRetornarUsuariosOrdenadosPorId

update
  deveAtualizarDadosDoUsuario
  deveLancarExcecaoAoAtualizarUsuarioInexistente
  deveLancarExcecaoAoAtualizarParaEmailDuplicado

desativar (soft delete)
  deveDesativarUsuarioExistente
  deveRetornarFalseAoDesativarUsuarioJaInativo
  deveRetornarFalseAoDesativarIdInexistente

Campos opcionais
  deveSalvarUsuarioComTelefone
  deveSalvarUsuarioSemTelefone
```

### SolverClientTest (12 testes)

```
Serializacao Java → JSON
  deveSerializarRequestComPedidoAsap
  deveSerializarRequestComPedidoHard
  deveSerializarCoordenadaDoDeposito

Deserializacao JSON → Java
  deveDeserializarResponseComRotas
  deveDeserializarResponseComNaoAtendidos
  deveDeserializarResponseComMultiplasRotas

Roundtrip
  deveSerializarEDeserializarCorretamente

Construcao do client
  deveCriarClientComUrl
  deveRejeitarUrlNula
  deveRejeitarUrlVazia

Metadados async
  deveSerializarMetadadosDeJobAsyncNoRequest
  deveDeserializarResultadoDeJobAsync
```

### Suites Service (24 testes)

```
RotaServiceTest (9 testes)
  Planejamento completo com solver stub + persistencia de rotas/entregas
  Rollback em falhas e short-circuit sem elegiveis
  Idempotencia e reprocessamento sem duplicacao

PedidoLifecycleServiceTest (3 testes)
  Transicao centralizada com lock pessimista
  Cobranca em cancelamento em rota (PENDENTE/NAO_APLICAVEL)
  Rejeicao de transicao invalida pelo dominio

AtendimentoTelefonicoServiceTest (5 testes)
  Normalizacao de telefone e criacao de cliente minimo
  Criacao de pedido em PENDENTE
  Idempotencia por `external_call_id`

ExecucaoEntregaServiceTest (4 testes)
  Eventos `ROTA_INICIADA`, `PEDIDO_ENTREGUE`, `PEDIDO_FALHOU`, `PEDIDO_CANCELADO`
  Atualizacao consistente de `rotas`, `entregas` e `pedidos`

ReplanejamentoWorkerServiceTest (3 testes)
  Debounce por janela de tempo
  Lock distribuido para evitar worker concorrente
  Marcacao de eventos processados no outbox `dispatch_events`
```

### Testes Python (27 testes)

```
test_vrp.py (14 testes)
  Conversao de tempo: hhmm_to_seconds, seconds_to_hhmm
  Solver: input vazio, entrega unica, restricao de capacidade
  Multiplas viagens, janelas de tempo

test_models.py (6 testes)
  Validacao Pydantic: defaults, time windows, galoes >= 1
  Construcao de SolverRequest

test_matrix.py (5 testes)
  Haversine: mesmo ponto (0m), distancias conhecidas
  Simetria: distancia A→B = distancia B→A
  Fallback: OSRM indisponivel → Haversine

test_main_async.py (2 testes)
  Cancelamento previo descarta resultado de `/solve`
  Job async retorna resultado consultavel em `/result/{job_id}`
```

---

## Convencoes

### Nomenclatura

Testes em **portugues**, descritivos, no padrao `deveVerboCondicao`:
- `deveRejeitarSenhaNula`
- `deveSalvarUsuarioERetornarComIdGerado`
- `supervisorPodeGerenciarAdmin`

### Organizacao

Cada arquivo de teste usa **blocos comentados** para agrupar testes por responsabilidade:

```java
// ========================================================================
// Validacao (politica de senha)
// ========================================================================
```

### Helpers

Metodos auxiliares privados para evitar repeticao:

```java
private Password senhaValida() {
    return Password.fromPlainText("senha123");
}

private User criarComPapel(UserPapel papel) {
    return new User("Teste", "teste@email.com", senhaValida(), papel);
}
```

### Sem Mocking

O projeto **nao usa mocks** (Mockito, etc.).
- Testes unitarios testam objetos puros
- Testes de integracao usam banco real (PostgreSQL em tmpfs)

### Isolamento

Testes de integracao garantem isolamento com limpeza automatica:

```java
@BeforeEach
void limparTabela() throws Exception {
    try (Connection conn = factory.getConnection();
         Statement stmt = conn.createStatement()) {
        stmt.execute("DELETE FROM users");
        stmt.execute("ALTER SEQUENCE users_id_seq RESTART WITH 1");
    }
}
```

---

## Infraestrutura de Teste

| Componente      | Configuracao                              |
| --------------- | ----------------------------------------- |
| Framework       | JUnit 5 (Jupiter) 5.11.3                 |
| Runner Maven    | maven-surefire-plugin 3.5.2              |
| Banco de teste  | PostgreSQL 16 Alpine, porta 5435         |
| Storage         | tmpfs (dados em memoria, rapido)          |
| Pool de conexao | HikariCP (max 5, timeout 5s)             |
| Credenciais     | postgres/postgres (via `.env`)            |
| Python          | pytest 8.0.0                              |

---

## Testes Futuros (por fase)

| Fase | Testes planejados                                         |
| ---- | --------------------------------------------------------- |
| 7    | Expandir RotaServiceTest (concorrencia multi-instancia e conflitos simultaneos) |
| 8    | Trilha de auditoria por transicao e regras de cobranca/compensacao por janela operacional |
| 9    | ValeServiceTest (debito atomico, saldo, movimentacoes)     |
