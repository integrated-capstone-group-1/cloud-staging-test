# Agent Conventions & Execution Rules

> Rebuilt from `ARCHITECTURE.md` (sections 1, 3, 8) and `CONTRACTS.md`. Lines tagged `[VERIFY H-nn]` are documented intent not yet confirmed against the code (see `HANDOFF_CODEBASE_AGENT.md`); remove the tag when confirmed.

## 1. Golden Rules for Executors
* **No Python in services.** All services, including the Risk Engine, are Java (Spring Boot 3.3.5, Java 21). **Exception:** the test harness (pytest, under `tests/`) may be Python; it never ships in a service.
* **The Ponytail Directive:** Default to the simplest solution that works. No speculative abstractions. Use standard libraries and existing framework features before adding dependencies. Keep diffs small.
* **Stop on Contradiction:** If a task card conflicts with `CONTRACTS.md`, or `CONTRACTS.md` conflicts with the existing schema or code, **STOP** and report. Do not guess. (In the fixer-breaker loop the FIXER classifies the conflict: `CODE_BUG` or `SPEC_FIX`.)

## 2. Tiers and Services
| Tier | Component | Port | Role |
|---|---|---|---|
| 2 | `api-gateway` (Spring Cloud Gateway) | 8080 | JWT verification, blacklist, rate limit, route isolation |
| 3 | Orchestrator (thin, stateless BFF) | n/a | Ownership checks and aggregation for customer requests; forwards payments to `transaction-service` `[VERIFY H-11]` |
| 3 | `login-service` | 8082 | BCrypt login, JWT, refresh, OTP, lockout, blacklist writes |
| 3 | `registration-service` | 8081 | Onboarding; calls CBS to provision customer, PHP pocket and card |
| 3 | `notification-service` | 8085 | Alerts and in-app inbox |
| 4 | `transaction-service` | 8084 | 7-status payment saga, CAS, idempotency, shortfall routing, cancel, reversals, standing instructions, `admin_approval` |
| 4 | `accounts-service` (Mock CBS) | 8083 | System of record for balances, holds, cards, ledger journal, settlement, outbox |
| 4 | `forex-service` | 8087 | Rates, 30-second quotes, stale-rate guard |
| 4 | `risk-engine` | 8088 | Synchronous risk scoring, advisory only, never mutates balances |
| 5 | Audit consumer (`AuditLedgerConsumer`) | n/a | Sole writer of the PostgreSQL audit tables; location (own module or inside `transaction-service`) `[VERIFY H-11]` |
| 5 | `reconciliation-service` | 8086 | Oracle vs PostgreSQL audit parity |
| 1 | Admin Portal (Thymeleaf) | 8090 | Back-office UI, calls services directly |

## 3. Data Ownership (single writer)
Oracle XE uses per-service schemas `CBS`, `PAYMENT` and `FX` `[VERIFY H-11]` (an earlier draft named a single `LEDGER_APP` schema).

| Store | Table | Sole writer |
|---|---|---|
| Oracle `CBS` | `customer_master`, `account_master`, `card_master`, `account_hold`, `ledger_entry`, `posting_reference`, `outbox_master` | `accounts-service` (`ledger_entry` insert-only). `customer_master` ownership `[VERIFY H-11]` |
| Oracle `PAYMENT` | `transaction_master`, `standing_instruction`, `admin_approval` | `transaction-service` |
| Oracle `FX` | `fx_rate` | `forex-service` `[VERIFY H-11]` (other documents place rates in PostgreSQL `fx_rate_cache`) |
| PostgreSQL Channel DB | `app_user_master` | `login-service`; registration also inserts credentials `[VERIFY H-11]` |
| PostgreSQL Channel DB | `notification_log` | `notification-service` |
| PostgreSQL Audit DB | `ledger_mutation_audit`, `risk_screening_audit`, `fx_conversion_audit` | The asynchronous `AuditLedgerConsumer` only, never in an HTTP request path. Triggers block `UPDATE` and `DELETE`. |
| PostgreSQL Audit DB | `recon_run_audit` / `recon_result_audit` | `reconciliation-service` `[VERIFY H-10]` |
| Redis | `jwt_blacklist:*` (login), `rate_limiter:*` (gateway), `balance_cache:*` (accounts), `idempotency:*` (transaction) | respective owner |

## 4. Architectural Invariants
* **No cross-database synchronous writes.** Synchronous mutations commit to Oracle and `outbox_master`. `OutboxMasterRelayService` publishes to Kafka; consumers write PostgreSQL.
* **Atomic core settlement.** Balance mutations go only through `POST /internal/core/settlement` on `accounts-service`. `transaction-service` claims `AUTHORIZED → PROCESSING` by CAS before calling it (ADR-002).
* **Pessimistic locking, ascending order.** `SELECT balance_amount FROM account_master WHERE account_id IN (...) ORDER BY account_id FOR UPDATE;` with IDs sorted ascending.
* **Fail closed.** A risk or security check that fails or exceeds 200 ms returns `503 SECURITY_CHECK_UNAVAILABLE` with no balance change.
* **Identity.** Caller identity comes only from the verified JWT (`X-Cust-Id` injected by the gateway); never from a request body.
* **Internal routes** (`/internal/**`, direct credit/debit, `/api/v1/ledger/mutate`) are blocked at the gateway.

## 5. Tech Stack & Conventions
* **JDK:** Java 21 (Eclipse Temurin). **Framework:** Spring Boot 3.3.5 / Spring Cloud 2023.0.3. **Build:** Maven multi-module (`./mvnw clean test`).
* **Packages:** `com.capstone.*`, except `reconciliation-service` (`com.bank.reconciliation.*`).
* **Headers:** `X-Idempotency-Key` on every mutation.
* **Money:** `BigDecimal`, decimal strings with scale 4 in JSON (`"100.0000"`).
* **Errors:** RFC 7807 with codes from the `CONTRACTS.md` catalog; no stack traces, SQL or internal hosts in bodies.

## 6. Definition of Done for Any Task
* Code compiles cleanly with no new compiler warnings.
* Endpoints strictly match `CONTRACTS.md`.
* Unit or slice tests cover the success path and an edge or failure path (insufficient funds, duplicate key, timeout).
* A defect fix adds a regression test that fails before the fix and passes after: JUnit in the owning module, or a black-box pytest under `tests/e2e/`.
* `check_invariants` (see `INVARIANTS.md`) is clean after the change.
* No sensitive data (PAN, CVV, raw passwords, JWTs, OTPs) in logs.
