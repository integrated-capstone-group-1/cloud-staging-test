-- =====================================================================
-- POSTGRESQL 15+ - AUDIT DB SEED DATA (REVISED SCHEMA COMPLIANT)
-- 02_seed_data_postgres.sql
--
-- Aligned with System Architecture Specification (ARCHITECTURE.md):
--   - ledger_mutation_audit rows match Oracle ledger_entry rows 1:1 on:
--     mutation_uuid = entry_id
--     txn_id
--     leg_no
--     account_id
--     mutation_amount
--     currency_code
--   - Guarantees reconciliation-service verifies 100% MATCHED with zero breaks.
--   - Seeds risk_screening_audit trail for all transactions.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. LEDGER_MUTATION_AUDIT
-- ---------------------------------------------------------------------

-- T1: DEPOSIT into Juan Savings - 1 CREDIT leg
INSERT INTO ledger_mutation_audit (mutation_uuid, txn_id, leg_no, account_id, currency_code, mutation_amount, mutation_type, txn_type, audit_state, business_date, created_at)
VALUES ('7b006281-4871-4dc6-b17f-04aafcd2810a', '05da5006-2cc5-4dd0-87ab-aeca1f0f7ab1', 1,
        '3722f77f-3d1b-4d55-8f83-b71bac91d095', 'PHP', 2000.0000, 'CREDIT', 'DEPOSIT', 'POSTED',
        DATE '2026-09-27', TIMESTAMPTZ '2026-09-27 08:00:05+00');

-- T2: WITHDRAWAL from Juan Checking - 1 DEBIT leg
INSERT INTO ledger_mutation_audit (mutation_uuid, txn_id, leg_no, account_id, currency_code, mutation_amount, mutation_type, txn_type, audit_state, business_date, created_at)
VALUES ('ad4a2ffd-df4c-46a8-baee-84652f9c3672', 'b314f899-2fc0-4087-80e6-09e87ece0d49', 1,
        'a86c97cc-1ea5-4767-bd5b-148968354f9c', 'PHP', 500.0000, 'DEBIT', 'WITHDRAWAL', 'POSTED',
        DATE '2026-09-27', TIMESTAMPTZ '2026-09-27 09:15:04+00');

-- T3: TRANSFER Juan Checking -> Maria Checking - DEBIT + CREDIT legs
INSERT INTO ledger_mutation_audit (mutation_uuid, txn_id, leg_no, account_id, currency_code, mutation_amount, mutation_type, txn_type, audit_state, business_date, created_at)
VALUES ('0c221483-efea-45be-adfc-4576500866d4', '8a2adc48-4cf1-4478-b317-57d60b9bf0ee', 1,
        'a86c97cc-1ea5-4767-bd5b-148968354f9c', 'PHP', 1000.0000, 'DEBIT', 'TRANSFER', 'POSTED',
        DATE '2026-09-27', TIMESTAMPTZ '2026-09-27 10:30:06+00');

INSERT INTO ledger_mutation_audit (mutation_uuid, txn_id, leg_no, account_id, currency_code, mutation_amount, mutation_type, txn_type, audit_state, business_date, created_at)
VALUES ('098cfaa7-06b1-4a07-bf5e-80d19bd30ed0', '8a2adc48-4cf1-4478-b317-57d60b9bf0ee', 2,
        'f95e7e52-3259-4b4d-ac66-b186a893cf66', 'PHP', 1000.0000, 'CREDIT', 'TRANSFER', 'POSTED',
        DATE '2026-09-27', TIMESTAMPTZ '2026-09-27 10:30:07+00');

-- T4: TRANSFER Maria Wallet -> Juan Savings - DEBIT + CREDIT legs
INSERT INTO ledger_mutation_audit (mutation_uuid, txn_id, leg_no, account_id, currency_code, mutation_amount, mutation_type, txn_type, audit_state, business_date, created_at)
VALUES ('de025c6c-547a-4010-980d-d07abc036d30', 'ffc2ea15-57a3-4d26-9840-611adf6deda6', 1,
        '86cabb96-61d5-462a-b33b-5a6435649eb4', 'PHP', 300.0000, 'DEBIT', 'TRANSFER', 'POSTED',
        DATE '2026-09-27', TIMESTAMPTZ '2026-09-27 13:45:05+00');

