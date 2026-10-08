-- =====================================================================
-- POSTGRESQL 15+ - CHANNEL DATABASE (CUSTOMER IDENTITY & NOTIFICATIONS)
-- Database: channel_db (User: channel_user)
--
-- Aligned with System Architecture Specification (ARCHITECTURE.md):
--   Tier 3 Channel Orchestration & Digital Banking Layer
--   - Physically isolated database for customer login credentials, PINs,
--     device hashes, and in-app inbox notification history.
--   - Completely segregated from Tier 4 Core Banking master ledgers.
--
-- Tables:
--   1. app_user_master  - BCrypt login credentials, PIN hashes, and device fingerprints
--   2. notification_log - Customer push, email, and in-app inbox alert logs
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- ---------------------------------------------------------------------
-- 1. APP_USER_MASTER (Channel DB)
-- Owned by login-service / registration-service.
-- Stores hashed authentication artifacts for retail customer mobile/web apps.
-- ---------------------------------------------------------------------
CREATE TABLE app_user_master (
    user_id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    customer_id      VARCHAR(36),                        -- Logical reference to CBS CUSTOMER_MASTER; NULL for admin/ops staff
    username         VARCHAR(50)   NOT NULL,             -- Unique email or login handle
    password_hash    VARCHAR(255)  NOT NULL,             -- BCrypt password hash ($2a$ / $2b$)
    pin_hash         VARCHAR(255),                       -- BCrypt 6-digit transaction/fallback PIN hash (R-10)
    device_hash      VARCHAR(64),                        -- SHA-256 fingerprint of bound mobile/web device
    failed_attempts  INTEGER       DEFAULT 0 NOT NULL,   -- Failed login attempt counter (locks at 3)
    locked_until     TIMESTAMPTZ,                        -- Lockout expiry timestamp
    active_status    VARCHAR(20)   DEFAULT 'ACTIVE' NOT NULL,
    role             VARCHAR(20)   DEFAULT 'CUSTOMER' NOT NULL,
    created_at       TIMESTAMPTZ   DEFAULT now() NOT NULL,
    created_by       VARCHAR(50)   DEFAULT 'SYSTEM' NOT NULL,
    updated_at       TIMESTAMPTZ,
    updated_by       VARCHAR(50),
    CONSTRAINT uq_app_user_master_username UNIQUE (username),
    CONSTRAINT ck_app_user_master_active_status 
        CHECK (active_status IN ('ACTIVE', 'SUSPENDED', 'LOCKED', 'DISABLED')),
    CONSTRAINT ck_app_user_master_role 
        CHECK (role IN ('CUSTOMER', 'OPS_VIEWER', 'ADMIN', 'CHECKER')),
    CONSTRAINT ck_app_user_failed_attempts 
        CHECK (failed_attempts >= 0)
);

CREATE INDEX ix_app_user_customer_id ON app_user_master (customer_id);
CREATE INDEX ix_app_user_username ON app_user_master (username);
CREATE INDEX ix_app_user_device ON app_user_master (device_hash);

COMMENT ON TABLE app_user_master IS 'Channel identity credentials, password/PIN hashes, and device bindings';

-- ---------------------------------------------------------------------
-- 2. NOTIFICATION_LOG (Channel DB)
-- Owned by notification-service.
-- Stores in-app inbox alerts, push notifications, and email delivery statuses.
-- ---------------------------------------------------------------------
CREATE TABLE notification_log (
    notif_id     UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    customer_id  VARCHAR(36)   NOT NULL,                 -- Logical reference to customer
    channel      VARCHAR(20)   DEFAULT 'IN_APP' NOT NULL,-- 'PUSH', 'EMAIL', 'IN_APP'
    message      TEXT          NOT NULL,                 -- User-facing alert message
    status       VARCHAR(20)   DEFAULT 'PENDING' NOT NULL,-- 'PENDING', 'SENT', 'FAILED', 'READ'
    created_at   TIMESTAMPTZ   DEFAULT now() NOT NULL,
    read_at      TIMESTAMPTZ,
    CONSTRAINT ck_notification_log_channel 
        CHECK (channel IN ('PUSH', 'EMAIL', 'IN_APP')),
    CONSTRAINT ck_notification_log_status 
        CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'READ'))
);

CREATE INDEX ix_notification_log_customer_id ON notification_log (customer_id);
CREATE INDEX ix_notification_log_created_at ON notification_log (created_at DESC);
CREATE INDEX ix_notification_log_status ON notification_log (status);

COMMENT ON TABLE notification_log IS 'Customer in-app inbox notifications and omnichannel dispatch logs';

