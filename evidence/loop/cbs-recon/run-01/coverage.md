# Coverage Report: cbs-recon (run-01)

## Emphasis & Constants
* **Emphasis:** Run 1 breadth scan across CBS, standing instructions, batch runner, and dual-datasource reconciliation surfaces (E2E-07, E2E-08). Retests of verified findings across all prior domains, perimeter route cloaking on internal batch endpoints, admin authorization on reconciliation triggers, and database invariant verification.
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
* **Invariant baseline:** PASS (exit code 0; all invariants clean after stack reset).

---

## Retest Results
* **`RETEST PASS F-001`**: `POST /api/v1/ledger/mutate` returns `403 Forbidden` at Gateway.
* **`RETEST PASS F-002`**: `POST /api/v1/payments` with `funding_pocket_id` missing `quote_id` returns `400 Bad Request` (`VALIDATION_FAILED`).
* **`RETEST PASS F-003`**: `POST /api/v1/payments` with scale > 4 or scientific notation returns `400 Bad Request` (`VALIDATION_FAILED`).
* **`RETEST PASS F-005`**: `GET /api/v1/payments/{id}` cross-customer query returns `403 Forbidden` (`ACCESS_DENIED`).

---

## Step Outcomes & Seed Journeys
* **E2E-07 (Standing Instructions Auto-Debit Batch Execution):** Internal batch trigger (`/internal/batch/standing-instructions/execute`) is strictly inaccessible via edge Gateway (`404 Not Found`). All `[HARNESS]` steps (database seeding and direct container batch execution) skipped per boundaries.
* **E2E-08 (Dual-DataSource Reconciliation Engine Run):** Reconciliation endpoints (`POST /api/recon/runs`, `POST /api/v1/recon/trigger`) probed. Calling with admin or customer tokens returns `401 Unauthorized` at Gateway (recorded as contract query under `[VERIFY H-09]`). All `[HARNESS]` steps skipped per boundaries.
* **Skipped Steps:** All `[HARNESS]` steps skipped per boundaries.

---

## Attack Areas
* **Area 1 (Input):** Probed hostile payloads on recon routes.
* **Area 2 (Idempotency):** Retests confirmed.
* **Area 3 (Authorization & IDOR):** Unauthenticated callers and customers cannot trigger reconciliation or access internal routes.
* **Area 6 (Business Logic & Boundary Protection):** Internal batch endpoints (`/internal/batch/**`, `/internal/recon/**`) return `404` and cannot be reached through the edge Gateway.

---

## Contract Queries
1. **`[VERIFY H-09]` Reconciliation Route Ingress:** Endpoints `POST /api/recon/runs` and `POST /api/v1/recon/trigger` return HTTP `401 Unauthorized` through the Gateway even when presented with a valid Bearer JWT bearing `ROLE_ADMIN`, indicating unconfirmed route mapping or auth filtering for `reconciliation-service`.

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
| None | - | - | No new findings in run-01. All previous findings (`F-001` through `F-005`) passing retest. |

