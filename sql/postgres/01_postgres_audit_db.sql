-- =====================================================================
-- POSTGRESQL 15+ - IMMUTABLE AUDIT DATABASE CONSOLIDATED DDL
-- Database: ledger_audit (User: ledger_audit)
--
-- Aligned with System Architecture Specification (ARCHITECTURE.md):
--   Tier 5 Event Bus, Audit & Reconciliation Layer
--   - Single-writer append-only evidence store populated by AuditLedgerConsumer
--   - Independent counterparty store for reconciliation-service
--   - True DB-level immutability enforced via triggers blocking UPDATE and DELETE
--
-- Tables (5 Audit & Recon Tables per ARCHITECTURE.md Section 3, 5.1, 5.2):
--   Audit Schema (AuditLedgerConsumer):
--     1. ledger_mutation_audit - Immutable append-only double-entry ledger postings
--     2. risk_screening_audit  - Fraud scores, heuristic evaluations & rule hits
--     3. fx_conversion_audit   - Executed currency conversions and locked quotes
--   Recon Schema (reconciliation-service):
--     4. recon_run_audit       - Reconciliation execution runs and metadata
--     5. recon_result_audit    - Reconciled legs, drift breaks and discrepancies
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- ---------------------------------------------------------------------
-- 1. LEDGER_MUTATION_AUDIT (Audit Schema)
-- Immutable double-entry leg journal.
-- Enforced as strictly append-only: database trigger blocks UPDATE & DELETE.
-- ---------------------------------------------------------------------
CREATE TABLE ledger_mutation_audit (
    mutation_uuid    UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    txn_id           VARCHAR(36)    NOT NULL,
    leg_no           INTEGER        DEFAULT 1 NOT NULL,
    account_id       VARCHAR(36)    NOT NULL,
    mutation_amount  NUMERIC(18,4)  NOT NULL,
    currency_code    CHAR(3)        NOT NULL,
    mutation_type    VARCHAR(10)    NOT NULL, -- 'DEBIT', 'CREDIT'
    txn_type         VARCHAR(30)    NOT NULL, -- 'WITHDRAWAL', 'DEPOSIT', 'TRANSFER', 'REVERSAL'
    audit_state      VARCHAR(20)    NOT NULL, -- 'PENDING', 'COMMITTED', 'ROLLED_BACK', 'POSTED', 'REVERSED'
    business_date    DATE           DEFAULT CURRENT_DATE NOT NULL,
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT ck_ledger_audit_mutation_type
        CHECK (mutation_type IN ('DEBIT','CREDIT')),
    CONSTRAINT ck_ledger_audit_txn_type
        CHECK (txn_type IN ('WITHDRAWAL','DEPOSIT','TRANSFER','REVERSAL')),
    CONSTRAINT ck_ledger_audit_state
        CHECK (audit_state IN ('PENDING','COMMITTED','ROLLED_BACK','POSTED','REVERSED')),
    CONSTRAINT ck_ledger_audit_amount_positive
        CHECK (mutation_amount > 0),
    CONSTRAINT ck_ledger_audit_currency_code
        CHECK (char_length(btrim(currency_code)) = 3)
);

CREATE UNIQUE INDEX uq_ledger_audit_txn_leg ON ledger_mutation_audit (txn_id, leg_no);
CREATE INDEX ix_ledger_audit_txn_acct_type ON ledger_mutation_audit (txn_id, account_id, mutation_type);
CREATE INDEX ix_ledger_audit_txn_id ON ledger_mutation_audit (txn_id);
CREATE INDEX ix_ledger_audit_account_id ON ledger_mutation_audit (account_id);
CREATE INDEX ix_ledger_audit_created_at ON ledger_mutation_audit (created_at);
CREATE INDEX ix_ledger_audit_biz_date ON ledger_mutation_audit (business_date);

COMMENT ON TABLE ledger_mutation_audit IS 'Immutable append-only regulatory evidence of double-entry ledger postings';

-- Trigger: Enforce true database-level immutability for ledger mutations
CREATE OR REPLACE FUNCTION fn_ledger_mutation_audit_append_only()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'ledger_mutation_audit is append-only: % operation not permitted on mutation_uuid %',
        TG_OP, COALESCE(OLD.mutation_uuid, NULL);
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ledger_mutation_audit_append_only
    BEFORE UPDATE OR DELETE ON ledger_mutation_audit
    FOR EACH ROW EXECUTE FUNCTION fn_ledger_mutation_audit_append_only();

