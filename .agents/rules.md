# LedgerLock — AI Agents Workspace Rules

## 1. Tech Stack & Conventions

- **Language & Framework:** Java 25 + Spring Boot 4.1.2 (Maven, `.yaml` config).
- **Base Package:** `tech.eyadsharkawy.ledgerlock`
- **Architecture:** Modular Monolith (`accounts`, `catalog`, `payment`, `integration`, `fees`, `fraud`, `notifications`,
  `reconciliation`, `security`, `audit`) enforced via Spring Modulith / ArchUnit architectural tests
  (`ModularArchitectureTest`).
  Keep repositories, JPA entities (where external modules only need IDs/DTOs), and protocol-specific adapters
  package-private,
  and never create circular dependencies between packages.
- **Authentication & Security:** Self-contained Spring Security stateless JWT authentication (`CUSTOMER`, `AGENT`,
  `SUPPORT`, `ADMIN`). Never introduce external IdPs (such as Keycloak) into the project or Compose stack.
- **Logging:** Keep human-readable console logs in default/local profiles (`application.yaml`); keep structured ECS JSON
  logging isolated to `application-prod.yaml` (`prod` profile) and always populate SLF4J `MDC` (`paymentReference`,
  `billerCode`) on payment/webhook flows.
- **Documentation & Design Artifacts:** Keep all Markdown files (`README.md`, `docs/requirements.md`,
  `docs/design/*.md`,
  `docs/prompt-log.md`) strictly emoji-free and synchronized with the Phase 1 Design Pack (`01-use-case-diagram.md`
  through `05-er-diagram.md`).

## 2. Non-Negotiable Domain Rules

- **Money Precision:** NEVER use `double` or `float` for monetary amounts, fees, or percentages. Always use
  `java.math.BigDecimal` with explicit rounding (`RoundingMode.HALF_UP`, scale 2 for EGP, `NUMERIC(19,2)` in
  PostgreSQL).
- **Ledger Integrity:** NEVER directly update or overwrite `availableBalance` or `heldBalance` on a Wallet. Every
  balance change must append an immutable `LedgerEntry` (`TOP_UP`, `HOLD`, `RELEASE`, `CAPTURE`, `FEE_REVENUE`,
  `AGENT_COMMISSION`, `REFUND`), and balances must equal the algebraic sum of ledger movements.
- **Auditability:** Every ledger movement and admin mutation must persist an immutable record in `audit_logs` capturing
  `actorId` (from `SecurityContextHolder`), `action`, `entityType`, `entityId`, `timestamp`, and `reason`.
- **State Machine:** Payment state transitions must strictly follow the allowed transitions (`INITIATED` ->
  `FUNDS_HELD` / `REJECTED` -> `SENT_TO_BILLER` -> `COMPLETED` / `FAILED` / `UNKNOWN` -> `REFUNDED`) and record each
  transition in `payment_state_history`. Never auto-refund `UNKNOWN` payments.
- **Concurrency & Idempotency:** Use pessimistic locking (`@Lock(LockModeType.PESSIMISTIC_WRITE)`) on `Wallet` rows
  when placing, capturing, or releasing holds (and on `Payment` rows during webhook callbacks), and enforce unique
  `idempotency_key` constraints on payment requests.
- **Event-Driven Side Effects:** Publish domain events (`PaymentCompletedEvent`, `PaymentFailedEvent`) after transaction
  commit so `notifications` dispatches SMS/push alerts with up to 3 retries (`UNDELIVERED` on exhaustion) without ever
  rolling back a settled payment.
- **Configuration over Hardcoding:** All business limits, fee tiers, timeouts (`10s`), and retry delays (`1m`, `5m`,
  `15m`) must live in `application.yaml` via `@ConfigurationProperties`, never hardcoded.

## 3. TDD, Code Quality & Course Compliance

- **GitHub Flow:** Never push directly to `main`. Create short-lived feature/docs branches (`feat/...`, `fix/...`,
  `docs/...`), open a Pull Request, and require green CI checks (`lint-and-format.yml`, `test.yml`, `sonar.yml`,
  `docker-verify.yml`) before merging.
- **TDD Workflow:** When asked to implement a new feature, write the failing JUnit 5 test FIRST before writing
  production code.
- **Formatting & Linting:** Code must pass `./mvnw spotless:check` (Google Java Format `1.28.0` for Java 25 + unused
  import removal) and `./mvnw checkstyle:check` (`google_checks.xml`) in CI (`lint-and-format.yml`). Run
  `./mvnw spotless:apply` after generating or editing Java files.
- **Static Analysis & Coverage:** Maintain `>= 70%` JaCoCo line coverage on domain and business logic packages
  (`test.yml`)
  and `0` critical/blocker issues or new security hotspots in **SonarQube** (`sonar.yml`).
- **Commit Attribution:** Whenever AI generates >50% of a change, include `[AI: Antigravity]` in the Git commit message
  and append an entry to `/docs/prompt-log.md`.
- **Clean Code:** Keep SonarQube and Checkstyle clean (no unused imports, standard formatting, constructor injection,
  clear Javadoc on public module interfaces).