# Business & Technical Requirements

## 1. Executive Summary & Architectural Philosophy
This implementation plan establishes the architectural and engineering blueprint for a modern, **MariBank-inspired digital banking platform**. 

### Core Strategic Decisions
1. **Channel vs. Core Separation**: Our `accounts-service` + Oracle XE is the Mock Core Banking System (CBS), acting as the authoritative System of Record (SoR) for ledger balances under pessimistic row locks. The `transaction-service` transitions into the Channel Orchestration Engine for workflows, smart routing, risk pre-flight checks, and auditing.
2. **Unified Primary PHP Account + Virtual Card + Currency Pockets**: Customers receive one primary PHP account linked to a single Virtual Card. Optional foreign currency sub-wallets ("pockets": USD, EUR) can be opened on demand.
3. **Smart Card Spending & Auto-Conversion Engine**: If the exact currency pocket has funds, debits directly (0% FX fee). If insufficient, auto-converts from another pocket (with explicit user confirmation for transfers).
4. **No Python or OFS**: The backend is purely Java (Spring Boot). The core interface is REST JSON.

## 2. Scope & Goals
Build a unified, cloud-ready retail core banking ledger and mobile/web channel layer. The platform must guarantee zero overdrafts, 100% audit parity, and resilient transaction processing. 

## 3. Non-Goals & Out of Scope
*   **No Python:** The entire backend stack, including the risk engine, is strictly Java.
*   **No T24 OFS:** We are not integrating with an external legacy core, nor parsing OFS strings. Our Accounts Service *is* the Mock CBS via REST/JSON.
*   **No Live Interbank FX:** We rely strictly on a cached hourly rate from an external mock API.

## 4. Constraints
*   **Tech Stack:** Java 21, Spring Boot 3.3, Oracle XE 21c (Master), PostgreSQL 15 (Audit), Kafka, Redis.
*   **Data Consistency:** Strict single-writer ownership. Oracle is mutable (via pessimistic locking). Postgres is strictly append-only.

## 5. User Stories
*   **R-001 (Onboarding):** Registration automatically creates a PHP pocket. USD/EUR pockets are opened on demand.
*   **R-002 (Smart Payment):** If I try to pay 10 EUR but only have 5 EUR and 1000 PHP, the system offers to fund the remaining amount from my PHP pocket.
*   **R-003 (Immutable Audit):** Every balance change generates an outbox event and an append-only audit log in PostgreSQL.
*   **R-004 (Admin Freeze):** Admins can freeze an account, overriding any customer lock/unlock commands.
*   **R-005 (Fail-Safe Risk):** If the risk engine takes more than 200ms to respond, the transaction is safely rejected (fail-closed).

## 6. Integrated Capstone Evaluation Matrix
### Rubric Breakdown & Verification Criteria

#### Task 1: Domain Definition, DB Setup
- **Unified API Contract**: OpenAPI schemas for Cards, Pockets, Remittance, Risk.
- **Multi-Tier Persistence**: Oracle XE for master balances, PostgreSQL for immutable risk & mutation audits.

#### Task 2: Backend Business Logic & Perimeter Security
- **Remittance Engine**: Spring Boot dual-commit orchestration holding master transaction during security screening.
- **Sync Risk Engine**: Java service delivering risk evaluations in $< 200\text{ms}$; score $> 0.85$ drop logic.
- **Gateway Perimeter**: Token decryption and Redis-backed rate limiting emitting HTTP 429 upon flood attacks.

#### Task 3: DevSecOps & Client Assembly
- **Zero-Trust Network**: Internal microservices and databases fully encapsulated.
- **Flutter Clients**: Clean Material 3 mobile and web UI with live virtual card, pocket balances, and FX swaps.

#### Task 4: Chaos Engineering & Defense
- **Scenario 1 Defense**: DB degradation triggers Resilience4j breaker, redirecting traffic to cache without app crashing.
- **Scenario 2 Defense**: Risk engine outage caught by SLA timer within $200\text{ms}$; ledger remains completely clean.
- **Scenario 3 Defense**: Port penetration scan proves internal container ports are inaccessible from outside.
