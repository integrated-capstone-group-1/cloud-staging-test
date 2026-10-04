# System Contracts & API Specifications

---

## 1. Authoritative Database Schemas & DDL Deltas

The baseline schemas are established in the repository at:
* Oracle XE: [`sql/oracle/01_oracle_main_db.sql`](file:///c:/Users/RBS83790/OneDrive%20-%20EastWest%20Banking%20Corporation/Documents/training/full-stack/capstone-project/capstone-banking-system/sql/oracle/01_oracle_main_db.sql)
* PostgreSQL: [`sql/postgres/01_postgres_audit_db.sql`](file:///c:/Users/RBS83790/OneDrive%20-%20EastWest%20Banking%20Corporation/Documents/training/full-stack/capstone-project/capstone-banking-system/sql/postgres/01_postgres_audit_db.sql)

### 1.1 Oracle XE Deltas (`03_oracle_deltas.sql`)
The following additive tables and columns extend the existing Oracle database for Cards, Amount Holds, and Pockets:

```sql
ALTER SESSION SET CONTAINER = XEPDB1;
ALTER SESSION SET CURRENT_SCHEMA = LEDGER_APP;

-- 1. Extend ACCOUNT_MASTER with primary pocket flag
ALTER TABLE account_master ADD (
    is_primary CHAR(1) DEFAULT 'N' NOT NULL,
    CONSTRAINT ck_account_master_is_primary CHECK (is_primary IN ('Y', 'N'))
);

-- 2. CARD_MASTER (Virtual Cards linked to Customer & Primary Account)
CREATE TABLE card_master (
    card_id            VARCHAR2(36)   NOT NULL,
    customer_id        VARCHAR2(36)   NOT NULL,
    account_id         VARCHAR2(36)   NOT NULL,
    card_token         VARCHAR2(64)   NOT NULL,
    masked_pan         VARCHAR2(19)   NOT NULL, -- e.g. 4532-XXXX-XXXX-8821
    expiry_date        VARCHAR2(5)    NOT NULL, -- MM/YY
    cvv_hash           VARCHAR2(64)   NOT NULL,
    card_status        VARCHAR2(20)   DEFAULT 'ACTIVE' NOT NULL,
    ecom_enabled       NUMBER(1)      DEFAULT 1 NOT NULL,
    daily_limit        NUMBER(18,2)   DEFAULT 50000.00 NOT NULL,
    created_at         TIMESTAMP      DEFAULT SYSTIMESTAMP NOT NULL,
    created_by         VARCHAR2(50)   NOT NULL,
    updated_at         TIMESTAMP,
    updated_by         VARCHAR2(50),
    CONSTRAINT pk_card_master PRIMARY KEY (card_id),
    CONSTRAINT fk_card_master_customer FOREIGN KEY (customer_id)
        REFERENCES customer_master (customer_id),
    CONSTRAINT fk_card_master_account FOREIGN KEY (account_id)
        REFERENCES account_master (account_id),
    CONSTRAINT uq_card_master_token UNIQUE (card_token),
    CONSTRAINT ck_card_master_status CHECK (card_status IN ('ACTIVE','FROZEN','BLOCKED')),
    CONSTRAINT ck_card_master_ecom CHECK (ecom_enabled IN (0, 1))
);

CREATE INDEX ix_card_master_customer ON card_master (customer_id);

-- 3. AMOUNT_HOLD_MASTER (For Reservations & Pre-authorizations)
CREATE TABLE amount_hold_master (
    hold_id            VARCHAR2(36)   NOT NULL,
    account_id         VARCHAR2(36)   NOT NULL,
    txn_id             VARCHAR2(36),
    hold_amount        NUMBER(18,4)   NOT NULL,
    reason             VARCHAR2(100)  NOT NULL,
    status             VARCHAR2(20)   DEFAULT 'ACTIVE' NOT NULL,
    expires_at         TIMESTAMP      NOT NULL,
    created_at         TIMESTAMP      DEFAULT SYSTIMESTAMP NOT NULL,
    created_by         VARCHAR2(50)   NOT NULL,
    updated_at         TIMESTAMP,
    updated_by         VARCHAR2(50),
    CONSTRAINT pk_amount_hold_master PRIMARY KEY (hold_id),
    CONSTRAINT fk_amount_hold_account FOREIGN KEY (account_id)
        REFERENCES account_master (account_id),
    CONSTRAINT ck_amount_hold_status CHECK (status IN ('ACTIVE','RELEASED','EXECUTED')),
    CONSTRAINT ck_amount_hold_positive CHECK (hold_amount > 0)
);

CREATE INDEX ix_amount_hold_account ON amount_hold_master (account_id, status);

-- 4. STANDING_INSTRUCTION (For EOD Recurring Batch Payments)
CREATE TABLE standing_instruction (
    instruction_id       VARCHAR2(36)   NOT NULL,
    customer_id          VARCHAR2(36)   NOT NULL,
    source_account_id    VARCHAR2(36)   NOT NULL,
    target_account_id    VARCHAR2(36)   NOT NULL,
    amount               NUMBER(18,4)   NOT NULL,
    currency_code        CHAR(3)        NOT NULL,
    frequency            VARCHAR2(20)   NOT NULL, -- 'DAILY', 'WEEKLY', 'MONTHLY'
    next_execution_date  DATE           NOT NULL,
    status               VARCHAR2(20)   DEFAULT 'ACTIVE' NOT NULL,
    created_at           TIMESTAMP      DEFAULT SYSTIMESTAMP NOT NULL,
    created_by           VARCHAR2(50)   NOT NULL,
    CONSTRAINT pk_standing_instruction PRIMARY KEY (instruction_id),
    CONSTRAINT fk_si_customer FOREIGN KEY (customer_id)
        REFERENCES customer_master (customer_id),
    CONSTRAINT fk_si_source_account FOREIGN KEY (source_account_id)
        REFERENCES account_master (account_id),
    CONSTRAINT ck_si_status CHECK (status IN ('ACTIVE','PAUSED','CANCELLED')),
    CONSTRAINT ck_si_amount CHECK (amount > 0)
);
```

### 1.2 PostgreSQL Deltas (`03_postgres_deltas.sql`)
The following additive tables extend PostgreSQL for Risk Screening audit:

```sql
-- RISK_SCREENING_AUDIT
CREATE TABLE risk_screening_audit (
    screening_id         UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    txn_id               VARCHAR(36)    NOT NULL,
    customer_id          VARCHAR(36)    NOT NULL,
    risk_score           NUMERIC(4,2)   NOT NULL, -- 0.00 to 1.00
    risk_level           VARCHAR(20)    NOT NULL, -- LOW, MEDIUM, HIGH, CRITICAL
    risk_decision        VARCHAR(30)    NOT NULL, -- ALLOW, STEP_UP_OTP, HOLD, REJECT
    triggered_rules      JSONB          NOT NULL,
    evaluation_latency_ms NUMERIC(8,2)  NOT NULL,
    evaluated_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT ck_risk_screening_score CHECK (risk_score >= 0.00 AND risk_score <= 1.00),
    CONSTRAINT ck_risk_screening_level CHECK (risk_level IN ('LOW','MEDIUM','HIGH','CRITICAL')),
    CONSTRAINT ck_risk_screening_decision CHECK (risk_decision IN ('ALLOW','STEP_UP_OTP','HOLD','REJECT'))
);

CREATE INDEX ix_risk_screening_txn ON risk_screening_audit (txn_id);
CREATE INDEX ix_risk_screening_cust ON risk_screening_audit (customer_id);

-- Append-only trigger for risk screening audit
CREATE TRIGGER trg_risk_screening_audit_append_only
    BEFORE UPDATE OR DELETE ON risk_screening_audit
    FOR EACH ROW EXECUTE FUNCTION fn_ledger_mutation_audit_append_only();
```

---

## 2. Internal Core Banking API: Atomic Settlement

**Owner:** `accounts-service`  
**Consumer:** `transaction-service`  
**Purpose:** Solves **B-01**. Executes multiple debit and credit legs (including multi-pocket smart funding and internal Bank FX balancing) in **one atomic database transaction** with deterministic ascending row locks.

### `POST /internal/core/settlement`
* **Headers:** `Content-Type: application/json`
* **Request Body:**
```json
{
  "posting_reference_id": "TXN-9821-UUID",
  "txn_type": "TRANSFER",
  "legs": [
    {
      "account_id": "acct-juan-php-01",
      "direction": "DEBIT",
      "amount": "2517.2000",
      "currency": "PHP"
    },
    {
      "account_id": "bank-fx-php-01",
      "direction": "CREDIT",
      "amount": "2480.0000",
      "currency": "PHP"
    },
    {
      "account_id": "bank-fee-income-php-01",
      "direction": "CREDIT",
      "amount": "37.2000",
      "currency": "PHP"
    },
    {
      "account_id": "acct-maria-eur-01",
      "direction": "CREDIT",
      "amount": "40.0000",
      "currency": "EUR"
    }
  ]
}
```
* **Response (200 OK):**
```json
{
  "posting_reference_id": "TXN-9821-UUID",
  "core_reference_id": "FT2627800001",
  "status": "SETTLED",
  "settled_at": "2026-10-04T19:30:00.000Z"
}
```
* **Error Responses:**
  * `422 Unprocessable Entity`: `INSUFFICIENT_FUNDS`
    ```json
    {
      "error_code": "INSUFFICIENT_FUNDS",
      "message": "Account acct-juan-php-01 available balance 1000.0000 is insufficient for debit 2517.2000",
      "account_id": "acct-juan-php-01"
    }
    ```
  * `422 Unprocessable Entity`: `ACCOUNT_FROZEN`
  * `409 Conflict`: `DUPLICATE_POSTING_REFERENCE` (Returns already-settled response if identical reference is resent).

---

## 3. Public REST API Contracts (Channel & Gateway)

All client mutation requests require:
* `Authorization: Bearer <jwt>`
* `X-Idempotency-Key: <uuid>`

### 3.1 Payments & Smart Routing
#### `POST /api/v1/payments`
Processes a payment with exact match or smart multi-currency auto-conversion.

* **Request Body:**
```json
{
  "recipient_account_id": "acct-maria-eur-01",
  "amount": "50.0000",
  "currency": "EUR",
  "funding_pocket_id": "acct-juan-php-01",
  "quote_id": "q-981-uuid"
}
```
*(Note: `funding_pocket_id` and `quote_id` are optional if the payer has sufficient balance in the target currency `EUR`)*.

* **Response (200 OK):**
```json
{
  "transaction_id": "txn-5541-uuid",
  "core_reference_id": "FT2627800001",
  "status": "COMPLETED",
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
  "created_at": "2026-10-04T19:30:00.000Z"
}
```
* **Error Responses:**
  * `422 Unprocessable Entity`: `INSUFFICIENT_FUNDS`
    When funds are lacking and no funding pocket is provided:
    ```json
    {
      "error_code": "INSUFFICIENT_FUNDS",
      "message": "Target pocket EUR balance is insufficient",
      "target_currency": "EUR",
      "shortfall_amount": "40.0000",
      "funding_options": [
        {
          "funding_pocket_id": "acct-juan-php-01",
          "currency": "PHP",
          "available_balance": "50000.0000",
          "required_amount": "2517.2000",
          "fx_fee": "37.2000",
          "quote_id": "q-981-uuid",
          "expires_in_seconds": 30
        }
      ]
    }
    ```
  * `422 Unprocessable Entity`: `DECLINED_HIGH_RISK`
  * `503 Service Unavailable`: `SECURITY_CHECK_UNAVAILABLE` (Risk engine timeout)
  * `400 Bad Request`: `QUOTE_EXPIRED`
  * `409 Conflict`: `REQUEST_IN_PROGRESS` (Idempotency lock active)

---

### 3.2 FX Quotes
#### `GET /api/v1/fx/quote`
* **Query Parameters:** `?from=PHP&to=EUR&amount=40.0000`
* **Response (200 OK):**
```json
{
  "quote_id": "q-981-uuid",
  "source_currency": "PHP",
  "target_currency": "EUR",
  "target_amount": "40.0000",
  "required_source_amount": "2480.0000",
  "spot_rate": "62.00000000",
  "spread_percentage": "1.50",
  "fee_source_amount": "37.2000",
  "total_source_amount": "2517.2000",
  "valid_until": "2026-10-04T19:30:30.000Z",
  "expires_in_seconds": 30
}
```

---

### 3.3 Synchronous Java Risk Screening (Internal)
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
  "latency_ms": 14.5
}
```

---

## 4. Kafka Event Schemas

### 4.1 Topic: `transaction.events`
Published by `OutboxMasterRelayService` from `outbox_master`.

```json
{
  "event_id": "evt-7712-uuid",
  "aggregate_type": "TRANSACTION",
  "aggregate_id": "txn-5541-uuid",
  "event_type": "payment.completed",
  "timestamp": "2026-10-04T19:30:00.000Z",
  "payload": {
    "txn_id": "txn-5541-uuid",
    "core_reference_id": "FT2627800001",
    "customer_id": "cust-user-001",
    "legs": [
      {
        "account_id": "acct-juan-php-01",
        "direction": "DEBIT",
        "amount": "2517.2000",
        "currency": "PHP"
      },
      {
        "account_id": "bank-fx-php-01",
        "direction": "CREDIT",
        "amount": "2480.0000",
        "currency": "PHP"
      },
      {
        "account_id": "bank-fee-income-php-01",
        "direction": "CREDIT",
        "amount": "37.2000",
        "currency": "PHP"
      },
      {
        "account_id": "acct-maria-eur-01",
        "direction": "CREDIT",
        "amount": "40.0000",
        "currency": "EUR"
      }
    ]
  }
}
```

### 4.2 Topic: `dlq.failed.mutations`
Dead Letter Queue topic for unprocessable mutation messages. Retries are attempted 3 times with exponential backoff before landing here.

---

## 5. Enum & Status Mappings

| Domain Concept | Persisted DB Column & Value | JSON API Representation |
| :--- | :--- | :--- |
| **Account Status** | `account_master.account_status IN ('ACTIVE','FROZEN','CLOSED')` | `"ACTIVE"`, `"FROZEN"`, `"CLOSED"` |
| **Card Status** | `card_master.card_status IN ('ACTIVE','FROZEN','BLOCKED')` | `"ACTIVE"`, `"FROZEN"`, `"BLOCKED"` |
| **Transaction Status** | `transaction_master.txn_status IN ('PENDING','COMMITTED','ROLLED_BACK')` | `"PENDING"`, `"COMPLETED"`, `"FAILED"`, `"HELD_FOR_REVIEW"` |
| **Audit State** | `ledger_mutation_audit.audit_state IN ('PENDING','COMMITTED','ROLLED_BACK')` | N/A (Internal audit store) |
| **Risk Score / Level** | `risk_screening_audit.risk_level IN ('LOW','MEDIUM','HIGH','CRITICAL')` | `"LOW"`, `"MEDIUM"`, `"HIGH"` |
| **Risk Action** | `risk_screening_audit.risk_decision IN ('ALLOW','STEP_UP_OTP','HOLD','REJECT')` | `"ALLOW"`, `"STEP_UP_OTP"`, `"HOLD"`, `"REJECT"` |
