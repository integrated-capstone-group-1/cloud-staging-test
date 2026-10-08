-- =====================================================================
-- POSTGRESQL 15+ - CHANNEL DATABASE SEED DATA
-- 02_seed_data_channel.sql
--
-- Aligned with System Architecture Specification (ARCHITECTURE.md):
--   1. Customer and administrative user credentials (app_user_master)
--   2. In-app inbox alert logs and dispatch history (notification_log)
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. APP_USER_MASTER
-- Standard Personas:
--   Admin: admin@ledgerbank.com / admin123 (ROLE_ADMIN)
--   Customer 1: juan.delacruz@example.com / password123 (ROLE_CUSTOMER)
--   Customer 2: maria.santos@example.com / password123 (ROLE_CUSTOMER)
-- ---------------------------------------------------------------------
INSERT INTO app_user_master (user_id, customer_id, username, password_hash, pin_hash, device_hash, failed_attempts, role, active_status, created_at, created_by)
VALUES ('69aec640-58c2-41a0-ad68-856e7c9345cc', '2d49c23b-5f65-48da-aa68-28b155dd4022',
        'admin@ledgerbank.com', '$2b$12$k4MRn5BmsyCSbiVa6k8e0O/xp/YSne2/CQuvxVOKohZ86.VXAWkoW',
        NULL, NULL, 0, 'ADMIN', 'ACTIVE',
        TIMESTAMPTZ '2026-09-01 00:00:00+00', 'seed');

INSERT INTO app_user_master (user_id, customer_id, username, password_hash, pin_hash, device_hash, failed_attempts, role, active_status, created_at, created_by)
VALUES ('5790bfd2-5c28-4b59-abe8-3f460099a264', '7bd20304-dd6a-4e45-850b-58df8b535be4',
        'juan.delacruz@example.com', '$2b$12$7c3QXl8O0IFVgBcH1ea2nOKPMKiVkP4x85RR2.w31/iHKwXYDwvre',
        '$2a$10$wK1Rk2vJQwO6fDq6k6j5h.6n1bQx1Y1KqX8e.h9y/h8l8g8f8e8d8', 'd3b07384d113edec49eaa6238ad5ff00', 0,
        'CUSTOMER', 'ACTIVE', TIMESTAMPTZ '2026-09-20 00:00:00+00', 'seed');

INSERT INTO app_user_master (user_id, customer_id, username, password_hash, pin_hash, device_hash, failed_attempts, role, active_status, created_at, created_by)
VALUES ('a40e6a5f-72c4-4c1a-a298-815053f14b23', '66e07d02-05c9-4deb-87d5-10e23cf5968f',
        'maria.santos@example.com', '$2b$12$7c3QXl8O0IFVgBcH1ea2nOKPMKiVkP4x85RR2.w31/iHKwXYDwvre',
        '$2a$10$wK1Rk2vJQwO6fDq6k6j5h.6n1bQx1Y1KqX8e.h9y/h8l8g8f8e8d8', 'c4ca4238a0b923820dcc509a6f75849b', 0,
        'CUSTOMER', 'ACTIVE', TIMESTAMPTZ '2026-09-20 00:00:00+00', 'seed');

-- ---------------------------------------------------------------------
-- 2. NOTIFICATION_LOG
-- Initial onboarding notices and transaction alerts for Juan & Maria
-- ---------------------------------------------------------------------
INSERT INTO notification_log (notif_id, customer_id, channel, message, status, created_at, read_at)
VALUES ('e1111111-1111-1111-1111-111111111101', '7bd20304-dd6a-4e45-850b-58df8b535be4',
        'IN_APP', 'Welcome to Capstone Banking! Your savings and checking pockets are active and funded.',
        'READ', TIMESTAMPTZ '2026-09-20 08:00:00+00', TIMESTAMPTZ '2026-09-20 08:30:00+00');

INSERT INTO notification_log (notif_id, customer_id, channel, message, status, created_at, read_at)
VALUES ('e1111111-1111-1111-1111-111111111102', '7bd20304-dd6a-4e45-850b-58df8b535be4',
        'PUSH', 'Your transfer of PHP 1,000.00 to Maria Santos has been posted successfully.',
        'SENT', TIMESTAMPTZ '2026-09-27 10:30:08+00', NULL);

INSERT INTO notification_log (notif_id, customer_id, channel, message, status, created_at, read_at)
VALUES ('e1111111-1111-1111-1111-111111111103', '66e07d02-05c9-4deb-87d5-10e23cf5968f',
        'IN_APP', 'Welcome to Capstone Banking! Your primary checking pocket and virtual card are active.',
        'READ', TIMESTAMPTZ '2026-09-20 08:00:00+00', TIMESTAMPTZ '2026-09-20 09:00:00+00');

INSERT INTO notification_log (notif_id, customer_id, channel, message, status, created_at, read_at)
VALUES ('e1111111-1111-1111-1111-111111111104', '66e07d02-05c9-4deb-87d5-10e23cf5968f',
        'PUSH', 'You received PHP 1,000.00 from Juan Dela Cruz. Available balance: PHP 9,000.00.',
        'SENT', TIMESTAMPTZ '2026-09-27 10:30:08+00', NULL);

