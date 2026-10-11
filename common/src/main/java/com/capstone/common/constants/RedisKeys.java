package com.capstone.common.constants;

public final class RedisKeys {

    private RedisKeys() {
    }

    public static final String BALANCE_PREFIX =
            "balance:";

    public static final String IDEMPOTENCY_PREFIX =
            "idempotency:";

    public static final String TOKEN_PREFIX =
            "token:";

    public static final String TRANSACTION_ID_PREFIX =
            "transaction-id:";

    public static final String OTP_PREFIX =
            "otp:";

    /** accountId is a String UUID from ACCOUNT_MASTER.account_id */
    public static String balanceKey(String accountId) {
        return BALANCE_PREFIX + accountId;
    }

    public static String idempotencyKey(String key) {
        return IDEMPOTENCY_PREFIX + key;
    }

    public static String tokenKey(String jwt) {
        return TOKEN_PREFIX + jwt;
    }

    /**
     * Redis key for the transaction UUID generated/captured
     * by TransactionIdInterceptor.
     */
    public static String transactionIdKey(String transactionId) {
        return TRANSACTION_ID_PREFIX + transactionId;
    }

    public static String otpKey(String identifier) {
        return OTP_PREFIX + identifier;
    }

    public static String otpAttemptsKey(String identifier) {
        return OTP_PREFIX + "attempts:" + identifier;
    }

    public static String otpVerifiedKey(String identifier) {
        return OTP_PREFIX + "verified:" + identifier;
    }

    // --- DIG-69: Device Binding & Login Lockout ---
    public static final String DEVICE_PREFIX = "device:";
    public static final String LOGIN_PREFIX = "login:";

    public static String deviceBindingKey(String customerId) {
        return DEVICE_PREFIX + "bound:" + customerId;
    }

    public static String loginFailedAttemptsKey(String identifier) {
        return LOGIN_PREFIX + "failed:" + identifier;
    }

    public static String loginLockoutKey(String identifier) {
        return LOGIN_PREFIX + "locked:" + identifier;
    }

    // --- DIG-70: PIN Fallback & Lockout ---
    public static final String PIN_PREFIX = "pin:";

    public static String pinKey(String customerId) {
        return PIN_PREFIX + "hash:" + customerId;
    }

    public static String pinAttemptsKey(String customerId) {
        return PIN_PREFIX + "attempts:" + customerId;
    }

    public static String pinLockoutKey(String customerId) {
        return PIN_PREFIX + "locked:" + customerId;
    }
}