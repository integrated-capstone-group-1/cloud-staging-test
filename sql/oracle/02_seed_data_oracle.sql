-- =====================================================================
-- ORACLE XE 21c - SEED DATA (REVISED SCHEMA COMPLIANT)
-- 02_seed_data_oracle.sql
--
-- Aligned with System Architecture Specification (ARCHITECTURE.md):
-- 1. Standard Customer Personas & KYC Records (customer_master)
-- 2. Core Account Pockets & Internal Bank Clearing Accounts (account_master)
-- 3. Virtual Cards with PCI-DSS Masked PAN & Daily Limits (card_master)
-- 4. Treasury Foreign Exchange Reference Rates (fx_rate)
-- 5. Standard Initial Transactions covering DEPOSIT, WITHDRAWAL, TRANSFER (transaction_master)
-- 6. CBS Settlement Posting References (posting_reference)
-- 7. Authoritative Core Journal Double-Entry Legs (ledger_entry)
--    - entry_id matches mutation_uuid in postgres/02_seed_data_postgres.sql
-- 8. Core Transactional Outbox Events (outbox_master)
-- 9. App User Credentials for Oracle Fallback (app_user_master)
-- =====================================================================

ALTER SESSION SET CONTAINER = XEPDB1;
ALTER SESSION SET CURRENT_SCHEMA = LEDGER_APP;

-- =====================================================================
-- 1. CUSTOMER_MASTER
-- =====================================================================
INSERT INTO customer_master (customer_id, first_name, last_name, email, contact_no, birth_date, id_type, id_number, residential_address, kyc_status, created_at, created_by)
VALUES ('2d49c23b-5f65-48da-aa68-28b155dd4022', 'System', 'Administrator', 'admin@ledgerbank.com', NULL, NULL, NULL, NULL, NULL, 'VERIFIED', SYSTIMESTAMP, 'SYSTEM');

INSERT INTO customer_master (customer_id, first_name, last_name, email, contact_no, birth_date, id_type, id_number, residential_address, kyc_status, created_at, created_by)
VALUES ('7bd20304-dd6a-4e45-850b-58df8b535be4', 'Juan', 'Dela Cruz', 'juan.delacruz@example.com', '+639171234567', DATE '1995-03-14', 'PASSPORT', 'P8291044A', '128 Pioneer St, Mandaluyong, Metro Manila', 'VERIFIED', SYSTIMESTAMP, 'SYSTEM');

INSERT INTO customer_master (customer_id, first_name, last_name, email, contact_no, birth_date, id_type, id_number, residential_address, kyc_status, created_at, created_by)
VALUES ('66e07d02-05c9-4deb-87d5-10e23cf5968f', 'Maria', 'Santos', 'maria.santos@example.com', '+639189876543', DATE '1998-11-02', 'NATIONAL_ID', 'N12345678', '45 Ayala Avenue, Makati City, Metro Manila', 'VERIFIED', SYSTIMESTAMP, 'SYSTEM');

-- =====================================================================
-- 2. APP_USER_MASTER (Fallback credentials in Oracle schema)
-- =====================================================================
INSERT INTO app_user_master (user_id, customer_id, username, password_hash, role, active_status, created_at, created_by)
VALUES ('69aec640-58c2-41a0-ad68-856e7c9345cc', '2d49c23b-5f65-48da-aa68-28b155dd4022',
        'admin@ledgerbank.com', '$2b$12$k4MRn5BmsyCSbiVa6k8e0O/xp/YSne2/CQuvxVOKohZ86.VXAWkoW',
        'ADMIN', 'ACTIVE', SYSTIMESTAMP, 'SYSTEM');

INSERT INTO app_user_master (user_id, customer_id, username, password_hash, role, active_status, created_at, created_by)
VALUES ('5790bfd2-5c28-4b59-abe8-3f460099a264', '7bd20304-dd6a-4e45-850b-58df8b535be4',
        'juan.delacruz@example.com', '$2b$12$7c3QXl8O0IFVgBcH1ea2nOKPMKiVkP4x85RR2.w31/iHKwXYDwvre',
        'CUSTOMER', 'ACTIVE', SYSTIMESTAMP, 'SYSTEM');

