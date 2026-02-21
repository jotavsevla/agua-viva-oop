const { execFileSync } = require("node:child_process");
const path = require("node:path");
const { test, expect } = require("@playwright/test");

const API_BASE = process.env.API_BASE || "http://localhost:8082";
const DB_CONTAINER = process.env.DB_CONTAINER || "postgres-oop-test";
const DB_SERVICE = process.env.DB_SERVICE || DB_CONTAINER;
const COMPOSE_FILE = process.env.COMPOSE_FILE || "compose.yml";
const DB_USER = process.env.DB_USER || "postgres";
const DB_NAME = process.env.DB_NAME || "agua_viva_oop_test";
const ROOT_DIR = path.resolve(__dirname, "..", "..", "..");
const DEFAULT_SCENARIOS = ["feliz", "falha", "cancelamento"];

function normalizeScenario(value) {
  const scenario = String(value || "").trim().toLowerCase();
  if (!DEFAULT_SCENARIOS.includes(scenario)) {
    throw new Error(`cenario invalido: ${value}`);
  }
  return scenario;
}

function scenariosToRun() {
  const raw = process.env.SCENARIO;
  if (!raw) {
    return DEFAULT_SCENARIOS;
  }

  return raw
    .split(",")
    .map((part) => normalizeScenario(part))
    .filter((value, index, arr) => arr.indexOf(value) === index);
}

function expectedStatusForScenario(scenario) {
  return scenario === "feliz" ? "ENTREGUE" : "CANCELADO";
}

function terminalEventTypeForScenario(scenario) {
  if (scenario === "feliz") {
    return "PEDIDO_ENTREGUE";
  }
  if (scenario === "falha") {
    return "PEDIDO_FALHOU";
  }
  return "PEDIDO_CANCELADO";
}

function extractPedidoId(text) {
  const match = String(text || "").match(/pedidoId=(\d+)/);
  if (!match) {
    throw new Error(`pedidoId nao encontrado no resumo: ${text}`);
  }
  return Number(match[1]);
}

function fetchEntregaRotaByPedidoId(pedidoId) {
  const safePedidoId = Number(pedidoId);
  if (!Number.isInteger(safePedidoId) || safePedidoId <= 0) {
    throw new Error(`pedidoId invalido para lookup: ${pedidoId}`);
  }

  const sql =
    `SELECT e.rota_id, e.id AS entrega_id FROM entregas e ` +
    `WHERE e.pedido_id = ${safePedidoId} ORDER BY e.id DESC LIMIT 1;`;

  const raw = execSql(sql)
    .trim()
    .split("\n")
    .filter(Boolean)
    .at(-1);

  if (!raw) {
    throw new Error(`nenhuma entrega encontrada para pedidoId=${safePedidoId}`);
  }

  const [rotaRaw, entregaRaw] = raw.split("|").map((part) => part.trim());
  const rotaId = Number(rotaRaw);
  const entregaId = Number(entregaRaw);

  if (!Number.isInteger(rotaId) || rotaId <= 0 || !Number.isInteger(entregaId) || entregaId <= 0) {
    throw new Error(`retorno invalido do psql para pedidoId=${safePedidoId}: ${raw}`);
  }

  return { rotaId, entregaId };
}

