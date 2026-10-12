# System Architecture

## Service topology

The platform is two deployable units. The backend is a Java 21 / Spring Boot 3.5 application exposing a versioned JSON API (`/api/v1/**`) plus an unauthenticated-but-signed webhook ingress (`/webhooks/**`). The frontend is a React 19 / TypeScript SPA built by Vite, served as static assets. They communicate over bearer-JWT HTTPS only; there is no shared runtime state, no server-side session, and no coupling beyond the wire contract.

## Domain-Driven Design

Business rules live inside rich aggregates, not procedural service scripts. `Account` enforces its own invariants — a non-clearing account physically cannot be debited below zero, and any amount with more than two decimals is rejected before it can drift under Postgres's rounding. `EscrowContract` owns its state machine: `INVITED → INITIATED → FUNDED → SETTLED`, with `CANCELLED` reachable only before settlement. Attempting to fund an already-funded contract or cancel a settled one throws `InvalidStateTransitionException` inside the aggregate, before persistence — so an invalid state is unrepresentable, not merely filtered. Services (`EscrowService`, `LedgerService`, `AuthService`) orchestrate transactions and call the aggregates; they contain no invariants themselves.

## Transactional outbox

Every state change that produces a notification faces the dual-write problem: the database commit and the event dispatch cannot be atomic through two different channels. Persist the row and the event could be lost; publish first and a rollback leaves a phantom notification. Spring Modulith's `spring-modulith-starter-jpa` solves this with a transactional outbox — `@TransactionalEventListener` writes the event publication into the `event_publication` table inside the *same* database transaction as the business change, then dispatches asynchronously after commit. There is no custom scheduler, polling loop, or bespoke outbox entity; the framework owns the guarantee.

## Concurrency control

Ledger operations cannot rely on optimistic locking: under contention, two transfers against the same account would read the same balance, one would fail a version check, and retry storms would follow. Instead, every balance mutation takes `SELECT ... FOR UPDATE` pessimistic write locks on the account rows. To prevent lock-ordering deadlocks — transaction A holding account 1 while wanting account 2, B holding 2 while wanting 1 — all locks are acquired in ascending-UUID order, both inside `LedgerService.transfer` and when `EscrowService` locks the full set of accounts a lifecycle operation touches. The hash chain that makes ledger entries tamper-evident serializes on a singleton `ledger_chain` row, also taken `FOR UPDATE`.

## Dynamic multi-tenancy

Contracts associate parties through business email addresses resolved at creation time, not through fixed roles. Any registered entity can be the buyer on one contract and the seller on the next — permissions are evaluated per contract by comparing the caller's entity id against `buyer_id` and `seller_id`. A contract can also be created for an email that is not registered yet: it persists in `INVITED` status with the seller slot open, and `attachPendingInvites` fills the slot automatically when that address completes registration, inside the same transaction that creates the new user's accounts.
