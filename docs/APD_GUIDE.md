# APD Guide: Capstone Banking System (v2.3 Reconciled Baseline)

This guide walks teammates, evaluators, and coding agents through the complete Analysis, Planning, and Design (APD) baseline for the **Capstone Banking System**, fully reconciled with BRD/TRD v2.3 and Jira Backlog v01.

---

## The System in One Minute

The platform delivers a modern, MariBank-inspired retail digital banking engine:
1. **Unified Customer Experience:** One customer receives one primary deposit account (PHP) linked to a single Virtual Card, with optional foreign currency sub-wallets ("pockets": USD, EUR).
2. **Smart FX Routing:** Transactions in foreign currencies debit the matching pocket with 0% FX fees. If the pocket has insufficient funds, the system computes the exact shortfall, presents a guaranteed 30-second quote, and auto-converts from the primary PHP pocket upon customer confirmation.
3. **Core Banking Guarantee:** The **Accounts Service** acts as the Mock Core Banking System (CBS) and is the **sole writer** of account balances. It executes multi-leg debit and credit operations in Oracle XE 21c inside a single atomic database transaction using deterministic ascending row locks (`SELECT ... FOR UPDATE`).
4. **Compare-and-Set (CAS) Lifecycle:** `transaction-service` claims transitions from `AUTHORIZED` $\to$ `PROCESSING` via atomic CAS. Cancellation is permitted only before this claim wins.
5. **Amount Holds & Reservations:** `accounts-service` manages an `account_hold` engine. Available balance is strictly $\text{balance\_amount} - \text{held\_amount}$.
6. **Asynchronous Ledger Audit:** Balance mutations commit atomically with an Oracle outbox record (`outbox_master`). Kafka asynchronously relays these events to PostgreSQL 15+, where an append-only audit consumer records immutable legal legs protected by database triggers.
7. **Fail-Closed Security & Risk Holds (ADR-001):** A Java Spring Boot **Risk Engine** screens transactions under a strict $\le 200\text{ms}$ SLA. Score $> 0.85$ places transfers into `RESERVED` status under an administrative review queue. A timeout fails closed immediately (`503 SECURITY_CHECK_UNAVAILABLE`).

> **Golden Architectural Rule:** *The Channel coordinates; the Core owns the money; Kafka delivers the audit trail; the Gateway protects the perimeter.*

---

## Document Map (The APD Structure)

| Stage | Document | Purpose & Reader |
|---|---|---|
| **Decisions** | [`decisions/`](./decisions/) | Architecture Decision Records: [`ADR-001`](./decisions/ADR-001-risk-score-outcome-handling.md) (Risk holds), [`ADR-002`](./decisions/ADR-002-status-lifecycle-holds-and-single-writer-ownership.md) (CAS & ownership), [`ADR-003`](./decisions/ADR-003-chaos-scenarios-and-resilience-scope.md) (Graded chaos), [`ADR-004`](./decisions/ADR-004-scope-boundaries-and-deferred-features.md) (Scoping). |
| **Analysis** | [`REQUIREMENTS.md`](./REQUIREMENTS.md) | Business goals, v2.3 user stories, constraints (Java-only, no OFS), and deferred features list. |
| **Planning** | [`PLAN.md`](./PLAN.md) | Capacity reality model, 9-day rebalanced milestones, team ownership matrix, and traceability table. |
| **Design** | [`ARCHITECTURE.md`](./ARCHITECTURE.md)<br/>[`CONTRACTS.md`](./CONTRACTS.md) | Full Mermaid diagrams, component boundaries, single-writer matrix, ERD, DDL deltas, and JSON REST/Kafka schemas. |
| **Verification** | [`ACCEPTANCE.md`](./ACCEPTANCE.md)<br/>[`SEED.md`](./SEED.md) | Definition of Done, 4 primary graded chaos engineering test runbooks, and test fixtures. |
| **Agent Rules** | [`AGENTS.md`](./AGENTS.md)<br/>[`tasks/`](./tasks/) | Executor agent invariants, single-writer rules, and 14 bounded task cards (`T-001` through `T-014`). |

---

## 1. Resolved Architectural Decisions (ADR Summary)

* **ADR-001 (Risk Outcome):** Score $> 0.85 \implies$ `RESERVED` status under `RISK_HOLD` in Admin review queue (`DIG-51`). Engine timeout $\implies$ fail-closed `503 SECURITY_CHECK_UNAVAILABLE`.
* **ADR-002 (Ownership & CAS):** `transaction-service` owns `transaction_master` and executes CAS claim before calling CBS settlement. `accounts-service` owns `account_master`, `account_hold`, and `outbox_master`. Core settlement accepts `capture_hold_id`. Sweeper inquires via `GET /internal/core/settlement/{ref}`.
* **ADR-003 (Graded Chaos):** 4 primary live graded scenarios (`CH-01` Oracle degradation, `CH-02` Risk kill, `CH-03` Perimeter scan, `CH-04` PostgreSQL audit recovery) plus 7 automated secondary scenarios.
* **ADR-004 (Scope Triage):** Customer 360, Reversals, Holds, Real-time monitoring, and DLQ are IN-SCOPE. External B2B partner APIs, EOD report packs, and GL engine are DEFERRED to Post-Capstone.

