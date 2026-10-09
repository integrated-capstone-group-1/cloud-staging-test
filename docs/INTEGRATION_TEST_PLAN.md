# Integration Test Plan (v2.0)
## Component Seams, Multi-Tier Subsystems & Service-to-Service Contracts

---

## 1. Objective & Scope of Integration Testing

Integration testing verifies the **seams and interactions between subsystems** across the Capstone Banking System. While unit tests validate business logic in isolation using mocks, integration testing exercises actual network, persistence, caching, and message bus boundaries:
* **Service-to-Database Seams:** Real Oracle XE 21c (schemas `CBS`, `PAYMENT`, `FX`) and PostgreSQL 15 (Channel DB & Audit DB).
* **Service-to-Service Seams:** Feign HTTP clients, Resilience4j circuit breakers, timeouts, and fallback mechanisms.
* **Transactional Outbox & Event Bus Seams:** Outbox table polling, Kafka broker topic partitioning, retry topics, and single-writer audit consumers.
* **Edge Perimeter Seams:** Spring Cloud Gateway `:8080` route forwarding, JWT signature fast-path, Redis token-bucket rate limiting, and internal route cloaking.
* **Client-to-Gateway Seams:** Flutter Dio HTTP client network interceptors, secure token storage, and administrative portal REST bridges.

---

## 2. 7-Member Parallel Workstream Allocation (Zero Overlap)

To guarantee that all 7 engineers can execute integration testing simultaneously without blocking each other, the test seams are divided into 7 mutually exclusive workstreams:

```mermaid
flowchart TD
    subgraph ParallelIntegration["7 Non-Overlapping Integration Workstreams"]
        W1["Workstream 1 - Yancey: CBS Engine and Oracle XE Seams"]
        W2["Workstream 2 - Rohan: Channel Orchestrator and FX Seams"]
        W3["Workstream 3 - PK: Identity, Risk SLA and Admin Portal Seams"]
        W4["Workstream 4 - Russel: API Gateway and Perimeter Seams"]
        W5["Workstream 5 - Gym: Event Bus, Outbox Relay and Audit Seams"]
        W6["Workstream 6 - Lanz: Mobile Core Client and Network Seams"]
        W7["Workstream 7 - Angge: Mobile UI State and Widget Seams"]
    end
```

| Workstream | Owner | Subsystems & Boundaries Tested | Primary Task Cards | Mapped Jira Issues |
|:---|:---|:---|:---|:---|
| **Workstream 1** | **Yancey** | `accounts-service` $\leftrightarrow$ Oracle XE `CBS` schema & `reconciliation-service` read replica. | `T-002`, `T-012` | `DIG-3`, `DIG-4`, `DIG-52`, `DIG-66`, `DIG-84`, `DIG-85`, `DIG-112` |
| **Workstream 2** | **Rohan** | `orchestrator-service` (:8089) $\leftrightarrow$ `transaction-service` (:8084) $\leftrightarrow$ `accounts-service` (Feign) & `forex-service` (Quotes & CAS). | `T-001`, `T-007` | `DIG-14`, `DIG-15`, `DIG-70`, `DIG-71`, `DIG-73`, `DIG-74`, `DIG-75`, `DIG-80`, `DIG-83` |
| **Workstream 3** | **PK** | `login-service` / `registration-service` $\leftrightarrow$ `accounts-service`, `risk-engine` & `frontend-web`. | `T-003`, `T-006`, `T-011` | `DIG-6`, `DIG-7`, `DIG-12`, `DIG-21`, `DIG-22`, `DIG-23`, `DIG-50`, `DIG-54`, `DIG-90` |
| **Workstream 4** | **Russel** | `api-gateway` (:8080) $\leftrightarrow$ Downstream Microservices, Redis 7, and Zero-Trust Network. | `T-005`, `T-014` | `DIG-8`, `DIG-25`, `DIG-26`, `DIG-28`, `DIG-30`, `DIG-31` |
| **Workstream 5** | **Gym** | Oracle `outbox_master` $\leftrightarrow$ Kafka Broker $\leftrightarrow$ `AuditLedgerConsumer` $\leftrightarrow$ PostgreSQL Audit DB. | `T-004`, `T-014` | `DIG-5`, `DIG-20`, `DIG-32`, `DIG-88`, `DIG-89` |
| **Workstream 6** | **Lanz** | Flutter Dio HTTP Client $\leftrightarrow$ Android KeyStore $\leftrightarrow$ Gateway `:8080`. | `T-009`, `T-010` | `DIG-10`, `DIG-38`, `DIG-40`, `DIG-44`, `DIG-46`, `DIG-94`, `DIG-95` |
| **Workstream 7** | **Angge** | Flutter UI Widgets $\leftrightarrow$ Mobile State BLoC $\leftrightarrow$ Endpoints (Pockets, Shortfall Sheet, Cards). | `T-008`, `T-010` | `DIG-11`, `DIG-13`, `DIG-39`, `DIG-41`, `DIG-42`, `DIG-47`, `DIG-49`, `DIG-98` |

