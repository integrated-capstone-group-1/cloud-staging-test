# Architecture & ERD Explainer: Capstone Banking System

Sources: `ARCHITECTURE.md` (§ refs), `REQUIREMENTS.md`, ADR-001..004, `AGENTS.md`, task cards, `00_jira_backlog_v01.xlsx` (DIG keys).
Order: simple, then practical, then technical. **W** = what, **H** = how, **Y** = why.

---

## 1. The whole thing in 60 seconds

Think of one bank branch.

| Branch | Our system |
|---|---|
| Front door + guard | **API Gateway** (:8080): checks ID, limits crowds, hides every other door |
| Customer reception desk | **Thin Orchestrator** (Tier 3): coordinates authenticated customer journeys, checks ownership, aggregates home view |
| Channel identity desk | **Login & Registration** (Tier 3): BCrypt, JWT, device binding, with isolated **Channel DB** (PostgreSQL) |
| Back office payment hub | **Transaction / Payment Service** (:8084 in `PAYMENT` schema, Tier 4): 7-status saga, CAS locks, auto-debit, maker-checker |
| Vault + the only teller allowed to touch cash | **Core Banking System (Mock CBS - `accounts-service`)** (:8083 in `CBS` schema, Tier 4): row-locked balances, holds, `card_master` virtual cards & limits, `ledger_entry` journal, and outbox |
| Treasury desk | **Forex Service** (:8087 in `FX` schema, Tier 4): daily EOD Frankfurter polling, 30s locked quotes |
| Fraud inspection desk | **Risk Engine** (:8088 / Java, Tier 4): synchronous velocity/amount check ($\le 200\text{ms}$ SLA, advisory only) |
| CCTV + signed paper ledger nobody can edit | **Audit DB** (PostgreSQL 15+, append-only): written exclusively by **Audit Consumer** |
| Sticky notes on the desk | **Redis 7** (fast, disposable): rate counters, token blacklist, 120s idempotency fast-lock |
| Mail room | **Kafka** (partitioned broker :9092): carries events, retry topics, and DLQ |
| Internal auditor who counts the vault against paper ledger | **Reconciliation Service** (:8086): dual-data-source auditor comparing Oracle master vs Postgres audit legs |

