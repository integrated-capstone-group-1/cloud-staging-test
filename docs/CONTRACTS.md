# System Contracts & API Specifications

---

## 1. Global Conventions, Error Catalog & State Transitions

### 1.1 Global Conventions
* **Edge Entrypoint:** `http://localhost:8080` (Spring Cloud Gateway)
* **Authentication:** `Authorization: Bearer <jwt>`
* **Idempotency:** `X-Idempotency-Key: <uuid>` (Scoped to authenticated customer: `UNIQUE (customer_id, idempotency_key)` in Oracle; 24h Redis TTL).
* **Monetary Representation:** Strings formatted as fixed-point decimals (`"50.0000"`) with scale 4 (`NUMBER(18,4)` in DB) to eliminate float precision loss.
* **Timestamps:** ISO-8601 UTC string (`"2026-10-05T11:15:00.000Z"`).
* **Package Tree Ground Truth:** All microservices adhere to `com.capstone.*` (e.g. `com.capstone.transaction.*`, `com.capstone.accounts.*`, `com.capstone.risk.*`), except `reconciliation-service` which adheres to `com.bank.reconciliation.*`.
* **Legacy Adapter Route:** Existing `POST /api/v1/ledger/mutate` is preserved as an internal adapter wrapping CBS settlement, mapping single-leg deposits and withdrawals to the bank cash clearing counterparty account (`bank-cash-clearing-php-01`), guaranteeing legs always balance to zero.
* **Network Boundary Protection (B-06):** Core settlement (`/internal/core/settlement/**`), account direct balance mutations (`/api/accounts/*/credit`, `/api/accounts/*/debit`, `/api/v1/accounts/*/credit`, `/api/v1/accounts/*/debit`), and risk internal routes (`/internal/risk/**`) are strictly blocked at the API Gateway with `403 Forbidden`. Furthermore, within `accounts-service`, balance manipulation endpoints require `ROLE_INTERNAL`.

### 1.2 Error Catalog (RFC 7807 Format)

| HTTP Status | Error Code | Description & Trigger |
| :--- | :--- | :--- |
| `400 Bad Request` | `VALIDATION_FAILED` | Malformed body, missing required fields, or non-numeric amount. |
| `400 Bad Request` | `QUOTE_EXPIRED` | The 30-second locked FX quote expired before payment confirmation. |
| `401 Unauthorized` | `INVALID_CREDENTIALS` | Invalid username/password or invalid/expired JWT. |
| `401 Unauthorized` | `TOKEN_REVOKED` | Bearer JWT exists in Redis blacklist (`token:<jwt_hash>`). |
| `403 Forbidden` | `ACCESS_DENIED` | Customer attempted to access `/admin/**` or internal CBS endpoints. |
| `409 Conflict` | `IDEMPOTENCY_IN_PROGRESS`| Duplicate `X-Idempotency-Key` currently processing (`SETNX` returned 0). |
| `409 Conflict` | `STATE_CONFLICT` / `TXN_NOT_CANCELLABLE` | CAS update failed (e.g. attempting to cancel a transaction already in `PROCESSING`). |
| `422 Unprocessable` | `INSUFFICIENT_FUNDS` | Pocket available balance cannot cover payment; carries `funding_options`. |
| `422 Unprocessable` | `DECLINED_HIGH_RISK` | Synchronous risk score $> 0.85$ (or transfer placed under admin hold). |
| `422 Unprocessable` | `ACCOUNT_FROZEN` | Account status is `FROZEN` (admin freeze) or `CLOSED`. |
| `422 Unprocessable` | `ECOM_TRANSACTIONS_DISABLED` | Virtual card e-commerce toggle is disabled. |
| `422 Unprocessable` | `FX_RATE_STALE` | Cached exchange rate is older than 24 hours. |
| `422 Unprocessable` | `REVERSAL_INSUFFICIENT_FUNDS` | Recipient pocket has already spent funds, causing deficit on reversal. |
| `422 Unprocessable` | `TXN_ALREADY_REVERSED` | Transaction has already been reversed. |
| `429 Too Many Req` | `RATE_LIMIT_EXCEEDED` | Redis token-bucket quota exhausted (15/s, burst 30/s). |
| `503 Unavailable` | `SECURITY_CHECK_UNAVAILABLE`| Risk engine timed out ($> 200\text{ms}$) or container offline (Fail-closed). |
| `503 Unavailable` | `SERVICE_DEGRADED` | Circuit breaker tripped open during database latency spike. |