INSERT INTO app_user_master (user_id, customer_id, username, password_hash, role, active_status, created_at, created_by)
VALUES ('a40e6a5f-72c4-4c1a-a298-815053f14b23', '66e07d02-05c9-4deb-87d5-10e23cf5968f',
        'maria.santos@example.com', '$2b$12$7c3QXl8O0IFVgBcH1ea2nOKPMKiVkP4x85RR2.w31/iHKwXYDwvre',
        'CUSTOMER', 'ACTIVE', SYSTIMESTAMP, 'SYSTEM');

-- =====================================================================
-- 3. ACCOUNT_MASTER
-- Includes customer currency pockets and internal bank clearing pools.
-- Balances reflect the net posted state after transactions T1-T5.
-- =====================================================================
-- Customer Juan Pockets (account_number: 100000000001)
INSERT INTO account_master (account_id, customer_id, account_number, account_type, currency_code, normal_balance, balance_amount, held_amount, customer_locked, admin_frozen, account_status, is_primary, version, created_at, created_by)
VALUES ('3722f77f-3d1b-4d55-8f83-b71bac91d095', '7bd20304-dd6a-4e45-850b-58df8b535be4',
        '100000000001', 'SAVINGS', 'PHP', 'CR', 17300.0000, 0, 'N', 'N', 'ACTIVE', 'Y', 0,
        TIMESTAMP '2026-09-20 00:00:00', 'SYSTEM');

INSERT INTO account_master (account_id, customer_id, account_number, account_type, currency_code, normal_balance, balance_amount, held_amount, customer_locked, admin_frozen, account_status, is_primary, version, created_at, created_by)
VALUES ('a86c97cc-1ea5-4767-bd5b-148968354f9c', '7bd20304-dd6a-4e45-850b-58df8b535be4',
        '100000000001', 'CHECKING', 'PHP', 'CR', 3500.0000, 0, 'N', 'N', 'ACTIVE', 'N', 0,
        TIMESTAMP '2026-09-20 00:00:00', 'SYSTEM');

INSERT INTO account_master (account_id, customer_id, account_number, account_type, currency_code, normal_balance, balance_amount, held_amount, customer_locked, admin_frozen, account_status, is_primary, version, created_at, created_by)
VALUES ('acct-juan-eur-01', '7bd20304-dd6a-4e45-850b-58df8b535be4',
        '100000000001', 'WALLET', 'EUR', 'CR', 100.0000, 0, 'N', 'N', 'ACTIVE', 'N', 0,
        TIMESTAMP '2026-09-20 00:00:00', 'SYSTEM');

-- Customer Maria Pockets (account_number: 100000000002)
INSERT INTO account_master (account_id, customer_id, account_number, account_type, currency_code, normal_balance, balance_amount, held_amount, customer_locked, admin_frozen, account_status, is_primary, version, created_at, created_by)
VALUES ('f95e7e52-3259-4b4d-ac66-b186a893cf66', '66e07d02-05c9-4deb-87d5-10e23cf5968f',
        '100000000002', 'CHECKING', 'PHP', 'CR', 9000.0000, 0, 'N', 'N', 'ACTIVE', 'Y', 0,
        TIMESTAMP '2026-09-20 00:00:00', 'SYSTEM');

INSERT INTO account_master (account_id, customer_id, account_number, account_type, currency_code, normal_balance, balance_amount, held_amount, customer_locked, admin_frozen, account_status, is_primary, version, created_at, created_by)
VALUES ('86cabb96-61d5-462a-b33b-5a6435649eb4', '66e07d02-05c9-4deb-87d5-10e23cf5968f',
        '100000000002', 'WALLET', 'PHP', 'CR', 2950.0000, 0, 'N', 'N', 'ACTIVE', 'N', 0,
        TIMESTAMP '2026-09-20 00:00:00', 'SYSTEM');