---

## 3. Step-by-Step Integration Test Execution Runbooks

### 3.1 Workstream 1: Yancey — CBS Engine & Oracle XE Seams

#### Prerequisites:
```bash
docker compose up -d oracle-db
# Wait for Oracle container healthcheck: healthy
```

#### Step-by-Step Execution:
* **SEAM-YAN-01: Row-Level Locking & Deadlock Prevention Seam (`DIG-52`, `BR-01`)**
  * *Step 1:* Run the integration test:
    ```bash
    ./mvnw test -pl accounts-service -Dtest=CoreSettlementServiceIntegrationTest#verifyAscendingRowLocking
    ```
  * *Step 2:* The test spawns 2 concurrent transfers: Pair 1 (`acct-A` $\to$ `acct-B`), Pair 2 (`acct-B` $\to$ `acct-A`).
  * *Step 3:* Inspect SQL logs: Verify both threads acquire locks on `MIN(acct_id)` first, then `MAX(acct_id)`.
  * *Step 4:* Assert 0 `ORA-00060` (deadlock detected) errors; both transactions commit sequentially.
* **SEAM-YAN-02: Atomic Multi-Leg Settlement & Ledger Journal Seam (`DIG-66`, `PLAN.md` Delta §3.2)**
  * *Step 1:* Issue multi-leg settlement request to CBS:
    ```bash
    curl -X POST http://localhost:8083/internal/core/settlement \
      -H "Content-Type: application/json" \
      -H "X-Internal-Role: ROLE_INTERNAL" \
      -d '{
        "transaction_id": "txn-int-001",
        "posting_reference_id": "FT2627800099",
        "legs": [
          {"account_id": "acct-juan-php-01", "entry_type": "DEBIT", "amount": "1000.0000", "currency": "PHP"},
          {"account_id": "acct-maria-php-01", "entry_type": "CREDIT", "amount": "1000.0000", "currency": "PHP"}
        ]
      }'
    ```
  * *Step 2:* Verify HTTP `200 OK` with `core_reference_id: "FT2627800099"`.
  * *Step 3:* Query Oracle XE via SQL client:
    ```sql
    SELECT * FROM ledger_entry WHERE txn_id = 'txn-int-001';
    SELECT status FROM outbox_master WHERE txn_id = 'txn-int-001';
    ```
  * *Step 4:* Assert exactly 2 `ledger_entry` records exist with correct `running_balance`, and 1 `outbox_master` row exists with `status = 'PENDING'`.
* **SEAM-YAN-03: Hold Placement, Available Balance & Expiry Seam (`DIG-84`, `DIG-85`, `BR-33`)**
  * *Step 1:* Place hold:
    ```bash
    curl -X POST http://localhost:8083/internal/accounts/acct-juan-php-01/holds \
      -H "Content-Type: application/json" \
      -H "X-Internal-Role: ROLE_INTERNAL" \
      -d '{"amount": "2500.0000", "currency": "PHP", "reason_code": "FRAUD_REVIEW"}'
    ```
  * *Step 2:* Query account balance:
    ```bash
    curl -s http://localhost:8083/internal/accounts/acct-juan-php-01
    ```
  * *Step 3:* Assert: `balance_amount` remains `50000.0000`, `held_amount = 2500.0000`, and `available_balance = 47500.0000`.
  * *Step 4:* Release hold via `DELETE /internal/accounts/acct-juan-php-01/holds/{holdId}`. Verify `available_balance` returns to `50000.0000`.
* **SEAM-YAN-04: Dual-DataSource Reconciliation Read Seam (`DIG-112`)**
  * *Step 1:* Run reconciliation read test:
    ```bash
    ./mvnw test -pl reconciliation-service -Dtest=ReconciliationEngineIntegrationTest#verifyOracleReadReplica
    ```
  * *Step 2:* Assert `ReconciliationEngine` successfully streams active account records from Oracle XE `account_master` without initiating write locks.

---

### 3.2 Workstream 2: Rohan — Channel Orchestrator, Feign Clients & FX Seams

#### Prerequisites:
```bash
docker compose up -d oracle-db postgres-db redis forex-service accounts-service transaction-service
```

#### Step-by-Step Execution:
* **SEAM-ROH-01: CoreBankingClient Feign Bridge Seam (`DIG-14`)**
  * *Step 1:* Run Feign client contract test:
    ```bash
    ./mvnw test -pl transaction-service "-Dtest=CoreBankingClientIntegrationTest"
    ```
  * *Step 2:* Test issues settlement from `transaction-service` to `http://accounts-service:8083/internal/core/settlement`.
  * *Step 3:* Assert: Feign client correctly serializes `CoreSettlementRequest`, handles `200 OK` response mapping, and translates `422 INSUFFICIENT_FUNDS` into domain `InsufficientBalanceException`.
