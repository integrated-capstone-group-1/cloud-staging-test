# Coverage Report: async-audit-dlq (run-01)

## Emphasis & Constants
* **Emphasis:** Track 5 / E2E-10 scan across asynchronous event processing, DLQ administrative controls, audit log persistence, cross-customer isolation on mutation audit streams, and eventual invariant consistency (`I-4`, `I-5`, `I-7`).
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
* **`RETEST PASS F-005`**: Cross-customer payment query `GET /api/v1/payments/{id}` returns HTTP `403 Forbidden` (`ACCESS_DENIED`).
* **`RETEST PASS F-006`**: Client-supplied `X-Cust-Id` header is stripped/sanitized by Gateway filter and returns HTTP `200 OK`.

---

## Step Outcomes & Seed Journeys
* **E2E-10 (Audit Log & Eventual Invariant Drain):**
  * **DLQ Administrative Route Probing:**
    * Probed `GET /api/v1/admin/dlq`, `GET /api/admin/dlq`, and `POST /api/v1/admin/dlq/replay`.
    * Unauthenticated calls return `401 Unauthorized` / `404 Not Found`.
    * Non-admin customer calls return `401` / `403` / `404 Not Found` (no privilege escalation).
  * **Ledger Audit Trail Query (`GET /api/v1/ledger/audit/{id}`):**
    * Submitted valid transaction for Customer A (`f20f9b72-3ae5-47c3-bccf-5fe690e4a1af`).
    * Queried audit trail using Customer B's token.
    * Route returned HTTP `200 OK` exposing internal account IDs, mutation UUIDs, leg numbers, amounts, and debit/credit directions.
    * Logged as **`F-007`** (S1 - Cross-customer IDOR).
  * **Eventual Invariant Drain:**
    * Allowed asynchronous event pipeline to drain and verified eventual consistency invariants `I-4`, `I-5`, `I-7`. All invariants clean.

---

## Attack Areas
* **Area 3 (Authorization & IDOR):** Discovered S1 vulnerability on `GET /api/v1/ledger/audit/{id}` allowing cross-customer read access to double-entry ledger mutation audit trails.
* **Area 5 (Async & Audit Trail Integrity):** DLQ administrative routes properly protected; audit trail records generated with correct double-entry legs.

---

## Contract Queries
* None.

---

## Unconfirmed Observations
* None.

---

## Final Global Invariant Result
* **Exit code:** `0` (PASS)
* **Output:** All invariants `I-1` through `I-13` PASS (`(0)` mismatches across all tables).

---

## Summary Table

| ID | Severity | Oracle | One-line Summary |
|---|---|---|---|
| [F-007](findings/F-007.md) | S1 | F7 | Cross-customer IDOR on `GET /api/v1/ledger/audit/{id}` exposing financial ledger mutation records |

