# Implementation Plan & Jira Sprint Mapping

---

## 1. MoSCoW Feature Prioritization

* **MUST HAVE (Core Capstone Deliverable):**
  * Single-writer Core Banking System (`accounts-service`) with pessimistic locking (`SELECT ... FOR UPDATE`) in Oracle XE 21c.
  * Internal atomic settlement API (`POST /internal/core/settlement`).
  * Digital Banking Channel Orchestrator (`transaction-service`) supporting exact match and smart multi-currency shortfall auto-conversion.
  * Synchronous Java Risk & Fraud Screening Engine (`risk-engine`) with $\le 200\text{ms}$ fail-closed SLA.
  * Transactional Outbox pattern (`outbox_master`) and Kafka event publishing to PostgreSQL `ledger_mutation_audit`.
  * API Gateway with JWT validation, Redis rate-limiting (Token-Bucket), and zero-trust port isolation.
  * Flutter Mobile Client (Android) with Keystore JWT storage, virtual cards, currency pockets, and send-money flow.
  * Operational Admin Web Portal (Thymeleaf on `:8090`) for account lock/unlock, freeze/unfreeze, and real-time monitoring.
  * All 4 Graded Chaos Engineering scenarios passed.
* **SHOULD HAVE:**
  * Recipient lookup name-masking (`GET /api/v1/recipients/lookup`).
  * In-app alerts list and email notifications via Kafka.
  * Scheduled Dual-DataSource Reconciliation Service (`reconciliation-service`).
  * Virtual card e-commerce toggle (`ecom_enabled`).
* **COULD HAVE (Stretch):**
  * Downloadable monthly e-statement PDF.
  * Customer-facing Flutter Web build.
* **WON'T HAVE (Out of Scope):**
  * Python microservices (all backend is 100% Java).
  * Temenos T24 OFS string parsing (replaced with JSON REST).
  * Live interbank FX trading or external card-network acquiring.

---

## 2. 7-Member Ownership & Jira Backlog Mapping

Cross-referenced directly with `00_jira_backlog.xlsx`:

| Owner | Track | Primary Component Domain | Jira Backlog Tickets | Task Cards |
| :--- | :--- | :--- | :--- | :--- |
| **Yancey** | Full-Stack | **Core CBS & Master Ledger**<br/>`accounts-service`, Oracle XE DDL, Row Locks | `DIG-3` (DDL scripts), `DIG-4` (HikariCP pools), `DIG-9` (Error handler), `DIG-16` (Idempotency), `DIG-17` (Admin params), `DIG-52` (Locks), `DIG-58` (Resilience4j) | **`T-002`**, **`T-008`**, **`T-012`** |
| **Rohan** | Full-Stack | **Channel Orchestration & Messaging**<br/>`transaction-service`, Outbox Relay, Smart FX | `DIG-14` (Core JSON bridge), `DIG-15` (Payment orchestrator), `DIG-20` (Kafka outbox), `DIG-23` (Risk rules), `DIG-24` (Risk decisions), `DIG-35` (Notifications), `DIG-51` (Held txns) | **`T-001`**, **`T-004`**, **`T-007`**, **`T-013`** |
| **PK** | Full-Stack | **Auth, Risk Engine & Admin Web**<br/>`login-service`, `risk-engine`, Thymeleaf UI | `DIG-6` (Registration), `DIG-7` (Login/JWT), `DIG-12` (Email OTP), `DIG-18` (Txn history), `DIG-21/22` (Java Risk Engine), `DIG-50/53` (Admin login/audit) | **`T-003`**, **`T-006`**, **`T-011`** |
| **Russel** | Cloud Lead | **Perimeter, Networking & Chaos**<br/>`api-gateway`, Port cloaking, TLS, Chaos suite | `DIG-8` (Gateway JWT), `DIG-25` (Gateway 8080), `DIG-28` (Secrets), `DIG-30` (Bridge network), `DIG-31` (TLS), `DIG-34` (Observability), `DIG-60` (Chaos tests) | **`T-005`**, **`T-014`** |
| **Gym** | Cloud Ops | **Platform, Caching & Load Testing**<br/>Redis rate limits, Kafka infra, JMeter | `DIG-5` (Redis cache), `DIG-26` (Rate limiter), `DIG-27` (Correlation IDs), `DIG-29` (Dockerfiles), `DIG-32` (Kafka setup), `DIG-33` (Encrypted storage), `DIG-61` (JMeter) | **`T-005`**, **`T-012`**, **`T-014`** |
| **Lanz** | Mobile Lead | **Flutter Core Client & Security**<br/>Dio client, Android Keystore, Send flow | `DIG-10` (Device binding), `DIG-38` (Flutter setup), `DIG-40` (Keystore), `DIG-42` (Send money flow), `DIG-44` (Client breaker), `DIG-46` (Error UI), `DIG-48` (Suspicious txn) | **`T-009`**, **`T-010`** |
| **Angge** | Mobile UX | **Flutter Views, Cards & Experience**<br/>Virtual card carousel, Pockets, Receipts | `DIG-11` (PIN fallback), `DIG-13` (OTP screens), `DIG-39` (Auth screens), `DIG-41` (Home & Pockets), `DIG-43` (Receipts), `DIG-47` (Alerts), `DIG-49` (Card lock UI) | **`T-010`** |

