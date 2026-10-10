# Coverage Report: payments (run-02)

## Emphasis & Constants
* **Emphasis:** Retest of `F-003` (amount scale > 4 and scientific notation rejection) and `F-004` (concurrent idempotency fast-locking without SQL/schema leaks), depth probes on smart routing and payment query endpoints, and IDOR authorization boundary testing.
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
* **Invariant baseline:** PASS (exit code 0; all I-1 through I-13 green).

---

## Retest Results
* **`RETEST PASS F-003`**: `POST /api/v1/payments` with scale > 4 (`10.123456`, `10.12345678`) and scientific notation (`1e3`) is strictly rejected with HTTP `400 Bad Request` and error code `VALIDATION_FAILED` (`"detail": "Invalid monetary amount format: must be a positive decimal string with scale at most 4 and without scientific notation"`).
* **`RETEST PASS F-004`**: Concurrent requests sharing the same `X-Idempotency-Key` are properly locked via Redis fast-lock returning HTTP `409 Conflict` with `REQUEST_IN_PROGRESS`. Zero SQL, constraint names, or ORA-00001 errors leaked.

---

## Step Outcomes & Seed Journeys
* **E2E-02 (Same-Currency Transfer & Idempotency):** EXECUTED. Payment creation, query, and fast-lock concurrency retested and confirmed.
* **E2E-03 (Smart Shortfall Multi-Currency):** Shortfall quote validation re-verified.
* **E2E-04 (Cancellation & CAS Lifecycle):** Post-settlement cancellation conflict re-verified.
* **Skipped Steps:** All `[HARNESS]` steps skipped per boundaries.

---

## Attack Areas
* **Area 1 (Input):** Retest pass for F-003 monetary validations.
* **Area 2 (Idempotency) & Area 7 (Concurrency):** Retest pass for F-004 fast-locking.
* **Area 3 (Authorization & IDOR):** Probed `GET /api/v1/payments/{id}`. Critical IDOR vulnerability identified: Any authenticated customer can query any other customer's transaction details by transaction ID and receive HTTP `200 OK` (logged as **F-005**). Conversely, `GET /api/v1/transactions/{id}` properly checks ownership and returns `403 ACCESS_DENIED`.
* **Area 4 (State Machine):** Validated.
* **Area 5 (FX & Shortfall):** Validated.
* **Area 6 (Business Logic):** Validated.

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
| [F-005](findings/F-005.md) | S1 | F7 | Cross-customer IDOR vulnerability on `GET /api/v1/payments/{id}` leaking payment records across customers |

