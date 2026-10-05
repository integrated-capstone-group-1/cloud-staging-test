# Business & Technical Requirements

## 1. Executive Summary & Architectural Philosophy
The **Capstone Banking System** is a high-performance, fault-tolerant retail digital banking platform built upon a **MariBank/Wise-inspired digital banking architecture**.

### Core Strategic Decisions
1. **Core Banking vs. Channel Orchestration Split:**
   * [`accounts-service`](file:///c:/Users/RBS83790/OneDrive%20-%20EastWest%20Banking%20Corporation/Documents/training/integrated-capstone/capstone-banking-system/accounts-service) is the **Mock Core Banking System (CBS)** and authoritative System of Record (SoR) for ledger balances in Oracle XE 21c, executing atomic settlements under pessimistic row locks.
   * [`transaction-service`](file:///c:/Users/RBS83790/OneDrive%20-%20EastWest%20Banking%20Corporation/Documents/training/integrated-capstone/capstone-banking-system/transaction-service) is the **Channel Orchestrator**, managing the 7-status transaction lifecycle, pre-flight fraud risk screening, smart currency routing, and Kafka event staging.
2. **Unified Deposit Account with Virtual Card & Multi-Currency Pockets:**
   * One customer receives **one primary deposit account (PHP)** linked to a **single Virtual Card**.
   * Optional foreign currency sub-wallets ("pockets": USD, EUR) are opened on demand.
3. **Smart FX Routing & Funding Assistance:**
   * Exact-match transactions debit foreign currency pockets with **0% FX fee**.
   * If target funds are insufficient, the system auto-converts the exact shortfall from the primary PHP pocket using a guaranteed 30-second locked quote upon customer confirmation.
4. **100% Java Backend & REST JSON Core:**
   * The Risk Engine is Java Spring Boot. The CBS interface is strictly REST JSON. No Python and no Temenos T24 OFS string parsing.
5. **Fail-Closed Security & Asynchronous Audit:**
   * Risk evaluation timeout $\le 200\text{ms}$ fails closed immediately.
   * Synchronous payments commit to Oracle + `outbox_master`. PostgreSQL `ledger_mutation_audit` is populated 100% asynchronously via Kafka.

---

## 2. In-Scope Business Requirements (BRD v2.3 Mapping)

| ID | Requirement | Priority | Architecture Component |
| :--- | :--- | :--- | :--- |
| **BR-01** | Prevent account balance from going negative under concurrent load | Must | `accounts-service` (`SELECT ... FOR UPDATE`, `CHECK (balance_amount >= 0)`) |
| **BR-02** | Every balance mutation produces exactly one immutable audit record | Must | `ledger_mutation_audit` (PostgreSQL triggers block UPDATE/DELETE) |
| **BR-03** | Uniform APIs across Mobile, Web, and Admin channels | Must | `api-gateway` (:8080) |
| **BR-04** | Cross-currency transfers use cached FX rates; never depend on live external calls | Must | `forex-service` (hourly cache from api.frankfurter.dev) |
| **BR-05** | Retried requests never create duplicate transactions | Must | `X-Idempotency-Key` + Redis fast-lock + Oracle unique constraint |
| **BR-07** | Password + fingerprint biometric login, email OTP step-up | Must | `login-service`, Android KeyStore, `local_auth` |
| **BR-08** | Screen transfers for fraud; reject if screening exceeds 200ms | Must | Java `risk-engine` (Resilience4j $\le 200\text{ms}$ fail-closed) |
| **BR-09** | Customer account/card lock; admin freeze takes precedence | Must | `account_master.account_status` (`ACTIVE`, `FROZEN`, `CLOSED`) |
| **BR-10** | Send money via account number or internal QR code | Must | `transaction-service`, Flutter QR scanner |
| **BR-11** | Transaction alerts to customers; risk & AMLA alerts to admins | Should | `notification-service`, Admin Web Portal |
| **BR-12** | Virtual card issuance linked to primary PHP account | Should | `card_master` in Oracle XE |
| **BR-14** | Resilient fault tolerance under database, network, or service failure | Must | Resilience4j circuit breakers, Redis fallback, Kafka DLQ |
| **BR-16** | Registration auto-provisions one primary PHP account per customer | Must | `registration-service` $\to$ `accounts-service` |
| **BR-18** | Risk score assigns LOW (allow), MEDIUM (OTP), HIGH (hold) | Must | Java `risk-engine` (ADR-001: score $> 0.85 \implies$ `RESERVED` hold) |
| **BR-19** | Admin-editable limits, AMLA thresholds, and FX spreads | Should | Admin parameters screen in `frontend-web` |
| **BR-20** | Sessions bound to device ID with PIN fallback | Must | `login-service` device cache + Flutter local auth |
| **BR-21** | Home screen displays cached balance during ledger degradation | Must | Redis `balance_cache:*` with timestamp |
| **BR-26** | In-app pocket-to-pocket conversion at locked rates | Must | `forex-service` quotes + `accounts-service` settlement |
| **BR-27** | Same-currency transfers avoid cross-currency FX fees | Must | Exact match direct debit (0% FX fee) |
| **BR-28** | Funding-assisted cross-currency payments with shortfall conversion | Must | `transaction-service` Smart Routing Engine |
| **BR-29** | Admin-configurable rate-age limit (`FX_RATE_STALE` after 24h) | Should | `forex-service` stale rate guard |
| **BR-30** | Full transaction lifecycle: Initiated, Authorized, Processing, Posted, Failed, Reserved, Cancelled | Must | `transaction-service` CAS status transitions |
| **BR-31** | Customer cancellation before processing begins | Must | `POST /api/v1/payments/{id}/cancel` (CAS on `AUTHORIZED`) |
| **BR-32** | Administrative transaction reversal via linked compensating transaction | Must | `POST /api/v1/admin/transfers/{id}/reverse` |
| **BR-33** | Amount holds: place, release, and capture against available balance | Must | `accounts-service` `account_hold` table & `held_amount` |
| **BR-34** | Retry policy and Dead Letter Queue (DLQ) with admin replay | Must | Spring Kafka retry topics + DLQ console |
| **BR-35** | Real-time transaction monitoring dashboard on Admin Portal | Must | `frontend-web` Thymeleaf dashboard |
| **BR-36** | Customer 360 view aggregating KYC, pockets, txns, and risk score | Must | `frontend-web` `/admin/customers/{id}` |

---

## 3. Explicit Deferred & Post-Capstone Scope (ADR-004)

The following capabilities from BRD v2.3 and `00_additional-requirements.md` are documented for enterprise design completeness but are **explicitly deferred to post-capstone**:
1. **EOD Report Pack Download (`DIG-107`):** Cryptographically checksummed external report export.
2. **Partner Remittance B2B API Integrations (`DIG-109`, `DIG-110`):** Outbound/inbound partner webhook adapter (all capstone transfers remain internal).
3. **General Ledger Chart-of-Accounts Run (`DIG-106`):** Enterprise GL journal postings (audit parity is proven via Dual-DataSource reconciliation).
4. **Mobile Standing Instructions Setup Screen (`DIG-108`):** Customer recurring UI (standing instructions execute via backend batch scheduler).

---

## 4. Evaluation Criteria & Graded Chaos Suite

The platform is evaluated against the 100-point Capstone Rubric and the 11 chaos engineering scenarios documented in [`docs/decisions/ADR-003-chaos-scenarios-and-resilience-scope.md`](file:///c:/Users/RBS83790/OneDrive%20-%20EastWest%20Banking%20Corporation/Documents/training/integrated-capstone/capstone-banking-system/docs/decisions/ADR-003-chaos-scenarios-and-resilience-scope.md), led by the 4 primary graded scenarios:
1. **`CH-01`**: Oracle DB degradation (Breaker trips $\to$ Redis cache fallback $\to$ 503 fail-safe).
2. **`CH-02`**: Risk engine killed/slow ($\le 200\text{ms}$ timeout $\to$ 503 `SECURITY_CHECK_UNAVAILABLE`, zero balance mutations).
3. **`CH-03`**: Perimeter port scan (Zero internal ports accessible; only `:8080` and `:8090` open).
4. **`CH-04`**: PostgreSQL audit outage (Customer payments commit in Oracle; Kafka consumer retries; 100% parity restored).