* **SEAM-ROH-02: Forex Quote & 30s Locked Rate Seam (`DIG-74`, `DIG-75`, `BR-04`)**
  * *Step 1:* Run automated Maven FX quote test (or request conversion quote from live `forex-service`):
    ```bash
    ./mvnw test -pl forex-service "-Dtest=ForexQuoteAndSchedulerTest#quoteCalculatesSpreadAccurately"
    ```
    Live curl equivalent:
    ```bash
    curl -s "http://localhost:8087/api/v1/fx/quote?from=PHP&to=EUR&amount=100.00"
    ```
  * *Step 2:* Verify JSON response includes `quote_id`, `rate_applied` (with 1.5% spread), `expires_at`, and `ttl_seconds: 30`.
  * *Step 3:* Query Oracle XE: `SELECT * FROM fx_rate WHERE base_currency = 'EUR' AND quote_currency = 'PHP';`
  * *Step 4:* Assert quote matches database rate without making live HTTP calls to external feeds.
* **SEAM-ROH-03: Compare-and-Set (CAS) Lifecycle Transition Seam (`DIG-80`, `BR-30`)**
  * *Step 1:* Run CAS transition test:
    ```bash
    ./mvnw test -pl transaction-service "-Dtest=TransactionLifecycleIntegrationTest#verifyCasClaimTransition"
    ```
  * *Step 2:* Initiate transaction `TXN-CAS-1` in status `AUTHORIZED`.
  * *Step 3:* Two concurrent worker threads execute CAS claim to `PROCESSING`.
  * *Step 4:* Assert: Exactly 1 thread receives 1 updated row and proceeds to settlement; the 2nd thread receives 0 updated rows and safely aborts.
* **SEAM-ROH-04: Smart Shortfall Multi-Currency Orchestration Seam (`DIG-73`, `BR-28`)**
  * *Step 1:* Run automated Smart Shortfall test (or post payment through thin orchestrator / transaction-service):
    ```bash
    ./mvnw test -pl transaction-service "-Dtest=SmartShortfallIntegrationTest#verifySmartShortfallOrchestration"
    ```
    Live curl equivalent:
    ```bash
    curl -X POST http://localhost:8084/api/v1/payments \
      -H "Content-Type: application/json" \
      -H "X-Cust-Id: cust-user-001" \
      -H "X-Idempotency-Key: e8a91176-0000-4000-8000-000000000001" \
      -d '{
        "recipient_account_id": "acct-maria-eur-01",
        "amount": "50.0000",
        "currency": "EUR",
        "funding_pocket_id": "acct-juan-php-01",
        "quote_id": "q-valid-uuid"
      }'
    ```
  * *Step 2:* Verify HTTP `200 OK` with status `POSTED`.
  * *Step 4:* Verify CBS settlement executed all 6 legs atomically.
* **SEAM-ROH-05: Channel Orchestrator Payment Forwarding & Ownership Enforcement Seam (`DIG-14`, `DIG-70`, `BR-01`)**
  * *Step 1:* Run Channel Orchestrator payment and ownership integration tests:
    ```bash
    ./mvnw test -pl orchestrator-service "-Dtest=ChannelOrchestratorIntegrationTest#shouldOrchestratePaymentJourneyEndToEnd,ChannelOrchestratorIntegrationTest#shouldBlockUnauthorizedPaymentBeforeReachingCoreLedger"
    ```
    Live curl equivalent (Forwarding to Transaction Service):
    ```bash
    curl -X POST http://localhost:8089/api/v1/payments \
      -H "Content-Type: application/json" \
      -H "Authorization: Bearer <customer-jwt>" \
      -H "X-Idempotency-Key: idemp-orch-001" \
      -d '{
        "sender_account_id": "acct-juan-eur-01",
        "recipient_account_id": "acct-maria-eur-01",
        "amount": 50.00,
        "currency": "EUR"
      }'
    ```
  * *Step 2:* Verify caller identity and account ownership check (`cust-user-101` owns `acct-juan-eur-01`).
  * *Step 3:* Verify orchestrator forwards valid authenticated payment to Transaction Service (`:8084`) and returns `200 OK` with `status: POSTED` and `transaction_id`.
  * *Step 4:* Attempt unauthorized payment from an unowned sender account (`acct-victim-php-01`).
  * *Step 5:* Assert orchestrator rejects with `403 Forbidden` (`Access denied: You do not own account`) before reaching Transaction Service or core ledger.
* **SEAM-ROH-06: Backend-for-Frontend (BFF) Home Dashboard Aggregation Seam (`DIG-39`, `DIG-41`)**
  * *Step 1:* Run BFF Home Dashboard aggregation test:
    ```bash
    ./mvnw test -pl orchestrator-service "-Dtest=ChannelOrchestratorIntegrationTest#shouldAggregateHomeDashboardAcrossAllThreeServices"
    ```
    Live curl equivalent:
    ```bash
    curl -s http://localhost:8089/api/v1/dashboard/home \
      -H "Authorization: Bearer <customer-jwt>"
    ```
  * *Step 2:* Assert `orchestrator-service` aggregates accounts from `accounts-service` (`:8083`), recent payments from `transaction-service` (`:8084`), and notifications from `notification-service` (`:8085`) into a single unified JSON response in 1 network roundtrip.
