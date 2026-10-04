# Acceptance Criteria, Definition of Done & Chaos Protocols

---

## 1. Definition of Done (DoD) per Task Card

A task is completed and eligible for merge only when:
1. **Compilation & Style:** Clean build with `./mvnw clean test-compile` producing zero compilation errors.
2. **Contract Fidelity:** Payload schemas strictly match `CONTRACTS.md` with exact JSON field names, types, and status codes.
3. **Data Ownership Compliance:**
   * Only `accounts-service` mutates `account_master` in Oracle XE.
   * PostgreSQL audit tables are written strictly by asynchronous Kafka consumers (`AuditLedgerConsumer`), never directly in the HTTP mutate path.
4. **Lock Ordering & Concurrency:** Multi-row queries on `account_master` must use ascending alphanumeric ID ordering with `FOR UPDATE`.
5. **Automated Test Coverage:**
   * At least 1 unit/slice test verifying the successful execution path.
   * At least 1 unit/slice test verifying failure handling (insufficient funds, invalid currency, duplicate key, or timeout).
6. **Zero Plaintext Secrets:** Logs contain no PAN, CVV, raw passwords, or unmasked authorization tokens.

---

## 2. Graded Chaos Scenarios (Matching Capstone Rubric)

### Scenario 1: Oracle Database Degradation (Circuit Breaker & Fallback)
* **Chaos Injection:** Inject latency into Oracle container:
  ```bash
  docker exec -u 0 oracle-db tc qdisc add dev eth0 root netem delay 800ms
  ```
* **Verification Steps:**
  1. Trigger account balance inquiries via Gateway.
  2. Latency breaches Resilience4j threshold ($180\text{ms}$), tripping the circuit breaker to `OPEN`.
  3. Subsequent balance queries immediately return cached data from Redis (`balance_cache:*`) with an HTTP 200 response indicating cached timestamp.
  4. Balance mutations return `HTTP 503 Service Degraded` fail-safely without hung threads.
* **Remediation:**
  ```bash
  docker exec -u 0 oracle-db tc qdisc del dev eth0 root
  ```

---

### Scenario 2: Synchronous Risk Engine Timeout (Fail-Closed SLA < 200ms)
* **Chaos Injection:** Artificially delay Risk Engine response by $300\text{ms}$ or terminate the container:
  ```bash
  docker stop risk-engine
  ```
* **Verification Steps:**
  1. Execute `POST /api/v1/payments` via Gateway.
  2. Spring Cloud Gateway or `transaction-service` Resilience4j `TimeLimiter` triggers an SLA timeout at $\le 200\text{ms}$.
  3. Payment fails closed immediately: returns `HTTP 503 Service Unavailable` with error code `SECURITY_CHECK_UNAVAILABLE`.
  4. Verify in Oracle: **Zero balances are debited**, no partial transaction state is written.
* **Remediation:**
  ```bash
  docker start risk-engine
  ```

---

### Scenario 3: Perimeter Port Rejection Check (Zero-Trust Scan)
* **Chaos Probe:** Execute external network scan against internal ports:
  ```powershell
  Test-NetConnection -ComputerName localhost -Port 8081  # Registration
  Test-NetConnection -ComputerName localhost -Port 8083  # Accounts CBS
  Test-NetConnection -ComputerName localhost -Port 8084  # Transactions
  Test-NetConnection -ComputerName localhost -Port 1521  # Oracle DB
  Test-NetConnection -ComputerName localhost -Port 5432  # Postgres DB
  ```
* **Expected Result:**
  * `TcpTestSucceeded : False` across ALL internal service ports.
  * Only Port `:8080` (API Gateway) and `:8090` (Admin Web) are reachable from outside.

---

### Scenario 4: PostgreSQL Audit Outage & Recovery (Zero Audit Loss)
* **Chaos Injection:** Stop PostgreSQL container:
  ```bash
  docker stop postgres-audit-db
  ```
* **Verification Steps:**
  1. Post a payment (`POST /api/v1/payments`).
  2. **Payment succeeds for the customer:** Oracle balance mutation commits and `outbox_master` row is written.
  3. `AuditLedgerConsumer` Kafka consumer encounters connection error connecting to PostgreSQL.
  4. **Consumer pauses and retries with backoff**; it does **NOT** dead-letter ledger events to `.DLT` during database downtime.
  5. Restart PostgreSQL: `docker start postgres-audit-db`.
  6. Consumer catches up automatically. Run reconciliation: **Audit parity is 100% restored**.
