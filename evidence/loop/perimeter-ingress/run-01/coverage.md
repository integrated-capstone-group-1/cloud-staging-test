# Coverage Report: perimeter-ingress (run-01)

## Emphasis & Constants
* **Emphasis:** Track 4 / E2E-09 scan across edge perimeter ingress, route cloaking, header sanitization, security response headers, and rate limiting burst behavior.
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
* **Invariant baseline:** PASS (exit code 0; all I-1 through I-13 clean).

---

## Retest Results
* **`RETEST PASS F-001`**: `/api/v1/ledger/mutate` strictly returns `403 Forbidden`.
* **`RETEST PASS F-002`**: Payments with `funding_pocket_id` missing `quote_id` return `400 Bad Request` (`VALIDATION_FAILED`).
* **`RETEST PASS F-003`**: Payments with scale > 4 or scientific notation return `400 Bad Request` (`VALIDATION_FAILED`).
* **`RETEST PASS F-005`**: Cross-customer query on `GET /api/v1/payments/{id}` returns `403 Forbidden` (`ACCESS_DENIED`).

---

## Step Outcomes & Seed Journeys
* **E2E-09 (Gateway Route Isolation, Rate Limiting & Header Sanitization):**
  * **Steps 1 & 2 (Rate Limiter Burst):** Sent 40 rapid requests; all returned `200 OK` without triggering `429 RATE_LIMIT_EXCEEDED` (recorded as contract query).
  * **Steps 3 & 4 (Header Sanitization):** Sending client-supplied `X-Cust-Id` header causes the Gateway to crash with HTTP `500 Internal Server Error` breaking RFC 7807 (logged as **F-006**).
  * **Step 5 (Route Cloaking):** Verified that `/internal/**` routes return `404 Not Found` and direct mutation routes return `403 Forbidden`.
  * **Security Headers:** Verified Gateway injects standard security headers (`X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `X-XSS-Protection`).

---

## Attack Areas
* **Area 3 (Authorization & Header Sanitization):** Gateway fails to gracefully strip client-supplied `X-Cust-Id` headers, crashing with HTTP 500 on all protected routes (F-006).
* **Area 6 (Business Logic & Boundary Protection):** Internal route cloaking verified across 10 internal and direct-mutation paths.

---

## Contract Queries
1. **`[VERIFY H-16]` Rate Limiting Threshold**: 40 rapid burst requests to `/api/v1/payments/history` completed with `200 OK` without triggering `429 RATE_LIMIT_EXCEEDED` (contract specifies quota 15/s, burst 30/s).

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
| [F-006](findings/F-006.md) | S2 | F1, F4 | HTTP 500 Internal Server Error and Gateway crash on client-supplied `X-Cust-Id` header |