* **SEAM-ROH-07: Multi-Currency Pockets Presentation & Ownership Seam (`DIG-71`, `BR-01`)**
  * *Step 1:* Run pockets query test:
    ```bash
    ./mvnw test -pl orchestrator-service "-Dtest=ChannelOrchestratorIntegrationTest#shouldRetrieveMultiCurrencyPockets"
    ```
    Live curl equivalent:
    ```bash
    curl -s http://localhost:8089/api/v1/accounts/acct-php-01/pockets \
      -H "Authorization: Bearer <customer-jwt>"
    ```
  * *Step 2:* Assert caller ownership is verified before querying multi-currency pockets.
  * *Step 3:* Assert JSON array contains all customer multi-currency accounts (e.g. PHP, EUR, USD) with accurate balances and currency codes.

---

### 3.3 Workstream 3: PK — Identity, Security, Risk Engine & Admin Portal Seams

#### Prerequisites:
```bash
docker compose up -d oracle-db redis risk-engine registration-service login-service frontend-web channel-db
```

#### Step-by-Step Execution & Verified Evidence:
* **SEAM-PK-01: Registration & CBS Auto-Provisioning Seam (`DIG-6`, `BR-16`)**
  * *Step 1:* Register new user via Gateway:
    ```powershell
    curl.exe -s --json '{\"firstName\":\"Elena\",\"lastName\":\"Reyes\",\"email\":\"elena.reyes@example.com\",\"contactNo\":\"+639171234567\",\"birthDate\":\"1992-05-14\",\"password\":\"password123\"}' http://localhost:8080/api/auth/register
    ```
  * *Step 2:* Verify HTTP `201 Created` with generated `customerId` upon normal flow.
  * *Step 3:* Inject failure on CBS accounts provisioning; verify RFC 7807 problem response:
    ```json
    {
      "type": "https://capstone.bank/errors/invalid-state",
      "title": "Invalid State",
      "status": 400,
      "detail": "Failed to auto-provision primary PHP account in CBS: Error reading Accounts Service response",
      "instance": "/api/auth/register"
    }
    ```
  * *Step 4:* Check PostgreSQL `channel_db` rollback:
    ```powershell
    docker exec -i channel-db psql -U channel_user -d channel_db -c "SELECT user_id, username, active_status FROM app_user_master WHERE username = 'elena.reyes@example.com';"
    ```
  * *Step 5:* Assert: Exactly `0 rows` returned. Transaction rollback cleans up user credentials completely (**PASS ✅**).

* **SEAM-PK-02: BCrypt Login, JWT Issuance, Redis Blacklist & 3-Strike Lockout (`DIG-7`, `DIG-8`, `BR-07`, `BR-20`)**
  * *Step 1:* Execute login:
    ```powershell
    curl.exe -i -s --json '{\"email\":\"maria.santos@example.com\",\"password\":\"password123\"}' http://localhost:8080/api/auth/login
    ```
  * *Step 2:* Verify HTTP `200 OK` with signed JWT Bearer token and customer profile claims.
  * *Step 3:* Logout user to blacklist active token:
    ```powershell
    curl.exe -i -s -X POST http://localhost:8080/api/auth/logout -H "Authorization: Bearer <TOKEN>"
    ```
  * *Step 4:* Check Redis blacklist TTL:
    ```powershell
    docker exec redis redis-cli ttl "token:<TOKEN>"
    ```
    *Result:* `3575` seconds remaining TTL. Subsequent call with this token returns `401 Unauthorized` (`X-Auth-Error: Token has been revoked`).
  * *Step 5:* Brute-force lockout verification: 3 consecutive failed logins trigger lockout.
    ```powershell
    docker exec redis redis-cli ttl "login:locked:juan.delacruz@example.com"
    ```
    *Result:* `833` seconds remaining TTL. Attempt 4 immediately returns `401 Unauthorized` with account temporarily locked (**PASS ✅**).

* **SEAM-PK-03: Synchronous Risk Screening HTTP Seam (`DIG-21`, `DIG-23`, `BR-08`, `CH-02`)**
  * *Step 1:* Post risk evaluation payload to `risk-engine`:
    ```powershell
    curl -s -X POST http://localhost:8088/internal/risk/evaluate -H "Content-Type: application/json" -d '{"customer_id":"cust-user-001","amount":"250000.0000","currency":"PHP","source_ip":"192.168.1.1"}'
    ```
  * *Step 2:* Verify response:
    ```json
    {
      "score": 0.6,
      "level": "MEDIUM",
      "decision": "STEP_UP_OTP",
      "triggered_rules": ["AMOUNT_ANOMALY", "DEVICE_ANOMALY"],
      "evaluation_latency_ms": 6.17
    }
    ```
  * *Step 3:* Assert latency: Engine internal processing completed in **6.17 ms**, total network round-trip was **35.0 ms** (well within 50ms target and 200ms SLA).
  * *Step 4:* Terminate container `docker stop risk-engine`. Send payment request to `transaction-service`. Assert fails closed with `503 SECURITY_CHECK_UNAVAILABLE` (**PASS ✅**).

