# LedgerLock — Software Requirements Specification (Phase 1)

## 1. Problem Summary

LedgerLock (GitHub repository: `ledger-lock/back-end`) is an Egyptian digital wallet and bill-payment backend platform.
Customers and retail agents (operating point-of-sale terminals) use the platform to inquire about and pay recurring
utility bills—specifically electricity, water, mobile top-ups, and internet services—directly from an EGP-denominated
digital wallet.

Because LedgerLock custodies customer funds and acts as an intermediary with external utility providers (billers), the
backend must guarantee strict financial correctness:

- **Zero double charges and zero negative balances** under concurrent requests or network retries.
- **Immutable ledger accounting**, where wallet balances (available and held) are derived strictly from append-only
  ledger entries rather than direct balance overwrites.
- **Resilient external integrations** capable of handling synchronous REST/JSON, slow SOAP/XML, asynchronous webhook
  callbacks, and nightly batch reconciliation files without blocking unrelated billers.

---

## 2. Stakeholders

| Stakeholder                                               | Role & Interest in the System                                                                                                                                                                                                                                                                  |
|:----------------------------------------------------------|:-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Customer (Basic & Verified)**                           | Tops up their EGP wallet, checks bill balances, pays utility bills, views transaction history, and receives payment receipts or failure notifications.                                                                                                                                         |
| **Retail Agent**                                          | Initiates bill payments on behalf of walk-in customers through a LedgerLock terminal and earns a 40% commission on the service fee upon payment completion.                                                                                                                                    |
| **System Administrator**                                  | Configures active/disabled billers and services, updates fee rules, promotion periods, and transaction limits at runtime without redeployment, and views daily financial totals.                                                                                                               |
| **Customer Support Employee**                             | Searches payments by unique reference to inspect full state history, blocks/unblocks suspicious accounts, reviews nightly reconciliation discrepancies, and approves manual refund requests.                                                                                                   |
| **External Billers (ElectroCo, AquaCo, TopUpNet, NetCo)** | Utility companies that expose bill inquiry and payment interfaces across heterogeneous protocols and provide daily settlement files for reconciliation.                                                                                                                                        |
| **Engineering & DevOps Team**                             | Maintains the modular monolith in `ledger-lock/back-end`, enforces code quality gates via Spotless, Checkstyle, and SonarQube, maintains test coverage and automated Oracle server deployments via GitHub Actions CI/CD, and evolves notifications/reconciliation into event-driven consumers. |

---

## 3. Functional Requirements (User Stories & Acceptance Criteria)

### Accounts, Authentication & Wallet Management

- **FR-1 [Must] Customer Registration:** As a new customer, I want to register an account so that I receive a digital
  wallet with a `BASIC` verification level.
    - **Given** valid registration details for an unregistered user, **When** they submit a registration request,
      **Then** an active account is created with role `CUSTOMER`, verification level `BASIC`, and an EGP wallet
      initialized to `0.00` available and `0.00` held balance.
- **FR-2 [Should] Verification Upgrade:** As a customer, I want to upgrade my account to `VERIFIED` so that my daily
  payment limit increases from 5,000 EGP to 30,000 EGP.
    - **Given** a `BASIC` customer account, **When** verification approval is recorded, **Then** the account status
      updates to `VERIFIED` and subsequent fraud checks enforce the 30,000 EGP daily limit.
- **FR-3 [Must] View Balance & History:** As a customer, I want to view my available balance, held balance, and
  transaction history so that I can track my funds.
    - **Given** an authenticated customer with prior ledger activity, **When** they query their wallet endpoint,
      **Then** the system returns exact `availableBalance`, `heldBalance`, and a chronological list of ledger
      transactions.
- **FR-4 [Must] Wallet Top-Up:** As a customer, I want to top up my wallet via simulated card or agent cash so that I
  have funds to pay bills.
    - **Given** an active wallet and a positive top-up amount, **When** a top-up is confirmed, **Then** a `CREDIT`
      ledger entry is appended and `availableBalance` increases by the exact amount.
- **FR-5 [Must] Ledger-Derived Balances:** As a financial auditor, I want every balance movement recorded as an
  immutable ledger entry so that balances are never overwritten directly.
    - **Given** any hold, release, charge, top-up, or commission event, **When** the transaction commits, **Then** an
      immutable `LedgerEntry` is persisted and the wallet balance equals the algebraic sum of its ledger entries.
