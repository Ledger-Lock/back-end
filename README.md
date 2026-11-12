# LedgerLock (`ledger-lock/back-end`) — PayLink Digital Wallet & Bill Payment

Digital wallet and utility bill-payment backend platform implementing the **PayLink** specification, built with **Java
25** and **Spring Boot 4.1.2**, enforcing strict financial correctness (`BigDecimal` precision, append-only
immutable ledger accounting, pessimistic wallet locking, and idempotent payment execution).

---

## Architecture & Tech Stack

* **Runtime & Framework:** Java 25, Spring Boot 4.1.2, Maven
* **Persistence & Migrations:** PostgreSQL 17, Spring Data JPA, Flyway
* **Messaging & Async Events:** Apache Kafka 3.9.0 (KRaft mode)
* **Security:** Stateless Spring Security JWT RBAC (`CUSTOMER`, `AGENT`, `SUPPORT`, `ADMIN`)
* **Observability & API Docs:** Spring Boot Actuator, OpenAPI / Swagger UI (`springdoc-openapi`), ECS structured JSON
  logging (`prod` profile) with SLF4J `MDC` correlation
* **Quality Gates & CI/CD:** Spotless (Google Java Format), Checkstyle (`google_checks.xml`), JaCoCo (`>= 70%`
  coverage), SonarQube, Testcontainers, and GitHub Actions

### Modular Monolith Packages (`tech.eyadsharkawy.ledgerlock`)

```text
src/main/java/tech/eyadsharkawy/ledgerlock/
├── accounts/         # Users, verification levels, wallets, and immutable ledger entries
├── audit/            # Immutable compliance audit trail for money & admin mutations
├── catalog/          # Active billers, services, and dynamic input field schemas
├── fees/             # Strategy-based fee calculation, promotions, and agent commissions
├── fraud/            # Chain of Responsibility rules (status, velocity, single & daily limits)
├── integration/      # BillerAdapter implementations (ElectroCo REST, AquaCo SOAP, TopUpNet Async, NetCo)
├── notifications/    # Event-driven SMS/push dispatch with 3-attempt retry handling
├── payment/          # Bill inquiry, idempotent payment orchestration, state machine, webhook callbacks
├── reconciliation/   # Nightly biller CSV settlement parsing and discrepancy reporting
└── security/         # Stateless JWT filter, token provider, and role-based access rules
```

---

## Documentation

* **[Software Requirements Specification (`docs/requirements.md`)](./docs/requirements.md)** — Functional requirements
  (`FR-1` to `FR-29`), measurable NFR targets, and resolved domain ambiguities.
* **[Phase 1 Design Pack (`docs/design/`)](./docs/design/README.md)** — Use Case, Domain Class, Sequence, Payment State
  Machine, and PostgreSQL ER diagrams.
* **[AI Prompt Log (`docs/prompt-log.md`)](./docs/prompt-log.md)** — Traceability log of AI-assisted architecture,
  requirements, and implementation decisions.

---

## Local Development Quickstart

Run the full stack with two commands:

```bash
git clone https://github.com/Ledger-Lock/back-end.git && cd back-end
docker compose up --build -d
```

Verify service health:

```bash
curl http://127.0.0.1:8080/actuator/health
```

### Build, Lint & Test Locally

```bash
# Apply Google Java Format
./mvnw spotless:apply

# Run formatting and Checkstyle checks
./mvnw spotless:check checkstyle:check

# Run unit and Testcontainers integration tests with JaCoCo report
./mvnw verify
```

---

## Deployment Health Check & Rollback Procedure

### Production Deployment & Health Verification

The GitHub Actions CI/CD pipeline (`ci.yml` -> `deploy.yml`) automatically builds, verifies, and deploys pushes on
`main` to the host environment. Because production does not expose host ports (isolating traffic to `ledger-lock-net`),
health is verified directly via Docker's container health check:

```bash
docker compose -f docker-compose.yaml -f docker-compose.prod.yaml up -d --build

# Automated health check (no exposed host port required)
docker inspect --format='{{.State.Health.Status}}' ledger-lock-backend
# Or execute directly inside the container namespace:
docker exec ledger-lock-backend wget -qO- http://localhost:8080/actuator/health
```

### Rollback Step

If a deployment fails the health check or encounters a critical defect:

```bash
# 1. Check out the previous known-stable release tag (e.g., phase-1)
git checkout phase-1

# 2. Re-deploy the verified container stack
docker compose -f docker-compose.yaml -f docker-compose.prod.yaml up -d --build

# 3. Confirm health recovery via Docker container inspection
docker inspect --format='{{.State.Health.Status}}' ledger-lock-backend
```