### 1.3 State Transition Table (7-Status Lifecycle + Reversal Marker)

| From Status | Permitted To Status | Trigger / Action | Enforcing Rule |
| :--- | :--- | :--- | :--- |
| `INITIATED` | `AUTHORIZED` | Pre-flight checks & Risk evaluation complete (Score $\le 0.85$). | `transaction-service` |
| `INITIATED` | `RESERVED` | Risk Score $> 0.85 \implies$ placed in Admin Held Review queue (`RISK_HOLD`). | `transaction-service` |
| `INITIATED` | `FAILED` | Validation error, risk engine timeout, or account frozen. | `transaction-service` |
| `INITIATED` | `CANCELLED` | Customer issues cancel before authorization finishes. | CAS on `txn_status = 'INITIATED'` |
| `AUTHORIZED`| `PROCESSING` | Worker claims transaction to initiate CBS settlement. | CAS on `txn_status = 'AUTHORIZED'` |
| `AUTHORIZED`| `CANCELLED` | Customer issues cancel before worker claim. | CAS on `txn_status = 'AUTHORIZED'` |
| `PROCESSING`| `POSTED` | CBS returns `200 OK` (Core reference `FT...` generated). | `transaction-service` |
| `PROCESSING`| `FAILED` | CBS settlement returns `422 INSUFFICIENT_FUNDS` or DB error. | `transaction-service` |
| `RESERVED`  | `AUTHORIZED` | Admin approves held transfer in review queue. | Admin API action |
| `RESERVED`  | `FAILED` | Admin rejects held transfer in review queue. | Admin API action |
| `POSTED`    | `POSTED` (Reversed) | Administrative reversal creates compensating transaction (`reversal_of_txn_id`). | Admin Reversal API |

---

## 2. Public REST API Endpoints (Gateway Exposed)

### 2.1 Payments & Smart Routing
#### `POST /api/v1/payments`
* **Headers:** `Authorization: Bearer <jwt>`, `X-Idempotency-Key: <uuid>`
* **Request Body (Direct or Shortfall Funding):**
```json
{
  "recipient_account_id": "acct-maria-eur-01",
  "amount": "50.0000",
  "currency": "EUR",
  "funding_pocket_id": "acct-juan-php-01",
  "quote_id": "q-981-uuid"
}
```
* **Response (200 OK - Direct or Shortfall Success):**
```json
{
  "transaction_id": "txn-5541-uuid",
  "core_reference_id": "FT2627800001",
  "status": "POSTED",
  "paid_amount": "50.0000",
  "paid_currency": "EUR",
  "funding_breakdown": {
    "direct_debited_amount": "10.0000",
    "direct_debited_currency": "EUR",
    "converted_shortfall_amount": "40.0000",
    "funding_debited_amount": "2480.0000",
    "funding_debited_currency": "PHP",
    "fx_fee_amount": "37.2000",
    "fx_rate_applied": "62.00000000"
  },
  "created_at": "2026-10-05T11:15:00.000Z"
}
```

* **Response (202 Accepted - Step-up OTP Required, Risk Score 0.60–0.85):**
```json
{
  "transaction_id": "txn-5541-uuid",
  "status": "INITIATED",
  "action_required": "STEP_UP_OTP",
  "message": "Step-up OTP verification required for elevated transaction risk",
  "expires_in_seconds": 300
}
```

* **Response (202 Accepted - Risk Hold Placed, Risk Score > 0.85):**
```json
{
  "transaction_id": "txn-5541-uuid",
  "status": "RESERVED",
  "hold_id": "hold-8812-uuid",
  "message": "Transaction held for administrative security review",
  "review_queue": "ADMIN_FRAUD_REVIEW"
}
```