- **FR-29 [Must] Stateless JWT Authentication & RBAC:** As a security engineer, I want endpoints protected by
  self-contained Spring Security stateless JWT authentication and role-based access control (`CUSTOMER`, `AGENT`,
  `SUPPORT`, `ADMIN`) so that actor identity is verified without external IdP overhead.
    - **Given** an authenticated caller with a valid signed JWT, **When** they invoke a restricted endpoint, **Then**
      the system enforces role permissions (`403 Forbidden` on unauthorized roles) and extracts `actorId` into
      `SecurityContextHolder` for audit logging.

### Biller Catalog

- **FR-6 [Must] Admin Biller Management:** As an admin, I want to add, edit, and disable billers and services so that I
  can control available utilities.
    - **Given** an authenticated admin, **When** they disable a biller or service, **Then** new bill inquiries and
      payments for that service are rejected while existing in-flight payments complete normally.
- **FR-7 [Must] Browse Billers & Input Schemas:** As a customer, I want to view active billers and their required input
  fields (e.g., meter number, phone number) so that I know what information to enter.
    - **Given** active services in the catalog, **When** a customer requests the biller catalog, **Then** the API
      returns each active biller, service type, min/max limits, and required input field definitions.

### Bill Inquiry & Payment Processing

- **FR-8 [Must] Bill Inquiry:** As a customer, I want to run a bill inquiry before paying so that I can see the bill
  amount, service fee, and total cost.
    - **Given** an active biller and valid customer identifier (e.g., meter number), **When** the customer requests an
      inquiry, **Then** the system queries the biller adapter, calculates the applicable fee, and returns `billAmount`,
      `serviceFee`, and `totalAmount`.
- **FR-9 [Must] Pay Bill from Wallet:** As a customer, I want to pay a utility bill from my wallet so that my bill is
  settled.
    - **Given** a customer with sufficient available balance and passing fraud checks, **When** they submit a payment
      request, **Then** the system holds `totalAmount`, invokes the biller adapter, finalizes the charge upon
      confirmation, and records LedgerLock's fee revenue.
- **FR-10 [Must] Request Idempotency:** As a mobile client, I want repeated payment requests with the same
  `idempotencyKey` to return the original result so that slow networks never cause double charges.
    - **Given** a payment request with an `idempotencyKey` that has already been processed, **When** an identical
      request arrives again, **Then** the system returns the stored original payment response without creating a second
      hold or calling the biller again.
- **FR-11 [Must] Strict Payment State Machine:** As a system architect, I want payments to transition only through valid
  states (`INITIATED`, `FUNDS_HELD`, `SENT_TO_BILLER`, `COMPLETED`, `FAILED`, `UNKNOWN`, `REFUNDED`) so that money
  states remain consistent.
    - **Given** a payment in state `COMPLETED`, `REJECTED`, or `REFUNDED`, **When** any component attempts another state
      transition, **Then** the domain model rejects the transition with an invalid state transition error.
- **FR-12 [Must] Fund Hold & Failure Release:** As a customer, I want my funds held before calling the biller and
  automatically released if the biller refuses so that I never lose money on failed bills.
    - **Given** a payment in `SENT_TO_BILLER` with funds held, **When** the biller returns an explicit refusal error,
      **Then** the payment transitions to `FAILED` and then `REFUNDED`, releasing the held balance back to
      `availableBalance`.
- **FR-13 [Must] Timeout Handling & Re-Query Retries:** As a customer, I want timed-out biller calls marked `UNKNOWN`
  and re-queried automatically rather than refunded prematurely so that I am not double-charged or credited falsely.
    - **Given** a live biller call exceeding the configurable 10-second timeout, **When** the timeout triggers, **Then**
      the payment transitions to `UNKNOWN` (keeping funds held) and schedules up to 3 status re-queries at 1, 5, and 15
      minutes.
- **FR-14 [Must] Asynchronous Callback Handling:** As an integration service, I want to accept asynchronous webhook
  callbacks from TopUpNet and ignore duplicates so that mobile top-ups complete reliably.
    - **Given** a TopUpNet payment in `SENT_TO_BILLER` or `UNKNOWN`, **When** a `"success"` callback arrives twice,
      **Then** the first callback transitions the payment to `COMPLETED` and captures the hold, while the second
      callback is logged and ignored idempotently.
- **FR-15 [Must] Multi-Protocol Biller Support:** As a platform operator, I want to integrate ElectroCo (REST/JSON),
  AquaCo (SOAP/XML), and TopUpNet (async REST + callback) behind a unified adapter interface so that protocol
  differences are isolated.
    - **Given** requests targeting ElectroCo, AquaCo, and TopUpNet, **When** payments are executed, **Then** each
      adapter translates payloads to/from its native format without leaking protocol details into `payment` or
      `accounts`.

