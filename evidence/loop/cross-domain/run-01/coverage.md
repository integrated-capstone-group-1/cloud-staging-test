# Coverage Report: cross-domain (run-01)

## Emphasis & Constants
* **Emphasis:** Final backend adversary scan across cross-customer resource isolation (IDOR), forged identity headers, cross-customer idempotency collisions, chained state transitions, and whole-system invariants.
* **Constants:**
  * `ENTRY_POINT`: `http://localhost:8080`
  * `ADMIN_URL`: `http://localhost:8090` (unreachable; routed via `:8080`)
  * `TEST_OTP`: `none`
  * `CANCEL_WINDOW_SECONDS`: 5
  * `INVARIANT_SCRIPT`: `tests/invariants/check_invariants.py`
  * `MAX_RPS`: 10
  * `PARALLEL_CAP`: 10
  * `REQUEST_BUDGET`: 500

---

## Preflight Result
* **Constants check:** PASS.
* **Gateway & JWT authentication:** PASS (Admin, Customer A `juan`, Customer B `maria` tokens successfully issued).
* **Invariant baseline:** PASS (exit code 0; all I-1 through I-13 clean).

---

## Retest Results
* **`RETEST PASS F-001`**: Direct balance mutation `/api/v1/ledger/mutate` returns HTTP `403 Forbidden`.
* **`RETEST PASS F-002`**: Payments with `funding_pocket_id` missing `quote_id` return HTTP `400 Bad Request` (`VALIDATION_FAILED`).
* **`RETEST PASS F-003`**: Payments with scale > 4 return HTTP `400 Bad Request` (`VALIDATION_FAILED`).
* **`RETEST PASS F-004`**: Duplicate idempotency key handled cleanly via fast-locking without raw SQL leak.
* **`RETEST PASS F-005`**: Cross-customer payment read `GET /api/v1/payments/{id}` returns HTTP `403 Forbidden` (`ACCESS_DENIED`).
* **`RETEST PASS F-006`**: Client-supplied `X-Cust-Id` header is stripped/sanitized by Gateway filter and returns HTTP `200 OK`.
* **`RETEST PASS F-007`**: Cross-customer ledger audit read `GET /api/v1/ledger/audit/{id}` returns HTTP `403 Forbidden` (`ACCESS_DENIED`).

---

## Step Outcomes & Probes
* **Probe 2.1 (Cross-Customer Payment Cancellation):**
  * Maria attempted to cancel Juan's active payment via `POST /api/v1/payments/{juan_txn_id}/cancel`.
  * Gateway/service returned HTTP `403 Forbidden` (`ACCESS_DENIED`). PASS.
* **Probe 2.2 (Cross-Customer Pocket Enumeration):**
  * Maria attempted to query Juan's pockets via `GET /api/v1/accounts/{juan_account_id}/pockets`.
  * Returned HTTP `403 Forbidden` (`ACCESS_DENIED`). PASS.
* **Probe 2.3 (Cross-Customer Pocket Spending):**
  * Juan attempted to spend from Maria's pocket via `funding_pocket_id`.
  * Returned HTTP `403 Forbidden` (`ACCESS_DENIED`). PASS.
* **Probe 2.4 (Cross-Customer FX Quote Hijacking):**
  * Juan generated locked FX quote `q-61393291-5249-44ff-9279-d840c85efa05`.
  * Maria submitted currency conversion `POST /api/v1/fx/convert` specifying Juan's `quote_id`.
  * Returned HTTP `200 OK` and executed conversion on Maria's account using Juan's quote.
  * Violates `CONTRACTS.md §2.1` ("quote_id must belong to the caller") and Oracle `F7`. Logged as **`F-008`** (S1).
* **Probe 2.5 (Virtual Card Access):**
  * Customer accounts returned empty cards list; tested authorization boundaries. PASS.
* **Probe 2.6 (Privilege Escalation via Header Forgery):**
  * Injected `X-Role: ROLE_ADMIN`, `X-Roles: ROLE_ADMIN`, `X-User-Roles: ROLE_ADMIN`, and `X-Cust-Id: cust-admin-001` with customer JWT.
  * Admin endpoints `/api/v1/admin/held-transfers` and `/api/v1/admin/accounts/{id}/freeze` returned HTTP `403 Forbidden`. PASS.
* **Probe 2.7 (Idempotency Key Scoping across Customers):**
  * Juan submitted payment with key `K`. Maria submitted payment with the exact same key `K`.
  * Maria's request received `409 REQUEST_IN_PROGRESS`. Recorded as observation: Redis fast-lock appears globally keyed rather than scoped by `customer_id`.
* **Probe 2.8 (Chained State Abuse on Posted Transaction):**
  * Juan attempted to cancel an already-`POSTED` transaction.
  * Returned HTTP `409 Conflict` (`Transaction already in POSTED and cannot be cancelled`). PASS.

---

## Attack Areas
* **Area 3 (Authorization & IDOR):** Discovered S1 vulnerability on `POST /api/v1/fx/convert` where `quote_id` ownership is not verified against the caller's JWT identity.
* **Area 6 (Business Logic & State Transitions):** Cancellation on terminal states and administrative routes verified properly protected.

---

## Observations
1. **Idempotency Key Global Scoping:** Re-using the same `X-Idempotency-Key` across two completely different customers returns `409 Conflict`, indicating the Redis fast-lock key is `idempotency:<uuid>` rather than scoped as `idempotency:<cust_id>:<uuid>` per `CONTRACTS.md §1.1`.

---

## Final Global Invariant Result
* **Exit code:** `0` (PASS)
* **Output:** All invariants `I-1` through `I-13` PASS (`(0)` mismatches across all tables).

---

## Summary Table

| ID | Severity | Oracle | One-line Summary |
|---|---|---|---|
| [F-008](findings/F-008.md) | S1 | F7 | Cross-customer FX quote hijacking permitted on `POST /api/v1/fx/convert` |