* **Response (422 Unprocessable Entity - Insufficient Funds with Smart Funding Options):**
```json
{
  "error_code": "INSUFFICIENT_FUNDS",
  "message": "Primary pocket balance is insufficient for this payment",
  "shortfall": {
    "target_amount": "50.0000",
    "target_currency": "EUR",
    "available_in_target_pocket": "10.0000",
    "deficit_amount": "40.0000"
  },
  "funding_options": [
    {
      "pocket_id": "acct-juan-php-01",
      "currency": "PHP",
      "available_balance": "50000.0000",
      "estimated_required_amount": "2517.2000",
      "suggested_quote_id": "q-981-uuid"
    }
  ]
}
```

* **Response (422 Unprocessable Entity - Stale FX Rate):**
```json
{
  "error_code": "FX_RATE_STALE",
  "message": "Exchange rate cache is older than 24 hours. Conversions temporarily suspended."
}
```

#### `POST /api/v1/payments/{id}/cancel`
* **Headers:** `Authorization: Bearer <jwt>`
* **Response (200 OK):** `{"transaction_id": "txn-5541-uuid", "status": "CANCELLED"}`
* **Response (409 Conflict):** `{"error_code": "TXN_NOT_CANCELLABLE", "message": "Transaction already in PROCESSING and cannot be cancelled"}`

#### `GET /api/v1/payments/{id}`
* **Response (200 OK):** Returns live transaction state, core reference, and stage.

#### `GET /api/v1/payments/history`
* **Response (200 OK):** Returns paginated transaction history for authenticated customer.

---

### 2.2 ForEx Quotes & In-App Conversions
#### `GET /api/v1/fx/quote`
* **Query Parameters:** `?from=PHP&to=EUR&amount=40.0000`
* **Response (200 OK):**
```json
{
  "quote_id": "q-981-uuid",
  "source_currency": "PHP",
  "target_currency": "EUR",
  "target_amount": "40.0000",
  "spot_rate": "62.00000000",
  "spread_percentage": "1.50",
  "fee_source_amount": "37.2000",
  "total_source_amount": "2517.2000",
  "valid_until": "2026-10-05T11:15:30.000Z",
  "expires_in_seconds": 30
}
```

#### `POST /api/v1/fx/convert`
* **Request Body:** `{"quote_id": "q-981-uuid", "source_pocket_id": "acct-juan-php-01", "target_pocket_id": "acct-juan-eur-01"}`
* **Response (200 OK):** Returns converted balance confirmations.

---

### 2.3 Virtual Cards, Recipient Lookups & Customer Controls
#### `GET /api/v1/cards`
* **Response (200 OK):** Returns virtual cards with masked PAN (`4532-XXXX-XXXX-8821`), expiry, `card_status`, `ecom_enabled`, and `daily_limit`. *(Note: CVV is never returned or stored in plaintext)*.

#### `PUT /api/v1/cards/{id}/ecom`
* **Request Body:** `{"ecom_enabled": 1}`
* **Response (200 OK):** `{"card_id": "card-juan-01", "ecom_enabled": 1}`

#### `PUT /api/v1/cards/{id}/status`
* **Request Body:** `{"status": "FROZEN"}`
* **Response (200 OK):** `{"card_id": "card-juan-01", "card_status": "FROZEN"}`

#### `GET /api/v1/recipients/lookup`
* **Query Parameters:** `?account_id=acct-maria-eur-01`
* **Response (200 OK):** `{"account_id": "acct-maria-eur-01", "masked_name": "Maria S.", "currency": "EUR"}`

#### `PUT /api/v1/accounts/{id}/lock`
* **Request Body:** `{"reason": "Customer requested temporary self-lock"}`
* **Response (200 OK):** `{"account_id": "acct-juan-php-01", "account_status": "FROZEN"}`

#### `PUT /api/v1/accounts/{id}/unlock`
* **Request Body:** `{"otp_code": "849201"}`
* **Response (200 OK):** `{"account_id": "acct-juan-php-01", "account_status": "ACTIVE"}`

---

### 2.4 Authentication, OTP & Session Management
#### `POST /api/auth/otp/request`
* **Request Body:** `{"transaction_id": "txn-5541-uuid"}`
* **Response (200 OK):** `{"message": "OTP dispatched to registered email", "expires_in_seconds": 300}`