* **SEAM-PK-04: Dynamic Risk Parameter Hot-Reload (`DIG-22`, `BR-19`)**
  * *Step 1:* Post parameter update:
    ```powershell
    curl -s -X PUT http://localhost:8088/internal/risk/parameters -H "Content-Type: application/json" -d '{"weight_velocity":0.5,"threshold_low_max":0.5,"threshold_medium_max":0.8,"updated_by":"admin@capstonebank.com"}'
    ```
  * *Step 2:* Verify HTTP `200 OK` response:
    ```json
    {
      "success": true,
      "message": "Risk parameters dynamically reloaded to v2",
      "data": {"version": 2, "threshold_medium_max": 0.8}
    }
    ```
  * *Step 3:* Query `GET http://localhost:8088/internal/risk/parameters` immediately confirms version 2 active without service restart (**PASS ✅**).

* **SEAM-PK-05: Admin Portal Authentication & Live Telemetry (:8090) (`DIG-51`, `DIG-90`, `BR-03`)**
  * *Step 1:* Probe unauthenticated access: `GET http://localhost:8090/admin/dashboard` &rarr; `302 Found` redirect to `/login`.
  * *Step 2:* Authenticate staff: `POST http://localhost:8090/login` with `email=admin@ledgerbank.com&password=admin123`.
  * *Step 3:* Verify session cookie `JSESSIONID` issued and `/admin/dashboard` renders:
    * Philippine Peso Reserve: `phpBal = 50,025,300` (matches Oracle `ACCOUNT_MASTER`)
    * Euro Reserve: `eurBal = 1,000,150` (matches Oracle `ACCOUNT_MASTER`)
    * Real-time SSE hourly mutation chart rendered dynamically.
    * North South Bank branding and operation controls displayed (**PASS ✅**).

---

### 3.4 Workstream 4: Russel — API Gateway Perimeter Security & Isolation Seams

#### Prerequisites:
```bash
docker compose up -d redis api-gateway login-service accounts-service transaction-service
```

#### Step-by-Step Execution:
* **SEAM-RUS-01: Gateway JWT Fast-Path Validation & Header Injection Seam (`DIG-8`, `DIG-25`)**
  * *Step 1:* Run automated Maven seam test:
    ```bash
    ./mvnw test -pl api-gateway "-Dtest=GatewayPerimeterSecurityIntegrationTest#seamRus01_jwtFastPathAndHeaderInjection"
    ```
    Live curl equivalent:
    ```bash
    curl -s http://localhost:8080/api/v1/accounts/pockets \
      -H "Authorization: Bearer <valid_customer_jwt>"
    ```
  * *Step 2:* Verify Gateway strips any inbound untrusted headers (`X-Cust-Id`, `X-Cust-Role`, `X-Cust-Email`) and validates JWT signature.
  * *Step 3:* Verify Gateway forwards request to downstream service with cryptographically verified header `X-Cust-Id: cust-user-001` injected.
* **SEAM-RUS-02: Token Blacklist Interception Filter Seam (`DIG-8`)**
  * *Step 1:* Run automated Maven blacklist interception test:
    ```bash
    ./mvnw test -pl api-gateway "-Dtest=GatewayPerimeterSecurityIntegrationTest#seamRus02_tokenBlacklistInterception"
    ```
    Live verification steps (Redis + curl):
    ```bash
    # 1. Insert blacklisted token in Redis:
    redis-cli SET "token:<jwt_token>" "BLACKLISTED" EX 900
    # 2. Send request with revoked token:
    curl -i http://localhost:8080/api/v1/accounts/pockets \
      -H "Authorization: Bearer <jwt_token>"
    ```
  * *Step 2:* Assert Gateway intercepts request before routing downstream and returns `401 Unauthorized` with header `X-Auth-Error: TOKEN_REVOKED`.
  * *Step 3:* Verify downstream microservices receive 0 requests.
* **SEAM-RUS-03: Redis Reactive Rate Limiter Seam (`DIG-26`)**
  * *Step 1:* Run automated Maven token-bucket rate limiter test:
    ```bash
    ./mvnw test -pl api-gateway "-Dtest=GatewayPerimeterSecurityIntegrationTest#seamRus03_reactiveRateLimiterBurstAndReplenish"
    ```
    Live curl verification equivalent:
    ```bash
    # Send 35 rapid requests in a loop:
    for i in {1..35}; do curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/api/v1/accounts/pockets -H "Authorization: Bearer <valid_jwt>"; done
    ```
  * *Step 2:* Verify requests 1–30 return `200 OK` (burst quota).
  * *Step 3:* Verify requests 31–35 return `429 Too Many Requests` with `Retry-After: 1` header.
  * *Step 4:* Verify requests resume `200 OK` after 1 second token replenishment.
