-- =====================================================================
-- ORACLE XE 21c - MASTER OLTP DATABASE CONSOLIDATED DDL
-- Database Schemas: CBS, PAYMENT, FX (Unified under LEDGER_APP)
--
-- Aligned with System Architecture Specification (ARCHITECTURE.md):
--   Tier 4 Core Banking System (CBS), Payment Hub, and Forex Engine.
--
-- Tables (11 Master Tables per ARCHITECTURE.md Section 3 & 5.1):
--   CBS Schema (accounts-service):
--     1. customer_master      - Core customer KYC & CIF record
--     2. account_master       - Multi-currency pockets & live balances
--     3. card_master          - Virtual cards, tokens & spend limits
--     4. account_hold         - In-flight risk & administrative amount holds
--     5. ledger_entry         - Authoritative immutable double-entry journal
--     6. posting_reference    - Settlement deduplication barrier
--     7. outbox_master        - Authoritative core event outbox
--   PAYMENT Schema (transaction-service):
--     8. transaction_master   - 7-status payment saga & CAS lifecycle
--     9. standing_instruction - Auto-debit recurring batch instructions
--    10. admin_approval       - 4-eyes maker-checker authorization queue
--   FX Schema (forex-service):
--    11. fx_rate              - Hourly / daily cached treasury exchange rates
-- =====================================================================

ALTER SESSION SET CONTAINER = XEPDB1;
ALTER SESSION SET CURRENT_SCHEMA = LEDGER_APP;

-- ---------------------------------------------------------------------
-- 1. CUSTOMER_MASTER (CBS Schema)
-- ---------------------------------------------------------------------
CREATE TABLE customer_master (
    customer_id          VARCHAR2(36)   NOT NULL,
    first_name           VARCHAR2(100)  NOT NULL,
    last_name            VARCHAR2(100)  NOT NULL,
    email                VARCHAR2(255)  NOT NULL,
    contact_no           VARCHAR2(20),
    birth_date           DATE,
    id_type              VARCHAR2(50),
    id_number            VARCHAR2(100),
    residential_address  VARCHAR2(255),
    kyc_status           VARCHAR2(20)   DEFAULT 'PENDING' NOT NULL,
    created_at           TIMESTAMP      DEFAULT SYSTIMESTAMP NOT NULL,
    created_by           VARCHAR2(50)   NOT NULL,
    updated_at           TIMESTAMP,
    updated_by           VARCHAR2(50),
    CONSTRAINT pk_customer_master PRIMARY KEY (customer_id),
    CONSTRAINT uq_customer_master_email UNIQUE (email),
    CONSTRAINT ck_customer_master_email CHECK (email LIKE '%@%.%'),
    CONSTRAINT ck_customer_master_kyc CHECK (kyc_status IN ('PENDING', 'VERIFIED', 'REJECTED'))
);

CREATE INDEX ix_customer_master_email ON customer_master (email);

COMMENT ON TABLE customer_master IS 'Core customer Information File (CIF) and KYC record';