#### `POST /api/auth/otp/verify`
* **Request Body:** `{"transaction_id": "txn-5541-uuid", "otp_code": "849201"}`
* **Response (200 OK):** `{"status": "AUTHORIZED", "verified": true}`

#### `POST /api/auth/refresh`
* **Request Body:** `{"refresh_token": "rt-9812-uuid"}`
* **Response (200 OK):** Returns new 15-minute access JWT and rotated refresh token.

---

### 2.5 Admin Operations & Administrative Reversals
#### `POST /api/v1/admin/transfers/{id}/reverse` (Alias: `POST /api/v1/admin/transactions/{id}/reverse`)
* **Headers:** `Authorization: Bearer <jwt_admin>`
* **Request Body:**
```json
{
  "reason": "Administrative dispute adjustment per compliance memo 2026-09"
}
```
* **State & Ownership Rules (B-03):**
  * Original transaction (`txn_id`) retains its terminal status `POSTED`.
  * Creates a new compensating transaction row with `txn_type = 'REVERSAL'`, `reversal_of_txn_id = <original_txn_id>`.
  * Guaranteed uniqueness: `UNIQUE (reversal_of_txn_id)` prevents double-reversals.
  * Checks recipient pocket balance prior to core settlement: if recipient balance cannot cover reversal, returns `422 REVERSAL_INSUFFICIENT_FUNDS`.
  * Settlement is executed via CBS with inverted legs.
* **Response (200 OK):**
```json
{
  "reversal_txn_id": "txn-rev-9011-uuid",
  "original_txn_id": "txn-5541-uuid",
  "core_reference_id": "FT2627800099",
  "status": "POSTED",
  "reversed_amount": "50.0000",
  "reversed_currency": "EUR",
  "settled_at": "2026-10-05T11:20:00.000Z"
}
```
* **Response (422 Unprocessable):** `{"error_code": "TXN_ALREADY_REVERSED", "message": "Transaction txn-5541-uuid has already been reversed by txn-rev-9011-uuid"}`
* **Response (422 Unprocessable):** `{"error_code": "REVERSAL_INSUFFICIENT_FUNDS", "message": "Recipient pocket balance is insufficient to refund reversed funds"}`

#### `PUT /api/v1/admin/accounts/{id}/freeze`
* **Request Body:** `{"reason": "AML Investigation Hold"}`
* **Response (200 OK):** `{"account_id": "acct-maria-eur-01", "account_status": "FROZEN"}`

#### `GET /api/v1/admin/held-transfers`
* **Response (200 OK):** Returns list of transactions in `RESERVED` status awaiting compliance decision.

#### `POST /api/v1/admin/held-transfers/{id}/review`
* **Request Body:** `{"decision": "APPROVE"}` (or `"REJECT"`)
* **Response (200 OK):** Releases hold and progresses status to `AUTHORIZED` (if approved) or `FAILED` (if rejected).

---

## 3. Internal Service-to-Service APIs (Network Blocked at Gateway)

### 3.1 Mock Core Banking System Settlement (CBS)
**Owner:** `accounts-service` (`com.capstone.accounts.*`)

#### `POST /internal/core/settlement`
**Leg-Balancing Invariant (B-03):** Every settlement request must balance to zero per currency ($\sum \text{Credits} = \sum \text{Debits}$).

* **6-Leg Cross-Currency Shortfall Settlement Request Body:**
```json
{
  "posting_reference_id": "txn-5541-uuid",
  "txn_type": "TRANSFER",
  "capture_hold_id": "hold-8812-uuid",
  "legs": [
    {
      "account_id": "acct-juan-eur-01",
      "direction": "DEBIT",
      "amount": "10.0000",
      "currency": "EUR",
      "leg_no": 1
    },
    {
      "account_id": "acct-juan-php-01",
      "direction": "DEBIT",
      "amount": "2517.2000",
      "currency": "PHP",
      "leg_no": 2
    },
    {
      "account_id": "bank-fx-php-01",
      "direction": "CREDIT",
      "amount": "2480.0000",
      "currency": "PHP",
      "leg_no": 3
    },
    {
      "account_id": "bank-fee-income-php-01",
      "direction": "CREDIT",
      "amount": "37.2000",
      "currency": "PHP",
      "leg_no": 4
    },
    {
      "account_id": "bank-fx-eur-01",
      "direction": "DEBIT",
      "amount": "40.0000",
      "currency": "EUR",
      "leg_no": 5
    },
    {
      "account_id": "acct-maria-eur-01",
      "direction": "CREDIT",
      "amount": "50.0000",
      "currency": "EUR",
      "leg_no": 6
    }
  ]
}
```
* **Response (200 OK):**
```json
{
  "posting_reference_id": "txn-5541-uuid",
  "core_reference_id": "FT2627800001",
  "status": "SETTLED",
  "settled_at": "2026-10-05T11:15:02.000Z"
}
```

