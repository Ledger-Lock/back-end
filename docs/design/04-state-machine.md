# 04 — Payment State Machine Diagram (`FR-11`)

```mermaid
stateDiagram-v2
    [*] --> INITIATED: POST /api/v1/payments<br/>(Unique idempotencyKey)
    INITIATED --> REJECTED: Fraud rule failed<br/>(Blocked / Velocity >5/60s /<br/>Single or Daily Limit exceeded)
    INITIATED --> FUNDS_HELD: Fraud passed &<br/>LedgerEntry(HOLD) committed
    FUNDS_HELD --> SENT_TO_BILLER: Dispatched to BillerAdapter
    SENT_TO_BILLER --> COMPLETED: Biller confirms success<br/>[LedgerEntry(CAPTURE) +<br/>Optional Agent Commission]
    SENT_TO_BILLER --> FAILED: Biller explicitly refuses<br/>(Invalid meter / refused)
    SENT_TO_BILLER --> UNKNOWN: Network / Read Timeout (>10s)<br/>[Funds remain HELD]
    UNKNOWN --> COMPLETED: Async callback OR re-query<br/>confirms settlement<br/>[LedgerEntry(CAPTURE)]
    UNKNOWN --> FAILED: Async callback OR re-query<br/>confirms failure
    UNKNOWN --> UNKNOWN: Re-query timeout<br/>(retryCount < 3: 1m, 5m, 15m)
    FAILED --> REFUNDED: Automatic hold release<br/>[LedgerEntry(RELEASE)]
    COMPLETED --> REFUNDED: Support approves post-completion<br/>refund request (FR-27)<br/>[LedgerEntry(REFUND)]
    REJECTED --> [*]
    REFUNDED --> [*]
```

### State Transition Matrix & Invariants

| From State       | Allowed Next States                              | Ledger Action                                   | Terminal?   |
|:-----------------|:-------------------------------------------------|:------------------------------------------------|:------------|
| `INITIATED`      | `FUNDS_HELD`, `REJECTED`                         | `HOLD` (if `FUNDS_HELD`); none (if `REJECTED`)  | No          |
| `REJECTED`       | *(none)*                                         | None                                            | **Yes**     |
| `FUNDS_HELD`     | `SENT_TO_BILLER`                                 | None (funds already held)                       | No          |
| `SENT_TO_BILLER` | `COMPLETED`, `FAILED`, `UNKNOWN`                 | `CAPTURE` + `AGENT_COMMISSION` (if `COMPLETED`) | No          |
| `UNKNOWN`        | `COMPLETED`, `FAILED`, `UNKNOWN` (max 3 retries) | **Never auto-refunded while `UNKNOWN`**         | No          |
| `FAILED`         | `REFUNDED`                                       | `RELEASE` (heldBalance -> availableBalance)     | No          |
| `COMPLETED`      | `REFUNDED` (only via Support approval `FR-27`)   | `REFUND` (compensating credit)                  | Conditional |
| `REFUNDED`       | *(none)*                                         | None                                            | **Yes**     |