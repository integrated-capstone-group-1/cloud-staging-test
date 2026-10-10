# Coverage Report: perimeter-ingress (run-02)

## Emphasis & Constants
* **Emphasis:** Retest of `F-006` (header sanitization on client-supplied `X-Cust-Id` header across multiple endpoints without HTTP 500 errors), re-verification of prior findings (`F-001`, `F-003`, `F-005`), and final global database invariant check.
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
* **Gateway JWT enforcement:** PASS (401 unauth / 401 tampered token).
* **Invariant baseline:** PASS (exit code 0; all invariants I-1 through I-13 clean).

---

## Retest Results
* **`RETEST PASS F-006`**: Client-supplied `X-Cust-Id` headers are now gracefully sanitized/overridden by the Gateway across all endpoints (`/api/v1/payments/history`, `/api/v1/accounts/{id}/pockets`, `/api/v1/cards`), returning HTTP `200 OK` for the authenticated customer without HTTP 500 errors or identity spoofing.
* **`RETEST PASS F-001`**: Confirmed; `/api/v1/ledger/mutate` strictly returns `403 Forbidden`.
* **`RETEST PASS F-003`**: Confirmed; payments with scale > 4 return `400 VALIDATION_FAILED`.
* **`RETEST PASS F-005`**: Confirmed; cross-customer payment queries return `403 ACCESS_DENIED`.

---

## Step Outcomes & Seed Journeys
* **E2E-09 (Gateway Route Isolation, Rate Limiting & Header Sanitization):** Header sanitization retested and confirmed clean across multiple surfaces. Route cloaking on `/internal/**` and direct mutation routes confirmed.
* **Skipped Steps:** All `[HARNESS]` steps skipped per boundaries.

---

## Attack Areas
* **Area 3 (Authorization & Header Sanitization):** Retest pass for F-006.
* **Area 6 (Business Logic & Boundary Protection):** Confirmed clean.

---

## Contract Queries
* None.

---

## Unconfirmed Observations
* None.

---

## Final Global Invariant Result
* **Exit code:** `0` (PASS)
* **Output:** All invariants I-1 through I-13 clean.

---

## Summary Table

| ID | Severity | Oracle | One-line Summary |
|---|---|---|---|
| None | - | - | No new findings in run-02. All previous findings (`F-001` through `F-006`) verified and passing retests. |

