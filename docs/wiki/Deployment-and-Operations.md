# Deployment & Operations

## Testing against reality

The backend test suite runs against real PostgreSQL 16 via Testcontainers over the local Docker socket — not H2, not an in-memory substitute. This is a deliberate choice: the platform's correctness claims rest on pessimistic row locking (`SELECT ... FOR UPDATE`), check constraints, and serializable hash-chain ordering, and an in-memory database lies about all three. The suite covers concurrency races (two funds, double settles), a jqwik property test proving the debit-equals-credit ledger invariant, profile isolation (prod profile serves 404 for demo routes and rejects unsigned webhooks), and the full P2P lifecycle in both directions. Playwright exercises the real SPA against the real backend for the registration → verification → trade → fund → webhook → settlement path, including a dual-browser-context test where one user funds and another settles.

## Production topology

The backend runs containerized on Azure Container Apps (`escrowrail-api`); it can scale to zero when idle, and the 60-second frontend timeout exists to survive that cold start. PostgreSQL is managed Supabase — the free tier is IPv6-only on the direct host, so production connects through the IPv4 Supavisor pooler. Flyway owns the schema (`ddl-auto: validate`); Hibernate only verifies entities match. The frontend deploys to Vercel as a static bundle. `main` pushes trigger `deploy-prod.yml`: backend container deploy, frontend deploy, then a health poll against `/actuator/health`.

## Profile isolation

Three profiles with hard boundaries. `prod`: no seeded data, no demo controllers (the routes return 404), webhook secrets mandatory, mock signatures rejected, `allow-mock-webhooks: false`. `demo`: seeds the buyer/seller fixture accounts, enables the demo gateway (`/api/v1/demo/**`) that signs simulated webhooks through the *real* HMAC path, and compiles the frontend with `VITE_ENABLE_DEMO_TOOLS=true` so the account switcher, webhook simulator card, and top-up buttons exist. `dev`/`test`: full tooling plus the `/dev/verification-token` backdoor that e2e suites use to complete email verification without a mail server. `/api/v1/demo/config` is unauthenticated by design in the demo profile — it returns the demo password and webhook signing secrets so any evaluator can exercise the full lifecycle; that controller does not exist in production builds.

## Operations

An hourly `IdempotencySweeper` deletes replay records older than 24 hours over an indexed timestamp column — the table that guards duplicate requests cannot grow unboundedly. Response caching holds a 5-minute replay window: a repeated POST replays byte-for-byte with `X-Idempotent-Replay: true`, while an identical request still in flight gets an explicit 409 rather than a second execution.
