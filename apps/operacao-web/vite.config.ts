import { defineConfig } from "vite";

const host = process.env.VITE_HOST ?? "localhost";

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
