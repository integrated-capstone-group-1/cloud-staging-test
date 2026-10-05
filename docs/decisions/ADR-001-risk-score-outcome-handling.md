# ADR-001: Risk Score Outcome Handling (Reject vs Risk Hold vs Timeout)

## Context & Problem
BRD/TRD v2.3 (BR-33, TR-R05, TR-R08), Jira `DIG-24`, and `DIG-84` specify the risk evaluation decision behavior. Earlier documents had contradictory guidance: some stated all transfers with score > 0.85 are immediately rejected, while others described placing high-risk transfers into an administrative hold queue. Furthermore, amount holds were sometimes confused with risk holds.

## Decision
1. **Engine Timeout / Offline (<= 200ms SLA breach):**
   * The transaction **fails closed** immediately.
   * HTTP Response: `503 Service Unavailable` with `SECURITY_CHECK_UNAVAILABLE`.
   * Persisted state: `FAILED`. No funds move, no holds created.
2. **High-Risk Score (> 0.85):**
   * Score > 0.85 transitions the transaction to `RESERVED` status.
   * Emits `RISK_HOLD` event to `txn.lifecycle`.
   * Enqueues into the Admin Held Review queue (`DIG-51`).
   * The customer receives `202 Accepted` with status `RESERVED` (or clear message indicating transfer is under manual review).
   * Funds are placed under an amount hold in `accounts-service` (`hold_type = 'RISK'`).
3. **Medium-Risk Score (0.50 <= Score <= 0.85):**
   * Triggers Step-Up Email OTP (`STEP_UP_OTP`).
4. **Low-Risk Score (< 0.50):**
   * Automatically allowed (`ALLOW`), proceeds to `PROCESSING`.
5. **Clarification on Amount Holds:**
   * Amount holds (`account_hold`) are a general-purpose balance reservation mechanism owned by `accounts-service`. They support standing instructions, card pre-authorizations, and risk holds (`hold_type IN ('RISK', 'SI', 'PREAUTH')`).

## Consequences
* Preserves `DIG-51` (Admin held queue) and `DIG-85` (Hold expiry job).
* Aligns with BR-18, BR-33, TR-R05, TR-R08, and TR-HD01–TR-HD06.
