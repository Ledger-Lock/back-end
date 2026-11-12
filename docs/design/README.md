# LedgerLock (`ledger-lock/back-end`) — Phase 1 Design Pack

This directory contains the architectural and behavioral models for **LedgerLock**, organized by view:

| Artifact                             | File                                                         | Scope                                                                                                                                                                                                                                                     |
|:-------------------------------------|:-------------------------------------------------------------|:----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Use Case Diagram**                 | [`01-use-case-diagram.md`](./01-use-case-diagram.md)         | Actor boundaries (`Customer`, `Retail Agent`, `Customer Support`, `System Admin`, `External Billers`, `Nightly Scheduler`) mapped to `FR-1` through `FR-29`.                                                                                              |
| **Domain Class Diagram**             | [`02-domain-class-diagram.md`](./02-domain-class-diagram.md) | Modular monolith packages, Strategy (`FeeCalculator`), Chain of Responsibility (`FraudRule`), Adapter (`BillerAdapter`), and immutable `LedgerEntry` domain model.                                                                                        |
| **Sequence Diagrams**                | [`03-sequence-diagrams.md`](./03-sequence-diagrams.md)       | End-to-end synchronous bill payment (pessimistic locking, hold, adapter dispatch, capture, commission, audit, event), TopUpNet async callback + timeout re-query flow, and dedicated refund of failed payments (automatic hold release & support refund). |
| **Payment State Machine**            | [`04-state-machine.md`](./04-state-machine.md)               | Valid `PaymentStatus` transitions (`INITIATED` -> `FUNDS_HELD` / `REJECTED` -> `SENT_TO_BILLER` -> `COMPLETED` / `FAILED` / `UNKNOWN` -> `REFUNDED`) and ledger side-effects.                                                                             |
| **Entity-Relationship (ER) Diagram** | [`05-er-diagram.md`](./05-er-diagram.md)                     | PostgreSQL 17 relational schema (`users`, `wallets`, `ledger_entries`, `billers`, `biller_services`, `payments`, `payment_state_history`, `fee_rules`, `promotions`, `audit_logs`, `notifications`, `reconciliation_reports`).                            |

---

## Core Design Patterns Applied

1. **Adapter Pattern (`integration`):** `BillerAdapter` isolates `ElectroCo` (REST/JSON), `AquaCo` (SOAP/XML),
   `TopUpNet` (Async REST + Webhook), and `NetCo` behind a single contract (`inquire`, `pay`, `checkStatus`). Adding a
   new biller or upgrading ElectroCo API v2 requires **zero changes** outside `integration` and configuration.
2. **Chain of Responsibility (`fraud`):** `FraudCheckChain` executes ordered `FraudRule` beans (`AccountStatusRule` ->
   `VelocityAbuseRule` -> `SingleTransactionLimitRule` -> `DailyLimitRule`) prior to holding wallet funds.
3. **Strategy Pattern (`fees`):** `FeeCalculator` resolves the active `FeeStrategy` per `ServiceCategory` (`FIXED`,
   `PERCENTAGE_WITH_MIN`, `TIERED`) after checking `PromotionService` (`0.00` EGP override), and computes `40%` retail
   agent commission via `BigDecimal` (`RoundingMode.HALF_UP`).
4. **Immutable Append-Only Ledger (`accounts`):** `Wallet.availableBalance` and `Wallet.heldBalance` are never mutated
   without appending a corresponding `LedgerEntry` (`TOP_UP`, `HOLD`, `RELEASE`, `CAPTURE`, `FEE_REVENUE`,
   `AGENT_COMMISSION`, `REFUND`) inside the same `@Transactional` boundary alongside an immutable `AuditLog`.
5. **Transactional Outbox / Domain Events (`payment` -> `notifications`):** `PaymentCompletedEvent` and
   `PaymentFailedEvent` trigger asynchronous SMS/push notifications with up to 3 retries (`UNDELIVERED` on exhaustion)
   without ever rolling back a settled financial transaction.