-- ---------------------------------------------------------------------
-- 2. RISK_SCREENING_AUDIT (Audit Schema)
-- Audit trail for synchronous Risk Engine fraud evaluations.
-- ---------------------------------------------------------------------
CREATE TABLE risk_screening_audit (
    screening_id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    txn_id                VARCHAR(36)    NOT NULL,
    customer_id           VARCHAR(36)    NOT NULL,
    risk_score            NUMERIC(4,2)   NOT NULL, -- 0.00 to 1.00
    risk_level            VARCHAR(20)    NOT NULL, -- 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL'
    risk_decision         VARCHAR(30)    NOT NULL, -- 'ALLOW', 'STEP_UP_OTP', 'HOLD', 'REJECT'
    triggered_rules       JSONB          NOT NULL,
    evaluation_latency_ms NUMERIC(8,2)   DEFAULT 0.00 NOT NULL,
    evaluated_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_at            TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT ck_risk_screening_score
        CHECK (risk_score >= 0.00 AND risk_score <= 1.00),
    CONSTRAINT ck_risk_screening_level
        CHECK (risk_level IN ('LOW','MEDIUM','HIGH','CRITICAL')),
    CONSTRAINT ck_risk_screening_decision
        CHECK (risk_decision IN ('ALLOW','STEP_UP_OTP','HOLD','REJECT'))
);

CREATE INDEX ix_risk_screening_txn ON risk_screening_audit (txn_id);
CREATE INDEX ix_risk_screening_cust ON risk_screening_audit (customer_id);
CREATE INDEX ix_risk_screening_created ON risk_screening_audit (created_at);

COMMENT ON TABLE risk_screening_audit IS 'Append-only audit trail of fraud evaluation decisions and heuristic rule hits';

CREATE OR REPLACE FUNCTION fn_risk_screening_audit_append_only()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'risk_screening_audit is append-only: % operation not permitted on screening_id %',
        TG_OP, COALESCE(OLD.screening_id, NULL);
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_risk_screening_audit_append_only
    BEFORE UPDATE OR DELETE ON risk_screening_audit
    FOR EACH ROW EXECUTE FUNCTION fn_risk_screening_audit_append_only();

-- ---------------------------------------------------------------------
-- 3. FX_CONVERSION_AUDIT (Audit Schema)
-- Audit log of currency conversions executed with 30s locked quotes.
-- ---------------------------------------------------------------------
CREATE TABLE fx_conversion_audit (
    conversion_id      UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    txn_id             VARCHAR(36)    NOT NULL,
    source_currency    CHAR(3)        NOT NULL,
    dest_currency      CHAR(3)        NOT NULL,
    source_amount      NUMERIC(18,4)  NOT NULL,
    fx_rate            NUMERIC(18,8)  NOT NULL,
    dest_amount        NUMERIC(18,4)  NOT NULL,
    conversion_status  VARCHAR(20)    NOT NULL DEFAULT 'COMPLETED',
    created_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT ck_fx_conversion_status
        CHECK (conversion_status IN ('COMPLETED','FAILED')),
    CONSTRAINT ck_fx_conversion_positive
        CHECK (source_amount > 0 AND fx_rate > 0 AND dest_amount > 0),
    CONSTRAINT ck_fx_conversion_diff_currency
        CHECK (source_currency <> dest_currency)
);

CREATE INDEX ix_fx_conversion_audit_txn_id ON fx_conversion_audit (txn_id);
CREATE INDEX ix_fx_conversion_audit_created ON fx_conversion_audit (created_at);

COMMENT ON TABLE fx_conversion_audit IS 'Immutable audit of foreign exchange conversions applied to cross-currency transfers';

CREATE OR REPLACE FUNCTION fn_fx_conversion_audit_append_only()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'fx_conversion_audit is append-only: % operation not permitted on conversion_id %',
        TG_OP, COALESCE(OLD.conversion_id, NULL);
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_fx_conversion_audit_append_only
    BEFORE UPDATE OR DELETE ON fx_conversion_audit
    FOR EACH ROW EXECUTE FUNCTION fn_fx_conversion_audit_append_only();

