# Coverage Report: identity-admin (run-01)

## Emphasis & Constants
* **Emphasis:** Run 1 initial breadth scan across identity, onboarding, admin authentication, session lifecycle, and edge boundary protection.
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
* **Constants check:** PASS (all filled; `TEST_OTP=none` set by user).
* **Gateway JWT enforcement:** PASS (Unauthenticated: `401 Unauthorized`; Tampered token: `401 Unauthorized`).
* **Invariant baseline:** PASS (exit code 0; all I-1 through I-13 clean).

---

## Step Outcomes & Seed Journeys
* **E2E-01 (KYC Onboarding & Session):** PARTIALLY BLOCKED. `POST /api/auth/register` returns `201 Created` with `customerId`, but subsequent `POST /api/auth/login` fails with `401 Invalid Credentials` for fresh accounts. Session lifecycle (login, JWT issue, logout, token blacklist enforcement) verified using seeded personas (`SEED.md`).
* **E2E-05 (Risk Hold & 4-Eyes Queue):** BLOCKED (Step-up OTP flow blocked because `TEST_OTP` is `none`).
* **E2E-06 (Admin Ops & Reversals):** PARTIALLY EXECUTED (Admin authentication verified with `admin@ledgerbank.com`; perimeter boundary protection probed).
* **Skipped Steps:** All `[HARNESS]` steps (database inspection, container manipulation, direct internal service port calls) skipped per boundaries.

---

## Attack Areas
* **Area 1 (Input):** TRIED. Missing fields, SQL metacharacters in registration/login, oversized bodies, malformed JSON, and bad field types all rejected cleanly with `400 Validation Failed` / `400 Bad Request`.
* **Area 2 (Idempotency):** TRIED. Duplicate registration with identical email returns `409 Conflict`.
* **Area 3 (Authorization & IDOR):** TRIED. Unauthenticated calls to admin endpoints return `401`/`403`.
* **Area 4 (State Machine):** TRIED. Token logout successfully invalidates token; subsequent use returns `401 Unauthorized`.
* **Area 5 (FX & Shortfall):** NOT APPLICABLE to domain `identity-admin`.
* **Area 6 (Business Logic & Boundary Protection):** TRIED. Probed perimeter routes specified in CONTRACTS Section 1.1 (`B-06`). CRITICAL BREACH identified: `/api/v1/ledger/mutate` is accessible through edge gateway and permits unauthorized credit mutations.
* **Area 7 (Concurrency):** TRIED. Parallel registration race (5 concurrent threads with identical email payload): exactly 1 succeeded, 4 rejected with conflict.

---

## Retest Results
* Initial run; no prior findings to retest.

---

## Contract Queries
1. **`[VERIFY H-08]` Fresh Customer Registration vs. Login:** `POST /api/auth/register` returns `201 Created`, but newly registered accounts cannot log in via `POST /api/auth/login` (`401 Invalid Credentials`). Only seeded personas (`juan.delacruz@example.com`, `admin@ledgerbank.com`, etc.) authenticate successfully.
2. **`[VERIFY H-09]` Admin Portal Host Availability:** `ADMIN_URL` (`http://localhost:8090`) is inactive / not listening. Staff/admin APIs are served through the gateway at `:8080`.
3. **`[VERIFY H-16]` Route Block Status Codes:** Direct balance mutations under `/api/v1/accounts/*/credit` and `/debit` return `401` unauthenticated and `404` authenticated instead of being intercepted at the Gateway with `403 Forbidden`.

---

## Unconfirmed Observations
* None.

---

## Final Global Invariant Result
* **Exit code:** `1` (Hard invariant failure)
* **Trigger:** Mutation via edge-exposed `/api/v1/ledger/mutate`.
* **Output:**
  ```text
  I-1 PASS (0)
  I-2 PASS (0)
  I-3 PASS (0)
  I-6 PASS (0)
  I-8 PASS (0)
  I-9 FAIL (1) Baseline drift: PHP: base=-149941977.4000 current=-149941962.4000
  I-10 FAIL (2) Accounts where balance != running balance: 3722f77f-3d1b-4d55-8f83-b71bac91d095, f95e7e52-3259-4b4d-ac66-b186a893cf66
  I-11 PASS (0)
  I-12 PASS (0)
  I-13 PASS (0)
  I-4 PASS (0)
  I-5 PASS (0)
  I-7 PASS (0)
  ```

---

## Summary Table

| ID | Severity | Oracle | One-line Summary |
|---|---|---|---|
| [F-001](findings/F-001.md) | S1 | F3, F6, F7, F8 | `/api/v1/ledger/mutate` exposed via Gateway allowing arbitrary balance credit and violating money invariants (I-9 drift, I-10 balance mismatch) |

