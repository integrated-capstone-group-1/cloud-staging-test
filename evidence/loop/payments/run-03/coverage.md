# Coverage Report: payments (run-03)

## Emphasis & Constants
* **Emphasis:** Retest of `F-005` (cross-customer IDOR vulnerability on `GET /api/v1/payments/{id}`), re-verification of `F-003` (scale > 4 validation) and `F-004` (concurrent idempotency fast-locking), and final global database invariant check.
* **Constants:**
  * `ENTRY_POINT`: `http://localhost:8080`
  * `ADMIN_URL`: `http://localhost:8090`
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
* **`RETEST PASS F-005`**: `GET /api/v1/payments/{id}` now strictly checks customer ownership. When invoked with another customer's token, it returns HTTP `403 Forbidden` with error code `ACCESS_DENIED` (`"detail": "Access denied: You do not have permission to view payment status for customer..."`). Legitimate customer queries return `200 OK`. 3 of 3 attempts passed.
* **`RETEST PASS F-003`**: Confirmed; invalid scales and scientific notation continue to return `400 VALIDATION_FAILED`.
* **`RETEST PASS F-004`**: Confirmed; concurrent requests return cached response or `409 REQUEST_IN_PROGRESS` without database errors.

---

## Step Outcomes & Seed Journeys
* **E2E-02 (Same-Currency Transfer & Idempotency):** Core payment flow, cached replays, and IDOR protection confirmed.
* **E2E-03 (Smart Shortfall Multi-Currency):** Shortfall quote isolation and validations confirmed.
* **E2E-04 (Cancellation & CAS Lifecycle):** CAS cancellation conflicts confirmed.
* **Skipped Steps:** All `[HARNESS]` steps skipped per boundaries.

---

## Attack Areas
* **Area 1 (Input):** Validated.
* **Area 2 (Idempotency):** Validated.
* **Area 3 (Authorization & IDOR):** Retest pass for F-005.
* **Area 4 (State Machine):** Validated.
* **Area 5 (FX & Shortfall):** Validated.
* **Area 6 (Business Logic):** Validated.
* **Area 7 (Concurrency):** Validated.

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
| None | - | - | No new findings in run-03. All previous findings (`F-001` through `F-005`) verified and passing retests. |