* **SEAM-RUS-04: Internal Route Cloaking Filter Seam (`CONTRACTS.md` §1.1)**
  * *Step 1:* Run automated Maven route cloaking test:
    ```bash
    ./mvnw test -pl api-gateway "-Dtest=GatewayPerimeterSecurityIntegrationTest#seamRus04_internalRouteCloaking"
    ```
    Live curl equivalent:
    ```bash
    curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/internal/core/settlement
    curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/api/accounts/acct-01/credit
    ```
  * *Step 2:* Assert Gateway returns `403 Forbidden` across all internal CBS mutation routes with header `X-Route-Error: ACCESS_DENIED`. Downstream services receive 0 requests.

---

### 3.5 Workstream 5: Gym — Async Messaging, Outbox Relay & Audit Seams

#### Prerequisites:
```bash
docker compose up -d oracle-db postgres-db kafka accounts-service
```

#### Step-by-Step Execution:
* **SEAM-GYM-01: Outbox Polling Relay to Kafka Seam (`DIG-20`, `DIG-32`, `BR-02`)**
  * *Step 1:* Run automated Maven seam test:
    ```bash
    ./mvnw test -pl transaction-service "-Dtest=AsyncOutboxAndAuditIntegrationTest#seamGym01_outboxPollingRelayToKafka"
    ```
    Live Oracle & Kafka verification equivalent:
    ```sql
    -- 1. Insert staged outbox record into Oracle XE outbox_master:
    INSERT INTO outbox_master (outbox_id, source_service, aggregate_type, aggregate_id, event_type, payload, status, attempt_count, created_at)
    VALUES ('ob-int-01', 'accounts-service', 'SETTLEMENT', 'txn-int-01', 'SETTLEMENT_COMPLETED', '{"postingReferenceId":"txn-int-01","legs":[{"accountId":"acct-juan-eur-01","direction":"DEBIT","amount":10.0000,"currency":"EUR","legNo":1},{"accountId":"acct-maria-eur-01","direction":"CREDIT","amount":10.0000,"currency":"EUR","legNo":2}]}', 'PENDING', 0, CURRENT_TIMESTAMP);
    COMMIT;
    ```
  * *Step 2:* Observe `OutboxMasterRelayService` scheduled poll (executes every 500ms).
  * *Step 3:* Query Oracle XE: Assert `status` updates to `'PUBLISHED'` with `published_at` populated.
    ```sql
    SELECT outbox_id, status, published_at FROM outbox_master WHERE outbox_id = 'ob-int-01';
    ```
  * *Step 4:* Consume Kafka topic `transaction.events`:
    ```bash
    kafka-console-consumer --bootstrap-server localhost:9092 --topic transaction.events --from-beginning --max-messages 1
    ```
  * *Step 5:* Assert `payment.completed` event is emitted with `event_key = 'txn-int-01'` and full leg details.
* **SEAM-GYM-02: Audit Consumer to PostgreSQL Seam (`DIG-88`, `BR-02`)**
  * *Step 1:* Run automated Maven audit consumer test:
    ```bash
    ./mvnw test -pl transaction-service "-Dtest=AsyncOutboxAndAuditIntegrationTest#seamGym02_auditConsumerToPostgres"
    ```
    Live Kafka & PostgreSQL verification equivalent:
    ```bash
    # Publish synthetic settlement event to Kafka:
    echo '{"eventId":"evt-int-01","aggregateType":"TRANSACTION","aggregateId":"txn-int-01","eventType":"payment.completed","timestamp":"2026-10-08T14:00:00Z","payload":{"txnId":"txn-int-01","postingReferenceId":"FT2627800001","customerId":"cust-user-001","legs":[{"leg_no":1,"account_id":"acct-juan-eur-01","direction":"DEBIT","amount":10.0000,"currency":"EUR"},{"leg_no":2,"account_id":"acct-maria-eur-01","direction":"CREDIT","amount":10.0000,"currency":"EUR"}]}}' | kafka-console-producer --bootstrap-server localhost:9092 --topic transaction.events
    ```
  * *Step 2:* Observe `AuditLedgerConsumer` log: `processed 2 legs for txnId=txn-int-01`.
  * *Step 3:* Query PostgreSQL `ledger_mutation_audit`:
    ```sql
    SELECT mutation_uuid, txn_id, account_id, leg_no, mutation_type, mutation_amount, currency_code, audit_state FROM ledger_mutation_audit WHERE txn_id = 'txn-int-01';
    ```
  * *Step 4:* Assert 2 immutable rows exist (DEBIT and CREDIT) with deterministic UUIDv5 `mutation_uuid` matching leg index.