### Fees, Commissions & Fraud Rules

- **FR-16 [Must] Configurable Fee & Promotion Engine:** As a product owner, I want fees calculated by service rules
  (2.50 EGP fixed for electricity/water; 1.5% min 1 EGP for mobile; tiered 2 EGP / 4 EGP for internet) with 0 EGP during
  active biller promotions.
    - **Given** a bill payment during an active promotion window for that biller, **When** the fee is calculated,
      **Then** the service fee evaluates to `0.00` EGP; otherwise, the service-specific formula applies with `HALF_UP`
      decimal rounding.
- **FR-17 [Should] Agent Payments & Commission:** As a retail agent, I want to pay a bill on behalf of a customer and
  earn a 40% commission on the service fee when the payment completes.
    - **Given** a payment initiated via an agent terminal with a `2.50` EGP service fee, **When** the payment reaches
      `COMPLETED`, **Then** `1.00` EGP (40%) is credited via ledger entry to the agent's wallet.
- **FR-18 [Must] Single & Daily Payment Limits:** As a risk controller, I want payments outside single-transaction
  limits (5 EGP – 10,000 EGP) or above the user's daily limit (5,000 EGP basic / 30,000 EGP verified) rejected
  immediately.
    - **Given** a `BASIC` user who has already paid 4,800 EGP today, **When** they attempt a 300 EGP bill payment,
      **Then** the fraud chain rejects the payment (`REJECTED`) with a clear daily-limit error message before holding
      funds.
- **FR-19 [Should] Velocity Abuse Block:** As a security system, I want accounts making more than 5 payments within 60
  seconds automatically blocked for 10 minutes.
    - **Given** an account with 5 payment attempts in the trailing 60 seconds, **When** a 6th attempt arrives within
      that window, **Then** the account is blocked for 10 minutes and the payment is `REJECTED`.

### Support, Notifications, Audit & Reconciliation

- **FR-20 [Must] Support Payment Lookup:** As a support employee, I want to search any payment by its unique reference
  and inspect its complete transition and ledger history.
    - **Given** a valid payment reference, **When** a support employee queries the lookup endpoint, **Then** the
      response includes current status, timestamps, biller responses, and all associated ledger entries.
- **FR-21 [Should] Account Block & Unblock:** As a support employee, I want to manually block and unblock customer
  accounts so that I can respond to fraud alerts.
    - **Given** a blocked account, **When** the customer attempts to initiate a payment, **Then** the fraud rule chain
      rejects the request until a support employee unblocks the account.
- **FR-22 [Must] Completion & Failure Notifications:** As a customer, I want to receive an SMS and push notification
  when my payment completes or fails.
    - **Given** a payment transitioning to `COMPLETED` or `FAILED`/`REFUNDED`, **When** the domain event is published,
      **Then** the notification module dispatches both an SMS and a push notification containing the payment reference
      and outcome.
- **FR-23 [Should] Notification Retries:** As a reliable messaging service, I want failed SMS or email deliveries
  retried up to 3 times before marking them `UNDELIVERED` without rolling back the payment.
    - **Given** a simulated SMS gateway outage during payment completion, **When** dispatch fails 3 consecutive times,
      **Then** the notification status is recorded as `UNDELIVERED` while the payment remains `COMPLETED`.
- **FR-24 [Should] Runtime Configuration Updates:** As an admin, I want to update fee values, promotion dates, and
  limits without redeploying the application.
    - **Given** updated fee or limit parameters saved via the admin configuration API/database, **When** the next bill
      inquiry or fraud check runs, **Then** the new values take effect immediately without restarting the service.
- **FR-25 [Must] Immutable Audit Log:** As a compliance officer, I want every money movement and admin configuration
  change written to an audit log with actor, timestamp, and reason.
    - **Given** any wallet ledger movement or admin mutation, **When** the operation executes, **Then** an immutable
      audit record is persisted capturing `actorId`, `action`, `entityId`, `timestamp`, and `reason`.
- **FR-26 [Should] Nightly Reconciliation Report:** As a finance operator, I want to upload or process a biller's daily
  settlement CSV file and generate a discrepancy report.
    - **Given** a settlement file listing a payment as settled that LedgerLock recorded as `FAILED` (or missing),
      **When** reconciliation runs, **Then** the mismatch is flagged in the reconciliation report for support review.
