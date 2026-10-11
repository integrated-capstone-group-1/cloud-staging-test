# Acceptance Criteria, Definition of Done & Graded Chaos Protocols

---

## 1. Definition of Done (DoD) per Task Card

A task is completed and eligible for merge to `main` only when:
1. **Clean Compilation:** Builds cleanly with `./mvnw clean test-compile` (or `flutter analyze`) producing zero compilation errors.
2. **Contract Strictness:** Payload schemas, error codes, and HTTP statuses strictly match [`docs/CONTRACTS.md`](CONTRACTS.md).
3. **Single-Writer Ownership Compliance:**
   * Only `accounts-service` mutates `account_master`, `account_hold`, and `outbox_master`.
   * `transaction-service` owns `transaction_master` and CAS transitions.
   * PostgreSQL audit tables are written strictly by asynchronous Kafka consumers (`AuditLedgerConsumer`), never in the HTTP mutate path.
4. **Lock Ordering & Concurrency:** Multi-row operations on `account_master` sort IDs in ascending alphanumeric order before acquiring `FOR UPDATE` locks.
5. **Automated Test Coverage:**
   * At least 1 unit/slice test verifying the successful path.
   * At least 1 test verifying failure/edge handling (insufficient funds, duplicate key, or timeout).
6. **Zero Plaintext Secrets:** Logs contain no PAN, CVV, raw passwords, or unmasked authorization tokens.
7. **Invariants:** `check_invariants` ([`docs/INVARIANTS.md`](INVARIANTS.md)) reports clean after the task's tests.

---

## 2. Graded Chaos Scenarios (ADR-003)

The following 4 primary scenarios are **graded live demonstration requirements** for the evaluation panel:

### Scenario 1: Oracle Database Degradation (`CH-01`)
* **Chaos Injection:**
  ```bash
  docker exec -u 0 oracle-db tc qdisc add dev eth0 root netem delay 800ms
  ```
* **Verification Steps:**
  1. Trigger account balance inquiries via Gateway.
  2. Latency breaches Resilience4j threshold ($180\text{ms}$), tripping circuit breaker to `OPEN`.
  3. Subsequent balance queries immediately return cached data from Redis (`balance_cache:*`) with timestamp label.
  4. Balance mutations return `HTTP 503 Service Degraded` fail-safely without hung Tomcat threads.
* **Remediation:**
  ```bash
  docker exec -u 0 oracle-db tc qdisc del dev eth0 root
  ```

---

### Scenario 2: Synchronous Risk Engine Timeout (`CH-02`)
* **Chaos Injection:** Delay response by $> 300\text{ms}$ or terminate container:
  ```bash
  docker stop risk-engine
  ```
* **Verification Steps:**
  1. Execute `POST /api/v1/payments` via Gateway.
  2. Resilience4j `TimeLimiter` triggers an SLA timeout at $\le 200\text{ms}$.
  3. Payment fails closed immediately: returns `HTTP 503 Service Unavailable` with `SECURITY_CHECK_UNAVAILABLE`.
  4. Verify in Oracle: **Zero balances debited**, no partial transaction state written.
* **Remediation:**
  ```bash
  docker start risk-engine
  ```

---

### Scenario 3: Perimeter Port Rejection Check (`CH-03`)
* **Chaos Probe:** Execute external network scan against internal ports:
  ```powershell
  Test-NetConnection -ComputerName localhost -Port 8081  # Registration
  Test-NetConnection -ComputerName localhost -Port 8083  # Accounts CBS
  Test-NetConnection -ComputerName localhost -Port 8084  # Transactions
  Test-NetConnection -ComputerName localhost -Port 8088  # Risk Engine
  Test-NetConnection -ComputerName localhost -Port 1521  # Oracle DB
  Test-NetConnection -ComputerName localhost -Port 5432  # Postgres DB
  ```
* **Expected Result:**
  * `TcpTestSucceeded : False` across ALL internal service ports.
  * Only Port `:8080` (API Gateway) and `:8090` (Admin Web) are reachable from outside.

---

### Scenario 4: PostgreSQL Audit Outage & Recovery (`CH-04`)
* **Chaos Injection:** Stop PostgreSQL container:
  ```bash
  docker stop postgres-audit-db
  ```
* **Verification Steps:**
  1. Post a payment (`POST /api/v1/payments`).
  2. **Payment succeeds for the customer:** Oracle balance mutation commits and `outbox_master` row is written.
  3. `AuditLedgerConsumer` encounters connection exception connecting to PostgreSQL.
  4. **Consumer pauses and retries with backoff**; it does **NOT** dead-letter financial ledger events to `.DLT`.
  5. Restart PostgreSQL: `docker start postgres-audit-db`.
  6. Consumer catches up automatically. Run reconciliation: **Audit parity is 100% restored**.

---

## 3. Secondary Automated Resilience Scenarios (BRD Section 14)

* **`CH-05` (Kafka Outage):** Outbox accumulates in Oracle `outbox_master`; drains on broker recovery.
* **`CH-06` (FX API Outage):** `api.frankfurter.dev` unreachable; system serves from cache until 24h stale rate guard blocks new conversions with `FX_RATE_STALE`.
* **`CH-07` (Poison Message):** Malformed JSON routes to `<topic>.retry`, then `<topic>.dlq`, without blocking the consumer partition. `[VERIFY H-10]` (number of retry topics)
* **`CH-08` (Process Kill Mid-Transaction):** Service killed during `PROCESSING`; `StuckTransactionSweeper` calls `GET /internal/core/settlement/{ref}` to resolve to `POSTED` or `FAILED`.

