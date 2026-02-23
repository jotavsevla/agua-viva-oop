const fs = require("node:fs");
const path = require("node:path");
const { spawnSync } = require("node:child_process");

const rootDir = path.resolve(__dirname, "..");
const testsDir = path.join(rootDir, "tests");

let testFiles = [];
try {
  testFiles = fs
    .readdirSync(testsDir, { withFileTypes: true })
    .filter((entry) => entry.isFile() && entry.name.endsWith(".test.js"))
    .map((entry) => path.join("tests", entry.name))
    .sort();
} catch (error) {
  console.error(`Falha ao listar testes em ${testsDir}: ${error.message}`);
  process.exit(1);
}

if (testFiles.length === 0) {
  console.error("Nenhum teste encontrado em tests/*.test.js");
  process.exit(1);
}

const result = spawnSync(process.execPath, ["--test", ...testFiles], {
  cwd: rootDir,
  stdio: "inherit",
  shell: false,
});

if (result.error) {
  console.error(`Falha ao executar testes Node: ${result.error.message}`);
  process.exit(1);
}

process.exit(result.status ?? 1);
