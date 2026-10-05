# ADR-002: Status Lifecycle, Amount Holds, and Single-Writer Ownership

## Context & Problem
BRD/TRD v2.3 specifies a 7-status transaction lifecycle:
`INITIATED` -> `AUTHORIZED` -> `PROCESSING` -> `POSTED` (or `FAILED`, `RESERVED`, `CANCELLED`).
It also specifies amount holds and customer cancellation before processing.
However, there was an ownership contradiction:
* The BRD assumed a monolithic "Transaction/Ledger Service" where status, holds, and balances could be checked in a single database transaction.
* In our distributed microservices architecture, `transaction-service` owns `transaction_master`, while `accounts-service` owns `account_master`, `account_hold`, and ledger mutations.
* If `accounts-service` needed to re-check `transaction_master.status` under lock during settlement (as implied by Jira `DIG-66` AC5), it would violate the single-writer principle and require cross-service database coupling.

## Decision
1. **Compare-and-Set (CAS) Claim in `transaction-service`:**
   * Before calling settlement in `accounts-service`, `transaction-service` claims the transition:
     `UPDATE transaction_master SET status = 'PROCESSING', stage = 'SETTLING' WHERE txn_id = ? AND status = 'AUTHORIZED'`
   * If CAS succeeds: `transaction-service` proceeds to call `accounts-service`. Any concurrent customer cancellation (`UPDATE ... WHERE status IN ('INITIATED', 'AUTHORIZED')`) will fail because the status is already `PROCESSING`.
   * If CAS fails: processing aborts (e.g., transaction was already cancelled by the customer).
2. **Data Ownership Separation:**
   * `transaction-service` strictly owns `transaction_master` and lifecycle state transitions (`txn.lifecycle` events).
   * `accounts-service` strictly owns `account_master`, `account_hold` table, `held_amount`, and `outbox_master`.
3. **Settlement API Extension (`capture_hold_id`):**
   * `POST /internal/core/settlement` accepts an optional `capture_hold_id`. If supplied, `accounts-service` releases the hold and settles the balance in the same atomic Oracle transaction.
4. **Idempotent Status Finalization & Sweeper:**
   * Upon successful response from `accounts-service`, `transaction-service` sets status to `POSTED`.
   * A background sweeper (`StuckTransactionSweeper`) queries `GET /internal/core/settlement/{posting_reference_id}` for any transactions stuck in `PROCESSING` (> 60s) to reconcile status to `POSTED` or `FAILED`.
5. **BRD Amendment:**
   * Amends TR-CN02 and TR-LC01 to note that atomic coordination is achieved via CAS claim on `transaction_master` followed by atomic core settlement in `accounts-service`, with sweeper reconciliation.

## Consequences
* Strict single-writer integrity is maintained.
* Cross-service database coupling is prevented.
* Race conditions between cancellation and processing are deterministically eliminated.
