## Description
<!-- Summarize changes, motivation, and context -->

## Financial Invariant Checklist
- [ ] No floating-point primitives (`float`, `double`) introduced in monetary paths.
- [ ] Double-entry ledger transactions balance to exactly zero (sum of debits == sum of credits).
- [ ] Pessimistic write locks (`SELECT ... FOR UPDATE`) applied in deterministic ascending-UUID order.
- [ ] New endpoints guarded by authentication or explicitly marked public.
- [ ] All inputs validated for scale <= 2 and non-negative constraints.

## Testing & Verification
- [ ] Backend: `./gradlew check` passes on Testcontainers PostgreSQL.
- [ ] Frontend: `npm run build` passes with zero TypeScript warnings.
- [ ] End-to-End: `npx playwright test` passes cleanly.
