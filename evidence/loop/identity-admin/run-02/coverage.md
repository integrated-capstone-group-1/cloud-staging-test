# Coverage Report: identity-admin (run-02)

## Emphasis & Constants
* **Emphasis:** Retest of `F-001` (perimeter boundary enforcement), validation of invariant baseline after stack reset, deep probing of admin routes (`/api/admin/accounts`, `/api/admin/customers`, reversals, held transfers), and error handling under edge payment inputs.
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
* **Gateway JWT enforcement:** PASS (401 without token, 401 with tampered token).
* **Invariant baseline:** PASS (exit code 0; all I-1 through I-13 green).

---

## Retest Results
* **`RETEST PASS F-001`**: `POST /api/v1/ledger/mutate` is now strictly intercepted at the edge Gateway with `403 Forbidden`. Sibling routes (`/api/accounts/1/credit`, `/api/accounts/1/debit`, `/api/v1/accounts/1/credit`, `/api/v1/accounts/1/debit`) are all intercepted with `403 Forbidden`.

---

## Step Outcomes & Seed Journeys
* **E2E-01 (KYC Onboarding & Session):** Registration returns `201 Created`; fresh customer login remains blocked on `[VERIFY H-08]`. Session lifecycle verified with seeded customer credentials (`juan.delacruz@example.com`, `maria.santos@example.com`).
* **E2E-05 (Risk Hold & 4-Eyes Queue):** BLOCKED (`TEST_OTP` is `none`).
* **E2E-06 (Admin Ops & Reversals):** EXECUTED. Admin login verified with `admin@ledgerbank.com`. Master accounts registry (`/api/admin/accounts`) and customers registry (`/api/admin/customers`) accessible by admin (`200 OK`) and blocked for customer tokens (`403 Forbidden`). Administrative reversal endpoint (`POST /api/v1/admin/transactions/{id}/reverse`) requires `Idempotency-Key`, returns `404 Not Found` for non-existent transactions, and returns `403 Forbidden` when invoked by non-admin customer.
* **Skipped Steps:** All `[HARNESS]` steps skipped per boundaries.

---

## Attack Areas
* **Area 1 (Input):** Probed input variations on payments. Providing `funding_pocket_id` without `quote_id` causes an unhandled `NullPointerException` (HTTP 500 Internal Server Error) and leaks internal class name `com.capstone.orchestrator.dto.PaymentResponse` (logged as **F-002**).
* **Area 2 (Idempotency):** Admin reversal enforces `Idempotency-Key` header (`400 Bad Request` if omitted).
* **Area 3 (Authorization & IDOR):** TRIED. Customer tokens are blocked with `403 Forbidden` from `/api/admin/accounts`, `/api/admin/customers`, and `/api/v1/admin/transactions/*/reverse`. Forged headers (`X-Cust-Id`, `X-Role`, `X-Roles`, `X-User-Role`) are ignored by Gateway.
* **Area 4 (State Machine):** Reversal of non-existent transaction cleanly returns `404 Not Found`. Customer attempting reversal returns `403 Forbidden`.
* **Area 5 (FX & Shortfall):** Probed via funding pocket parameters.
* **Area 6 (Business Logic & Boundary Protection):** Perimeter verified clean post-fix.
* **Area 7 (Concurrency):** Concurrent requests stayed within caps.

---

## Contract Queries
1. **`[VERIFY H-08]` Fresh Customer Registration vs. Login:** `POST /api/auth/register` creates customer (`201 Created`), but subsequent `POST /api/auth/login` fails with `401 Invalid Credentials`.
2. **`[VERIFY H-09]` Held Transfers & Freeze Routes:** `GET /api/v1/admin/held-transfers` and `PUT /api/v1/admin/accounts/{id}/freeze` return `404 Not Found` (endpoint routes unconfirmed).

---

## Unconfirmed Observations
* None.

---

## Final Global Invariant Result
* **Exit code:** `0` (PASS)
* **Output:** All invariants I-1 through I-13 PASSED.

---

## Summary Table

| ID | Severity | Oracle | One-line Summary |
|---|---|---|---|
| [F-002](findings/F-002.md) | S2 | F1, F2 | `POST /api/v1/payments` returns HTTP 500 and leaks internal Java class name when `funding_pocket_id` is supplied without `quote_id` |