**One payment in 6 steps** ([ARCHITECTURE.md §4](file:///c:/Users/RBS83790/OneDrive%20-%20EastWest%20Banking%20Corporation/Documents/training/integrated-capstone/banking-backend-monorepo/docs/ARCHITECTURE.md#L196-L266)):
1. Customer initiates via Gateway $\rightarrow$ Thin Orchestrator checks account ownership $\rightarrow$ passes to Transaction Service.
2. Transaction Service saves it as `INITIATED`, synchronously asks Risk ($\le 200\text{ms}$ fail-closed $\rightarrow$ moves to `AUTHORIZED`).
3. If foreign currency shortfall exists, Forex gives a 30-second locked quote with 1.5% margin.
4. Transaction Service claims the payment: CAS `AUTHORIZED → PROCESSING`. From here, customer cancel attempts lose the race.
5. CBS settles all legs atomically in Oracle under row locks (`SELECT ... FOR UPDATE ORDER BY account_id`), writes immutable `ledger_entry` legs, and enqueues `outbox_master` in the **same Oracle commit**.
6. Transaction Service marks `POSTED` (customer receives receipt); OutboxRelay asynchronously pushes to Kafka $\rightarrow$ Audit Consumer writes immutable evidence to Postgres Audit DB.

---

## 2. Components (Grouped by 6 Tiers)

| Layer | Component | W (what) | Y (why it exists) | Backlog / Task |
|---|---|---|---|---|
| **Tier 1: Client** | Flutter mobile + web | Customer app, one codebase | Every channel hits the same APIs (BR-03) | DIG-38/40/94/10/39/41/42 · T-009, T-010 |
| **Tier 1: Client** | Admin portal (:8090) | Staff screens: Customer 360, freeze, held queue, 4-eyes approval, DLQ, monitoring | Staff powers and admin operations must never exist in customer apps | DIG-50/54/53/102/90 · T-011 |
| **Tier 2: Edge** | API Gateway (:8080) | JWT check, blacklist, rate limit, routing, port cloaking | Single perimeter ingress; makes CH-03 (port scan defense) pass | DIG-25/8/26/31/27 · T-005 |
| **Tier 3: Channel** | Thin Orchestrator | Stateless customer BFF: ownership check, home aggregation, error mapping | Coordinates customer journeys without holding balance or money state | Architectural Revision (Option A) |
| **Tier 3: Channel** | Registration (:8081) | Customer KYC onboarding; calls CBS to open CIF, PHP pocket & card | Customer onboarding coordinates with CBS and saves channel credentials | DIG-6 · T-006 |
| **Tier 3: Channel** | Login & Identity (:8082) | BCrypt, JWT minting, device binding, PIN hash | Identity is physically isolated from core financial state | DIG-7/12/69/70/71 · T-006 |
| **Tier 3: Channel** | Notification (:8085) | Push / email alerts from events, in-app inbox | A slow email or SMTP timeout must never stall a live payment | DIG-35 · T-004 |
| **Tier 4: Core & Domain**| Transaction / Payment (:8084) | Payment hub: 7-status saga, CAS claim, idempotency fast-lock, standing instructions, 4-eyes approval | Sequences risk $\rightarrow$ FX $\rightarrow$ CBS settlement without owning accounts or balance math | DIG-14/15/16/73/80/81/83/86/87 · T-001, T-008 |
| **Tier 4: Core & Domain**| Mock Core Banking (CBS - `accounts-service` :8083) | Balances, multi-currency pockets, virtual cards (`card_master`), holds, atomic 6-leg settlement, `ledger_entry`, transactional outbox | The sole authoritative vault permitted to mutate balance state | DIG-52/66/67/84/85/68/77/58/4/17 · T-002 |
| **Tier 4: Core & Domain**| Forex Service (:8087) | Daily EOD Frankfurter poll, 30s locked quotes (1.5% spread), stale rate guard (>24h) | Shields live payments from external API outages and latency (BR-04, CH-06) | DIG-74/75 · T-007 |
| **Tier 4: Core & Domain**| Risk Engine (:8088 / Java) | Heuristic fraud scoring; **advises only** ($\le 200\text{ms}$ fail-closed) | Fraud scoring changes frequently and must never possess rights to move money | DIG-21/22/79 · T-003 |
| **Tier 4: Core & Domain**| EOD Batch Service | Checkpointed auto-debit scheduler for standing instructions | Scheduled batch executions run off customer request paths | DIG-105 · T-013 |
| **Tier 5: Async & Audit**| Kafka Broker (:9092) | Partitioned topics (`transaction.events`, `txn.lifecycle`), retry topics, DLQ | Decouples financial settlement commit from downstream logging and telemetry | DIG-32/20/89 · T-004, T-014 |
| **Tier 5: Async & Audit**| Audit Consumer (`audit-consumer`) | Dedicated single-writer consuming Kafka events $\rightarrow$ appends to PostgreSQL | Single pipe writing evidence; isolates audit from app failure modes | DIG-88 · T-004 |
| **Tier 5: Async & Audit**| Reconciliation (:8086) | Dual-source auditor comparing Oracle master balances vs Postgres audit legs | Maker-Checker principle: auditor must not share code or runtime with audited service | DIG-112 · T-012 |
| **Tier 6: Storage** | Oracle XE 21c, Channel DB, Audit DB, Redis 7 | Dedicated multi-tier data persistence | ACID OLTP vault, isolated credentials, immutable audit history, disposable cache | Master Data Layer |

---

## 3. Data: Three Stores, Dedicated Schemas & The 18 Tables

| Store | Database / Schema | Role & Characteristics |
|---|---|---|
| **Oracle XE 21c** | `CBS`, `PAYMENT`, `FX` schemas | Master OLTP System of Record. Pessimistic row-level locks, constraints, ACID guarantees. |
| **PostgreSQL (Channel DB)**| `channel_db` | Customer login credentials, device bindings, and in-app alert inbox. Physically isolated from the core ledger. |
| **PostgreSQL 15+ (Audit DB)**| `audit` & `recon` schemas | Append-only evidence store. Database triggers strictly raise exceptions on `UPDATE` or `DELETE`. |
| **Redis 7** | In-memory key-value | Disposable accelerator. JWT blacklist, rate counters, balance fast-read cache, 120s idempotency fast-lock (`SETNX`). |

### The 18 Tables in Plain Words (Grouped by Business Domain)

| Domain | Store / Schema | Tables | Sole Writer | Purpose & Business Meaning |
|---|---|---|---|---|
| **Core Vault (CBS)** | Oracle XE (`CBS`) | `customer_master`<br/>`account_master`<br/>`card_master`<br/>`account_hold`<br/>`ledger_entry`<br/>`posting_reference`<br/>`outbox_master` | `accounts-service` | The authoritative banking ledger. Tracks KYC verified customer records, multi-currency pockets (`balance_amount`, `normal_balance`, `held_amount`), virtual debit cards and limits (`card_master`), fund reservations (`account_hold`), the immutable leg journal (`ledger_entry`), settlement deduplication (`posting_reference`), and atomic event enqueuing (`outbox_master`). |
| **Payment Hub** | Oracle XE (`PAYMENT`) | `transaction_master`<br/>`standing_instruction`<br/>`admin_approval` | `transaction-service` | Payment orchestration state. Tracks 7-status payment sagas and 11-stage progress, auto-debit rules, and 4-eyes maker-checker staff approvals. |
| **Treasury (FX)** | Oracle XE (`FX`) | `fx_rate` | `forex-service` | Exchange rates cached once daily at EOD from external provider (`api.frankfurter.dev`). Moved to Oracle so foreign conversions survive Postgres outages (CH-04). |
| **Channel Identity**| Postgres (`Channel DB`) | `app_user_master`<br/>`notification_log` | `login-service`<br/>`notification-service` | Customer and staff BCrypt passwords, PIN hashes, device hashes, failed login counters, and customer in-app notification inbox. |
| **Immutable Audit** | Postgres (`Audit DB` - `audit`) | `ledger_mutation_audit`<br/>`risk_screening_audit`<br/>`fx_conversion_audit` | `AuditConsumer` (Async) | Append-only regulatory evidence of every settled leg (deterministic UUIDv5), fraud heuristic score, and converted currency rate. |
| **Reconciliation** | Postgres (`Audit DB` - `recon`) | `recon_run_audit`<br/>`recon_result_audit` | `reconciliation-service` | Historical logs of recon audit executions, matched leg tallies, and detected balance variance breaks. |

Technical details worth knowing:
- $\text{Available Balance} = \text{balance\_amount} - \text{held\_amount}$. Holds reduce spending power without deducting the balance.
- `transaction_master.txn_status` transitions only via Compare-and-Set (`UPDATE ... WHERE status='AUTHORIZED'`). Winning thread locks out customer cancels.
- `posting_reference.core_reference_id` is `UNIQUE`; repeated settlement calls cannot post duplicate balance mutations.
- `ledger_entry` records `running_balance`, `value_date`, `business_date`, and `narrative` inside CBS in the exact same Oracle transaction as the balance update.
- Audit rows use `mutation_uuid = UUIDv5(txn_id + "-leg-" + leg_no)` with `UNIQUE (txn_id, leg_no)`, guaranteeing idempotent Kafka consumption.
- Cross-database links between Oracle XE and PostgreSQL are **logical only** (no distributed foreign keys).

---

## 4. Design questions (banking context)

### Q1. Why is X connected to this and not that?
Two rules explain almost every arrow:
- **Synchronous** when the caller cannot continue without the answer: Risk (decide before money moves), Forex quote, CBS settlement.
- **Asynchronous (Kafka)** when the work is a *consequence*: audit, notifications, monitoring.

| Chosen | Instead of | Because |
|---|---|---|
| Clients → Gateway → services | Clients → services | One place for JWT, rate limit, and closed ports (CH-03) |
| Txn → **CBS API** | Txn → `account_master` directly | One writer enforces locks and atomic 6-leg settlement |
| CBS → outbox → Kafka → consumer → Postgres | CBS → Postgres in the same request | No dual-write; Postgres down ≠ payments down (CH-04) |
| Risk called synchronously | Risk via Kafka | A fraud verdict is needed *before* settlement; fail-closed in 200 ms |
| Forex polls hourly | Forex called per payment | External outage must not stop payments (CH-06) |
| Recon reads both DBs | Recon only listens to Kafka | A check that uses the same pipe it audits cannot catch that pipe losing messages |

### Q2. Why does a component sit in the channel layer vs the core?
**Test: "If this component is wrong, can money be wrong?"**
- **Core**: defines or changes a balance, must stay right even if every channel misbehaves. Balances, holds (they shrink available balance), settlement, posting reference, outbox.
- **Channel**: identity, permission to proceed, routing, presentation. It can *request* money movement, never perform it.

Examples: Risk is channel (advises, can't move money). Forex is channel (quotes; CBS executes the conversion legs). Cancel is channel (it changes intent/status in `transaction_master`). Holds are core.
Real banks mirror this: a payment hub / digital channel in front, a core system behind.

### Q3. Is it OK for channel services to touch either DB?
- **Oracle Master: No.** Channel services in Tier 3 never touch Oracle XE directly. Only Tier 4 core & domain services hold Oracle credentials (`CBS`, `PAYMENT`, and `FX` schemas), enforced via Docker network isolation.
- **PostgreSQL Audit DB: No.** Channel services cannot access the Audit DB. Only `AuditConsumer` has an `INSERT`-only role, and `reconciliation-service` holds a read-only role.
- **Channel DB: Dedicated.** Tier 3 services write only to `postgres-channel` (`app_user_master`, `notification_log`), keeping channel data physically separate from the core ledger.

### Q4. Shouldn't my app only touch app-specific data (nothing from audit or bank tables)?
**Yes. The single-writer rule is now strictly implemented:**
- `accounts-service` (Mock CBS) is the sole writer of the `CBS` schema (`customer_master`, `account_master`, `card_master`, `account_hold`, `ledger_entry`, `posting_reference`, `outbox_master`).
- `transaction-service` (Payment Hub) is the sole writer of the `PAYMENT` schema (`transaction_master`, `standing_instruction`, `admin_approval`).
- `forex-service` is the sole writer of `FX_RATE` in the `FX` schema (daily EOD poll).
- `AuditConsumer` is the sole writer of `ledger_mutation_audit`, `risk_screening_audit`, and `fx_conversion_audit` in the Audit DB.
- `login-service` and `notification-service` touch only `channel_db` (`app_user_master`, `notification_log`).

### Q5. Is Main + Audit + Cache + Channel DB the right split?
**Yes.** Source of truth, tamper-resistant evidence, customer channel state, and disposable speed:
1. **Oracle XE (Main OLTP):** ACID isolation, row-level locks, constraints, and double-entry consistency for customer and clearing accounts.
2. **PostgreSQL Channel DB:** Isolates logins, passwords, PIN hashes, and notification inboxes so channel traffic never burdens the core ledger.
3. **PostgreSQL Audit DB:** Append-only compliance store protected by DB triggers that raise exceptions on `UPDATE` or `DELETE`. Preserves evidence even during application faults.
4. **Redis 7:** Never truth. Fast in-memory cache for sliding-window rate limiters, token blacklists, balance fast-reads, and 120s idempotency fast-locks (`SETNX`).
5. **Surviving Postgres Outages (CH-04):** `fx_rate` was moved from Postgres to Oracle (`FX` schema). If Postgres goes down, live cross-currency payments continue uninterrupted in Oracle while audit events safely buffer in Kafka.

### Q6. Why is reconciliation in a separate tier with Kafka? Merge into channel? Illustration?
Two different things:
- **The tier box (#5)**: Groups asynchronous and compliance control-plane services off the critical request path.
- **Recon as its own service is real and essential.** It is an independent control: the checker must not share code, credentials, or runtime with the services it checks (Maker-Checker principle). Merging it into channel or core services would allow bugs to conceal their own discrepancies.
- Recon reads live balances directly from Oracle (`account_master`) and historical legs from Postgres (`ledger_mutation_audit`), publishing discrepancy alerts to Kafka (`transaction.events`).

### Q7. Is Accounts being the lone service in the mock core OK?
**Yes, and the core journal gap is now closed:**
- `accounts-service` is the authoritative core vault. Settlement mutates balances, holds, `posting_reference`, and `outbox_master` in **one Oracle transaction** under ascending row locks (`SELECT ... FOR UPDATE ORDER BY account_id`).
- **Core Journal (`ledger_entry`):** Every double-entry leg is written directly in Oracle at settlement time with `running_balance`, `value_date`, `business_date`, and `narrative`. This gives statements and live audit checks immediate consistency inside Oracle rather than waiting for Kafka.
- **Account Restrictions:** `customer_locked` (blocks debits only) and `admin_frozen` (blocks both debits and credits) cleanly resolve restriction precedence (BR-09).
- **Customer Record (CIF) & Cards:** `customer_master` and `card_master` live in the `CBS` schema.

### Q8. Is a rename or rescope needed?
**No service renames needed.** Retain `transaction-service`, `accounts-service`, and `forex-service` across the codebase to avoid breaking backlog tickets and OpenAPI specs.
- Relabel conceptually: *Accounts Service = Core Banking System (CBS)*, *Transaction Service = Payment Orchestrator / Payment Hub*.
- The 16 enterprise-overkill tables (`PAYMENT_OUTBOX`, `TXN_STAGE_EVENT`, `EOD_BALANCE_SNAPSHOT`, `DLQ_ENTRY`, etc.) are permanently cut, locking the system at **18 clean tables**.

### Q9. Why should `accounts-service` own `card_master` instead of `transaction-service`?
- **Banking Reality:** In core banking, debit cards, virtual cards, card limits, and PAN tokens are tied directly to customer deposit accounts and CIF records.
- **Atomic Provisioning:** When a customer registers, CBS provisions the customer CIF (`customer_master`), the initial PHP account pocket (`account_master`), and the virtual card (`card_master`) in **a single Oracle transaction**.
- **Payment Hub references tokens:** `transaction-service` does not own cards; it simply accepts a card payment referencing `card_token` and settles it against the card's underlying account in CBS.

### Q10. What is `admin_approval` for?
- **4-Eyes Maker-Checker principle (ADR-002 / BR-32):** High-risk administrative actions (manual reversals of posted transactions, releasing high-value fraud holds, or unfreezing admin-frozen accounts) cannot be executed by a single staff user.
- **Enforced Workflow:**
  1. Maker initiates a request via Admin Web Portal (:8090) $\rightarrow$ creates `admin_approval` with `status = 'PENDING'`, capturing `target_type`, `target_id`, `maker_id`, and `proposed_payload`.
  2. Checker reviews the request in the portal $\rightarrow$ the database enforces `CHECK (checker_id <> maker_id)`.
  3. Once approved (`status = 'APPROVED'`), the system performs the sensitive action on `transaction_master` or `account_master`.

### Q11. What is `posting_reference` for if we already have `txn_id`?
- **Core Settlement Deduplication Barrier:** `txn_id` belongs to the external Payment Hub (`transaction-service`). Over flaky networks, settlement retries could cause duplicate calls to Mock CBS.
- **Bank Reference & Idempotency:** CBS records `posting_reference_id = txn_id` and generates a bank-internal reference `core_reference_id` (e.g. `FT2627800001`).
- When CBS receives `POST /internal/core/settlement`, it verifies `posting_reference`. If `txn_id` is already recorded as `SETTLED`, it returns the existing `core_reference_id` immediately without re-executing balance mutations or inserting duplicate ledger legs.

### Q12. What is `ledger_entry` for? Isn't this just a duplicate of `ledger_mutation_audit`?
- **Two different databases, two different purposes:**
  1. `ledger_entry` (Oracle XE `CBS` schema):
     - **OLTP Core Journal:** Written synchronously in the exact same Oracle transaction as the balance update under row locks.
     - Contains `running_balance`, `value_date`, `business_date`, and `narrative`.
     - Powers real-time customer account statements and balance inquiries with zero lag.
  2. `ledger_mutation_audit` (PostgreSQL `audit` schema):
     - **Immutable Regulatory Evidence:** Appended asynchronously by `AuditConsumer` via Kafka events.
     - Protected by PostgreSQL triggers that strictly raise exceptions on `UPDATE` or `DELETE`.
     - Decoupled from Oracle OLTP: if Postgres goes down, core banking in Oracle remains alive.
     - `reconciliation-service` audits `ledger_entry` against `ledger_mutation_audit` to prove ledger integrity.

### Q13. Do we need an outbox for each DB (Channel, Main, Audit)? Why only 1 outbox?
- **Transactional Outbox is only needed where local database transactions must atomically publish events to an external message broker (Kafka) without dual-write loss.**
- **Oracle XE (`CBS` schema):** The **only** place where financial balance mutations happen. Emitting `payment.completed` atomically alongside balance updates is mandatory to guarantee downstream audit and notification delivery. Hence, `outbox_master` lives in `CBS`.
- **Audit DB (PostgreSQL):** A **terminal sink**. It only consumes events from Kafka and inserts audit rows. It never publishes events to Kafka; an outbox here is completely unnecessary.
- **Channel DB (PostgreSQL):** Stores user credentials (`app_user_master`) and in-app alerts (`notification_log`). Login generates stateless JWTs; Notification is an event consumer. Neither needs to publish financial saga events via an outbox.
- **Conclusion:** Having 1 outbox (`outbox_master` in CBS) satisfies system consistency without multi-database outbox bloat.

### Q14. How does Auth and Onboarding actually flow?
- Previously drawn paths showed Registration calling Transaction Service. That was incorrect.
- **Correct Flow:**
  1. Customer submits KYC registration via Gateway to `registration-service` (:8081).
  2. `registration-service` calls Mock CBS (`accounts-service` :8083) via `POST /internal/customers`.
  3. In a single Oracle ACID transaction, CBS provisions:
     - `customer_master` (CIF record & KYC status),
     - `account_master` (default primary PHP pocket),
     - `card_master` (virtual debit card with token and limits).
  4. CBS returns `customer_id` and account/card details.
  5. `registration-service` writes the customer's credentials (username, BCrypt password, PIN hash, device hash) directly to its own **Channel DB** (`app_user_master`).
  6. `registration-service` responds 201 Created to the user.
  7. **`transaction-service` is never called during onboarding.**

### Q15. Does the Admin Web Portal access CBS directly?
- **Yes.** The Channel Layer (`thin-orchestrator`) is strictly customer-facing.
- The Admin Web Portal (:8090) is an internal staff operations tool within the backend monorepo. Staff holding `ROLE_ADMIN` and `ROLE_CHECKER` have direct internal network access to:
  - `accounts-service` (:8083) for Customer 360 views, account locks, and manual hold reviews.
  - `transaction-service` (:8084) for 4-eyes approval execution and Kafka DLQ message inspections/replays.
  - `reconciliation-service` (:8086) for discrepancy break inspection.

### Q16. How do Kafka Outbox, Retries, and DLQ operate?
- **Outbox Write:** When CBS settles a transaction in Oracle XE, it writes the balance mutations, the journal legs, and the event to `outbox_master` in the **same database commit**.
- **Outbox Relay:** `OutboxMasterRelayService` polls `outbox_master` (status `PENDING`) and publishes `transaction.events` to Kafka, marking status `PUBLISHED`.
- **Retry & DLQ Routing:** If a consumer (e.g., `AuditConsumer` or `NotificationService`) encounters a transient error, Spring Kafka routes the message to retry topics (`*.retry.1`). Upon retry exhaustion (e.g., 3 failed attempts), the Dead Letter Publishing Recoverer routes the message to the Dead Letter Queue (`*.dlq`).
- **Admin Replay:** Staff can view poison messages in `transaction.events.dlq` via the Admin Web Portal (:8090) and trigger a replay after fixing root causes.

---

## 5. Spec issues found while tracing (fix before freeze)

| # | Issue | Where | Fix |
|---|---|---|---|
| 1 | Hold table owner conflict: `amount_hold_master` owned by transaction-service vs `account_hold` owned by accounts-service | `AGENTS.md` §2 vs `ARCHITECTURE.md` §3, ADR-002 | Follow ADR-002; edit AGENTS.md |
| 2 | Registration "auto-provisions" account and card, but CONTRACTS has no open-account or issue-card endpoint, the diagram has no Registration → CBS arrow, and `card_master` is owned by transaction-service | T-006, DIG-6, CONTRACTS §3 | Add `POST /internal/accounts` and an internal card-issue contract |
| 3 | Audit consumer placed in transaction-service files; backlog calls it `audit-consumer` | T-004 §2, DIG-88 | Separate module |
| 4 | Risk audit writer: transaction-service (matrix) vs risk-engine (DIG-79) | §3 vs backlog | Pick one; prefer event-driven |
| 5 | Mutable tables in "immutable" Postgres; FX cache vs CH-04 | §3, ADR-003 | Q5 fix |
| 6 | Unowned/missing: `posting_reference`, `outbox_audit`, parameter store, monitoring-service, OTP/monitoring Redis keys | ERD, §3 | Add to matrix and ERD |
| 7 | Customer 360: ADR-004 says in scope; backlog DIG-103 is Post-Capstone. ADR-004 cites DIG-108/110 with different meanings than the backlog (DIG-110 is CI/CD; DIG-108 doesn't exist). BR numbers differ (REQUIREMENTS BR-35/36 vs backlog BR-41/42) | ADR-004, REQUIREMENTS, backlog | Align IDs and scope before the next plan pass |
| 8 | Recon event topic `reconciliation.discrepancy` (ARCHITECTURE) vs `audit.events` (T-012); expected balance = Σcredits − Σdebits ignores seeded opening balances, giving false drift unless seeding writes audit legs | ARCHITECTURE §2, T-012 | One topic name; seed via audit legs or add an opening-balance term |
| 9 | Gateway routes `GET /api/v1/accounts/**` straight to CBS, bypassing the channel layer; Admin portal reads shown via Gateway but matrix lists direct DB reads | §2 vs §3 | Document as a deliberate read-only shortcut; give the portal a read-only Postgres role or an API |
| 10 | `account_type` lists SAVINGS/CHECKING/WALLET, but the product is one account type; bank FX/fee accounts share the table via a customer FK | ERD | Simplify the enum; allow null owner for internal accounts |

---

## 6. Requirements coverage (`REQUIREMENTS.md` vs components)

**Short answer: no. Most components map to a requirement, but REQUIREMENTS.md itself is incomplete, and several requirements have no home in the architecture.**

### 6.1 The requirements file vs the backlog
- `REQUIREMENTS.md` lists **28 BRs**. The backlog traces **40** (BR-01..42; no BR-06/BR-17).
- **Missing from REQUIREMENTS.md (12):** BR-13, 15, 22, 23, 24, 25, 37, 38, 39, 40, 41, 42.
  - Being built but unlisted: **BR-15** cloud deploy, **BR-22** statement + report suspicious, **BR-23** error messages, **BR-24** masked recipient, **BR-25** card e-commerce toggle, **BR-41** monitoring, **BR-37** standing instructions (stretch).
  - Deferred but not listed as deferred: BR-13, BR-36 (EOD batch), BR-38, BR-39, BR-40 (REQUIREMENTS §3 names features, not these IDs).
- No `TR-*` IDs and no NFR/KPI section (800 TPS, p95 token check) exist there, yet PLAN and the backlog cite them.
- It points to a `frontend-web` component that is not in ARCHITECTURE (the box is "Admin Web Portal").

**ID collisions (same number, different meaning):**

| ID | REQUIREMENTS.md | Backlog |
|---|---|---|
| BR-34 | Retry + DLQ **with admin replay** | Classification, retry, DLQ only |
| BR-35 | Real-time monitoring | Admin DLQ inspect / replay / discard |
| BR-36 | Customer 360 | EOD batch |

Monitoring is **BR-41** and Customer 360 is **BR-42** in the backlog. Priorities also differ: BR-19 (Should vs Must), BR-32 reversal (Must vs Should), Customer 360 (Must vs Should).
**Fix:** adopt the backlog numbering as canonical and import all 40 BRs.

### 6.2 Requirements with no (or partial) home in the architecture

| BR | Missing in ARCHITECTURE / ERD |
|---|---|
| BR-09 | Customer lock vs admin-freeze precedence can't be represented (Q7) |
| BR-16 / BR-12 | No Registration → CBS or card-issue path (spec issue #2) |
| BR-07 / BR-20 | No store for device binding, PIN hash or failed-attempt counter; OTP keys absent from the Redis matrix |
| BR-19 / BR-29 | No parameter table or owner (DIG-17) |
| BR-22 | No `suspicious_report` table or event; nothing says who generates statements (DIG-92 → transaction-service) |
| BR-34 / BR-35 | DLQ persistence and admin audit trail (DIG-89/90) have no table |
| BR-41 | `monitoring-service` read model + SSE feed (DIG-101) is not in the diagram |
| BR-32 | Maker-checker approval has no columns (only `reversal_of_txn_id`) |
| BR-42 / KYC | `customer_master` has no `kyc_status` |
| BR-15 | No deployment view (orchestrator, probes, only gateway public) |
| BR-03 | Gateway routes omit `/api/auth/otp`, `/api/auth/refresh`, `/api/v1/recipients`, `/api/v1/fx/convert`, `/api/v1/admin/**` |

### 6.3 Components with weak requirement backing
- **EOD Batch Service** is drawn with EOD completion, but EOD (DIG-104) is deferred; only standing instructions (DIG-105, stretch) are planned, and T-013 puts them inside transaction-service. Whether it stays a package or becomes a thin separate service depends on COB scope (§11).
- **Reconciliation** traces only to BR-02 (BR-38 GL is deferred). That's fine, but state it.
- **Grafana / observability** traces to TR-C07, which REQUIREMENTS doesn't define.

Everything else (Gateway, Login, Registration, Transaction, Risk, Forex, Accounts, Notification, Kafka, the three data stores) traces cleanly to at least one listed BR.

---

## 7. Requirement → component map (backlog numbering)

Abbreviations: **GW** gateway · **REG** registration · **LOGIN** login · **TXN** transaction · **RISK** risk-engine · **FX** forex · **ACCT** accounts (CBS) · **NOTIF** notification · **RECON** reconciliation · **ADMIN** admin portal · **MOB** Flutter apps · **AUDIT** audit-consumer (new, §8) · **MON** monitoring-service (new, §8).
Status: ✓ fits as drawn · ⚠ needs a change (§8) · ⏸ deferred (ADR-004).

| BR | Requirement (short) | Primary | Supporting | |
|---|---|---|---|---|
| 01 | No negative balance | ACCT | TXN | ✓ |
| 02 | One immutable audit record per mutation | ACCT → Kafka → AUDIT | RECON | ⚠ journal, extraction |
| 03 | Same APIs for all channels | GW | all | ⚠ routes |
| 04 | Cached FX, no live calls | FX | | ⚠ cache location |
| 05 | Idempotent retries | TXN | Redis, Oracle unique key, ACCT (`posting_reference`), MOB | ✓ |
| 07 | Password, biometric, OTP | LOGIN | MOB, NOTIF, Redis | ⚠ OTP/device storage |
| 08 | Fraud check ≤ 200 ms | RISK | TXN | ✓ |
| 09 | Customer lock, admin freeze wins | ACCT (account), TXN (card) | ADMIN, MOB | ⚠ lock flags |
| 10 | Send money by account or QR | TXN | ACCT (lookup), MOB | ✓ |
| 11 | Customer and admin alerts | NOTIF | RISK, ADMIN, Kafka | ⚠ inbox endpoint |
| 12 | Virtual card | TXN (`card_master`) | REG | ⚠ issuance path |
| 13 | Monthly e-statement | NOTIF | | ⏸ |
| 14 | Resilience | all, infra | Redis, Kafka DLQ | ✓ |
| 15 | Cloud deploy, CI/CD | infra | | ⚠ no deployment view |
| 16 | Registration provisions account | REG | ACCT, TXN | ⚠ internal contracts |
| 18 | Risk decision allow/OTP/hold | RISK + TXN | ACCT (RISK hold), ADMIN | ✓ |
| 19 | Admin-editable parameters | ACCT | ADMIN | ⚠ no table/owner |
| 20 | Device-bound session, PIN | LOGIN | MOB | ⚠ storage |
| 21 | Cached balance on home | ACCT | Redis, MOB | ✓ |
| 22 | Statement, report suspicious | TXN | MOB, NOTIF, ADMIN | ⚠ report table |
| 23 | Distinct error messages | common lib, GW | MOB | ✓ |
| 24 | Masked recipient | ACCT | MOB | ✓ |
| 25 | Card e-commerce toggle | TXN | MOB | ✓ |
| 26 | Pocket conversion | FX | ACCT | ✓ |
| 27 | Same-currency, no FX fee | TXN | ACCT | ✓ |
| 28 | Funding-assisted payment | TXN | FX, ACCT | ✓ |
| 29 | FX fees/spread editable, stale guard | FX | ACCT (parameters) | ⚠ parameters |
| 30 | Lifecycle + stage view | TXN | MON, Kafka | ✓ |
| 31 | Cancel before processing | TXN | ACCT (hold release) | ✓ |
| 32 | Admin reversal, maker-checker | TXN | ACCT, ADMIN | ⚠ approval table |
| 33 | Amount holds | ACCT | TXN | ✓ |
| 34 | Retry, DLQ | shared Kafka config in each consumer | | ✓ |
| 35 | DLQ inspect/replay/discard | MON | ADMIN (UI), Kafka | ⚠ no owner |
| 36 | EOD batch | | | ⏸ |
| 37 | Standing instructions | TXN (package) | | ✓ stretch |
| 38 | GL reconciliation | RECON | | ⏸ |
| 39 | EOD report pack | | | ⏸ |
| 40 | Partner integration | | | ⏸ |
| 41 | Real-time monitoring | MON | ADMIN (UI), Kafka, Redis | ⚠ not in diagram |
| 42 | Customer 360 | ADMIN (composes APIs) | REG, ACCT, TXN, AUDIT | ⚠ scope + read APIs |

**Tally: 18 ✓, 17 ⚠, 5 ⏸.**

---

## 8. Can it all fit into existing components? Almost.

**Yes for 15 of the 17 gaps. Two need new components:** `monitoring-service` (BR-35, BR-41) is genuinely needed; extracting `audit-consumer` (BR-02) is worth doing but not forced.

### Integrate into existing components

| # | Gap | Where | How |
|---|---|---|---|
| 1 | Posting journal | ACCT | Table `ledger_entry(txn_id, leg_no, account_id, dr_cr, amount, currency, posted_at)`, UNIQUE `(txn_id, leg_no)`. Insert inside the settlement transaction. RECON compares balance = Σ entries, then entries ↔ audit |
| 2 | Lock vs freeze precedence | ACCT | Two flags on `account_master`: `customer_locked`, `admin_frozen`. Customer unlock clears only its flag and is rejected while `admin_frozen`; admin unfreeze clears only its flag. Settlement checks both under the row lock. Update CONTRACTS §2.3/§2.5 and add an error code |
| 3 | Account and card provisioning | REG, ACCT, TXN | `POST /internal/accounts` and `POST /internal/cards`, both idempotent on `customer_id`. REG adds `customer_master.onboarding_status` (PENDING → ACTIVE) and retries, so a timeout after success can't create a duplicate |
| 4 | KYC | REG | `customer_master.kyc_status`; mock-verified on registration. Expose `GET /internal/customers/{id}` |
| 5 | Device, PIN, attempts, OTP | LOGIN | Oracle: `user_device`, plus `pin_hash`, `failed_attempts`, `locked_until` on `app_user_master` (must survive a Redis flush). OTP in Redis as `otp:*` with TTL; add to the Redis ownership matrix |
| 6 | Parameters | ACCT | Append-only `system_parameter(key, value, version, effective_from, changed_by)`; `GET/PUT /internal/parameters`. Consumers cache with a short TTL; RISK never calls it inside its 200 ms path. Today three docs name three different owners (ERD none, backlog accounts, T-011 admin portal) |
| 7 | Suspicious report | TXN | Table `suspicious_report`; publish `report.suspicious`; NOTIF and the admin queue consume |
| 8 | Maker-checker | TXN | One table `admin_approval(txn_id, kind REVERSAL/HOLD_REVIEW, maker, checker, status)` with `CHECK (maker <> checker)` |
| 9 | FX cache vs CH-04 | FX | Move `fx_rate_cache` to an Oracle table owned by FX. Payments need Oracle anyway, so a Postgres outage no longer touches quotes. `fx_conversion_audit` becomes event-driven (`fx.converted` is already drawn) |
| 10 | Batch | TXN or `batch-service` | Standing instructions alone: a package inside transaction-service (T-013 paths) with a lock row so two instances don't both fire. If COB also orchestrates recon and other steps: a thin `batch-service` (§11) |
| 11 | In-app alerts | NOTIF | `GET /api/v1/notifications` reading its own log |
| 12 | Gateway routes | GW | Add `/api/auth/otp`, `/api/auth/refresh`, `/api/v1/recipients`, `/api/v1/fx/convert`, `/api/v1/admin/**` (ROLE_ADMIN) |
| 13 | Customer 360 | ADMIN | Stateless composition of REG, ACCT, TXN and AUDIT reads. First settle scope: ADR-004 says in, DIG-103 says post-capstone |
| 14 | Deployment view | docs | Add a diagram for BR-15 |
| 15 | Recon viewer | RECON | `GET /internal/recon/runs` for the admin viewer |

### Add (new components)

| Component | Status | What | How |
|---|---|---|---|
| **monitoring-service** | Needed (already DIG-101) | Real-time view and DLQ console | Consumes `txn.lifecycle`, keeps a rolling window in Redis, serves summary + SSE. Adds DLQ list/replay/discard; each action emits `dlq.action` for AUDIT to record. Routed via GW `/api/v1/admin/monitoring/**`, `/dlq/**` |
| **audit-consumer** | Recommended (named in DIG-88) | Single writer of immutable evidence | Own Maven module, INSERT-only Postgres role. Writes `ledger_mutation_audit`, `risk_screening_audit` and `fx_conversion_audit` from events (see revision proposal §8.1; DLQ tables belong to monitoring-service). Can be deployed inside transaction-service first and promoted to its own container later. Read-only `GET /internal/audit/**` for the admin viewer |

**Contracts/events to add to CONTRACTS.md** (only `transaction.events` is defined today): `txn.lifecycle`, `fx.converted`, `risk.decision`, `report.suspicious`, `dlq.action`, `audit.events` (recon), plus the internal endpoints above. Record the decisions as ADRs (audit extraction, monitoring-service, lock flags, parameter owner, FX cache, card issuance).

---

## 9. Why add a component instead of integrating? When?

**Default: integrate**, as a package with its own Maven module (DIG-63 already gives you modules). Each new service costs real sprint capacity: Dockerfile and compose (DIG-29), pipeline stage (DIG-110), gateway route and port cloaking (DIG-30), secrets (DIG-28), OpenAPI contract (DIG-62), probes and dashboards (DIG-34), a chaos surface, a task card, and a network hop with partial-failure handling. ADR-004 already had to cut 184 SP of overload.

**Rule**
1. **Needs the same ACID transaction as existing data → integrate. Hard rule.**
2. Otherwise count three triggers: (a) **different authority** over the data (who may write it), (b) **different failure or scaling profile**, (c) **different trust boundary** (credentials, exposure).
3. **Two or more → separate service. Zero or one → package.**

| Candidate | Same ACID? | Authority | Failure/scale | Trust | Verdict |
|---|---|---|---|---|---|
| `ledger_entry`, lock flags | **Yes** | | | | Integrate (ACCT) |
| Parameters | No | No | Mild (cache it) | No | Integrate |
| Standing instructions | No (reuses payment code) | No | Mild | No | Integrate (TXN) |
| Customer 360 | No | No (no data) | No | No | Integrate (ADMIN) |
| **audit-consumer** | No | **Yes** (evidence writer) | **Yes** (Postgres outage, CH-04) | **Yes** (INSERT-only role) | **Separate** |
| **monitoring-service** | No | n/a (read-only) | **Yes** (long-lived SSE, Kafka lag) | **Yes** (keeps the public :8090 portal free of Kafka/DB credentials) | **Separate** |
| *Risk engine (existing)* | No | Advisory only | **Yes** (200 ms SLA) | No | Separate ✓ |
| *Recon (existing)* | No | **Yes** (checker ≠ checked) | **Yes** (heavy reads) | **Yes** | Separate ✓ |

The last two rows show the rule reproduces decisions you already made.

**Revisit later when:** JMeter shows a package capping TPS or hurting payment latency; a chaos run shows its failure takes payments down; two people keep colliding in one module; it needs its own release cadence; or a second consumer needs the same data. Likely candidates: parameters → a config-service if several services need push-refresh and approvals; batch → its own thin service once COB orchestrates several services (§11).

---

## 10. Outbox vs DLQ

**Simple version.** The outbox is the **sender's outgoing tray**: the core writes the "payment done" note in the same database transaction as the money move, and a clerk posts it later, so a note can never be lost between "money moved" and "Kafka told". The DLQ is the **problem pile at the receiving end**: if a receiver can't process a note after several tries, it goes into quarantine for a human instead of blocking everyone or vanishing.

| | Outbox (`outbox_master`) | DLQ (`*.retry.N` → `*.dlq`) |
|---|---|---|
| **Side** | Producer (accounts-service) | Consumer (audit, notification, lifecycle…) |
| **Problem it solves** | Dual-write gap: Oracle commits but the Kafka publish fails | A consumer can't process a message, and it must neither block the stream nor disappear |
| **How it works** | Insert in the settlement transaction; relay polls `PENDING` every 500 ms, publishes, marks `PUBLISHED` (DIG-20) | Failure classified (`failure_class`, `retryable`); exponential backoff + jitter; retry topics, then `.dlq`; `dlq_entry` stored with masked payload (DIG-89) |
| **Guarantee** | At-least-once publish; Kafka down → rows pile up, nothing lost | No failure silently lost; admin can replay or discard with a reason (DIG-90) |
| **Duplicates** | Relay may republish → consumer dedupes (UUIDv5, UNIQUE `(txn_id, leg_no)`) | Replay re-publishes with the original key → same dedupe protects the ledger |

They are two halves of one pipe, and neither replaces the other: the outbox can't help when a *consumer* fails, and a DLQ can't close the dual-write gap.

**Which mechanism handles which failure (your chaos catalog):**

| Failure | Mechanism | Not the DLQ because |
|---|---|---|
| Kafka down (CH-05) | Outbox accumulates, relay catches up | Nothing was consumed yet |
| Postgres down (CH-04) | Audit consumer **pauses and retries** | The event is fine; the destination is down. Sending it to the DLQ would create a parity gap needing manual replay (DIG-88 AC2) |
| Malformed event (CH-07) | Retry topics, then DLQ; consumer keeps going | Retrying can never fix it, and one bad message must not block the partition |

Rule: *infrastructure problem → pause/retry; bad message → retry topics then DLQ.*

**Why a DLQ now.** As I understand your previous project, the outbox made Kafka publishing reliable, and consumers were mostly fire-and-forget. Here:
1. **Consumers carry integrity.** The audit ledger is written by a consumer, and BR-02 demands 100% parity, so a stuck or lost message is now an audit gap, not a missed notification.
2. **More consumers** (audit, notification, lifecycle/monitoring, recon alerts) means more places to fail.
3. **BRD v2.3 requires it** (BR-34/35), and CH-07 expects the consumer to continue past a poison message.
4. Ops needs to see, replay and measure failures (DIG-90, Grafana depth/age).

**Two spec gaps found:** (a) `outbox_master.status` has a `FAILED` value but no rule says when. Suggest: Kafka unavailable is never `FAILED` (stay `PENDING`, back off); only an unserializable payload becomes `FAILED` plus an alert. (b) `dlq_entry` (DIG-89) isn't in the ERD and has no owner; §8 assigns the DLQ console to monitoring-service, so give it that table.

---

## 11. COB and reconciliation

**Yes, connect them, but by trigger, not by merging.**

- **Why connect:** recon is only correct when the system has *caught up*. If it compares live Oracle balances while outbox rows haven't reached the audit DB, it reports false drift. COB's "drain" step is exactly that readiness gate. Today T-012 runs recon on its own schedule, so you'd have two clocks.
- **Why not merge:** recon must stay runnable on demand (DIG-112 AC3 uses it as the parity check after chaos runs), and the checker must not share code or failure modes with what it checks (Q6). COB only *starts* it and reads the result; recon writes its own results.
- **How:** batch calls `POST /internal/recon/runs {business_date}`, idempotent per date (a retry after a timeout returns the existing run). Recon publishes `reconciliation.completed`. Suggest: drift does **not** block rolling the date (it becomes a break plus an alert), but "not drained" blocks recon from starting; on timeout mark the run `INCOMPLETE` rather than report false drift.
- **Limit to know:** with no downtime (DIG-104), balances keep moving during the run. The proper fix is an end-of-day balance snapshot compared against audit legs up to the cut-off. For the capstone, drain-then-compare is acceptable if you state the limitation.

**What else belongs in COB.** Your own DIG-104 already lists the steps: *cut-off, drain, housekeeping, snapshot, GL recon, reports, roll date, SI.* Mapped to this project:

| # | Step | What it means here | Priority |
|---|---|---|---|
| 1 | Standing instructions | Each occurrence runs the normal payment pipeline with key `SI-{id}-{date}` | Your item |
| 2 | Cut-off | Fix the end of business date D (Asia/Manila); traffic is not stopped, later transactions simply belong to D+1. Needs a `business_date` on `ledger_entry` | Should |
| 3 | Drain | No stuck transactions, outbox empty up to cut-off, audit lag 0, DLQ not growing | **Must** before recon |
| 4 | Snapshot | `eod_balance_snapshot(business_date, account_id, balance, held)` owned by ACCT | Should |
| 5 | Reconciliation | Triggered as above (the audit-parity form; GL recon is deferred) | Your item |
| 6 | Reports | Minimal: an EOD summary (counts and totals by status/currency, recon result, DLQ depth). Full pack is deferred (DIG-107) | Could |
| 7 | Roll date | Mark D `CLOSED`, D+1 `OPEN`, publish `eod.completed` | Must |

**Not COB:** stuck sweeper, hold expiry, outbox relay and the FX poll stay on their own timers; COB only *checks* them in the drain step. Interest, fees, GL posting and statements aren't in this bank's scope.

**Decision to record:** do standing instructions run *before* cut-off (their postings belong to D and are covered by D's recon, matching your SI → recon idea) or *after* the date roll (they belong to D+1, as DIG-104's list implies)? Pick one and write it down.

**Where does it live? This changes my last answer.** I said to keep standing instructions as a package in transaction-service *until EOD returns*. A COB that orchestrates three services is that return. Applying the §9 rule: it owns its own state (`eod_run`, `eod_step`) and is long-running and heavy, so it shouldn't share a JVM or connection pool with payments.
- **Path 1, thin `batch-service` (sequencing only):** steps 1 → 3 → 5 → 7 (standing instructions first, then drain, so the postings they create have reached audit before recon compares). A `eod_run` row with UNIQUE `(business_date)` is the lock, and `eod_step` gives restart (CH-11, zero duplicate executions). The business logic stays where it is: it calls TXN `POST /internal/standing-instructions/run` and RECON as above. My rough estimate is 3–5 SP, but DIG-104 is 8 SP, deferred by ADR-004, so this needs an ADR amendment.
- **Path 2, if capacity says no:** keep standing instructions in transaction-service, recon on its own schedule, and present COB as the target design in the defense deck.

Either way it ranks below the graded chaos work (CH-01 to CH-04).

**Contracts to add:** `POST /internal/recon/runs`, `POST /internal/standing-instructions/run`, events `reconciliation.completed` and `eod.completed`, tables `eod_run`, `eod_step`, `eod_balance_snapshot`, and `business_date` on `ledger_entry`.

---

## 12. Explain it back (self-test)

1. **Why can't transaction-service just `UPDATE account_master`?** Single writer principle: only CBS (`accounts-service`) holds credentials and logic for row locks (`SELECT ... FOR UPDATE ORDER BY account_id`), balance invariants, and atomic 6-leg double-entry posting.
2. **Postgres is down mid-payment. What happens, and which chaos scenario proves it?** Payment commits completely in Oracle XE; outbox accumulates locally in `outbox_master`; Audit Consumer pauses and retries via Kafka (CH-04). Because `fx_rate` lives in Oracle (`FX` schema), foreign currency conversions proceed without impediment.
3. **Why is Recon a separate service, and why does it read the databases directly instead of Kafka?** Maker-Checker principle: the auditor must not share code, runtime, or message brokers with the pipeline it audits. It reads Oracle `account_master` and Postgres `ledger_mutation_audit` directly to catch missed or dropped events that Kafka never delivered.
4. **How does CBS preserve immediate ledger history without waiting for Kafka?** The core `ledger_entry` journal is inserted in the exact same Oracle transaction as `account_master` balance mutation. Statements and balance-check queries read directly from `ledger_entry` with zero replication lag.
5. **Why is `ledger_entry` inside accounts-service but `audit-consumer` a separate module?** `ledger_entry` requires the exact same ACID transaction as `account_master` (Rule 1: Same ACID $\rightarrow$ Integrate). `audit-consumer` crosses database and failure boundaries into PostgreSQL (Rule 2: Different store, authority, and failure domain $\rightarrow$ Separate).
6. **Kafka is down for 10 minutes, then Postgres, then a malformed event arrives. Which mechanism handles each, and which never reaches the DLQ?**
   * *Kafka down (CH-05):* Outbox rows accumulate in Oracle `outbox_master`; OutboxRelay resumes publishing upon recovery (never reaches DLQ).
   * *Postgres down (CH-04):* Audit Consumer pauses and retries (never reaches DLQ, avoiding false reconciliation breaks).
   * *Malformed poison message (CH-07):* Exponential retry topics exhausted $\rightarrow$ Spring Kafka `@DltHandler` routes to `.dlq` for admin inspection.
7. **How does CAS prevent the cancellation vs. settlement race?** `UPDATE transaction_master SET txn_status = 'PROCESSING' WHERE txn_id = ? AND txn_status = 'AUTHORIZED'`. The atomic update ensures only one thread succeeds: if settlement wins, customer cancel requests are rejected with `CONFLICT`; if cancel wins, settlement aborts before invoking CBS.
