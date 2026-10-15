# Agent Directives: Escrowrail

Escrowrail is a financial system. Every change is judged against ledger
invariants first, style second.

## Invariants — never break these

1. **Money is never a float.** `BigDecimal` / JSR-354 `MonetaryAmount`
   only, `HALF_EVEN`, scale ≤ 2 enforced in the domain. The frontend never
   does money arithmetic — amounts travel as JSON strings.
2. **Every funds movement is a balanced double-entry row** —
   debit→credit, hash-chained. No bare balance updates, ever.
3. **Layers are strict:** controllers → services → repository adapters →
   JPA. Domain code imports nothing from `infrastructure`.
4. **Constructor injection only.** Controllers never instantiate services.
5. **Typed domain exceptions** — never raw strings or untyped HTTP errors
   from business logic.
6. **UBO collision = HTTP 403.** Buyer and seller sharing a tax ID is
   self-dealing; reject at creation *and* at invite-attach.
7. **Settlement is webhook-only.** HMAC-verified GitHub/logistics events
   are the only FUNDED→SETTLED path. No manual settle endpoint exists —
   keep it that way.
8. **Convert first, then freeze.** Vault contents sit in the target
   invoice currency from the moment of funding.
9. **Demo tooling stays fenced.** Seeding, top-ups, the webhook simulator,
   and `/dev/**` backdoors exist only in `dev`/`demo` profiles.

## Definition of done

A task is not complete until:

```bash
cd backend-java && ./gradlew clean check   # real Postgres via Testcontainers
cd frontend && npm run build               # zero TypeScript errors
cd frontend && npx playwright test         # when frontend or edge paths changed
```
