## Summary of Changes

## Architectural & Invariant Checklist
- [ ] Layered separation respected (Router -> Service -> Repository).
- [ ] No `float` types used for currency (`Decimal` only).
- [ ] Double-entry ledger balanced across all operations.
- [ ] Custom domain exceptions used and handled.
- [ ] CI checks (Ruff, Mypy, Pytest) passed with exit code 0.