function sqlEscape(value) {
  return String(value || "").replace(/'/g, "''");
}

function execSql(sql) {
  return execFileSync(
    "docker",
    [
      "compose",
      "-f",
      path.join(ROOT_DIR, COMPOSE_FILE),
      "exec",
      "-T",
      DB_SERVICE,
      "psql",
      "-U",
      DB_USER,
      "-d",
      DB_NAME,
      "-q",
      "-Atc",
      sql
    ],
    { encoding: "utf8" }
  ).trim();
}

function extractLastInteger(raw, label) {
  const line = String(raw || "")
    .split("\n")
    .map((part) => part.trim())
    .filter((part) => /^\d+$/.test(part))
    .at(-1);
  const value = Number(line);
  if (!Number.isInteger(value) || value <= 0) {
    throw new Error(`nao foi possivel extrair inteiro para ${label}: ${raw}`);
  }
  return value;
}

function ensureAtendenteId(email) {
  const safeEmail = sqlEscape(email);
  const sql =
    "INSERT INTO users (nome, email, senha_hash, papel, ativo) " +
    `VALUES ('Atendente Playwright', '${safeEmail}', 'pw_hash_nao_usado', 'atendente', true) ` +
    "ON CONFLICT (email) DO UPDATE SET ativo = true " +
    "RETURNING id;";
  return extractLastInteger(execSql(sql), `atendente ${email}`);
}

function ensureClienteSaldo(telefone, saldo) {
  const safeTelefone = sqlEscape(telefone);
  const safeNome = sqlEscape(`Cliente PW ${telefone}`);
  const safeEndereco = sqlEscape("Rua Playwright, 100");
  const clienteSql =
    "INSERT INTO clientes (nome, telefone, tipo, endereco, latitude, longitude) " +
    `VALUES ('${safeNome}', '${safeTelefone}', 'PF', '${safeEndereco}', -16.7310, -43.8710) ` +
    "ON CONFLICT (telefone) DO UPDATE " +
    "SET nome = EXCLUDED.nome, " +
    "endereco = EXCLUDED.endereco, " +
    "latitude = EXCLUDED.latitude, " +
    "longitude = EXCLUDED.longitude, " +
    "atualizado_em = CURRENT_TIMESTAMP " +
    "RETURNING id;";
  const clienteId = extractLastInteger(execSql(clienteSql), `cliente ${telefone}`);

  const saldoSql =
    "INSERT INTO saldo_vales (cliente_id, quantidade) " +
    `VALUES (${clienteId}, ${Number(saldo)}) ` +
    "ON CONFLICT (cliente_id) DO UPDATE " +
    "SET quantidade = EXCLUDED.quantidade, atualizado_em = CURRENT_TIMESTAMP;";
  execSql(saldoSql);
  return clienteId;
}

async function wait(ms) {
  await new Promise((resolve) => setTimeout(resolve, ms));
}

async function fetchEntregaRotaWithRetry(pedidoId, attempts = 12, pauseMs = 500) {
  let lastError = null;

  for (let attempt = 1; attempt <= attempts; attempt += 1) {
    try {
      return fetchEntregaRotaByPedidoId(pedidoId);
    } catch (error) {
      lastError = error;
      if (attempt < attempts) {
        await wait(pauseMs);
      }
    }
  }

  throw lastError || new Error(`falha ao localizar entrega para pedidoId=${pedidoId}`);
}

async function ensureApiConnected(page) {
  await page.fill("#api-base", API_BASE);
  let lastError = null;
  for (let attempt = 1; attempt <= 5; attempt += 1) {
    await page.click("#api-connect");
    try {
      await expect(page.locator("#api-status")).toContainText("conectada", { timeout: 4000 });
      return;
    } catch (error) {
      lastError = error;
      await page.waitForTimeout(500);
    }
  }
  throw lastError || new Error("nao foi possivel conectar API no prototipo");
}

function extractNumberFromSummary(text, label) {
  const match = String(text || "").match(new RegExp(`${label}=(\\d+)`));
  return match ? Number(match[1]) : 0;
}

async function runGuidedFlow(page, scenario, seed = {}) {
  await page.click('button[data-view="pedidos"]');
  await page.selectOption("#e2eScenario", scenario);
  await page.selectOption("#e2eMetodoPagamento", "PIX");
  await page.fill("#e2eTelefone", String(seed.telefone || "(38) 99876-9901"));
  await page.fill("#e2eAtendenteId", String(seed.atendenteId || "1"));
  await page.click('#e2e-form button[type="submit"]');

  const runBox = page.locator(".result-box").filter({ hasText: "Fluxo guiado E2E" }).first();
  await expect(runBox).toContainText("sucesso", { timeout: 30000 });
  const resumo = await runBox.innerText();
  const pedidoId = extractPedidoId(resumo);
  return {
    pedidoId,
    rotaId: extractNumberFromSummary(resumo, "rotaId"),
    entregaId: extractNumberFromSummary(resumo, "entregaId"),
    resumo
  };
}

function seedForGuidedScenario(scenario) {
  const normalized = normalizeScenario(scenario);
  const suffix = normalized === "feliz" ? "41" : normalized === "falha" ? "42" : "43";
  const telefone = `(38) 99876-95${suffix}`;
  const atendenteId = ensureAtendenteId(`pw-guided-${normalized}@aguaviva.local`);
  ensureClienteSaldo(telefone, 12);
  return { telefone, atendenteId };
}

async function postApi(page, path, payload) {
  const response = await page.request.post(`${API_BASE}${path}`, {
    headers: { "Content-Type": "application/json" },
    data: payload
  });
  let body;
  try {
    body = await response.json();
  } catch (_) {
    body = {};
  }
  if (!response.ok()) {
    throw new Error(`${path} falhou com HTTP ${response.status()}: ${JSON.stringify(body)}`);
  }
  return body;
}

async function postApiWithStatus(page, path, payload) {
  const response = await page.request.post(`${API_BASE}${path}`, {
    headers: { "Content-Type": "application/json" },
    data: payload
  });
  let body;
  try {
    body = await response.json();
  } catch (_) {
    body = {};
  }
  return {
    status: response.status(),
    body
  };
}

function buildTerminalPayload(scenario, entregaId) {
  const terminalType = terminalEventTypeForScenario(scenario);
  if (terminalType === "PEDIDO_ENTREGUE") {
    return { eventType: terminalType, entregaId };
  }
  if (terminalType === "PEDIDO_FALHOU") {
    return {
      eventType: terminalType,
      entregaId,
      motivo: "falha automatizada playwright"
    };
  }
  return {
    eventType: terminalType,
    entregaId,
    motivo: "cancelamento automatizado playwright",
    cobrancaCancelamentoCentavos: 2500
  };
}

async function loadTimelineStatus(page, pedidoId) {
  await page.click('button[data-view="pedidos"]');
  await page.fill("#timelinePedidoId", String(pedidoId));
  await page.click('#timeline-form button[type="submit"]');

  const timelineBox = page.locator(".result-box").filter({ hasText: "Resposta timeline" }).first();
  await expect(timelineBox).toContainText("api real");
  const payload = JSON.parse(await timelineBox.locator("pre").innerText());
  const status = String(payload.status || payload.statusAtual || "").toUpperCase();
  if (!status) {
    throw new Error(`status ausente na timeline do pedidoId=${pedidoId}`);
  }
  return status;
}

for (const scenario of scenariosToRun()) {
  test(`deve executar loop operacional via UI no cenario ${scenario}`, async ({ page }, testInfo) => {
    await page.goto("/", { waitUntil: "networkidle" });
    await ensureApiConnected(page);

    const seed = seedForGuidedScenario(scenario);
    const run = await runGuidedFlow(page, scenario, seed);
    const pedidoId = run.pedidoId;

    const timelineStatus = await loadTimelineStatus(page, pedidoId);
    const expectedStatus = expectedStatusForScenario(scenario);
    expect(timelineStatus).toBe(expectedStatus);

    const report = {
      scenario,
      pedidoId,
      rotaId: run.rotaId,
      entregaId: run.entregaId,
      resumo: run.resumo,
      expectedStatus,
      timelineStatus
    };
    await testInfo.attach(`report-${scenario}.json`, {
      contentType: "application/json",
      body: Buffer.from(JSON.stringify(report, null, 2))
    });
    console.log(`[poc-ui] ${JSON.stringify(report)}`);
  });
}

test("deve aceitar atendimento em VALE quando cliente tem saldo", async ({ page }) => {
  await page.goto("/", { waitUntil: "networkidle" });
  await ensureApiConnected(page);

  const telefone = "(38) 99876-9551";
  const atendenteId = ensureAtendenteId("pw-vale-ok@aguaviva.local");
  ensureClienteSaldo(telefone, 6);

  const response = await postApiWithStatus(page, "/api/atendimento/pedidos", {
    externalCallId: `pw-vale-ok-${Date.now()}`,
    telefone,
    quantidadeGaloes: 2,
    atendenteId,
    metodoPagamento: "VALE"
  });

  expect(response.status).toBe(200);
  expect(Number(response.body?.pedidoId || 0)).toBeGreaterThan(0);
  expect(Boolean(response.body?.idempotente)).toBe(false);
});

test("deve bloquear atendimento em VALE quando cliente nao possui saldo", async ({ page }) => {
  await page.goto("/", { waitUntil: "networkidle" });
  await ensureApiConnected(page);

  const telefone = "(38) 99876-9552";
  const atendenteId = ensureAtendenteId("pw-vale-bloqueado@aguaviva.local");
  ensureClienteSaldo(telefone, 0);

  const response = await postApiWithStatus(page, "/api/atendimento/pedidos", {
    externalCallId: `pw-vale-bloqueado-${Date.now()}`,
    telefone,
    quantidadeGaloes: 1,
    atendenteId,
    metodoPagamento: "VALE"
  });

  expect(response.status).toBe(400);
  expect(String(response.body?.erro || "")).toContain("cliente nao possui vale");
});