* **Idempotency & Deduplication Invariant (B-04):**
  * When `posting_reference_id` is received, `accounts-service` calculates SHA-256 of request payload (`request_hash`).
  * If `posting_reference_id` exists in `posting_reference` table:
    * If `request_hash` matches and status is `SETTLED`, returns cached `core_reference_id` immediately with `200 OK`.
    * If `request_hash` differs, rejects with `409 Conflict`.
    * If status is `ABORTED` (written by sweeper tombstone), rejects execution with `409 Conflict`.

#### `GET /internal/core/settlement/{posting_reference_id}`
Inquiry endpoint for the `StuckTransactionSweeper`.
* If settled: Returns `{"posting_reference_id": "txn-5541-uuid", "core_reference_id": "FT2627800001", "status": "SETTLED"}`.
* If not found (404 Not Found): Sweeper writes an abort tombstone (`POST /internal/core/settlement/abort` or inserts `status = 'ABORTED'` in `posting_reference`) before marking `transaction_master` as `FAILED`, eliminating any race condition with slow in-flight settlement requests.

#### `POST /internal/accounts/{id}/holds`
* **Request Body:** `{"amount": "50.0000", "currency": "EUR", "hold_type": "RISK", "expires_in_hours": 24}`
* **Response (200 OK):** `{"hold_id": "hold-8812-uuid", "status": "ACTIVE", "held_amount": "50.0000"}`

#### `DELETE /internal/accounts/{id}/holds/{holdId}`
* **Response (200 OK):** `{"hold_id": "hold-8812-uuid", "status": "RELEASED"}`

---

### 3.2 Java Synchronous Risk Screening Engine
**Owner:** `risk-engine` (`com.capstone.risk.*`)

#### `POST /internal/risk/evaluate`
* **Request Body:**
```json
{
  "transaction_id": "txn-5541-uuid",
  "customer_id": "cust-user-001",
  "amount": "2517.2000",
  "currency": "PHP",
  "ip_address": "192.168.1.50",
  "device_id": "dev-fingerprint-99"
}
```
* **Response (200 OK):**
```json
{
  "score": 0.15,
  "level": "LOW",
  "decision": "ALLOW",
  "triggered_rules": [],
  "evaluation_latency_ms": 14.5
}
```

---

## 4. Kafka Topics & Event Schemas

### 4.1 Topic: `transaction.events`
Published by `OutboxMasterRelayService` from `outbox_master`. Consumed by `AuditLedgerConsumer` to append to PostgreSQL `ledger_mutation_audit`.

```json
{
  "event_id": "evt-7712-uuid",
  "aggregate_type": "TRANSACTION",
  "aggregate_id": "txn-5541-uuid",
  "event_type": "payment.completed",
  "timestamp": "2026-10-05T11:15:02.000Z",
  "payload": {
    "txn_id": "txn-5541-uuid",
    "core_reference_id": "FT2627800001",
    "customer_id": "cust-user-001",
    "legs": [
      {
        "leg_no": 1,
        "account_id": "acct-juan-eur-01",
        "direction": "DEBIT",
        "amount": "10.0000",
        "currency": "EUR"
      },
      {
        "leg_no": 2,
        "account_id": "acct-juan-php-01",
        "direction": "DEBIT",
        "amount": "2517.2000",
        "currency": "PHP"
      },
      {
        "leg_no": 3,
        "account_id": "bank-fx-php-01",
        "direction": "CREDIT",
        "amount": "2480.0000",
        "currency": "PHP"
      },
      {
        "leg_no": 4,
        "account_id": "bank-fee-income-php-01",
        "direction": "CREDIT",
        "amount": "37.2000",
        "currency": "PHP"
      },
      {
        "leg_no": 5,
        "account_id": "bank-fx-eur-01",
        "direction": "DEBIT",
        "amount": "40.0000",
        "currency": "EUR"
      },
      {
        "leg_no": 6,
        "account_id": "acct-maria-eur-01",
        "direction": "CREDIT",
        "amount": "50.0000",
        "currency": "EUR"
      }
    ]
  }
}
```

