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
| SolverClientTest      | Java      | Unitario   | 10     | Solver     |
| ConnectionFactoryTest | Java      | Integracao | 2      | Repository |
| UserRepositoryTest    | Java      | Integracao | 21     | Repository |
| ClienteRepositoryTest | Java      | Integracao | 13     | Repository |
| PedidoRepositoryTest  | Java      | Integracao | 12     | Repository |
| RotaServiceTest       | Java      | Integracao | 2      | Service    |
| test_vrp              | Python    | Unitario   | 14     | Solver     |
| test_models           | Python    | Unitario   | 6      | Solver     |
| test_matrix           | Python    | Unitario   | 5      | Solver     |

**Total: 161 testes** (137 Java + 24 Python)

---

## Como Rodar

### Java (JUnit 5)

```bash
# Todos os testes (unitarios + integracao)
mvn test

# Apenas uma classe de teste
mvn test -Dtest=PasswordTest
mvn test -Dtest=UserRepositoryTest

# Com output detalhado
mvn test -Dsurefire.useFile=false
```

**Pre-requisito para testes de integracao:** banco de teste rodando.

```bash
docker compose up -d postgres-oop-test
./apply-migrations.sh
```

### Python (pytest)

```bash
cd solver
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
pytest tests/ -v

# alternativa sem ativar venv (mais robusta)
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
| `SolverClientTest`| Serializacao Java→JSON (snake_case via Gson), deserializacao JSON→Java, roundtrip request/response, construcao do client |

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
| `RotaServiceTest`      | Orquestracao fim-a-fim da roteirizacao (solver stub HTTP + PostgreSQL real), persistencia de `rotas`/`entregas` e atualizacao de status dos pedidos atendidos |

**Caracteristicas:**
- Precisam de `postgres-oop-test` rodando (Docker)
- Banco de teste usa **tmpfs** (dados em memoria, sem persistencia)
- Limpeza automatica entre testes (delecao ou `TRUNCATE ... RESTART IDENTITY CASCADE`, dependendo da suite)
- Lifecycle JUnit 5: `@BeforeAll` (cria pool), `@BeforeEach` (limpa dados), `@AfterEach` (quando necessario) e `@AfterAll` (fecha pool)
- Tempo de execucao: ~20-25s (inclui startup do HikariCP)

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

### SolverClientTest (10 testes)

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
```

### Testes Python (24 testes)

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
| 7    | Expandir RotaServiceTest (rollback, erro de solver, idempotencia) |
| 8    | PedidoStateMachineTest (transicoes de status)              |
| 9    | ValeServiceTest (debito atomico, saldo, movimentacoes)     |