-- ---------------------------------------------------------------------
-- 4. RECON_RUN_AUDIT (Recon Schema)
-- Metadata header for batch reconciliation runs.
-- ---------------------------------------------------------------------
CREATE TABLE recon_run_audit (
    run_id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    business_date       DATE          DEFAULT CURRENT_DATE NOT NULL,
    scope               VARCHAR(30)   DEFAULT 'AUDIT_PARITY' NOT NULL, -- 'AUDIT_PARITY', 'HUB_TO_LEDGER'
    run_started_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    run_completed_at    TIMESTAMPTZ,
    window_start        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    window_end          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    total_txn_checked   INTEGER       NOT NULL DEFAULT 0,
    total_matched       INTEGER       NOT NULL DEFAULT 0,
    total_exceptions    INTEGER       NOT NULL DEFAULT 0,
    run_status          VARCHAR(20)   NOT NULL DEFAULT 'RUNNING',
    CONSTRAINT ck_recon_run_audit_status
        CHECK (run_status IN ('RUNNING','COMPLETED','FAILED')),
    CONSTRAINT ck_recon_run_audit_window
        CHECK (window_end >= window_start),
    CONSTRAINT ck_recon_run_audit_completed
        CHECK (run_completed_at IS NULL OR run_completed_at >= run_started_at)
);

CREATE INDEX ix_recon_run_audit_started_at ON recon_run_audit (run_started_at);
CREATE INDEX ix_recon_run_audit_status ON recon_run_audit (run_status);
CREATE INDEX ix_recon_run_audit_biz_date ON recon_run_audit (business_date);

COMMENT ON TABLE recon_run_audit IS 'Audit record of reconciliation batch runs comparing Oracle master vs PostgreSQL audit';

-- ---------------------------------------------------------------------
-- 5. RECON_RESULT_AUDIT (Recon Schema)
-- Granular reconciliation break records and matched parity legs.
-- ---------------------------------------------------------------------
CREATE TABLE recon_result_audit (
    result_id                UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    run_id                   UUID           NOT NULL,
    txn_id                   VARCHAR(36)    NOT NULL,
    account_id               VARCHAR(36),
    mutation_uuid            UUID,
    duplicate_mutation_uuid  UUID,
    recon_status             VARCHAR(20)    NOT NULL, -- 'MATCHED', 'EXCEPTION'
    exception_type           VARCHAR(30),
    expected_currency_code   VARCHAR(3),
    actual_currency_code     VARCHAR(3),
    expected_amount          NUMERIC(18,4),
    actual_amount            NUMERIC(18,4),
    variance_amount          NUMERIC(18,4),
    txn_status               VARCHAR(20),
    ledger_audit_state       VARCHAR(20),
    txn_completed_at         TIMESTAMPTZ,
    ledger_posted_at         TIMESTAMPTZ,
    posting_lag_seconds      INTEGER,
    severity                 VARCHAR(10)    NOT NULL DEFAULT 'LOW',
    created_at               TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT fk_recon_result_audit_run FOREIGN KEY (run_id)
        REFERENCES recon_run_audit (run_id),
    CONSTRAINT fk_recon_result_audit_ledger FOREIGN KEY (mutation_uuid)
        REFERENCES ledger_mutation_audit (mutation_uuid),
    CONSTRAINT fk_recon_result_audit_dup_ledger FOREIGN KEY (duplicate_mutation_uuid)
        REFERENCES ledger_mutation_audit (mutation_uuid),
    CONSTRAINT ck_recon_result_audit_status
        CHECK (recon_status IN ('MATCHED','EXCEPTION')),
    CONSTRAINT ck_recon_result_audit_exception_type
        CHECK (exception_type IS NULL OR exception_type IN (
            'MISSING_LEDGER_ENTRY','ORPHAN_LEDGER_ENTRY','AMOUNT_MISMATCH',
            'ACCOUNT_MISMATCH','STATUS_MISMATCH','DUPLICATE_ENTRY','LATE_POSTING',
            'CURRENCY_MISMATCH','BALANCE_DRIFT','UNMATCHED_LEG'
        )),
    CONSTRAINT ck_recon_result_audit_severity
        CHECK (severity IN ('LOW','MEDIUM','HIGH','CRITICAL'))
);

CREATE INDEX ix_recon_result_audit_run_id ON recon_result_audit (run_id);
CREATE INDEX ix_recon_result_audit_txn_id ON recon_result_audit (txn_id);
CREATE INDEX ix_recon_result_audit_status ON recon_result_audit (recon_status, exception_type);
CREATE INDEX ix_recon_result_audit_created_at ON recon_result_audit (created_at);

COMMENT ON TABLE recon_result_audit IS 'Reconciled ledger leg results and discrepancy exception breaks';
