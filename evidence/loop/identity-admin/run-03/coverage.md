# Coverage Report: identity-admin (run-03)

## Emphasis & Constants
* **Emphasis:** Retest of `F-001` (perimeter defense) and `F-002` (payment validation on funding pocket), verification of shortfall input constraints, admin reconciliation route probe, and global invariant verification.
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
* **`RETEST PASS F-001`**: `POST /api/v1/ledger/mutate` strictly returns `403 Forbidden` at the Gateway.
* **`RETEST PASS F-002`**: `POST /api/v1/payments` when `funding_pocket_id` is specified without `quote_id` returns HTTP `400 Bad Request` with `VALIDATION_FAILED` (`"detail": "quote_id is required when funding_pocket_id is specified"`) adhering to RFC 7807 envelope. 3 of 3 attempts passed.

---

## Step Outcomes & Seed Journeys
* **E2E-01 (KYC Onboarding & Session):** Session lifecycle verified with seed accounts. Fresh customer login unconfirmed (`[VERIFY H-08]`).
* **E2E-05 (Risk Hold & 4-Eyes Queue):** BLOCKED (`TEST_OTP` is `none`).
* **E2E-06 (Admin Ops & Reversals):** EXECUTED. Reversal access controls, customer token boundaries, and reconciliation routes probed.
* **Skipped Steps:** All `[HARNESS]` steps skipped per boundaries.

---

## Attack Areas
* **Area 1 (Input):** Verified shortfall input validation: missing quote_id returns `400 Bad Request` (`VALIDATION_FAILED`), unauthorized/victim funding pocket returns `403 Access Denied`.
* **Area 2 (Idempotency):** Verified on retests.
* **Area 3 (Authorization & IDOR):** Customer tokens blocked from admin endpoints (`403 Forbidden`). Unauthenticated calls return `401 Unauthorized`.
* **Area 4 (State Machine):** Retest pass; no abnormal state transitions.
* **Area 5 (FX & Shortfall):** Shortfall quote coupling verified.
* **Area 6 (Business Logic & Boundary Protection):** Perimeter boundaries verified clean post-fix.
* **Area 7 (Concurrency):** Retests and calls executed within caps.

---

## Contract Queries
1. **`[VERIFY H-08]` Fresh Customer Registration vs. Login:** `POST /api/auth/register` creates customer (`201 Created`), but subsequent `POST /api/auth/login` fails with `401 Invalid Credentials`.
2. **`[VERIFY H-09]` Admin Portal URL:** Host `:8090` is inactive; admin surfaces route via Gateway `:8080`.

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
| None | - | - | No new findings in run-03. All previous findings (`F-001`, `F-002`) verified and passing retest. |