---

## 3. Sprint Cadence & Process Workflow (Days 1–9)

```
┌────────────────────────────────────────────────────────────────────────┐
│                        9-DAY SPRINT SCHEDULE                           │
├───────────┬──────────┬─────────────────────────────────────────────────┤
│ Sprint    │ Timeline │ Focus Milestones & Deliverables                 │
├───────────┼──────────┼─────────────────────────────────────────────────┤
│ Sprint 0  │ Day 1    │ Domain Setup, Contracts & DDL Freeze            │
│           │          │ • Finalize CONTRACTS.md and DDL scripts         │
│           │          │ • Distribute initial Jira subtasks              │
├───────────┼──────────┼─────────────────────────────────────────────────┤
│ Sprint 1  │ Days 2–4 │ Foundation, Core Banking & Gateway              │
│           │          │ • Yancey: accounts-service atomic settlement    │
│           │          │ • PK: login-service, JWT issuance & auth        │
│           │          │ • Russel & Gym: Gateway 8080 & Redis Limiter    │
│           │          │ • Lanz & Angge: Flutter app shell & Keystore    │
├───────────┼──────────┼─────────────────────────────────────────────────┤
│ Sprint 2  │ Days 5–6 │ Orchestration, Risk Engine & Client Assembly    │
│           │          │ • Rohan: Payment smart routing & Outbox relay   │
│           │          │ • PK: Java Risk Rules Engine (< 200ms)          │
│           │          │ • Mobile: Send money flow & virtual card hub    │
│           │          │ • Yancey: Outbox consumer to Postgres audit     │
├───────────┼──────────┼─────────────────────────────────────────────────┤
│ Sprint 3  │ Day 7    │ Hardening, Chaos Engineering & JMeter           │
│           │          │ • Execute the 4 Chaos Scenarios in ACCEPTANCE   │
│           │          │ • JMeter load test sustained >= 800 TPS         │
│           │          │ • Verify 100% audit parity in PostgreSQL        │
├───────────┼──────────┼─────────────────────────────────────────────────┤
│ Sprint 4  │ Day 8    │ Mock Defense Rehearsal & Fix-List Triage        │
│           │          │ • Full end-to-end dry-run demo across devices   │
│           │          │ • Triage any remaining edge-case bugs           │
├───────────┼──────────┼─────────────────────────────────────────────────┤
│ Sprint 5  │ Day 9    │ Final Capstone Presentation & Technical Defense │
│           │          │ • Live panel presentation and code sign-off     │
└───────────┴──────────┴─────────────────────────────────────────────────┘
```

---

## 4. Daily Operational Process

1. **Daily Standup (9:00 AM, 15 Minutes):**
   * Inspect the Jira board.
   * Address inter-service blockers (e.g., Mobile needing API mock vs actual endpoint).
   * Confirm daily ticket ownership.
2. **Task Card Selection:**
   * Developers grab the corresponding task card under `docs/tasks/`.
3. **Agent Handoff Execution:**
   * Developers prompt their AI coding agents referencing `docs/AGENTS.md` and their task card.
4. **Verification Gate:**
   * Run `./mvnw clean test` and the task card's **done-when command**.
   * Verify against `docs/ACCEPTANCE.md`.
5. **Merge & Sync:**
   * Open PR, perform code review, rebase to `main`, and advance Jira ticket.
