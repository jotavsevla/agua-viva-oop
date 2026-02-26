const test = require("node:test");
const assert = require("node:assert/strict");

const {
  buildAtendimentoPayloadFromFormData,
  readAtendimentoFormState
} = require("../lib/front/atendimento");

function createFormData(entries) {
  return {
    get(name) {
      return Object.prototype.hasOwnProperty.call(entries, name) ? entries[name] : null;
    }
  };
}

test("deveMontarPayloadBasicoDeAtendimento", () => {
  const payload = buildAtendimentoPayloadFromFormData(
    createFormData({
      telefone: "(38) 99876-1234",
      quantidadeGaloes: "2",
      atendenteId: "7",
      metodoPagamento: "PIX",
      janelaTipo: "ASAP"
    })
  );

  assert.deepEqual(payload, {
    telefone: "(38) 99876-1234",
    quantidadeGaloes: 2,
    atendenteId: 7,
    metodoPagamento: "PIX",
    janelaTipo: "ASAP"
  });
});

test("deveMontarPayloadComCadastroClienteEGeo", () => {
  const payload = buildAtendimentoPayloadFromFormData(
    createFormData({
      telefone: "(38) 99876-2222",
      quantidadeGaloes: "1",
      atendenteId: "1",
      origemCanal: "MANUAL",
      manualRequestId: "m-123",
      externalCallId: "m-123",
      nomeCliente: "Maria Clara",
      endereco: "Rua A, 10",
      latitude: "-16.7310",
      longitude: "-43.8710"
    })
  );

  assert.deepEqual(payload, {
    telefone: "(38) 99876-2222",
    quantidadeGaloes: 1,
    atendenteId: 1,
    origemCanal: "MANUAL",
    manualRequestId: "m-123",
    externalCallId: "m-123",
    nomeCliente: "Maria Clara",
    endereco: "Rua A, 10",
    latitude: -16.731,
    longitude: -43.871
  });
});

test("deveRejeitarSourceEventIdEmCanalManual", () => {
  assert.throws(
    () =>
      buildAtendimentoPayloadFromFormData(
        createFormData({
          telefone: "38998761234",
          quantidadeGaloes: "1",
          atendenteId: "1",
          origemCanal: "MANUAL",
          sourceEventId: "evt-1"
        })
      ),
    /sourceEventId nao pode ser usado com origemCanal=MANUAL/i
  );
});

test("deveRejeitarCanalAutomaticoSemSourceEventId", () => {
  assert.throws(
    () =>
      buildAtendimentoPayloadFromFormData(
        createFormData({
          telefone: "38998761234",
          quantidadeGaloes: "1",
          atendenteId: "1",
          origemCanal: "WHATSAPP"
        })
      ),
    /sourceEventId obrigatorio para origemCanal=WHATSAPP/i
  );
});

test("deveRejeitarLatitudeSemLongitude", () => {
  assert.throws(
    () =>
      buildAtendimentoPayloadFromFormData(
        createFormData({
          telefone: "38998761234",
          quantidadeGaloes: "1",
          atendenteId: "1",
          latitude: "-16.7"
        })
      ),
    /latitude e longitude devem ser informadas juntas/i
  );
});

test("deveRejeitarJanelaHardSemHorarioCompleto", () => {
  assert.throws(
    () =>
      buildAtendimentoPayloadFromFormData(
        createFormData({
          telefone: "38998761234",
          quantidadeGaloes: "1",
          atendenteId: "1",
          janelaTipo: "HARD",
          janelaInicio: "09:00"
        })
      ),
    /janelaTipo=HARD exige janelaInicio e janelaFim/i
  );
});

test("deveRejeitarDivergenciaEntreManualRequestIdEExternalCallId", () => {
  assert.throws(
    () =>
      buildAtendimentoPayloadFromFormData(
        createFormData({
          telefone: "38998761234",
          quantidadeGaloes: "1",
          atendenteId: "1",
          origemCanal: "MANUAL",
          manualRequestId: "m-1",
          externalCallId: "m-2"
        })
      ),
    /manualRequestId diverge de externalCallId/i
  );
});

test("deveLerEstadoDoFormularioPreservandoStrings", () => {
  const state = readAtendimentoFormState(
    createFormData({
      telefone: " 38998761234 ",
      quantidadeGaloes: " 3 ",
      atendenteId: " 9 ",
      origemCanal: "manual",
      sourceEventId: " evt-1 ",
      metodoPagamento: "pix"
    })
  );

  assert.equal(state.telefone, "38998761234");
  assert.equal(state.quantidadeGaloes, "3");
  assert.equal(state.atendenteId, "9");
  assert.equal(state.origemCanal, "MANUAL");
  assert.equal(state.sourceEventId, "evt-1");
  assert.equal(state.metodoPagamento, "PIX");
});
