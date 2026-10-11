# Coverage Report: async-audit-dlq (run-02)

## Emphasis & Constants
* **Emphasis:** Retest of `F-007` (cross-customer IDOR vulnerability on `GET /api/v1/ledger/audit/{id}`), re-verification of prior verified findings (`F-001`, `F-002`, `F-003`, `F-005`, `F-006`), and final global database invariant check.
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
* **`RETEST PASS F-007`**: Cross-customer query to `GET /api/v1/ledger/audit/{id}` is now strictly blocked with HTTP `403 Forbidden` (`ACCESS_DENIED`, RFC 7807 error problem detail). Unauthenticated queries return `401 Unauthorized`. The authentic owner of the transaction is still permitted access (`200 OK`) to their ledger audit entries.
* **`RETEST PASS F-001`**: Confirmed; `/api/v1/ledger/mutate` strictly returns `403 Forbidden`.
* **`RETEST PASS F-002`**: Confirmed; payments with `funding_pocket_id` missing `quote_id` return `400 Bad Request` (`VALIDATION_FAILED`).
* **`RETEST PASS F-003`**: Confirmed; payments with scale > 4 return `400 Bad Request` (`VALIDATION_FAILED`).
* **`RETEST PASS F-005`**: Confirmed; cross-customer payment query on `GET /api/v1/payments/{id}` returns `403 Forbidden` (`ACCESS_DENIED`).
* **`RETEST PASS F-006`**: Confirmed; client-supplied `X-Cust-Id` header is sanitized by Gateway filter and returns `200 OK`.

---

## Step Outcomes & Seed Journeys
* **E2E-10 (Async Audit & DLQ Replay Probes):**
  * Retested IDOR boundary on `GET /api/v1/ledger/audit/{id}`.
  * Verified that customer ownership validation is enforced on audit log lookups.
  * Legitimate transaction owners receive full mutation audit details.
  * Third-party customers receive RFC 7807 `403 Access Denied`.
  * Anonymous callers receive `401 Unauthorized`.

---

## Attack Areas
* **Area 3 (Authorization & IDOR):** Retest pass for `F-007`. Access control and tenant boundaries verified intact across audit inspection routes.

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
| None | - | - | No new findings in run-02. All previous findings (`F-001` through `F-007`) verified and passing retests. |