INSERT INTO account_master (account_id, customer_id, account_number, account_type, currency_code, normal_balance, balance_amount, held_amount, customer_locked, admin_frozen, account_status, is_primary, version, created_at, created_by)
VALUES ('acct-maria-eur-01', '66e07d02-05c9-4deb-87d5-10e23cf5968f',
        '100000000002', 'WALLET', 'EUR', 'CR', 50.0000, 0, 'N', 'N', 'ACTIVE', 'N', 0,
        TIMESTAMP '2026-09-20 00:00:00', 'SYSTEM');

-- Internal Bank Clearing Accounts (customer_id is NULL)
INSERT INTO account_master (account_id, customer_id, account_number, account_type, currency_code, normal_balance, balance_amount, held_amount, customer_locked, admin_frozen, account_status, is_primary, version, created_at, created_by)
VALUES ('bank-fx-eur-01', NULL, 'BANKFXEUR', 'BANK_FX_POSITION', 'EUR', 'DR', 1000000.0000, 0, 'N', 'N', 'ACTIVE', 'N', 0,
        TIMESTAMP '2026-09-01 00:00:00', 'SYSTEM');

INSERT INTO account_master (account_id, customer_id, account_number, account_type, currency_code, normal_balance, balance_amount, held_amount, customer_locked, admin_frozen, account_status, is_primary, version, created_at, created_by)
VALUES ('bank-fx-php-01', NULL, 'BANKFXPHP', 'BANK_FX_POSITION', 'PHP', 'DR', 50000000.0000, 0, 'N', 'N', 'ACTIVE', 'N', 0,
        TIMESTAMP '2026-09-01 00:00:00', 'SYSTEM');

INSERT INTO account_master (account_id, customer_id, account_number, account_type, currency_code, normal_balance, balance_amount, held_amount, customer_locked, admin_frozen, account_status, is_primary, version, created_at, created_by)
VALUES ('bank-fee-income-php-01', NULL, 'BANKFEEPHP', 'BANK_FEE_INCOME', 'PHP', 'CR', 50000.0000, 0, 'N', 'N', 'ACTIVE', 'N', 0,
        TIMESTAMP '2026-09-01 00:00:00', 'SYSTEM');

INSERT INTO account_master (account_id, customer_id, account_number, account_type, currency_code, normal_balance, balance_amount, held_amount, customer_locked, admin_frozen, account_status, is_primary, version, created_at, created_by)
VALUES ('bank-cash-clearing-php-01', NULL, 'BANKCLEARING', 'BANK_CASH_CLEARING', 'PHP', 'DR', 100000000.0000, 0, 'N', 'N', 'ACTIVE', 'N', 0,
        TIMESTAMP '2026-09-01 00:00:00', 'SYSTEM');

-- =====================================================================
-- 4. CARD_MASTER (CBS Schema)
-- =====================================================================
INSERT INTO card_master (card_id, customer_id, account_id, card_token, masked_pan, expiry_date, card_status, ecom_enabled, daily_limit, created_at, created_by)
VALUES ('c4a1b2c3-d4e5-4f6a-8b9c-0d1e2f3a4b5c', '7bd20304-dd6a-4e45-850b-58df8b535be4',
        '3722f77f-3d1b-4d55-8f83-b71bac91d095', 'tok_juan_virtual_card_01', '4532-XXXX-XXXX-8821', '12/28',
        'ACTIVE', 1, 50000.00, TIMESTAMP '2026-09-20 00:00:00', 'SYSTEM');

INSERT INTO card_master (card_id, customer_id, account_id, card_token, masked_pan, expiry_date, card_status, ecom_enabled, daily_limit, created_at, created_by)
VALUES ('d5b2c3d4-e5f6-4a7b-9c0d-1e2f3a4b5c6d', '66e07d02-05c9-4deb-87d5-10e23cf5968f',
        'f95e7e52-3259-4b4d-ac66-b186a893cf66', 'tok_maria_virtual_card_01', '4532-XXXX-XXXX-9932', '06/29',
        'ACTIVE', 1, 50000.00, TIMESTAMP '2026-09-20 00:00:00', 'SYSTEM');

