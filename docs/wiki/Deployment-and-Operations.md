# Deployment & Operations

## Production topology

| Piece | Provider | Detail |
| --- | --- | --- |
| Frontend | Vercel — `escrowrail.vercel.app` | Static bundle; `VITE_API_BASE_URL` points at Azure |
| Backend | Azure Container Apps — `escrowrail-api` | linux/amd64 image; `minReplicas 0` = scale-to-zero when idle |
| Database | Supabase managed PG16 | Connected via the IPv4 Supavisor pooler — the free-tier direct host is IPv6-only |
| Email | Resend API | Verification, reset, invite, and welcome mail |
| CI/CD | GitHub Actions | `ci.yml`, `pr-verify.yml`, `deploy-staging.yml`, `deploy-prod.yml` |

`main` pushes trigger `deploy-prod.yml`: backend container deploy, frontend
deploy, then a health poll against `/actuator/health`. The 60-second
frontend HTTP timeout exists to survive a scale-to-zero cold start.

## Configuration

Every secret arrives by environment variable — nothing sensitive is
committed:

| Variable | Purpose |
| --- | --- |
| `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | Postgres connection (prod must include `sslmode=require`) |
| `JWT_SECRET` | HS256 signing key, ≥ 32 bytes — **no default, no boot without it** |
| `ESCROWRAIL_GITHUB_WEBHOOK_SECRET` | GitHub HMAC key — blank disables the endpoint |
| `ESCROWRAIL_LOGISTICS_WEBHOOK_SECRET` | Carrier HMAC key — blank disables the endpoint |
| `ESCROWRAIL_FIELD_ENCRYPTION_KEY` | AES-256-GCM field key (base64, 32 bytes) |
| `ESCROWRAIL_CORS_ORIGINS` | Comma-separated SPA origins |
| `ESCROWRAIL_MAIL_RESEND_API_KEY` | Resend API key — mail logs-and-drops without it |
| `ESCROWRAIL_SEED_ENABLED` | Seed demo tenants (dev/demo only) |

## Profile isolation

Three profiles with hard boundaries:

- **`prod`** — no seeded data, no demo controllers (routes return 404),
  webhook secrets mandatory, mock signatures rejected.
- **`demo`** — seeds the buyer/seller fixture accounts, enables
  `/api/v1/demo/**` (sandbox top-ups + a webhook simulator that signs
  through the *real* HMAC path), and the frontend builds with
  `VITE_ENABLE_DEMO_TOOLS=true` so the persona switcher and simulator card
  exist. `/api/v1/demo/config` is unauthenticated *by design* here — it
  hands any evaluator everything needed to exercise the full lifecycle.
- **`dev`/`test`** — everything in demo plus `/dev/verification-token`, the
  backdoor e2e suites use to complete email verification with no mail
  server.

## Schema discipline

Flyway owns the schema (`db/migration/V*.sql`); Hibernate runs
`ddl-auto: validate` — it verifies entities match the schema but can never
alter a funds table. `validate-on-migrate` is disabled because historical
migrations were intentionally rewritten during the Escrowrail rebrand —
checksums for already-applied versions diverge while V8 repairs the live
enum values.

## Testing against reality

The backend suite runs against real PostgreSQL 16 via Testcontainers over
the local Docker socket — not H2, not an in-memory substitute. That's
deliberate: the correctness claims rest on pessimistic row locking, check
constraints, and serializable hash-chain ordering, and an in-memory
database lies about all three. The suite covers concurrency races (double
funds, settle-vs-cancel), a jqwik property test for the
debit-equals-credit invariant, profile isolation, and the full P2P
lifecycle in both directions. Playwright exercises the real SPA against the
real backend — registration, verification, trade, funding, webhook
settlement, cancellation — including a dual-browser-context test where one
user funds and another receives settlement.

## Operations

- `IdempotencySweeper` evicts expired replay records hourly over an indexed
  timestamp column — the dedup table cannot grow unboundedly.
- Completed idempotent responses replay for 24 hours with
  `X-Idempotent-Replay: true`; a duplicate of an *in-flight* request gets a
  409 rather than a second execution.
- Gzip response compression is on for JSON over 1 KB — egress is billed.
- Hikari runs small (`maximum-pool-size: 5`, `minimum-idle: 0`, fast idle
  release) so the hosted Postgres and the container can both scale toward
  zero.