* **SEAM-GYM-03: Transient Retry & Poison Message Dead-Letter Queue Seam (`DIG-89`, `BR-34`)**
  * *Step 1:* Run automated Maven DLQ isolation and transient recovery test:
    ```bash
    ./mvnw test -pl transaction-service "-Dtest=AsyncOutboxAndAuditIntegrationTest#seamGym03_poisonMessageRoutedToDlqAndConsumerRecovers,AsyncOutboxAndAuditIntegrationTest#seamGym03_transientPostgresOutagePausesConsumerWithoutDlq"
    ```
    Live Kafka DLQ verification equivalent:
    ```bash
    # 1. Publish malformed poison JSON to Kafka:
    echo '{ invalid_json: corrupt }' | kafka-console-producer --bootstrap-server localhost:9092 --topic transaction.events
    ```
  * *Step 2:* Consume topic `transaction.events.dlq`:
    ```bash
    kafka-console-consumer --bootstrap-server localhost:9092 --topic transaction.events.dlq --from-beginning --max-messages 1
    ```
  * *Step 3:* Assert poison message is isolated into DLQ without stopping the consumer or dropping subsequent messages.
  * *Step 4:* Assert transient PostgreSQL connection failures trigger listener container pause and rethrow for Kafka backoff without sending valid financial records to DLQ.

---

### 3.6 Workstream 6: Lanz — Mobile Core Client & Network Seams

#### Prerequisites:
```bash
# Launch Flutter integration test harness pointing to Gateway :8080
flutter pub get
```

### 3.6 Workstream 6: Lanz — Mobile Core Client & Network Seams

#### Prerequisites:
```bash
# Launch Flutter integration test harness pointing to Gateway :8080
flutter pub get
```

#### Step-by-Step Execution:
* **SEAM-LAN-01: Dio HTTP Client & Gateway Communication Seam (`DIG-94`, `DIG-38`)**
  * *Objective:* Verify `ApiClient` (Dio HTTP client) correctly interacts with API Gateway `:8080`, enforcing timeouts, standard JSON headers, successful response parsing, and error status handling.
  * *Step 1:* Run Flutter network integration test:
    ```bash
    flutter test test/integration/dio_gateway_integration_test.dart
    ```
  * *Step 2:* Initialize `ApiClient` pointing to Gateway (`http://localhost:8080` / `http://10.0.2.2:8080`).
  * *Step 3:* Issue `GET /api/v1/accounts/pockets`; assert Dio receives HTTP `200 OK` and serializes response payload into typed `PocketAccount` models.
  * *Step 4:* Inspect outgoing request headers: assert `Content-Type: application/json` and `Accept: application/json` are automatically configured.
  * *Step 5:* Simulate network connection/read timeouts; assert client raises `DioExceptionType.connectionTimeout` / `receiveTimeout` without unhandled exceptions.
  * *Step 6:* Simulate gateway non-200 responses (e.g., `404 Not Found`, `500 Internal Error`); verify clean encapsulation in `DioException` response models.
* **SEAM-LAN-02: KeyStore Storage & Automatic Bearer Token Injection (`DIG-40`, `DIG-94`, `T-009`)**
  * *Objective:* Verify tokens securely stored in KeyStore / secure storage are automatically retrieved and attached as Authorization Bearer tokens across controller and repository boundaries.
  * *Step 1:* Run KeyStore auth injection integration test:
    ```bash
    flutter test test/integration/keystore_bearer_token_test.dart
    ```
  * *Step 2:* Authenticate user through login flow; persist JWT access token and refresh token into `SecureStorageService` (`AES/GCM/NoPadding`).
  * *Step 3:* Issue subsequent API calls (`GET /api/v1/accounts/pockets`, `POST /api/v1/payments`) via `BankingController` / `ApiClient`.
  * *Step 4:* Inspect outgoing request options: assert `AuthInterceptor` automatically injects `Authorization: Bearer <token>`.
  * *Step 5:* Execute user logout (`clearSession()`); verify access and refresh tokens are purged from storage while user PIN code is safely retained.
  * *Step 6:* Attempt authenticated call with `requiresAuth: true` after logout; assert client rejects with `401 UNAUTHENTICATED` client-side before network dispatch.
* **SEAM-LAN-03: Automatic UUID `X-Idempotency-Key` Header Seam (`DIG-94`, `BR-05`)**
  * *Objective:* Verify mutating payment operations always carry a canonical UUIDv4 `X-Idempotency-Key`, and retried payments reuse the identical key to guarantee exactly-once execution.
  * *Step 1:* Run idempotency header integration test:
    ```bash
    flutter test test/integration/idempotency_header_seam_test.dart
    ```
  * *Step 2:* Issue mutating payment request `POST /api/v1/payments` via `BankingController` / `ApiClient`.
  * *Step 3:* Assert `AuthInterceptor` generates and attaches a canonical 36-character UUIDv4 `X-Idempotency-Key` header matching RFC 4122.
  * *Step 4:* Re-submit / retry transaction with pre-existing key (e.g. during shortfall funding confirmation); assert exact same idempotency key is preserved across all attempts.
  * *Step 5:* Issue non-mutating `GET /api/v1/cards` and `GET /api/v1/accounts/pockets`; assert `X-Idempotency-Key` header is omitted.
  * *Step 6:* Verify successive distinct payment calls receive distinct, unique UUIDs to prevent collision across independent transfers.
