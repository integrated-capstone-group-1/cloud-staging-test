# Coverage Report: payments (run-01)

## Emphasis & Constants
* **Emphasis:** Run 1 breadth scan across payments domain. Seed journeys E2E-02 (same-currency transfer & idempotency), E2E-03 (FX shortfall & quotes), and E2E-04 (cancellation & CAS lifecycle). Hostile input mutations on monetary amounts and currencies; concurrent race testing on idempotency fast-locking.
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
* **`RETEST PASS F-001`**: `POST /api/v1/ledger/mutate` returns `403 Forbidden` at Gateway.
* **`RETEST PASS F-002`**: `POST /api/v1/payments` with `funding_pocket_id` missing `quote_id` returns `400 Bad Request` with `VALIDATION_FAILED`.

---

## Step Outcomes & Seed Journeys
* **E2E-02 (Same-Currency Transfer & Idempotency):** EXECUTED. Payment initiated (`200 OK` / `202 Accepted`). Replay with identical payload and key returns identical status. Replay with altered payload returns `409 Conflict`. Reusing same key from another customer is isolated per customer.
* **E2E-03 (Smart Shortfall Multi-Currency):** EXECUTED. FX Quote generation (`GET /api/v1/fx/quote`) returns 200 with quote ID, spread, and fee. Reusing another customer's quote is blocked with `403 Access Denied`. Reusing quote with mismatched amount is rejected with `422 Unprocessable`.
* **E2E-04 (Cancellation & CAS Lifecycle):** EXECUTED. Payment cancellation after final status returns `409 Conflict`. Double cancellation returns `409 Conflict`.
* **Skipped Steps:** All `[HARNESS]` steps skipped per boundaries.

---

## Attack Areas
* **Area 1 (Input):** Monetary input mutations probed. Amounts with negative value, zero, non-numeric strings, and NaN are cleanly rejected (`400 Bad Request`). However, amounts with scale exceeding 4 (`10.123456`, `10.12345678`) and scientific notation (`1e3`) are accepted with `200/202` instead of `400 Bad Request` (logged as **F-003**).
* **Area 2 (Idempotency) & Area 7 (Concurrency):** Concurrent identical requests with the same `X-Idempotency-Key` race into the database without edge fast-locking, triggering an Oracle `ORA-00001` unique constraint violation and leaking raw SQL insert statements into the client response (logged as **F-004**).
* **Area 3 (Authorization & IDOR):** Cross-customer quote usage rejected with `403 Access Denied`.
* **Area 4 (State Machine):** Post-settlement cancellation rejected with `409 Conflict`.
* **Area 5 (FX & Shortfall):** Mismatched shortfall quote amounts rejected with `422 Unprocessable`.
* **Area 6 (Business Logic):** Re-verified perimeter defense (`F-001`).

---

## Contract Queries
1. **`[VERIFY H-06]` Lowercase Currency Acceptance:** `POST /api/v1/payments` with `"currency": "php"` is accepted with `202 Accepted` instead of requiring uppercase `PHP` per CONTRACTS 164.
2. **Cancellation Error Envelope Detail:** When cancellation conflicts on a posted transaction, the `detail` property in the RFC 7807 response contains an escaped raw JSON string from the downstream service rather than clean descriptive text.

---

## Unconfirmed Observations
* None.

---

## Final Global Invariant Result
* **Exit code:** `0` (PASS; all invariants I-1 through I-13 clean).

---

## Summary Table

| ID | Severity | Oracle | One-line Summary |
|---|---|---|---|
| [F-003](findings/F-003.md) | S1 | F3, F8 | Monetary amounts with scale exceeding 4 and scientific notation (`1e3`) accepted on payment endpoint |
| [F-004](findings/F-004.md) | S1 | F2, F4, F5 | Concurrent payment requests with identical idempotency key bypass edge fast-lock and leak raw SQL on ORA-00001 |

