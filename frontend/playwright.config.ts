import { defineConfig } from "@playwright/test";

const BACKEND_PORT = 8619;
const FRONTEND_PORT = 5173;
const PG_PORT = 15432;

export default defineConfig({
  testDir: "./e2e",
  timeout: 60_000,
  retries: 0,
  workers: 1, // serialized — tests share one backend/DB
  use: {
    baseURL: `http://localhost:${FRONTEND_PORT}`,
  },
  globalTeardown: "./e2e/teardown.ts",
  webServer: [
    {
      // Fresh Postgres container + Spring Boot backend per run. The app's
      // SeedRunner provisions demo entities/balances on the empty database.
      command:
        "docker rm -f escrowrail-e2e-pg >/dev/null 2>&1 || true; " +
        "docker run --rm -d --name escrowrail-e2e-pg " +
        "-e POSTGRES_USER=escrowrail -e POSTGRES_PASSWORD=escrowrail " +
        `-e POSTGRES_DB=escrowrail_escrow -p ${PG_PORT}:5432 postgres:16-alpine >/dev/null && ` +
        "until docker exec escrowrail-e2e-pg pg_isready -U escrowrail -d escrowrail_escrow >/dev/null 2>&1; do sleep 1; done; " +
        "cd ../backend-java && " +
        `PORT=${BACKEND_PORT} ` +
        `DATABASE_URL=jdbc:postgresql://localhost:${PG_PORT}/escrowrail_escrow ` +
        "DATABASE_USER=escrowrail DATABASE_PASSWORD=escrowrail " +
        "JWT_SECRET=e2e-secret-e2e-secret-e2e-secret-32 " +
        "ESCROWRAIL_GITHUB_WEBHOOK_SECRET=e2e-gh-secret " +
        "ESCROWRAIL_LOGISTICS_WEBHOOK_SECRET=e2e-logi-secret " +
        "./gradlew bootRun --no-daemon",
      url: `http://localhost:${BACKEND_PORT}/actuator/health`,
      reuseExistingServer: false,
      timeout: 240_000,
    },
    {
      command: `npm run dev -- --port ${FRONTEND_PORT} --strictPort`,
      env: { VITE_API_BASE_URL: `http://localhost:${BACKEND_PORT}` },
      port: FRONTEND_PORT,
      reuseExistingServer: false,
      timeout: 30_000,
    },
  ],
});