---

## 2. 7-Member Delivery Matrix & Jira Mapping

| Member | Track | Primary Domain | Jira Tickets | Task Cards |
| :--- | :--- | :--- | :--- | :--- |
| **Yancey** | Full-Stack | Core CBS, Balances, Holds, DDL | `DIG-3`, `4`, `9`, `16`, `17`, `52`, `58`, `84`, `85` | `T-002`, `T-008`, `T-012` |
| **Rohan** | Full-Stack | Channel Orchestrator, Lifecycle, Reversals | `DIG-14`, `15`, `20`, `23`, `24`, `35`, `66`, `79`, `80` | `T-001`, `T-004`, `T-007`, `T-013` |
| **PK** | Full-Stack | Identity, Risk Engine, Admin Web, Customer 360 | `DIG-6`, `7`, `12`, `18`, `21`, `22`, `50`, `53`, `102`, `103`| `T-003`, `T-006`, `T-011` |
| **Russel** | Cloud Lead | Gateway, Perimeter Cloaking, TLS, Chaos Suite | `DIG-8`, `25`, `28`, `30`, `31`, `34`, `60`, `118` | `T-005`, `T-014` |
| **Gym** | Cloud Ops | Redis Limiter, Kafka Cluster, JMeter 800 TPS | `DIG-5`, `26`, `27`, `29`, `32`, `33`, `61` | `T-005`, `T-012`, `T-014` |
| **Lanz** | Mobile Lead | Flutter Architecture, Keystore, Send Money Flow | `DIG-10`, `38`, `40`, `42`, `44`, `46`, `48` | `T-009`, `T-010` |
| **Angge** | Mobile UX | Flutter UI Screens, Virtual Card Hub, Pockets | `DIG-11`, `13`, `39`, `41`, `43`, `47`, `49` | `T-010` |

---

## 3. Daily Execution Rhythm

```
1. Daily Morning Standup (09:00 AM, 15m)
   • Review Jira Sprint Board
   • Verify no inter-service dependency blockers
2. Select Task Card from docs/tasks/
3. Launch Coding Agent referencing AGENTS.md + Task Card + CONTRACTS.md
4. Run task card 'done-when' command & unit tests
5. Open PR, code review, merge to main, and update Jira status
```

---

## 4. Cold Dry-Run Verification (3 Sample Cards Checked)

To guarantee that a fresh agent can execute without asking any blocking questions:

1. **Cold Dry-Run Card 1: `T-001` (Smart Routing & Lifecycle)**
   * *Q: How does the agent prevent customer cancellation once settlement starts?*
     * *Ans in Spec:* `T-001 §2` specifies CAS query `UPDATE transaction_master SET txn_status = 'PROCESSING', stage = 'SETTLING' WHERE txn_id = ? AND txn_status = 'AUTHORIZED'`. If 0 rows affected, aborts.
   * *Q: Where are the exact JSON request and response fields?*
     * *Ans in Spec:* Referenced directly in `CONTRACTS.md §2.1` with full sample payloads and status codes.
2. **Cold Dry-Run Card 2: `T-002` (Core CBS Settlement & Holds)**
   * *Q: What SQL locks are required, and in what order?*
     * *Ans in Spec:* `T-002 §2` specifies: IDs sorted in ascending alphanumeric order, executing `SELECT balance_amount, held_amount FROM account_master WHERE account_id IN (...) ORDER BY account_id FOR UPDATE`.
   * *Q: How does a capture hold work?*
     * *Ans in Spec:* If `capture_hold_id` is supplied, `accounts-service` checks `account_hold`, decrements `held_amount`, updates hold to `'CAPTURED'`, and mutates balance in the same atomic commit.
3. **Cold Dry-Run Card 3: `T-003` (Java Risk Engine)**
   * *Q: What formula and weights are used for the risk score?*
     * *Ans in Spec:* $S = \min(1.00, 0.35 \cdot V + 0.30 \cdot A + 0.20 \cdot D)$ with clear threshold rules ($<0.50$ ALLOW, $0.50-0.85$ STEP_UP_OTP, $>0.85$ HOLD).
   * *Q: What happens on timeout?*
     * *Ans in Spec:* Resilience4j timeout at $200\text{ms}$ fails closed returning `HTTP 503 SECURITY_CHECK_UNAVAILABLE`. Zero balance changes.

**Defect Check:** Architecture and contracts fully reconciled; code-level verifications tracked under `HANDOFF_CODEBASE_AGENT.md`.


