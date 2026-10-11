# Agent Directives: Wise Smart Escrow

## Invariants & Design Standards
1. Balances must NEVER use float. Use Decimal exclusively.
2. Every fund movement must be a balanced double-entry transaction (debits == credits).
3. Follow strict OOP and Clean Architecture: Routers -> Services -> Repositories -> Database.
4. Use FastAPI `Depends` for dependency injection. Never instantiate concrete services inside router functions.
5. Raise typed domain exceptions from `app/exceptions.py`. Never return raw error strings or untyped HTTP exceptions from business logic.
6. UBO check: If buyer.ubo_tax_id == seller.ubo_tax_id, reject with HTTP 403.
7. Escrow releases must only happen via validated webhooks (GitHub PR merge or carrier API).
8. Convert first, then freeze: Funds inside the vault must sit in the target invoice currency.

## Definition of Done
A task is NOT complete until:
- ruff check . returns 0 warnings.
- ruff format --check . passes cleanly.
- mypy --strict . passes without errors.
- pytest -v passes 100% of integration, ledger, and service tests.