-- =====================================================================
-- 5. FX_RATE (FX Schema)
-- =====================================================================
INSERT INTO fx_rate (base_currency, quote_currency, rate, fetched_at, source)
VALUES ('USD', 'PHP', 58.50000000, TIMESTAMP '2026-09-27 00:00:00', 'frankfurter');

INSERT INTO fx_rate (base_currency, quote_currency, rate, fetched_at, source)
VALUES ('PHP', 'USD', 0.01709402, TIMESTAMP '2026-09-27 00:00:00', 'frankfurter');

INSERT INTO fx_rate (base_currency, quote_currency, rate, fetched_at, source)
VALUES ('EUR', 'PHP', 63.25000000, TIMESTAMP '2026-09-27 00:00:00', 'frankfurter');

INSERT INTO fx_rate (base_currency, quote_currency, rate, fetched_at, source)
VALUES ('PHP', 'EUR', 0.01581028, TIMESTAMP '2026-09-27 00:00:00', 'frankfurter');

INSERT INTO fx_rate (base_currency, quote_currency, rate, fetched_at, source)
VALUES ('EUR', 'USD', 1.08119658, TIMESTAMP '2026-09-27 00:00:00', 'frankfurter');

INSERT INTO fx_rate (base_currency, quote_currency, rate, fetched_at, source)
VALUES ('USD', 'EUR', 0.92490119, TIMESTAMP '2026-09-27 00:00:00', 'frankfurter');

-- =====================================================================
-- 6. TRANSACTION_MASTER (PAYMENT Schema)
-- T1: DEPOSIT +2000 into Juan Savings
-- T2: WITHDRAWAL -500 from Juan Checking
-- T3: TRANSFER 1000 from Juan Checking -> Maria Checking
-- T4: TRANSFER 300 from Maria Wallet -> Juan Savings
-- T5: DEPOSIT +750 into Maria Wallet
-- =====================================================================
INSERT INTO transaction_master (txn_id, customer_id, txn_type, debit_account_id, credit_account_id, mutation_amount, currency_code, is_cross_currency, dest_amount, dest_currency_code, txn_status, stage, core_reference_id, business_date, initiated_at, completed_at, created_at, created_by)
VALUES ('05da5006-2cc5-4dd0-87ab-aeca1f0f7ab1', '7bd20304-dd6a-4e45-850b-58df8b535be4',
        'DEPOSIT', NULL, '3722f77f-3d1b-4d55-8f83-b71bac91d095',
        2000.0000, 'PHP', 'N', NULL, NULL,
        'POSTED', 'COMPLETED', 'FT2627100001', DATE '2026-09-27',
        TIMESTAMP '2026-09-27 08:00:00', TIMESTAMP '2026-09-27 08:00:03', TIMESTAMP '2026-09-27 08:00:00', 'seed');

INSERT INTO transaction_master (txn_id, customer_id, txn_type, debit_account_id, credit_account_id, mutation_amount, currency_code, is_cross_currency, dest_amount, dest_currency_code, txn_status, stage, core_reference_id, business_date, initiated_at, completed_at, created_at, created_by)
VALUES ('b314f899-2fc0-4087-80e6-09e87ece0d49', '7bd20304-dd6a-4e45-850b-58df8b535be4',
        'WITHDRAWAL', 'a86c97cc-1ea5-4767-bd5b-148968354f9c', NULL,
        500.0000, 'PHP', 'N', NULL, NULL,
        'POSTED', 'COMPLETED', 'FT2627100002', DATE '2026-09-27',
        TIMESTAMP '2026-09-27 09:15:00', TIMESTAMP '2026-09-27 09:15:02', TIMESTAMP '2026-09-27 09:15:00', 'seed');

