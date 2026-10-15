# Security & Cryptography

## Identity lifecycle

Registration creates the user, the organization entity, and zero-balance currency accounts in one transaction — no half-registered state. Before activation, a one-time verification token is issued: only its SHA-256 digest is persisted (unique-indexed), the raw token exists solely in the sent email, and it carries a 24-hour expiry. `verifyEmail` marks the token consumed and activates the user in the same transaction, so a link can never activate twice. Login against an unverified account is rejected with a resend path; resends are rate-limited to one per 60 seconds, and the send happens `afterCommit` so a rolled-back transaction can never email a token that doesn't exist in the database.

## Stateless authentication

The API is an OAuth2 Resource Server using Nimbus JOSE. Tokens are HS256 JWTs carrying `sub` (user id) and `entity_id` (tenant) with a 120-minute expiry; the signing secret must be at least 32 bytes and has no default — the app refuses to boot without it. Passwords are hashed with Argon2id via Spring Security's `Argon2PasswordEncoder`. Sessions do not exist server-side: every request re-authenticates by signature.

## Cryptographic audit chaining

Each ledger row stores `current_hash`, a SHA-256 computed over the previous row's hash and this entry's identity — accounts, amount, description, timestamp. Mutation of any historical entry breaks every subsequent link. Under concurrency, inserts serialize on a singleton `ledger_chain` row locked `FOR UPDATE`, so the chain is linear by construction rather than by hope.

## Field-level encryption

Sensitive columns — most notably UBO tax IDs — are encrypted at rest with AES-256-GCM through a JPA `AttributeConverter`. Each write uses a fresh random 12-byte IV, so identical plaintexts produce different ciphertexts and the column cannot be rainbow-tabled. The key arrives by environment (`ESCROWRAIL_FIELD_ENCRYPTION_KEY`) and has no production default.

## Ingress hardening

Webhook endpoints are unauthenticated by design but cryptographically verified: GitHub calls must carry a valid `X-Hub-Signature-256` HMAC over the raw body, compared via `MessageDigest.isEqual` (constant time). A blank configured secret disables the endpoint entirely — it rejects rather than falls open. The idempotency filter buffers request bodies only up to 64 KB, returning HTTP 413 beyond that, so an unauthenticated client cannot exhaust the heap. Deduplication keys are derived from a SHA-256 of the *raw Authorization header* — the only unforgeable identity available before authentication runs — which prevents an attacker from replaying a victim's cached response by copying JWT claims into a self-signed token. `/api/v1/auth/**` is excluded from response caching so bearer tokens are never persisted as replay copies.