-- ---------------------------------------------------------------------
-- 2. ACCOUNT_MASTER (CBS Schema)
-- Authoritative System of Record for balances and currency pockets.
-- Row-locked via SELECT ... FOR UPDATE.
-- ---------------------------------------------------------------------
CREATE TABLE account_master (
    account_id       VARCHAR2(36)   NOT NULL,
    customer_id      VARCHAR2(36),  -- NULL for BANK_* clearing accounts
    account_number   VARCHAR2(16),  -- Shared across customer pockets
    account_type     VARCHAR2(30)   NOT NULL,
    currency_code    CHAR(3)        NOT NULL,
    normal_balance   CHAR(2)        DEFAULT 'CR' NOT NULL,
    balance_amount   NUMBER(18,4)   DEFAULT 0 NOT NULL,
    held_amount      NUMBER(18,4)   DEFAULT 0 NOT NULL,
    customer_locked  CHAR(1)        DEFAULT 'N' NOT NULL,
    admin_frozen     CHAR(1)        DEFAULT 'N' NOT NULL,
    account_status   VARCHAR2(20)   DEFAULT 'ACTIVE' NOT NULL,
    is_primary       CHAR(1)        DEFAULT 'N' NOT NULL,
    version          NUMBER(19)     DEFAULT 0 NOT NULL,
    created_at       TIMESTAMP      DEFAULT SYSTIMESTAMP NOT NULL,
    created_by       VARCHAR2(50)   NOT NULL,
    updated_at       TIMESTAMP,
    updated_by       VARCHAR2(50),
    CONSTRAINT pk_account_master PRIMARY KEY (account_id),
    CONSTRAINT fk_account_master_customer FOREIGN KEY (customer_id)
        REFERENCES customer_master (customer_id),
    CONSTRAINT ck_account_master_acct_type CHECK (
        account_type IN ('CUSTOMER', 'SAVINGS', 'CHECKING', 'WALLET', 
                         'BANK_FX_POSITION', 'BANK_FEE_INCOME', 'BANK_CASH_CLEARING', 'INTERNAL_BANK_FX')
    ),
    CONSTRAINT ck_account_master_status CHECK (account_status IN ('ACTIVE','FROZEN','CLOSED')),
    CONSTRAINT ck_account_master_norm_bal CHECK (normal_balance IN ('DR', 'CR')),
    CONSTRAINT ck_account_master_lock_flags CHECK (customer_locked IN ('Y', 'N') AND admin_frozen IN ('Y', 'N')),
    CONSTRAINT ck_account_master_is_primary CHECK (is_primary IN ('Y', 'N')),
    CONSTRAINT ck_cust_balance_nonneg CHECK (account_type <> 'CUSTOMER' OR balance_amount >= 0),
    CONSTRAINT ck_account_master_held_nonneg CHECK (held_amount >= 0),
    CONSTRAINT ck_account_type_cust_id CHECK (
        (account_type IN ('CUSTOMER','SAVINGS','CHECKING','WALLET') AND customer_id IS NOT NULL)
        OR
        (account_type NOT IN ('CUSTOMER','SAVINGS','CHECKING','WALLET') AND customer_id IS NULL)
    )
);

CREATE INDEX ix_account_master_customer_id ON account_master (customer_id);

-- Filtered Unique Index: Prevents multi-currency pocket duplication per customer
CREATE UNIQUE INDEX uq_cust_currency_pocket 
    ON account_master (CASE WHEN customer_id IS NOT NULL THEN customer_id END, currency_code);

CREATE UNIQUE INDEX uq_cust_acct_num_curr 
    ON account_master (CASE WHEN account_number IS NOT NULL THEN account_number END, currency_code);

COMMENT ON TABLE account_master IS 'Authoritative vault for balances and currency pockets, row-locked via SELECT ... FOR UPDATE';