INSERT INTO transaction_master (txn_id, customer_id, txn_type, debit_account_id, credit_account_id, mutation_amount, currency_code, is_cross_currency, dest_amount, dest_currency_code, txn_status, stage, core_reference_id, business_date, initiated_at, completed_at, created_at, created_by)
VALUES ('8a2adc48-4cf1-4478-b317-57d60b9bf0ee', '7bd20304-dd6a-4e45-850b-58df8b535be4',
        'TRANSFER', 'a86c97cc-1ea5-4767-bd5b-148968354f9c', 'f95e7e52-3259-4b4d-ac66-b186a893cf66',
        1000.0000, 'PHP', 'N', 1000.0000, 'PHP',
        'POSTED', 'COMPLETED', 'FT2627100003', DATE '2026-09-27',
        TIMESTAMP '2026-09-27 10:30:00', TIMESTAMP '2026-09-27 10:30:05', TIMESTAMP '2026-09-27 10:30:00', 'seed');

INSERT INTO transaction_master (txn_id, customer_id, txn_type, debit_account_id, credit_account_id, mutation_amount, currency_code, is_cross_currency, dest_amount, dest_currency_code, txn_status, stage, core_reference_id, business_date, initiated_at, completed_at, created_at, created_by)
VALUES ('ffc2ea15-57a3-4d26-9840-611adf6deda6', '66e07d02-05c9-4deb-87d5-10e23cf5968f',
        'TRANSFER', '86cabb96-61d5-462a-b33b-5a6435649eb4', '3722f77f-3d1b-4d55-8f83-b71bac91d095',
        300.0000, 'PHP', 'N', 300.0000, 'PHP',
        'POSTED', 'COMPLETED', 'FT2627100004', DATE '2026-09-27',
        TIMESTAMP '2026-09-27 13:45:00', TIMESTAMP '2026-09-27 13:45:04', TIMESTAMP '2026-09-27 13:45:00', 'seed');

INSERT INTO transaction_master (txn_id, customer_id, txn_type, debit_account_id, credit_account_id, mutation_amount, currency_code, is_cross_currency, dest_amount, dest_currency_code, txn_status, stage, core_reference_id, business_date, initiated_at, completed_at, created_at, created_by)
VALUES ('672898a7-a8d9-4041-8574-4fd1c4bc0743', '66e07d02-05c9-4deb-87d5-10e23cf5968f',
        'DEPOSIT', NULL, '86cabb96-61d5-462a-b33b-5a6435649eb4',
        750.0000, 'PHP', 'N', NULL, NULL,
        'POSTED', 'COMPLETED', 'FT2627100005', DATE '2026-09-27',
        TIMESTAMP '2026-09-27 16:00:00', TIMESTAMP '2026-09-27 16:00:02', TIMESTAMP '2026-09-27 16:00:00', 'seed');

-- =====================================================================
-- 7. POSTING_REFERENCE (CBS Schema)
-- =====================================================================
INSERT INTO posting_reference (posting_reference_id, core_reference_id, request_hash, txn_type, status, settled_at)
VALUES ('05da5006-2cc5-4dd0-87ab-aeca1f0f7ab1', 'FT2627100001', 'reqhash-001', 'DEPOSIT', 'SETTLED', TIMESTAMP '2026-09-27 08:00:03');

INSERT INTO posting_reference (posting_reference_id, core_reference_id, request_hash, txn_type, status, settled_at)
VALUES ('b314f899-2fc0-4087-80e6-09e87ece0d49', 'FT2627100002', 'reqhash-002', 'WITHDRAWAL', 'SETTLED', TIMESTAMP '2026-09-27 09:15:02');

INSERT INTO posting_reference (posting_reference_id, core_reference_id, request_hash, txn_type, status, settled_at)
VALUES ('8a2adc48-4cf1-4478-b317-57d60b9bf0ee', 'FT2627100003', 'reqhash-003', 'TRANSFER', 'SETTLED', TIMESTAMP '2026-09-27 10:30:05');

