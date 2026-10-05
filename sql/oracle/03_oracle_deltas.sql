-- =====================================================================
-- 03_ORACLE_DELTAS.SQL (Docs Packaging)
-- Additive DDL Deltas for Capstone Retail Ledger (v2.3)
-- Resolves Reviewer Blockers: B-01, B-02, B-03, B-04, B-06
-- =====================================================================

ALTER SESSION SET CONTAINER = XEPDB1;
ALTER SESSION SET CURRENT_SCHEMA = LEDGER_APP;

-- 1. Relax restrictive baseline check constraints on existing tables
ALTER TABLE account_master DROP CONSTRAINT ck_account_master_acct_type;
ALTER TABLE account_master ADD CONSTRAINT ck_account_master_acct_type 
    CHECK (account_type IN ('SAVINGS','CHECKING','WALLET','INTERNAL_BANK_FX'));

-- Update column default for txn_status before modifying constraint
ALTER TABLE transaction_master MODIFY (txn_status DEFAULT 'INITIATED');

-- Support legacy COMMITTED/PENDING plus the full 7-status lifecycle + REVERSED
ALTER TABLE transaction_master DROP CONSTRAINT ck_txn_status;
ALTER TABLE transaction_master ADD CONSTRAINT ck_txn_status 
    CHECK (txn_status IN ('PENDING','COMMITTED','ROLLED_BACK','INITIATED','AUTHORIZED','PROCESSING','POSTED','RESERVED','CANCELLED','FAILED','REVERSED'));

ALTER TABLE transaction_master DROP CONSTRAINT ck_txn_type;
ALTER TABLE transaction_master ADD CONSTRAINT ck_txn_type 
    CHECK (txn_type IN ('WITHDRAWAL','DEPOSIT','TRANSFER','REVERSAL'));

-- Relax debit/credit constraint to allow REVERSAL (both debit and credit accounts present)
ALTER TABLE transaction_master DROP CONSTRAINT ck_txn_debit_credit_by_type;
ALTER TABLE transaction_master ADD CONSTRAINT ck_txn_debit_credit_by_type CHECK (
    (txn_type = 'WITHDRAWAL' AND debit_account_id IS NOT NULL AND credit_account_id IS NULL)
    OR (txn_type = 'DEPOSIT' AND credit_account_id IS NOT NULL AND debit_account_id IS NULL)
    OR (txn_type = 'TRANSFER' AND debit_account_id IS NOT NULL AND credit_account_id IS NOT NULL)
    OR (txn_type = 'REVERSAL' AND debit_account_id IS NOT NULL AND credit_account_id IS NOT NULL)
);

-- 2. Extend account_master with primary pocket flag and held_amount
ALTER TABLE account_master ADD (
    is_primary   CHAR(1)       DEFAULT 'N' NOT NULL,
    held_amount  NUMBER(18,4)  DEFAULT 0   NOT NULL,
    CONSTRAINT ck_account_master_is_primary CHECK (is_primary IN ('Y', 'N')),
    CONSTRAINT ck_account_master_held_nonneg CHECK (held_amount >= 0)
);

-- 3. Extend transaction_master for 7-status lifecycle, CAS claim, and reversals
ALTER TABLE transaction_master ADD (
    customer_id         VARCHAR2(36),
    stage               VARCHAR2(30)   DEFAULT 'INITIATED' NOT NULL,
    core_reference_id   VARCHAR2(50),
    cancel_reason       VARCHAR2(100),
    reversal_of_txn_id  VARCHAR2(36),
    idempotency_key     VARCHAR2(64)
);

-- Backfill customer_id from debit_account_id or credit_account_id for seeded/existing rows
UPDATE transaction_master tm
SET customer_id = (
    SELECT customer_id FROM account_master am 
    WHERE am.account_id = COALESCE(tm.debit_account_id, tm.credit_account_id)
)
WHERE customer_id IS NULL;

-- Customer-scoped idempotency unique constraint (unique only when idempotency_key is present)
CREATE UNIQUE INDEX uq_txn_cust_idempotency ON transaction_master (customer_id, idempotency_key);
-- Unique constraint to prevent double-reversal of the same transaction
CREATE UNIQUE INDEX uq_txn_reversal_ref ON transaction_master (reversal_of_txn_id);

-- 4. ACCOUNT_HOLD (Amount Holds Engine - Owned by accounts-service)
CREATE TABLE account_hold (
    hold_id            VARCHAR2(36)   NOT NULL,
    account_id         VARCHAR2(36)   NOT NULL,
    txn_id             VARCHAR2(36),
    amount             NUMBER(18,4)   NOT NULL,
    currency_code      CHAR(3)        NOT NULL,
    hold_type          VARCHAR2(20)   NOT NULL, -- 'RISK', 'SI', 'PREAUTH'
    status             VARCHAR2(20)   DEFAULT 'ACTIVE' NOT NULL,
    expires_at         TIMESTAMP      NOT NULL,
    created_at         TIMESTAMP      DEFAULT SYSTIMESTAMP NOT NULL,
    created_by         VARCHAR2(50)   NOT NULL,
    updated_at         TIMESTAMP,
    updated_by         VARCHAR2(50),
    CONSTRAINT pk_account_hold PRIMARY KEY (hold_id),
    CONSTRAINT fk_account_hold_account FOREIGN KEY (account_id)
        REFERENCES account_master (account_id),
    CONSTRAINT ck_account_hold_status CHECK (status IN ('ACTIVE','RELEASED','CAPTURED','EXPIRED')),
    CONSTRAINT ck_account_hold_type CHECK (hold_type IN ('RISK','SI','PREAUTH')),
    CONSTRAINT ck_account_hold_amount CHECK (amount > 0)
);

CREATE INDEX ix_account_hold_account_status ON account_hold (account_id, status);

-- 5. POSTING_REFERENCE (Core Settlement Deduplication Store - Owned by accounts-service)
CREATE TABLE posting_reference (
    posting_reference_id VARCHAR2(36)   NOT NULL,
    core_reference_id    VARCHAR2(50)   NOT NULL,
    request_hash         VARCHAR2(64),
    txn_type             VARCHAR2(30)   NOT NULL,
    status               VARCHAR2(20)   DEFAULT 'SETTLED' NOT NULL,
    settled_at           TIMESTAMP      DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT pk_posting_reference PRIMARY KEY (posting_reference_id),
    CONSTRAINT uq_posting_core_ref UNIQUE (core_reference_id),
    CONSTRAINT ck_posting_ref_status CHECK (status IN ('SETTLED','ABORTED'))
);

-- 6. CARD_MASTER (Virtual Cards - Owned by transaction-service; CVV is never persisted)
CREATE TABLE card_master (
    card_id            VARCHAR2(36)   NOT NULL,
    customer_id        VARCHAR2(36)   NOT NULL,
    account_id         VARCHAR2(36)   NOT NULL,
    card_token         VARCHAR2(64)   NOT NULL,
    masked_pan         VARCHAR2(19)   NOT NULL, -- e.g. 4532-XXXX-XXXX-8821
    expiry_date        VARCHAR2(5)    NOT NULL, -- MM/YY
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

-- 7. STANDING_INSTRUCTION (Recurring Batch Payments - Owned by transaction-service)
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