INSERT INTO ledger_mutation_audit (mutation_uuid, txn_id, leg_no, account_id, currency_code, mutation_amount, mutation_type, txn_type, audit_state, business_date, created_at)
VALUES ('bf6978e7-c5c1-4060-bf2a-12862b82d44c', 'ffc2ea15-57a3-4d26-9840-611adf6deda6', 2,
        '3722f77f-3d1b-4d55-8f83-b71bac91d095', 'PHP', 300.0000, 'CREDIT', 'TRANSFER', 'POSTED',
        DATE '2026-09-27', TIMESTAMPTZ '2026-09-27 13:45:06+00');

-- T5: DEPOSIT into Maria Wallet - 1 CREDIT leg
INSERT INTO ledger_mutation_audit (mutation_uuid, txn_id, leg_no, account_id, currency_code, mutation_amount, mutation_type, txn_type, audit_state, business_date, created_at)
VALUES ('f6c7d7e6-0fda-4ec8-8804-03ced5dba3fa', '672898a7-a8d9-4041-8574-4fd1c4bc0743', 1,
        '86cabb96-61d5-462a-b33b-5a6435649eb4', 'PHP', 750.0000, 'CREDIT', 'DEPOSIT', 'POSTED',
        DATE '2026-09-27', TIMESTAMPTZ '2026-09-27 16:00:03+00');

-- ---------------------------------------------------------------------
-- 2. RISK_SCREENING_AUDIT
-- ---------------------------------------------------------------------
INSERT INTO risk_screening_audit (screening_id, txn_id, customer_id, risk_score, risk_level, risk_decision, triggered_rules, evaluation_latency_ms, evaluated_at, created_at)
VALUES ('11111111-1111-1111-1111-111111111101', '05da5006-2cc5-4dd0-87ab-aeca1f0f7ab1', '7bd20304-dd6a-4e45-850b-58df8b535be4',
        0.05, 'LOW', 'ALLOW', '[]'::jsonb, 12.40,
        TIMESTAMPTZ '2026-09-27 08:00:01+00', TIMESTAMPTZ '2026-09-27 08:00:01+00');

INSERT INTO risk_screening_audit (screening_id, txn_id, customer_id, risk_score, risk_level, risk_decision, triggered_rules, evaluation_latency_ms, evaluated_at, created_at)
VALUES ('11111111-1111-1111-1111-111111111102', 'b314f899-2fc0-4087-80e6-09e87ece0d49', '7bd20304-dd6a-4e45-850b-58df8b535be4',
        0.10, 'LOW', 'ALLOW', '[]'::jsonb, 15.20,
        TIMESTAMPTZ '2026-09-27 09:15:01+00', TIMESTAMPTZ '2026-09-27 09:15:01+00');

INSERT INTO risk_screening_audit (screening_id, txn_id, customer_id, risk_score, risk_level, risk_decision, triggered_rules, evaluation_latency_ms, evaluated_at, created_at)
VALUES ('11111111-1111-1111-1111-111111111103', '8a2adc48-4cf1-4478-b317-57d60b9bf0ee', '7bd20304-dd6a-4e45-850b-58df8b535be4',
        0.12, 'LOW', 'ALLOW', '[]'::jsonb, 18.70,
        TIMESTAMPTZ '2026-09-27 10:30:01+00', TIMESTAMPTZ '2026-09-27 10:30:01+00');

INSERT INTO risk_screening_audit (screening_id, txn_id, customer_id, risk_score, risk_level, risk_decision, triggered_rules, evaluation_latency_ms, evaluated_at, created_at)
VALUES ('11111111-1111-1111-1111-111111111104', 'ffc2ea15-57a3-4d26-9840-611adf6deda6', '66e07d02-05c9-4deb-87d5-10e23cf5968f',
        0.08, 'LOW', 'ALLOW', '[]'::jsonb, 14.10,
        TIMESTAMPTZ '2026-09-27 13:45:01+00', TIMESTAMPTZ '2026-09-27 13:45:01+00');

INSERT INTO risk_screening_audit (screening_id, txn_id, customer_id, risk_score, risk_level, risk_decision, triggered_rules, evaluation_latency_ms, evaluated_at, created_at)
VALUES ('11111111-1111-1111-1111-111111111105', '672898a7-a8d9-4041-8574-4fd1c4bc0743', '66e07d02-05c9-4deb-87d5-10e23cf5968f',
        0.06, 'LOW', 'ALLOW', '[]'::jsonb, 11.80,
        TIMESTAMPTZ '2026-09-27 16:00:01+00', TIMESTAMPTZ '2026-09-27 16:00:01+00');