- **FR-27 [Could] Post-Completion Refund Workflow:** As a customer, I want to request a refund on a completed payment
  that a support employee can approve.
    - **Given** a `COMPLETED` payment with an approved support refund request, **When** support approves the refund,
      **Then** a compensating credit ledger entry returns the funds to the customer's wallet.
- **FR-28 [Could] Admin Daily Totals Dashboard:** As an admin, I want to view daily aggregated counts and amounts for
  payments, fees earned, commissions paid, and failures per biller.
    - **Given** a target date, **When** the admin requests the daily summary endpoint, **Then** the system returns total
      payment volume, total fees earned, total agent commissions paid, and failure counts grouped by biller.

---

## 4. Non-Functional Requirements (Measurable Targets)

| Quality Attribute                | Target Metric                                                                                                                                                                                                             | Verification Method                                                                                                                                         |
|:---------------------------------|:--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|:------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Correctness**                  | **0 double charges** and **0 negative wallet balances** across concurrent payment and duplicate idempotency tests.                                                                                                        | Automated multi-threaded JUnit 5 concurrency & idempotency integration tests.                                                                               |
| **Performance**                  | Sustain **100 payments/second for 60 seconds** (6,000 total payments) with p95 latency < 500 ms against mock billers.                                                                                                     | Load test script (k6 / JMeter) executed against the Docker Compose stack.                                                                                   |
| **Resilience**                   | **100% availability** of unaffected billers when one biller experiences timeouts or 5xx outages.                                                                                                                          | Integration test simulating ElectroCo 12s timeout while AquaCo payments succeed.                                                                            |
| **Modifiability**                | Adding a new biller or absorbing ElectroCo API v2 touches **0 files** outside `integration` and configuration.                                                                                                            | `git diff --stat` inspection in CI/PR review for the ElectroCo v2 change request.                                                                           |
| **Auditability & Observability** | **100% of ledger movements and admin actions** recorded in `audit_logs` with actor, timestamp, and reason; structured **ECS JSON logs** with correlation MDC (`paymentReference`) emitted in production (`prod` profile). | Integration assertions verifying 1:1 `audit_logs` correspondence and `application-prod.yaml` ECS console logging verification.                              |
| **Testability & Code Quality**   | **>= 70% line coverage** on domain and business logic packages, **0 formatting/lint violations**, **0 critical/blocker SonarQube issues**, and **0 new security hotspots**.                                               | JaCoCo (`0.8.14`), Spotless (Google Java Format), Checkstyle (`google_checks.xml`), and **SonarQube** quality gate in modular GitHub Actions CI (`ci.yml`). |

---

## 5. Resolved Ambiguities & Design Decisions

During requirements analysis with the AI assistant, the following specification ambiguities were identified and
resolved:

1. **Do daily and single-payment limits apply to the principal bill amount or the total amount (bill + fee)?**
    - **Decision:** Limits (5 EGP min, 10,000 EGP max single; 5,000 / 30,000 EGP daily) are evaluated against the
      **principal bill amount**, while wallet balance sufficiency and holds are evaluated against
      `billAmount + serviceFee`. This prevents a customer paying an exact 5,000 EGP bill from being unexpectedly blocked
      by the 2.50 EGP service fee.
2. **What happens to agent commission if a payment fails or is refunded?**
    - **Decision:** Agent commission (40% of the service fee) is calculated during fee evaluation but **credited to the
      agent's wallet only when the payment transitions to `COMPLETED`**. Failed or rejected payments generate zero
      commission.
3. **Does a promotion (0 EGP fee) also result in 0 EGP agent commission?**
    - **Decision:** Yes—since agent commission is defined strictly as *40% of the service fee*, a promotional 0.00 EGP
      fee yields `0.00` EGP commission.
4. **How is the 10-minute velocity block lifted?**
    - **Decision:** The block is stored with a `blockedUntil` timestamp (`now + 10 minutes`). Once `now > blockedUntil`,
      the account automatically resumes normal operation without requiring manual support intervention (unless manually
      blocked by support under FR-21).
5. **Can a customer pay more than the inquired bill amount (e.g., partial or overpayment)?**
    - **Decision:** Customers pay the exact inquired bill amount for utility bills (preventing AquaCo's overpayment
      rejection rule), while mobile top-ups (TopUpNet) accept user-entered amounts within `[5 EGP, 10,000 EGP]`.
6. **How should authentication be implemented without adding heavy external dependencies for TA grading?**
    - **Decision:** Implement self-contained **Spring Security stateless JWT authentication** (`FR-29`) backed by the
      local `users` table rather than deploying an external Keycloak instance, keeping `docker compose up` lightweight
      and fast for local grading and Testcontainers CI.
