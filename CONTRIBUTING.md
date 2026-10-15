# Contributing

Escrowrail is a financial system — contributions are reviewed against
ledger invariants first, style second. If you're new to the codebase, the
[wiki](https://github.com/unnobatroo/escrowrail/wiki) explains the business
model, the money mechanics, and why the architecture is built the way it
is; read it before changing anything that touches balances.

## Prerequisites

- **JDK 21** (Temurin recommended)
- **Node.js 20+** and npm
- **Docker** (OrbStack, Docker Desktop, or a Linux daemon) — required:
  the backend suite runs against real PostgreSQL 16 via Testcontainers
- **Playwright browsers** for e2e: `npx playwright install`

## Workflow

Everything lands through a pull request against `main` — never push to
`main` directly. Branches: `feat/<slug>`, `fix/<slug>`, `chore/<slug>`,
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

Do not bypass Testcontainers with H2 or an in-memory stand-in: the suite
exercises pessimistic row locking, check constraints, and hash-chain
ordering — semantics an in-memory database does not have.

## Non-negotiable invariants

- **Money** — JSR-354 `MonetaryAmount` / `BigDecimal` only, `HALF_EVEN`
  rounding, scale ≤ 2 enforced in the domain. No `float`/`double` anywhere
  in a monetary path, and no arithmetic in the frontend.
- **Ledger** — every funds movement is one balanced debit→credit
  `LedgerTransaction`. Account locks are taken in ascending-UUID order
  under `SELECT ... FOR UPDATE`.
- **Security** — secrets are Argon2id-hashed or AES-256-GCM-encrypted;
  webhook verification is constant-time HMAC over the raw body;
  `/api/v1/auth/**` stays excluded from idempotency caching.
- **Settlement** — arrives only via verified webhooks. Never add a manual
  settle path.
- **Profiles** — demo fixtures (seeding, top-ups, mock webhooks, dev token
  endpoints) stay behind `dev`/`demo`; `prod` compiles none of them.

## Documentation

- Product and architecture docs live in `docs/wiki/` and mirror to the
  GitHub Wiki.
- Keep README command references in sync with the `Makefile`.
