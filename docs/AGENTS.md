# Agent Conventions & Execution Rules

## 1. Golden Rules for Executors
* **No Python.** All services, including the Risk Engine, must be written in Java (Spring Boot 3.3.5, Java 21).
* **The Ponytail Directive:** Default to the simplest solution that works. No speculative abstractions. Use standard libraries and existing framework features before adding dependencies. Keep diffs small.
* **Stop on Contradiction:** If a task card conflicts with `CONTRACTS.md`, or if `CONTRACTS.md` conflicts with existing codebase schema, **STOP** and report the issue. Do not guess.

## 2. Core Architectural Invariants & Data Ownership
* **Strict Single-Writer Data Ownership:**
  * **Oracle XE (`LEDGER_APP` schema):** The mutable System of Record (SoR).
    * `account_master` & `outbox_master`: **Owned solely by `accounts-service`**. No other service executes direct DML against these tables.
    * `customer_master`: Owned by `registration-service`.
    * `app_user_master`: Owned by `login-service`.
    * `transaction_master`, `card_master`, `amount_hold_master`: Owned by `transaction-service`.
  * **PostgreSQL (`public` schema):** The immutable audit database.
    * `ledger_mutation_audit`: **Written solely by the asynchronous `AuditLedgerConsumer`** in `transaction-service` consuming Kafka outbox events. **Never written in the synchronous HTTP request path.**
    * Triggers in PostgreSQL block all `UPDATE` and `DELETE` operations on audit tables.
* **No Cross-Database Synchronous Writes:**
  * Services must never attempt dual-writes to Oracle and PostgreSQL within the same HTTP request.
  * Synchronous mutations commit to Oracle + Oracle `outbox_master`. The `OutboxMasterRelayService` publishes events to Kafka, and consumers write to PostgreSQL.
* **Atomic Core Settlement:**
  * `transaction-service` orchestrates payments, risk checks, and quotes, but delegates balance mutations to `accounts-service` via the internal atomic settlement API: `POST /internal/core/settlement`.
* **Pessimistic Locking & Deadlock Prevention:**
  * All balance mutations in `accounts-service` must lock rows using:
    `SELECT balance_amount FROM account_master WHERE account_id IN (...) ORDER BY account_id FOR UPDATE;`
  * Account IDs must always be sorted in ascending alphanumeric order before querying.

## 3. Tech Stack & Repository Conventions
* **JDK:** Java 21 (Eclipse Temurin)
* **Framework:** Spring Boot 3.3.5 / Spring Cloud 2023.0.3
* **Build Tool:** Maven multi-module (`./mvnw clean test`)
* **Headers:** Always use `X-Idempotency-Key` for mutation idempotency.
* **Money Representation:** Always use `BigDecimal` formatted as decimal strings (`"100.0000"`) with scale 4 in JSON DTOs to avoid float precision loss.

## 4. Definition of Done for Any Task
* Code compiles cleanly with no new compiler warnings.
* Endpoints strictly match `CONTRACTS.md` specifications.
* Unit / Slice tests cover:
  1. Success path
  2. Edge/Failure path (e.g. insufficient funds, duplicate key, timeout)
* Zero sensitive data (PAN, CVV, raw passwords, JWTs) logged in plaintext.