INSERT INTO posting_reference (posting_reference_id, core_reference_id, request_hash, txn_type, status, settled_at)
VALUES ('ffc2ea15-57a3-4d26-9840-611adf6deda6', 'FT2627100004', 'reqhash-004', 'TRANSFER', 'SETTLED', TIMESTAMP '2026-09-27 13:45:04');

INSERT INTO posting_reference (posting_reference_id, core_reference_id, request_hash, txn_type, status, settled_at)
VALUES ('672898a7-a8d9-4041-8574-4fd1c4bc0743', 'FT2627100005', 'reqhash-005', 'DEPOSIT', 'SETTLED', TIMESTAMP '2026-09-27 16:00:02');

-- =====================================================================
-- 8. LEDGER_ENTRY (CBS Schema - Master Double-Entry Journal)
-- entry_id is the deterministic UUIDv5 matching mutation_uuid in postgres.
-- =====================================================================
-- T1: DEPOSIT into Juan Savings (1 CREDIT leg)
INSERT INTO ledger_entry (entry_id, txn_id, leg_no, account_id, direction, amount, currency_code, running_balance, value_date, business_date, narrative, posted_at)
VALUES ('7b006281-4871-4dc6-b17f-04aafcd2810a', '05da5006-2cc5-4dd0-87ab-aeca1f0f7ab1', 1,
        '3722f77f-3d1b-4d55-8f83-b71bac91d095', 'CREDIT', 2000.0000, 'PHP', 17000.0000,
        DATE '2026-09-27', DATE '2026-09-27', 'CASH_DEPOSIT', TIMESTAMP '2026-09-27 08:00:03');

-- T2: WITHDRAWAL from Juan Checking (1 DEBIT leg)
INSERT INTO ledger_entry (entry_id, txn_id, leg_no, account_id, direction, amount, currency_code, running_balance, value_date, business_date, narrative, posted_at)
VALUES ('ad4a2ffd-df4c-46a8-baee-84652f9c3672', 'b314f899-2fc0-4087-80e6-09e87ece0d49', 1,
        'a86c97cc-1ea5-4767-bd5b-148968354f9c', 'DEBIT', 500.0000, 'PHP', 4500.0000,
        DATE '2026-09-27', DATE '2026-09-27', 'ATM_WITHDRAWAL', TIMESTAMP '2026-09-27 09:15:02');

-- T3: TRANSFER Juan Checking -> Maria Checking (2 legs)
INSERT INTO ledger_entry (entry_id, txn_id, leg_no, account_id, direction, amount, currency_code, running_balance, value_date, business_date, narrative, posted_at)
VALUES ('0c221483-efea-45be-adfc-4576500866d4', '8a2adc48-4cf1-4478-b317-57d60b9bf0ee', 1,
        'a86c97cc-1ea5-4767-bd5b-148968354f9c', 'DEBIT', 1000.0000, 'PHP', 3500.0000,
        DATE '2026-09-27', DATE '2026-09-27', 'FUNDS_TRANSFER_OUT', TIMESTAMP '2026-09-27 10:30:05');

INSERT INTO ledger_entry (entry_id, txn_id, leg_no, account_id, direction, amount, currency_code, running_balance, value_date, business_date, narrative, posted_at)
VALUES ('098cfaa7-06b1-4a07-bf5e-80d19bd30ed0', '8a2adc48-4cf1-4478-b317-57d60b9bf0ee', 2,
        'f95e7e52-3259-4b4d-ac66-b186a893cf66', 'CREDIT', 1000.0000, 'PHP', 9000.0000,
        DATE '2026-09-27', DATE '2026-09-27', 'FUNDS_TRANSFER_IN', TIMESTAMP '2026-09-27 10:30:05');

