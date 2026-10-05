-- =====================================================================
-- 03_POSTGRES_DELTAS.SQL (Docs Packaging)
-- Additive DDL Deltas for Capstone Audit DB (PostgreSQL 15+)
-- Resolves Reviewer Blockers: B-01, B-02, B-03, B-04
-- =====================================================================

-- 1. Relax exception_type check on recon_result_audit to support BALANCE_DRIFT
ALTER TABLE recon_result_audit DROP CONSTRAINT IF EXISTS ck_recon_result_audit_exception_type;
ALTER TABLE recon_result_audit ADD CONSTRAINT ck_recon_result_audit_exception_type
    CHECK (exception_type IS NULL OR exception_type IN (
        'MISSING_LEDGER_ENTRY','ORPHAN_LEDGER_ENTRY','AMOUNT_MISMATCH',
        'ACCOUNT_MISMATCH','STATUS_MISMATCH','DUPLICATE_ENTRY','LATE_POSTING',
        'CURRENCY_MISMATCH','BALANCE_DRIFT'
    ));

-- 2. Relax txn_type and audit_state checks on ledger_mutation_audit to support REVERSAL and full lifecycle
ALTER TABLE ledger_mutation_audit DROP CONSTRAINT IF EXISTS ck_ledger_audit_txn_type;
ALTER TABLE ledger_mutation_audit ADD CONSTRAINT ck_ledger_audit_txn_type
    CHECK (txn_type IN ('WITHDRAWAL','DEPOSIT','TRANSFER','REVERSAL'));

ALTER TABLE ledger_mutation_audit DROP CONSTRAINT IF EXISTS ck_ledger_audit_state;
ALTER TABLE ledger_mutation_audit ADD CONSTRAINT ck_ledger_audit_state
    CHECK (audit_state IN ('PENDING','COMMITTED','ROLLED_BACK','POSTED','REVERSED'));

-- 3. Add leg_no column and idempotent uniqueness to ledger_mutation_audit
-- If existing rows have duplicate txn_ids (e.g. DEBIT and CREDIT legs), sequence them safely before adding NOT NULL & UNIQUE
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name = 'ledger_mutation_audit' AND column_name = 'leg_no'
    ) THEN
        ALTER TABLE ledger_mutation_audit ADD COLUMN leg_no INTEGER;

        -- Number existing legs sequentially per txn_id ordered by created_at, mutation_type
        WITH numbered AS (
            SELECT mutation_uuid, ROW_NUMBER() OVER (PARTITION BY txn_id ORDER BY created_at ASC, mutation_type ASC) as seq
            FROM ledger_mutation_audit
        )
        UPDATE ledger_mutation_audit l
        SET leg_no = n.seq
        FROM numbered n
        WHERE l.mutation_uuid = n.mutation_uuid;

        ALTER TABLE ledger_mutation_audit ALTER COLUMN leg_no SET NOT NULL;
        ALTER TABLE ledger_mutation_audit ALTER COLUMN leg_no SET DEFAULT 1;
    END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS uq_ledger_audit_txn_leg ON ledger_mutation_audit (txn_id, leg_no);

-- 4. Create RISK_SCREENING_AUDIT (Audit trail for Java Risk Engine evaluations)
CREATE TABLE IF NOT EXISTS risk_screening_audit (
    screening_id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    txn_id                VARCHAR(36)    NOT NULL,
    customer_id           VARCHAR(36)    NOT NULL,
    risk_score            NUMERIC(4,2)   NOT NULL, -- 0.00 to 1.00
    risk_level            VARCHAR(20)    NOT NULL, -- LOW, MEDIUM, HIGH, CRITICAL
    risk_decision         VARCHAR(30)    NOT NULL, -- ALLOW, STEP_UP_OTP, HOLD, REJECT
    triggered_rules       JSONB          NOT NULL,
    evaluation_latency_ms NUMERIC(8,2)   NOT NULL,
    evaluated_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT ck_risk_screening_score CHECK (risk_score >= 0.00 AND risk_score <= 1.00),
    CONSTRAINT ck_risk_screening_level CHECK (risk_level IN ('LOW','MEDIUM','HIGH','CRITICAL')),
    CONSTRAINT ck_risk_screening_decision CHECK (risk_decision IN ('ALLOW','STEP_UP_OTP','HOLD','REJECT'))
);

CREATE INDEX IF NOT EXISTS ix_risk_screening_txn ON risk_screening_audit (txn_id);
CREATE INDEX IF NOT EXISTS ix_risk_screening_cust ON risk_screening_audit (customer_id);

-- Dedicated append-only trigger function for risk_screening_audit
CREATE OR REPLACE FUNCTION fn_risk_screening_audit_append_only()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'risk_screening_audit is append-only: % not permitted on screening_id %',
        TG_OP, COALESCE(OLD.screening_id, NULL);
END;
$$ LANGUAGE plpgsql;

-- Attach append-only trigger to risk_screening_audit
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_trigger WHERE tgname = 'trg_risk_screening_audit_append_only'
    ) THEN
        CREATE TRIGGER trg_risk_screening_audit_append_only
            BEFORE UPDATE OR DELETE ON risk_screening_audit
            FOR EACH ROW EXECUTE FUNCTION fn_risk_screening_audit_append_only();
    END IF;
END $$;
