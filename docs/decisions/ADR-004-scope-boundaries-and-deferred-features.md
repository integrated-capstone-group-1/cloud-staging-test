# ADR-004: In-Scope Scope Boundaries and Post-Capstone Deferred Features

## Context & Problem
BRD/TRD v2.3 introduced several enterprise operations capabilities:
* Transaction Lifecycle & Reversals
* Amount Holds
* Real-time Transaction Monitoring Dashboard
* Customer 360 Dashboard
* End-of-Day (EOD) Batch (Standing Instructions, GL Recon, Reports)
* Partner / External System Integrations
The 9-day capstone sprint has a hard capacity ceiling of 5 SP/person/day (7 * 5 = 35 SP/day). Without explicit scoping, Sprint 2 and Sprint 3 face severe over-allocation.

## Decision
1. **Committed In-Scope (Capstone Core & Defense):**
   * **Customer 360 Dashboard:** IN-SCOPE (per user directive). Implemented in Admin Portal (frontend-web Thymeleaf) aggregating KYC, accounts/pockets, transaction history, and risk score.
   * **Transaction Lifecycle & Reversals:** IN-SCOPE. 7-status model, CAS transitions, customer cancel, admin reversal (compensating linked transaction).
   * **Amount Holds:** IN-SCOPE. account_hold table in accounts-service, held_amount, available balance deduction.
   * **Real-Time Monitoring Dashboard:** IN-SCOPE. Stream/SSE transaction view on Admin Portal.
   * **Dead Letter Queue (DLQ):** IN-SCOPE. Spring Kafka retry topics + DLQ topic + admin replay endpoint.
2. **Deferred to Post-Capstone Backlog:**
   * **EOD Report Pack (DIG-107):** Formatted PDF/CSV download with cryptographic checksums.
   * **Partner B2B Remittance API Integration (DIG-109, DIG-110):** Outbound/inbound partner webhook adapter (the bank functions as an internal retail ledger for the capstone).
   * **GL Reconciliation General Ledger Engine (DIG-106):** Full chart-of-accounts GL run (our Dual-DataSource reconciliation engine between Oracle and PostgreSQL provides the required audit parity).
   * **Mobile Standing Instructions Screen (DIG-108):** UI setup form (backend standing instruction engine executes automatically).

## Consequences
* Protects the 7-member team from impossible 184 SP sprint overloads.
* Guarantees all user-specified must-haves (Customer 360, Reversals, Holds, DLQ) are built and defended.
