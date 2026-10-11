# Agent Directives: Escrowrail

## Invariants & Design Standards
1. Balances must NEVER use float/double. Use `BigDecimal` / JSR 354 `MonetaryAmount` exclusively.
2. Every fund movement must be a balanced double-entry transaction (debits == credits), hash-chained for audit.
3. Follow strict OOP and Clean Architecture: Controllers -> Services -> Repository adapters -> JPA.
4. Use Spring constructor injection. Never instantiate concrete services inside controllers.
5. Raise typed domain exceptions. Never return raw error strings or untyped HTTP exceptions from business logic.
6. UBO check: If buyer.ubo_tax_id == seller.ubo_tax_id, reject with HTTP 403.
7. Escrow releases must only happen via HMAC-validated webhooks (GitHub PR merge or carrier API). No manual settle path.
8. Convert first, then freeze: Funds inside the vault must sit in the target invoice currency.
9. Money crosses the wire as JSON strings (snake_case fields), matching the frontend contract.

## Definition of Done
A task is NOT complete until:
- `cd backend-java && ./gradlew clean test` passes 100% (Testcontainers + Postgres).
- `cd frontend && npm run build` compiles with zero TypeScript errors.
- `cd frontend && npx playwright test` passes the SaaS E2E flow when frontend/edge paths changed.
