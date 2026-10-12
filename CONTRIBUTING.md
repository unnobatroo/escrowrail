# Contributing

Escrowrail is a financial system — every contribution is reviewed
against ledger invariants first, style second.

## Prerequisites

- **JDK 21** (Temurin recommended)
- **Node.js 20+** and npm
- **Docker** (OrbStack, Docker Desktop, or a Linux daemon) — required for
  Testcontainers; the test suite does not run without it
- **Playwright browsers** for e2e: `npx playwright install`

## Workflow

All changes land through a pull request against `main` — never push to
`main` directly. Branch naming: `feat/<slug>`, `fix/<slug>`, `chore/<slug>`,
`docs/<slug>`.

Commits follow Conventional Commits:

```
feat: add milestone webhook for carrier delivery receipts
fix: reject funding over-deposits beyond quoted amount
chore: bump moneta to 1.4.5
refactor: extract vault account lookup from EscrowService
docs: clarify FX rate-lock timing in README
```

## Verification

Run the full gate before opening a PR — CI runs exactly this:

```bash
cd backend-java && ./gradlew clean check   # Testcontainers boots real Postgres 16
cd frontend && npm run build && npx playwright test
```

Backend tests spin up a real PostgreSQL 16 container per run — pessimistic
locking, Flyway migrations, and the hash chain are exercised against actual
Postgres semantics, not mocks. Do not bypass Testcontainers with H2; the
locking behavior being tested does not exist there.

## Non-negotiable invariants

- **Money**: JSR 354 `MonetaryAmount`/`BigDecimal` only, `HALF_EVEN`
  rounding, scale ≤ 2 enforced in the domain. No `float`/`double` in any
  monetary path; no arithmetic in the frontend.
- **Ledger**: every funds movement is one balanced debit→credit
  `LedgerTransaction`. Account locks are taken in ascending-UUID order
  under `SELECT ... FOR UPDATE`.
- **Security**: secrets are Argon2id-hashed or AES-256-GCM-encrypted;
  webhook verification is constant-time HMAC; auth routes stay excluded
  from idempotency caching.
- **Profiles**: demo fixtures (seeding, top-ups, mock webhooks, dev token
  endpoints) must stay behind `dev`/`demo` profiles — `prod` strips them.

## Documentation

- Product/architecture docs live in `docs/wiki/` (mirrored to the GitHub
  Wiki once the remote is initialized).
- Keep README command references in sync with the `Makefile`.