-- T4: TRANSFER Maria Wallet -> Juan Savings (2 legs)
INSERT INTO ledger_entry (entry_id, txn_id, leg_no, account_id, direction, amount, currency_code, running_balance, value_date, business_date, narrative, posted_at)
VALUES ('de025c6c-547a-4010-980d-d07abc036d30', 'ffc2ea15-57a3-4d26-9840-611adf6deda6', 1,
        '86cabb96-61d5-462a-b33b-5a6435649eb4', 'DEBIT', 300.0000, 'PHP', 2200.0000,
        DATE '2026-09-27', DATE '2026-09-27', 'WALLET_TRANSFER_OUT', TIMESTAMP '2026-09-27 13:45:04');

INSERT INTO ledger_entry (entry_id, txn_id, leg_no, account_id, direction, amount, currency_code, running_balance, value_date, business_date, narrative, posted_at)
VALUES ('bf6978e7-c5c1-4060-bf2a-12862b82d44c', 'ffc2ea15-57a3-4d26-9840-611adf6deda6', 2,
        '3722f77f-3d1b-4d55-8f83-b71bac91d095', 'CREDIT', 300.0000, 'PHP', 17300.0000,
        DATE '2026-09-27', DATE '2026-09-27', 'WALLET_TRANSFER_IN', TIMESTAMP '2026-09-27 13:45:04');

-- T5: DEPOSIT into Maria Wallet (1 CREDIT leg)
INSERT INTO ledger_entry (entry_id, txn_id, leg_no, account_id, direction, amount, currency_code, running_balance, value_date, business_date, narrative, posted_at)
VALUES ('f6c7d7e6-0fda-4ec8-8804-03ced5dba3fa', '672898a7-a8d9-4041-8574-4fd1c4bc0743', 1,
        '86cabb96-61d5-462a-b33b-5a6435649eb4', 'CREDIT', 750.0000, 'PHP', 2950.0000,
        DATE '2026-09-27', DATE '2026-09-27', 'CASH_DEPOSIT', TIMESTAMP '2026-09-27 16:00:02');

-- =====================================================================
-- 9. OUTBOX_MASTER (CBS Schema)
-- =====================================================================
INSERT INTO outbox_master (outbox_id, source_service, aggregate_type, aggregate_id, event_type, payload, status, attempt_count, created_at, published_at)
VALUES ('b2e9bcdf-2e6a-4f2b-b9c9-2fc2b0483214', 'accounts-service', 'TRANSACTION', '05da5006-2cc5-4dd0-87ab-aeca1f0f7ab1',
        'payment.completed', '{"txnId":"05da5006-2cc5-4dd0-87ab-aeca1f0f7ab1","coreReferenceId":"FT2627100001","status":"SETTLED"}',
        'PUBLISHED', 1, TIMESTAMP '2026-09-27 08:00:03', TIMESTAMP '2026-09-27 08:00:04');

INSERT INTO outbox_master (outbox_id, source_service, aggregate_type, aggregate_id, event_type, payload, status, attempt_count, created_at, published_at)
VALUES ('980cfdf2-5b51-44e9-a16c-0b1ed784ea87', 'accounts-service', 'TRANSACTION', 'b314f899-2fc0-4087-80e6-09e87ece0d49',
        'payment.completed', '{"txnId":"b314f899-2fc0-4087-80e6-09e87ece0d49","coreReferenceId":"FT2627100002","status":"SETTLED"}',
        'PUBLISHED', 1, TIMESTAMP '2026-09-27 09:15:02', TIMESTAMP '2026-09-27 09:15:03');

INSERT INTO outbox_master (outbox_id, source_service, aggregate_type, aggregate_id, event_type, payload, status, attempt_count, created_at, published_at)
VALUES ('7aec3749-93a3-420b-a042-0bc990cb10c9', 'accounts-service', 'TRANSACTION', '8a2adc48-4cf1-4478-b317-57d60b9bf0ee',
        'payment.completed', '{"txnId":"8a2adc48-4cf1-4478-b317-57d60b9bf0ee","coreReferenceId":"FT2627100003","status":"SETTLED"}',
        'PUBLISHED', 1, TIMESTAMP '2026-09-27 10:30:05', TIMESTAMP '2026-09-27 10:30:06');

COMMIT;
