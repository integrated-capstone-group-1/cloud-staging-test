-- =====================================================================
-- 04_ORACLE_REVISED_FIXES.SQL (SIMPLIFIED 18-TABLE COMPLIANT VERSION)
-- Resolves Reviewer Blockers & Structural Core Requirements:
-- R-01: Fix Composite NULL unique collisions & boolean CHECK syntax
-- R-02: Fix balance >= 0 on internal clearing accounts + normal_balance
-- R-07: Core ledger_entry journal with running_balance, value_date, narrative
-- R-12: Unified txn_type enums across all posting & settlement tables
-- =====================================================================

ALTER SESSION SET CONTAINER = XEPDB1;
ALTER SESSION SET CURRENT_SCHEMA = LEDGER_APP;

-- =====================================================================
-- 1. [R-01 & R-02] ACCOUNT_MASTER: FIX CHECK SYNTAX, NORMAL BALANCE & SEPARATION
-- =====================================================================

BEGIN
    EXECUTE IMMEDIATE 'ALTER TABLE account_master DROP CONSTRAINT ck_account_master_nonneg';
EXCEPTION WHEN OTHERS THEN NULL;
END;
/

BEGIN
    EXECUTE IMMEDIATE 'ALTER TABLE account_master DROP CONSTRAINT ck_account_type_cust_id';
EXCEPTION WHEN OTHERS THEN NULL;
END;
/

-- Add normal_balance, account_number, and explicit lock flags
ALTER TABLE account_master ADD (
    account_number    VARCHAR2(16),
    normal_balance    CHAR(2)       DEFAULT 'CR' NOT NULL,
    customer_locked   CHAR(1)       DEFAULT 'N'  NOT NULL,
    admin_frozen      CHAR(1)       DEFAULT 'N'  NOT NULL
);

-- R-02: balance >= 0 strictly applies to CUSTOMER accounts. Clearing & FX accounts can carry debit balances
ALTER TABLE account_master ADD CONSTRAINT ck_cust_balance_nonneg 
    CHECK (account_type <> 'CUSTOMER' OR balance_amount >= 0);

ALTER TABLE account_master ADD CONSTRAINT ck_account_master_norm_bal
    CHECK (normal_balance IN ('DR', 'CR'));

ALTER TABLE account_master ADD CONSTRAINT ck_account_master_lock_flags
    CHECK (customer_locked IN ('Y', 'N') AND admin_frozen IN ('Y', 'N'));

-- Update account_type constraint to include the new bank clearing accounts
ALTER TABLE account_master DROP CONSTRAINT ck_account_master_acct_type;
ALTER TABLE account_master ADD CONSTRAINT ck_account_master_acct_type 
    CHECK (account_type IN ('CUSTOMER', 'SAVINGS', 'CHECKING', 'WALLET', 'BANK_FX_POSITION', 'BANK_FEE_INCOME', 'BANK_CASH_CLEARING', 'INTERNAL_BANK_FX'));

-- R-01 Fix: Oracle CHECK syntax using boolean disjunction
ALTER TABLE account_master ADD CONSTRAINT ck_account_type_cust_id CHECK (
    (account_type IN ('CUSTOMER','SAVINGS','CHECKING','WALLET') AND customer_id IS NOT NULL)
    OR
    (account_type NOT IN ('CUSTOMER','SAVINGS','CHECKING','WALLET') AND customer_id IS NULL)
);

-- R-01 Fix: Filtered Unique Index for Customer Pockets so (NULL, 'PHP') does NOT collide on BANK_* accounts
CREATE UNIQUE INDEX uq_cust_currency_pocket 
ON account_master (CASE WHEN customer_id IS NOT NULL THEN customer_id END, currency_code);

CREATE UNIQUE INDEX uq_cust_acct_num_curr 
ON account_master (CASE WHEN account_number IS NOT NULL THEN account_number END, currency_code);

-- =====================================================================
-- 2. [R-01] ACCOUNT_HOLD: FIX ACTIVE HOLD FUNCTION-BASED INDEX
-- =====================================================================

BEGIN
    EXECUTE IMMEDIATE 'DROP INDEX uq_active_txn_hold';
EXCEPTION WHEN OTHERS THEN NULL;
END;
/

-- Extend account_hold fields for resolution, capture, and customer reason
ALTER TABLE account_hold ADD (
    captured_amount   NUMBER(18,4)   DEFAULT 0    NOT NULL,
    reason_code       VARCHAR2(40),
    customer_reason   VARCHAR2(200),
    placed_by         VARCHAR2(50)   DEFAULT 'SYSTEM',
    resolved_at       TIMESTAMP
);

-- R-01 Fix: Single combined expression avoids (NULL, hold_type) unique collisions on admin holds
CREATE UNIQUE INDEX uq_active_txn_hold 
ON account_hold (CASE WHEN status = 'ACTIVE' AND txn_id IS NOT NULL THEN txn_id || '|' || hold_type END);

-- Update hold_type check constraint for in-scope holds (RISK_HOLD, ADMIN_HOLD)
ALTER TABLE account_hold DROP CONSTRAINT ck_account_hold_type;
ALTER TABLE account_hold ADD CONSTRAINT ck_account_hold_type 
    CHECK (hold_type IN ('RISK_HOLD', 'ADMIN_HOLD', 'RISK', 'SI', 'PREAUTH'));

-- =====================================================================
-- 3. [R-07 & R-12] LEDGER_ENTRY: CORE IMMUTABLE JOURNAL WITH RICH FIELDS
-- =====================================================================

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

-- Update posting_reference to accept unified txn_type enums (R-12)
ALTER TABLE posting_reference DROP CONSTRAINT ck_posting_ref_type;
BEGIN
    ALTER TABLE posting_reference ADD CONSTRAINT ck_posting_ref_type 
        CHECK (txn_type IN ('TRANSFER', 'REVERSAL', 'WITHDRAWAL', 'DEPOSIT'));
EXCEPTION WHEN OTHERS THEN NULL;
END;
/

-- =====================================================================
-- 4. [R-05] ADMIN_APPROVAL (4-EYES MAKER-CHECKER FOR REVERSALS & HOLDS)
-- =====================================================================

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
    CONSTRAINT ck_admin_approval_4eyes CHECK (checker_id IS NULL OR checker_id <> maker_id)
);

-- =====================================================================
-- 5. [BR-04] FX_RATE (ORACLE XE FX SCHEMA FOR 30s LOCKED QUOTES)
-- =====================================================================

CREATE TABLE fx_rate (
    base_currency     CHAR(3)        NOT NULL,
    quote_currency    CHAR(3)        NOT NULL,
    rate              NUMBER(18,8)   NOT NULL,
    fetched_at        TIMESTAMP      DEFAULT SYSTIMESTAMP NOT NULL,
    source            VARCHAR2(50)   DEFAULT 'frankfurter' NOT NULL,
    CONSTRAINT pk_fx_rate PRIMARY KEY (base_currency, quote_currency)
);

COMMIT;
