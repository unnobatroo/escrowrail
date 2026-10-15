# Security & Cryptography

## Identity lifecycle

Registration creates the user, the organization entity, and zero-balance
currency accounts in **one transaction** — there is no half-registered
state to exploit or clean up.

Before activation, a one-time verification token is issued with three
properties: only its SHA-256 digest is persisted (unique-indexed), the raw
token exists solely inside the sent email, and it carries a 24-hour expiry.
`verifyEmail` marks the token consumed and activates the user in the same
transaction — a link can never activate twice, and pending contract invites
attach only to *verified* users, so an unverified squatter can't claim a
trade addressed to someone else's inbox.

Login against an unverified account is rejected with a resend path; resends
are rate-limited to one per 60 seconds, and sends happen `afterCommit` — a
rolled-back transaction can never email a token that doesn't exist.
Password reset follows the same digest-only, single-use token pattern.

## Stateless authentication

The API is an OAuth2 Resource Server using Nimbus JOSE. Tokens are HS256
JWTs carrying `sub` (user id) and `entity_id` (the tenant) with a
120-minute expiry. The signing secret must be ≥ 32 bytes and has **no
default** — the application refuses to boot without `JWT_SECRET`.
Passwords are Argon2id-hashed via Spring Security's `Argon2PasswordEncoder`
(BouncyCastle provider). Sessions do not exist server-side: every request
re-authenticates by signature.

## Tenant isolation

Every repository read is scoped by `entity_id` extracted from the JWT at
the trust boundary. A stranger probing a foreign contract or ledger ID gets
**404 — not 403** — because even confirming the resource exists leaks
information. Authorization (buyer-only funding, buyer-only post-funding
cancellation) is enforced inside the locked transaction, not at the
controller.

## Cryptographic audit chaining

Each ledger row stores `current_hash`: SHA-256 over the previous row's hash
plus this entry's canonicalized fields. Editing any historical row breaks
every subsequent link — tampering is detectable by recomputation. Inserts
serialize on a singleton `ledger_chain` row locked `FOR UPDATE`, so the
chain stays linear even under concurrent writers.

## Field-level encryption

Sensitive columns — most notably UBO tax IDs — are encrypted at rest with
AES-256-GCM through a JPA `AttributeConverter`. Each write uses a fresh
random 12-byte IV, so identical plaintexts produce different ciphertexts
and the column cannot be rainbow-tabled. The key arrives via
`ESCROWRAIL_FIELD_ENCRYPTION_KEY`; only a published dev-only default
exists, never a production one. Log paths mask email addresses to
`***@domain` — recipient PII doesn't leak through error output.

## Ingress hardening

Webhook endpoints are unauthenticated by design but cryptographically
verified: callers must carry a valid HMAC-SHA256 over the **raw request
body**, compared via `MessageDigest.isEqual` (constant-time, so the
comparison doesn't leak through timing). This holds for both providers —
`X-Hub-Signature-256` for GitHub, the equivalent signature scheme for
logistics. A blank configured secret **disables** the endpoint entirely —
it rejects rather than falls open.

## Idempotency as a security boundary

The idempotency filter guards every mutating method (POST, PUT, PATCH,
DELETE — auth and webhooks excluded, which have their own replay
protections):

- Completed responses replay byte-for-byte for **24 hours** — retrying a
  fund call can never debit twice.
- In-flight claims carry a **5-minute TTL**, so a crashed request doesn't
  permanently wedge the key.
- Request bodies buffer only up to **64 KB** (413 beyond) — an
  unauthenticated client cannot exhaust the heap.
- Deduplication keys derive from a SHA-256 of the *raw Authorization
  header* — the only unforgeable identity available before authentication —
  so an attacker can't replay a victim's cached response by copying claims
  into a self-signed token.
- `/api/v1/auth/**` is excluded so bearer tokens are never persisted as
  replay copies.

## Demo tooling is fenced by profile

`DemoToolsController` compiles only under `demo`/`dev`; `prod` returns 404
for every `/api/v1/demo/**` route. The simulator it exposes doesn't fake
settlement — it signs payloads server-side and runs them through the *real*
HMAC verification path, requires the caller to be the contract's buyer, and
rejects blank secrets. The `/dev/**` token backdoor exists only in dev/test
— it is how e2e completes email verification without a mail server.