* **SEAM-LAN-04: Client Circuit Breaker & Retry Seam (`DIG-44`)**
  * *Objective:* Verify fail-fast client resilience: when repeated gateway errors occur, the client circuit breaker trips to OPEN, suppresses outbound requests, notifies UI listeners, and safely probes via half-open recovery.
  * *Step 1:* Run circuit breaker integration test:
    ```bash
    flutter test test/integration/circuit_breaker_retry_seam_test.dart
    ```
  * *Step 2:* Verify initial state is `CircuitBreakerState.closed`, allowing regular traffic through `ApiClient`.
  * *Step 3:* Simulate 3 consecutive gateway failures (HTTP 503 `SERVICE_DEGRADED` or timeouts). Assert circuit breaker trips to `CircuitBreakerState.open`.
  * *Step 4:* Attempt outbound requests while breaker is OPEN; assert `AuthInterceptor` immediately rejects with `ClientCircuitBreakerOpenException` / 503 `SERVICE_DEGRADED` without initiating any network traffic (0 network calls).
  * *Step 5:* Verify `ChangeNotifier` triggers UI state update so `BankingController` / `ServiceUnavailableScreen` displays offline banner.
  * *Step 6:* Fast-forward cooldown timer past reset timeout; assert transition to `halfOpen`. Issue successful probe request; assert breaker resets to `closed` with 0 failures.

---

### 3.7 Workstream 7: Angge — Mobile UI State & Screen Flow Seams

#### Prerequisites:
```bash
# Mobile emulator or device connected
cd mobile
```

#### Step-by-Step Execution:
* **SEAM-ANG-01: PIN Keypad Authentication & Lockout Screen Flow (`DIG-11`, `DIG-39`)**
  * *Step 1:* Run widget integration test:
    ```bash
    flutter test test/integration/pin_auth_flow_test.dart
    ```
  * *Step 2:* Enter 6-digit PIN on custom numeric keypad. Assert digits appear masked.
  * *Step 3:* Simulate 3 failed attempts from backend response. Assert screen transitions to `DeviceLockedScreen` showing countdown timer.
* **SEAM-ANG-02: Pockets Carousel & Multi-Currency Balances Seam (`DIG-41`)**
  * *Step 1:* Run carousel integration test:
    ```bash
    flutter test test/integration/pockets_carousel_test.dart
    ```
  * *Step 2:* Verify horizontal swipe moves between PHP, USD, and EUR pockets.
  * *Step 3:* Assert Ledger Balance and Available Balance are rendered with correct currency symbol and 4 decimal places.
* **SEAM-ANG-03: Shortfall Funding Bottom Sheet & Live 30s Quote Seam (`DIG-42`, `DIG-98`)**
  * *Step 1:* Trigger shortfall send money flow for €50.00.
  * *Step 2:* Assert `ShortfallFundingSheet` slides up displaying converted PHP amount and fee breakdown.
  * *Step 3:* Assert circular timer ticks down from 30s. At 0s, button changes to "Quote Expired - Tap to Refresh".
* **SEAM-ANG-04: Virtual Card Controls & E-Commerce Toggle Seam (`DIG-49`, `BR-25`)**
  * *Step 1:* Open Virtual Card Hub screen.
  * *Step 2:* Toggle "E-Commerce Transactions" switch to OFF.
  * *Step 3:* Verify API `PUT /api/v1/cards/{id}/controls` is called and toggle state updates with confirmation toast.

---

## 4. Acceptance Gates for Integration Phase

The integration phase is complete and certified for Full-System Testing only when:
1. **Workstream 1 (Yancey):** 100% pass on CBS row-locking and atomic 6-leg settlement tests with zero deadlocks.
2. **Workstream 2 (Rohan):** Feign client, 30s FX quote expiry, and CAS state claim transitions verified with zero race anomalies.
3. **Workstream 3 (PK):** Registration rollback, JWT blacklist in Redis, and $\le 200\text{ms}$ risk fail-closed verified.
4. **Workstream 4 (Russel):** Gateway route isolation blocks `/internal/**` with `403` and rate limiter enforces 15 req/s quota.
5. **Workstream 5 (Gym):** Outbox relay publishes to Kafka and PostgreSQL audit consumer records all legs with 100% parity.
6. **Workstream 6 (Lanz):** Mobile Dio client attaches JWT and UUID `X-Idempotency-Key` headers on all mutating calls.
7. **Workstream 7 (Angge):** Mobile UI widgets accurately display multi-currency balances, 30s countdowns, and card toggle states.
