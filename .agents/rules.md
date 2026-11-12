# LedgerLock — AI Agents Workspace Rules

## 1. Tech Stack & Conventions

- **Language & Framework:** Java 25 + Spring Boot 4.1.2 (Maven, `.yaml` config).
- **Base Package:** `tech.eyadsharkawy.ledgerlock`
- **Architecture:** Modular Monolith (`accounts`, `catalog`, `payment`, `integration`, `fees`, `fraud`, `notifications`,
  `reconciliation`, `security`, `audit`). Do not create circular dependencies between packages.
- **Authentication & Security:** Self-contained Spring Security stateless JWT authentication (`CUSTOMER`, `AGENT`,
  `SUPPORT`, `ADMIN`). Never introduce external IdPs (such as Keycloak) into the project or Compose stack.
- **Logging:** Keep human-readable console logs in default/local profiles (`application.yaml`); keep structured ECS JSON
  logging isolated to `application-prod.yaml` (`prod` profile) and always populate SLF4J `MDC` (`paymentReference`,
  `billerCode`) on payment/webhook flows.

## 2. Non-Negotiable Domain Rules

- **Money Precision:** NEVER use `double` or `float` for monetary amounts, fees, or percentages. Always use
  `java.math.BigDecimal` with explicit rounding (`RoundingMode.HALF_UP`, scale 2 for EGP).
- **Ledger Integrity:** NEVER directly update or overwrite `availableBalance` or `heldBalance` on a Wallet. Every
  balance change must append an immutable `LedgerEntry`, and balances must reflect ledger movements.
- **Auditability:** Every ledger movement and admin mutation must persist an immutable record in `audit_logs` capturing
  `actorId` (from `SecurityContextHolder`), `action`, `entityId`, `timestamp`, and `reason`.
- **State Machine:** Payment state transitions must strictly follow the allowed transitions (`INITIATED` ->
  `FUNDS_HELD` / `REJECTED` -> `SENT_TO_BILLER` -> `COMPLETED` / `FAILED` / `UNKNOWN` -> `REFUNDED`). Never auto-refund
  `UNKNOWN` payments.
- **Concurrency & Idempotency:** Use pessimistic locking (`@Lock(LockModeType.PESSIMISTIC_WRITE)`) when placing holds on
  a wallet, and enforce unique `idempotency_key` constraints on payment requests.
- **Configuration over Hardcoding:** All business limits, fee tiers, timeouts (`10s`), and retry delays (`1m`, `5m`,
  `15m`) must live in `application.yaml` via `@ConfigurationProperties`, never hardcoded.

## 3. TDD, Code Quality & Course Compliance

- **TDD Workflow:** When asked to implement a new feature, write the failing JUnit 5 test FIRST before writing
  production code.
- **Formatting & Linting:** Code must pass `./mvnw spotless:check` (Google Java Format + unused import removal) and
  `./mvnw checkstyle:check` (`google_checks.xml`) in CI (`lint-and-format.yml`). Run `./mvnw spotless:apply` after
  generating or editing Java files.
- **Static Analysis & Coverage:** Maintain `>= 70%` JaCoCo line coverage on domain and business logic packages and `0`
  critical/blocker issues or new security hotspots in **SonarQube** (`./mvnw verify sonar:sonar`).
- **Commit Attribution:** Whenever AI generates >50% of a change, remind the user to include `[AI: Antigravity]` in the
  Git commit message and append an entry to `/docs/prompt-log.md`.
- **Clean Code:** Keep SonarQube and Checkstyle clean (no unused imports, standard formatting, constructor injection,
  clear Javadoc on public module interfaces).