-- ---------------------------------------------------------------------
-- 3. CARD_MASTER (CBS Schema)
-- PCI-DSS compliant virtual cards linked to customer primary deposit accounts.
-- ---------------------------------------------------------------------
CREATE TABLE card_master (
    card_id            VARCHAR2(36)   NOT NULL,
    customer_id        VARCHAR2(36)   NOT NULL,
    account_id         VARCHAR2(36)   NOT NULL,
    card_token         VARCHAR2(64)   NOT NULL,
    masked_pan         VARCHAR2(19)   NOT NULL,
    expiry_date        VARCHAR2(5)    NOT NULL,
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
CREATE INDEX ix_card_master_account ON card_master (account_id);

COMMENT ON TABLE card_master IS 'PCI-DSS compliant virtual debit cards with tokenized PAN and spend limits';

-- ---------------------------------------------------------------------
-- 4. ACCOUNT_HOLD (CBS Schema)
-- In-flight risk and administrative balance reservation store.
-- ---------------------------------------------------------------------
CREATE TABLE account_hold (
    hold_id            VARCHAR2(36)   NOT NULL,
    account_id         VARCHAR2(36)   NOT NULL,
    txn_id             VARCHAR2(36),
    hold_type          VARCHAR2(20)   NOT NULL,
    amount             NUMBER(18,4)   NOT NULL,
    captured_amount    NUMBER(18,4)   DEFAULT 0 NOT NULL,
    currency_code      CHAR(3)        NOT NULL,
    status             VARCHAR2(20)   DEFAULT 'ACTIVE' NOT NULL,
    reason_code        VARCHAR2(40),
    customer_reason    VARCHAR2(200),
    placed_by          VARCHAR2(50)   DEFAULT 'SYSTEM' NOT NULL,
    expires_at         TIMESTAMP      NOT NULL,
    resolved_at        TIMESTAMP,
    created_at         TIMESTAMP      DEFAULT SYSTIMESTAMP NOT NULL,
    created_by         VARCHAR2(50)   NOT NULL,
    updated_at         TIMESTAMP,
    updated_by         VARCHAR2(50),
    CONSTRAINT pk_account_hold PRIMARY KEY (hold_id),
    CONSTRAINT fk_account_hold_account FOREIGN KEY (account_id)
        REFERENCES account_master (account_id),
    CONSTRAINT ck_account_hold_status CHECK (status IN ('ACTIVE','RELEASED','CAPTURED','EXPIRED')),
    CONSTRAINT ck_account_hold_type CHECK (hold_type IN ('RISK_HOLD', 'ADMIN_HOLD', 'RISK', 'SI', 'PREAUTH')),
    CONSTRAINT ck_account_hold_amount CHECK (amount > 0)
);

CREATE INDEX ix_account_hold_account_status ON account_hold (account_id, status);

-- Filtered unique index: Prevents duplicate active holds per transaction while permitting multiple active ADMIN_HOLDs
CREATE UNIQUE INDEX uq_active_txn_hold 
    ON account_hold (CASE WHEN status = 'ACTIVE' AND txn_id IS NOT NULL THEN txn_id || '|' || hold_type END);

COMMENT ON TABLE account_hold IS 'Active amount holds reducing available balance without deducting live ledger balance';

-- ---------------------------------------------------------------------
-- 5. LEDGER_ENTRY (CBS Schema)
-- Authoritative, append-only double-entry core journal with running balances.
-- ---------------------------------------------------------------------
CREATE TABLE ledger_entry (
    entry_id          VARCHAR2(36)   NOT NULL, -- UUIDv5(txn_id-leg-n) matching audit mutation_uuid
    txn_id            VARCHAR2(36)   NOT NULL,
    leg_no            NUMBER(3)      NOT NULL,
    account_id        VARCHAR2(36)   NOT NULL,
    direction         VARCHAR2(6)    NOT NULL, -- 'DEBIT', 'CREDIT'
    amount            NUMBER(18,4)   NOT NULL,
    currency_code     CHAR(3)        NOT NULL,
    running_balance   NUMBER(18,4),            -- Balance immediately after mutation
    hold_id           VARCHAR2(36),            -- Non-null if this leg captured a hold
    value_date        DATE           DEFAULT SYSDATE NOT NULL,
    business_date     DATE           DEFAULT SYSDATE NOT NULL,
    narrative         VARCHAR2(255),           -- Statement line narrative
    reversal_of_entry VARCHAR2(36),            -- Links reversal entry to original entry
    posted_at         TIMESTAMP      DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT pk_ledger_entry PRIMARY KEY (entry_id),
    CONSTRAINT uq_ledger_entry_leg UNIQUE (txn_id, leg_no),
    CONSTRAINT fk_ledger_entry_account FOREIGN KEY (account_id)
        REFERENCES account_master (account_id),
    CONSTRAINT ck_ledger_entry_dir CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ck_ledger_entry_amt CHECK (amount > 0)
);

CREATE INDEX ix_ledger_entry_acct_date ON ledger_entry (account_id, posted_at);
CREATE INDEX ix_ledger_entry_biz_date  ON ledger_entry (business_date);

COMMENT ON TABLE ledger_entry IS 'Synchronous OLTP master double-entry journal posted in same ACID transaction as account_master';

-- ---------------------------------------------------------------------
-- 6. POSTING_REFERENCE (CBS Schema)
-- Core settlement deduplication barrier returning idempotent core refs.
-- ---------------------------------------------------------------------
CREATE TABLE posting_reference (
    posting_reference_id VARCHAR2(36)   NOT NULL, -- equals txn_id
    core_reference_id    VARCHAR2(50)   NOT NULL,
    request_hash         VARCHAR2(64),
    txn_type             VARCHAR2(30)   NOT NULL,
    status               VARCHAR2(20)   DEFAULT 'SETTLED' NOT NULL,
    settled_at           TIMESTAMP      DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT pk_posting_reference PRIMARY KEY (posting_reference_id),
    CONSTRAINT uq_posting_core_ref UNIQUE (core_reference_id),
    CONSTRAINT ck_posting_ref_status CHECK (status IN ('SETTLED','ABORTED')),
    CONSTRAINT ck_posting_ref_type CHECK (txn_type IN ('TRANSFER', 'REVERSAL', 'WITHDRAWAL', 'DEPOSIT'))
);

COMMENT ON TABLE posting_reference IS 'CBS settlement deduplication store guaranteeing idempotency for /internal/core/settlement';

-- ---------------------------------------------------------------------
-- 7. OUTBOX_MASTER (CBS Schema)
-- Transactional outbox staging platform domain events atomically with balances.
-- ---------------------------------------------------------------------
CREATE TABLE outbox_master (
    outbox_id       VARCHAR2(36)  NOT NULL,
    source_service  VARCHAR2(50)  DEFAULT 'accounts-service' NOT NULL,
    aggregate_type  VARCHAR2(30)  NOT NULL,   -- e.g. 'ACCOUNT', 'TRANSACTION', 'HOLD'
    aggregate_id    VARCHAR2(36)  NOT NULL,
    event_type      VARCHAR2(60)  NOT NULL,   -- e.g. 'payment.completed', 'balance.updated'
    payload         CLOB          NOT NULL,
    status          VARCHAR2(20)  DEFAULT 'PENDING' NOT NULL,
    attempt_count   NUMBER        DEFAULT 0 NOT NULL,
    created_at      TIMESTAMP     DEFAULT SYSTIMESTAMP NOT NULL,
    published_at    TIMESTAMP,
    CONSTRAINT pk_outbox_master PRIMARY KEY (outbox_id),
    CONSTRAINT ck_outbox_master_status CHECK (status IN ('PENDING','PUBLISHED','FAILED'))
);

CREATE INDEX ix_outbox_master_status ON outbox_master (status, created_at);

COMMENT ON TABLE outbox_master IS 'Sole authoritative transactional outbox in CBS. Polled by OutboxMasterRelayService for Kafka.';

-- ---------------------------------------------------------------------
-- 8. TRANSACTION_MASTER (PAYMENT Schema)
-- 7-Status Payment Saga tracker with atomic CAS claim transitions.
-- ---------------------------------------------------------------------
CREATE TABLE transaction_master (
    txn_id              VARCHAR2(36)   NOT NULL,
    customer_id         VARCHAR2(36),
    txn_type            VARCHAR2(30)   NOT NULL,
    debit_account_id    VARCHAR2(36),
    credit_account_id   VARCHAR2(36),
    mutation_amount     NUMBER(18,4)   NOT NULL,
    currency_code       VARCHAR2(3)    NOT NULL,
    is_cross_currency   VARCHAR2(1)    DEFAULT 'N' NOT NULL,
    fx_rate             NUMBER(18,8),
    dest_amount         NUMBER(18,4),
    dest_currency_code  VARCHAR2(3),
    txn_status          VARCHAR2(20)   DEFAULT 'INITIATED' NOT NULL,
    stage               VARCHAR2(30)   DEFAULT 'INITIATED' NOT NULL,
    core_reference_id   VARCHAR2(50),
    idempotency_key     VARCHAR2(64),
    hold_id             VARCHAR2(36),
    original_txn_id     VARCHAR2(36),
    reversed            CHAR(1)        DEFAULT 'N',
    reversal_txn_id     VARCHAR2(36),
    reversal_of_txn_id  VARCHAR2(36),
    reversed_by_txn_id  VARCHAR2(36),
    cancel_reason       VARCHAR2(100),
    failure_code        VARCHAR2(40),
    risk_score          NUMBER(4,2),
    risk_decision       VARCHAR2(30),
    risk_rules          VARCHAR2(1000),
    business_date       DATE,
    initiated_at        TIMESTAMP      DEFAULT SYSTIMESTAMP NOT NULL,
    completed_at        TIMESTAMP,
    created_at          TIMESTAMP      DEFAULT SYSTIMESTAMP NOT NULL,
    created_by          VARCHAR2(50)   NOT NULL,
    updated_at          TIMESTAMP,
    updated_by          VARCHAR2(50),
    CONSTRAINT pk_transaction_master PRIMARY KEY (txn_id),
    CONSTRAINT fk_txn_debit_account FOREIGN KEY (debit_account_id)
        REFERENCES account_master (account_id),
    CONSTRAINT fk_txn_credit_account FOREIGN KEY (credit_account_id)
        REFERENCES account_master (account_id),
    CONSTRAINT ck_txn_type CHECK (txn_type IN ('WITHDRAWAL','DEPOSIT','TRANSFER','REVERSAL')),
    CONSTRAINT ck_txn_status CHECK (
        txn_status IN ('PENDING','COMMITTED','ROLLED_BACK','INITIATED',
                       'RESERVED','AUTHORIZED','PROCESSING','POSTED',
                       'CANCELLED','FAILED','REVERSED')
    ),
    CONSTRAINT ck_txn_amount_positive CHECK (mutation_amount > 0),
    CONSTRAINT ck_txn_is_cross_currency CHECK (is_cross_currency IN ('Y', 'N')),
    CONSTRAINT ck_txn_diff_accounts CHECK (
        debit_account_id IS NULL OR credit_account_id IS NULL OR debit_account_id <> credit_account_id
    ),
    CONSTRAINT ck_txn_debit_credit_by_type CHECK (
        (txn_type = 'WITHDRAWAL' AND debit_account_id IS NOT NULL AND credit_account_id IS NULL)
        OR (txn_type = 'DEPOSIT' AND credit_account_id IS NOT NULL AND debit_account_id IS NULL)
        OR (txn_type IN ('TRANSFER', 'REVERSAL') AND debit_account_id IS NOT NULL AND credit_account_id IS NOT NULL)
    ),
    CONSTRAINT ck_txn_completed_after CHECK (completed_at IS NULL OR completed_at >= initiated_at)
);

CREATE INDEX ix_txn_debit_account ON transaction_master (debit_account_id);
CREATE INDEX ix_txn_credit_account ON transaction_master (credit_account_id);
CREATE INDEX ix_txn_status ON transaction_master (txn_status);
CREATE INDEX ix_txn_completed_at ON transaction_master (completed_at);
CREATE INDEX ix_txn_customer_id ON transaction_master (customer_id);

CREATE UNIQUE INDEX uq_txn_cust_idempotency ON transaction_master (customer_id, idempotency_key);
CREATE UNIQUE INDEX uq_txn_reversal_ref ON transaction_master (reversal_of_txn_id);

COMMENT ON TABLE transaction_master IS 'Payment hub lifecycle saga orchestrating 7-status transitions with atomic CAS claim';

-- ---------------------------------------------------------------------
-- 9. STANDING_INSTRUCTION (PAYMENT Schema)
-- Recurring batch auto-debit payments scheduled for EOD processing.
-- ---------------------------------------------------------------------
CREATE TABLE standing_instruction (
    instruction_id       VARCHAR2(36)   NOT NULL,
    customer_id          VARCHAR2(36)   NOT NULL,
    source_account_id    VARCHAR2(36)   NOT NULL,
    target_account_id    VARCHAR2(36)   NOT NULL,
    amount               NUMBER(18,4)   NOT NULL,
    currency_code        CHAR(3)        NOT NULL,
    frequency            VARCHAR2(20)   NOT NULL,
    next_execution_date  DATE           NOT NULL,
    status               VARCHAR2(20)   DEFAULT 'ACTIVE' NOT NULL,
    consecutive_failures NUMBER         DEFAULT 0 NOT NULL,
    created_at           TIMESTAMP      DEFAULT SYSTIMESTAMP NOT NULL,
    created_by           VARCHAR2(50)   NOT NULL,
    updated_at           TIMESTAMP,
    updated_by           VARCHAR2(50),
    CONSTRAINT pk_standing_instruction PRIMARY KEY (instruction_id),
    CONSTRAINT fk_si_customer FOREIGN KEY (customer_id)
        REFERENCES customer_master (customer_id),
    CONSTRAINT fk_si_source_account FOREIGN KEY (source_account_id)
        REFERENCES account_master (account_id),
    CONSTRAINT fk_si_target_account FOREIGN KEY (target_account_id)
        REFERENCES account_master (account_id),
    CONSTRAINT ck_si_status CHECK (status IN ('ACTIVE','PAUSED','CANCELLED')),
    CONSTRAINT ck_si_frequency CHECK (frequency IN ('DAILY', 'WEEKLY', 'MONTHLY')),
    CONSTRAINT ck_si_amount CHECK (amount > 0)
);

CREATE INDEX ix_si_customer ON standing_instruction (customer_id);
CREATE INDEX ix_si_next_exec ON standing_instruction (next_execution_date, status);

COMMENT ON TABLE standing_instruction IS 'Standing instructions for recurring auto-debit schedules executed by batch-service';

-- ---------------------------------------------------------------------
-- 10. ADMIN_APPROVAL (PAYMENT Schema)
-- 4-Eyes Maker-Checker authorization queue for reversals and admin holds.
-- ---------------------------------------------------------------------
CREATE TABLE admin_approval (
    approval_id       VARCHAR2(36)   NOT NULL,
    target_type       VARCHAR2(30)   NOT NULL, -- 'REVERSAL', 'HOLD_REVIEW', 'ACCOUNT_UNFREEZE'
    target_id         VARCHAR2(64)   NOT NULL,
    proposed_payload  CLOB,
    maker_id          VARCHAR2(36)   NOT NULL,
    checker_id        VARCHAR2(36),
    status            VARCHAR2(20)   DEFAULT 'PENDING' NOT NULL,
    reason            VARCHAR2(255),
    created_at        TIMESTAMP      DEFAULT SYSTIMESTAMP NOT NULL,
    decided_at        TIMESTAMP,
    CONSTRAINT pk_admin_approval PRIMARY KEY (approval_id),
    CONSTRAINT ck_admin_approval_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT ck_admin_approval_target CHECK (target_type IN ('REVERSAL', 'HOLD_REVIEW', 'ACCOUNT_UNFREEZE')),
    CONSTRAINT ck_admin_approval_4eyes CHECK (checker_id IS NULL OR checker_id <> maker_id)
);

CREATE INDEX ix_admin_approval_status ON admin_approval (status, created_at);

COMMENT ON TABLE admin_approval IS 'Dual-control 4-eyes authorization queue enforcing checker <> maker';

-- ---------------------------------------------------------------------
-- 11. FX_RATE (FX Schema)
-- Daily EOD cached foreign exchange reference rates for 30s locked quotes.
-- ---------------------------------------------------------------------
CREATE TABLE fx_rate (
    base_currency     CHAR(3)        NOT NULL,
    quote_currency    CHAR(3)        NOT NULL,
    rate              NUMBER(18,8)   NOT NULL,
    fetched_at        TIMESTAMP      DEFAULT SYSTIMESTAMP NOT NULL,
    source            VARCHAR2(50)   DEFAULT 'frankfurter' NOT NULL,
    CONSTRAINT pk_fx_rate PRIMARY KEY (base_currency, quote_currency),
    CONSTRAINT ck_fx_rate_positive CHECK (rate > 0),
    CONSTRAINT ck_fx_rate_diff CHECK (base_currency <> quote_currency)
);

COMMENT ON TABLE fx_rate IS 'Treasury FX reference rates refreshed daily at EOD from external provider';

COMMIT;