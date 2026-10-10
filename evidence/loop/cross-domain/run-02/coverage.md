# Coverage Report: cross-domain (run-02)

## Emphasis & Constants
* **Emphasis:** Retest of `F-008` (cross-customer FX quote hijacking on `POST /api/v1/fx/convert`), regression re-verification of all prior verified findings (`F-001` through `F-007`), and final global database invariant check.
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
* **Gateway & JWT authentication:** PASS (Admin, Customer A `juan`, Customer B `maria` tokens issued).
* **Invariant baseline:** PASS (exit code 0; all invariants I-1 through I-13 clean).

---

## Retest Results
* **`RETEST PASS F-008`**: Cross-customer FX quote hijacking on `POST /api/v1/fx/convert` is strictly blocked with HTTP `403 Forbidden` (`ACCESS_DENIED`, RFC 7807 error problem detail). Anonymous conversions return HTTP `401 Unauthorized`. The authentic owner of the quote is allowed to convert (`200 OK`).
* **`RETEST PASS F-001`**: Confirmed; `/api/v1/ledger/mutate` strictly returns `403 Forbidden`.
* **`RETEST PASS F-002`**: Confirmed; payments with `funding_pocket_id` missing `quote_id` return `400 Bad Request` (`VALIDATION_FAILED`).
* **`RETEST PASS F-003`**: Confirmed; payments with scale > 4 return `400 Bad Request` (`VALIDATION_FAILED`).
* **`RETEST PASS F-004`**: Confirmed; duplicate idempotency keys handled cleanly via fast-locking without raw SQL leak.
* **`RETEST PASS F-005`**: Confirmed; cross-customer payment query on `GET /api/v1/payments/{id}` returns `403 Forbidden` (`ACCESS_DENIED`).
* **`RETEST PASS F-006`**: Confirmed; client-supplied `X-Cust-Id` header is sanitized by Gateway filter and returns `200 OK`.
* **`RETEST PASS F-007`**: Confirmed; cross-customer ledger audit read on `GET /api/v1/ledger/audit/{id}` returns `403 Forbidden` (`ACCESS_DENIED`).

---

## Step Outcomes & Seed Journeys
* Retested IDOR boundary on `POST /api/v1/fx/convert`.
* Verified that quote ownership is strictly validated against the caller's verified JWT customer identity.
* Validated that conversion succeeds for quote owner and is denied for third-party callers.

---

## Attack Areas
* **Area 3 (Authorization & IDOR):** Retest pass for `F-008`. Tenant boundaries verified intact across FX quote conversion routes.

---

## Contract Queries
* None.

---

## Unconfirmed Observations
* None.

---

## Final Global Invariant Result
* **Exit code:** `0` (PASS)
* **Output:** All invariants I-1 through I-13 PASS (`(0)` mismatches across all tables).

---

## Summary Table

| ID | Severity | Oracle | One-line Summary |
|---|---|---|---|
| None | - | - | No new findings in run-02. All previous findings (`F-001` through `F-008`) verified and passing retests. |