* **Idempotent Audit Consumer Invariant (B-04):**
  `AuditLedgerConsumer` generates `mutation_uuid` using UUIDv5 based on `(txn_id + "-leg-" + leg_no)`. This matches `UNIQUE (txn_id, leg_no)` in PostgreSQL, preventing duplicate rows on Kafka redelivery.

---

### 2.6 Transaction History, Suspicious Reports & Statement Downloads (T-001 / DIG-18, DIG-91, DIG-92)

#### `GET /v1/transactions/{id}` (DIG-18)
* **Headers:** `Authorization: Bearer <jwt>`
* **Response (200 OK):**
```json
{
  "transaction_id": "txn-5541-uuid",
  "status": "COMMITTED",
  "stage": "COMPLETED",
  "txn_type": "TRANSFER",
  "amount": 1500.0000,
  "currency": "PHP",
  "debit_account_id": "acct-juan-php-01",
  "credit_account_id": "acct-maria-php-02",
  "customer_id": "cust-juan-01",
  "core_reference_id": "CORE-REF-9912",
  "audit_ref": "/api/v1/ledger/audit/txn-5541-uuid",
  "audit_trail_url": "/api/v1/ledger/audit/txn-5541-uuid",
  "stages": [
    {
      "stage": "INITIATED",
      "status": "COMPLETED",
      "timestamp": "2026-10-07T10:00:00",
      "description": "Transaction created and request validated"
    },
    {
      "stage": "AUTHORIZED",
      "status": "COMPLETED",
      "timestamp": "2026-10-07T10:00:01",
      "description": "Risk screening and balance check approved"
    },
    {
      "stage": "PROCESSING",
      "status": "COMPLETED",
      "timestamp": "2026-10-07T10:00:02",
      "description": "Atomic balance mutation executed in core ledger"
    },
    {
      "stage": "SETTLED",
      "status": "COMPLETED",
      "timestamp": "2026-10-07T10:00:02",
      "description": "Transaction posted and double-entry mutation committed"
    }
  ]
}
```

#### `GET /v1/transactions?status=&from=&to=&page=&size=` (DIG-18)
* **Headers:** `Authorization: Bearer <jwt>`
* **Response (200 OK):** Paginated `PageResponse` containing transaction summaries with simplified stages and audit trail links.

#### `POST /v1/reports/suspicious` (DIG-91)
* **Headers:** `Authorization: Bearer <jwt>`, `Content-Type: application/json`
* **Request Body:**
```json
{
  "transaction_id": "txn-5541-uuid",
  "reason": "UNAUTHORIZED_TRANSACTION",
  "notes": "Did not recognize merchant charge",
  "customer_id": "cust-juan-01"
}
```
* **Response (201 Created):**
```json
{
  "case_id": "CASE-4E3ABA4A",
  "transaction_id": "txn-5541-uuid",
  "customer_id": "cust-juan-01",
  "status": "OPEN",
  "reason": "UNAUTHORIZED_TRANSACTION",
  "customer_notified": true,
  "message": "Dispute registered successfully. Admin fraud investigation case created."
}
```

#### `GET /v1/statements?from=YYYY-MM-DD&to=YYYY-MM-DD` (DIG-92)
* **Headers:** `Authorization: Bearer <jwt>`
* **Response (200 OK):**
  * `Content-Type: application/pdf`
  * `Content-Disposition: attachment; filename="statement-2026-09-07-2026-10-07.pdf"`
  * Binary PDF 1.4 document stream containing bank header, account metadata, balance summary cards, zebra-striped transaction ledger, and regulatory footer.
* **Reusable Generator Endpoints:**
  * `GET /v1/statements/monthly?year=2026&month=10`
  * `GET /v1/statements/eod?date=2026-10-07`

