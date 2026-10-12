# Wise Smart Escrow

## The commercial problem

Cross-border business transactions suffer from an inherent counterparty deadlock. A seller will not ship goods or deploy proprietary code without guaranteed payment, while a buyer refuses to wire funds upfront because recovering assets across jurisdictions is slow, expensive, and often impossible. The traditional escape hatches — letters of credit, documentary collections, and legacy escrow agents — were designed for a slower era. They charge opaque foreign-exchange markups of 3% to 4%, levy wire fees on every leg, and depend on manual paper processing that takes days to confirm what a webhook can prove in seconds.

## The Wise solution

Wise Smart Escrow resolves the deadlock by borrowing the mechanics of an Electronic Money Institution. When a buyer funds a contract, the deposit is converted immediately at the true mid-market rate — the midpoint between global buy and sell prices with no added spread — and charged a transparent 0.5% conversion fee. The converted amount lands in a segregated vault account per contract: ring-fenced from operational assets, never lent, never leveraged, never pooled.

Settlement is conditional and automatic, not manual. Each contract is bound to a verifiable real-world milestone — a merged GitHub pull request for software delivery, or a carrier delivery confirmation for physical goods. When the provider emits a cryptographically signed webhook, the platform settles without human approval: 99.2% of the vaulted amount disburses to the seller, and the platform retains a 0.8% milestone fee.

## The lifecycle

```mermaid
sequenceDiagram
    autonumber
    actor Buyer
    participant UI as React SaaS
    participant API as Spring Boot API
    participant Ledger as Double-Entry Ledger
    participant Vault as Segregated Vault (EUR)
    actor Seller
    participant Ingress as Webhook Source (GitHub/DHL)

    Buyer->>UI: Create trade (Target: 10,000 EUR)
    UI->>API: POST /api/v1/escrow (indicative quote: mid-market + 0.5% fee)
    API-->>UI: Contract INITIATED (Deposit required: 10,854.27 USD)
    Buyer->>UI: Confirm and fund
    UI->>API: POST /api/v1/escrow/{id}/fund (spot rate locked at execution)
    API->>Ledger: Transfer USD (USER -> WISE_CLEARING -> WISE_FEE)
    API->>Vault: Lock converted EUR (10,000.00 EUR)
    API-->>UI: Status -> FUNDED (3.5% ECB yield ticker — display-only)
    Note over Vault: Funds safeguarded under EMI regulations
    Ingress->>API: POST /api/v1/webhooks/milestone (HMAC verified)
    API->>Ledger: Settle payout (-0.8% milestone fee)
    Ledger->>Seller: Credit 9,920.00 EUR
    Ledger->>API: Credit 80.00 EUR (Wise Fee Account)
    API-->>UI: Status -> SETTLED
```

## What the buyer and seller each get

The buyer gets verifiable delivery: funds exist and are locked, but the seller cannot touch them until an independent third party attests the milestone. The seller gets verifiable payment: the moment the contract is funded, the money is provably in escrow — no chasing invoices, no chargeback risk, no waiting on a buyer's goodwill after delivery. Both parties get a hash-chained audit trail where every balance movement is a tamper-evident double-entry record.
