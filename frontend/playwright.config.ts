import { defineConfig } from "@playwright/test";

const BACKEND_PORT = 8619;
const FRONTEND_PORT = 5173;

export default defineConfig({
  testDir: "./e2e",
  timeout: 60_000,
  retries: 0,
  workers: 1, // serialized — tests share the backend's single DB
  use: {
    baseURL: `http://localhost:${FRONTEND_PORT}`,
  },
  webServer: [
    {
      // Fresh seeded SQLite DB per run — entities must exist for pickers.
      command:
        "cd .. && rm -f wise_escrow_e2e.db && " +
        `DATABASE_URL=sqlite:///./wise_escrow_e2e.db .venv/bin/python scripts/seed_demo_data.py && ` +
        `DATABASE_URL=sqlite:///./wise_escrow_e2e.db .venv/bin/uvicorn app.main:app --port ${BACKEND_PORT}`,
      port: BACKEND_PORT,
      reuseExistingServer: false,
      timeout: 30_000,
    },
    {
      command: `npm run dev -- --port ${FRONTEND_PORT} --strictPort`,
      env: { VITE_API_URL: `http://localhost:${BACKEND_PORT}` },
      port: FRONTEND_PORT,
      reuseExistingServer: false,
      timeout: 30_000,
    },
  ],
});
