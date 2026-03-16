import { defineConfig } from "vite";

const DEFAULT_HOST = "localhost";
const EXTERNAL_HOST = "0.0.0.0";
const nodeProcess = globalThis as typeof globalThis & {
  process?: {
    env?: Record<string, string | undefined>;
  };
};
const shouldBindAllInterfaces = nodeProcess.process?.env?.OPERACAO_WEB_BIND_ALL === "1";
const host = shouldBindAllInterfaces ? EXTERNAL_HOST : DEFAULT_HOST;

export default defineConfig({
  server: {
    host,
    port: 4175
  },
  preview: {
    host,
    port: 4175
  }
});
