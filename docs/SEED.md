# Seed Data & Testing Fixtures

Aligned directly with [`sql/oracle/02_seed_data_oracle.sql`](file:///c:/Users/RBS83790/OneDrive%20-%20EastWest%20Banking%20Corporation/Documents/training/full-stack/capstone-project/capstone-banking-system/sql/oracle/02_seed_data_oracle.sql) and [`sql/postgres/02_seed_data_postgres.sql`](file:///c:/Users/RBS83790/OneDrive%20-%20EastWest%20Banking%20Corporation/Documents/training/full-stack/capstone-project/capstone-banking-system/sql/postgres/02_seed_data_postgres.sql).

---

## 1. Customers & Users (Oracle XE: `customer_master` & `app_user_master`)

| Customer ID | Username / Email | Role | Name | Initial Status |
| :--- | :--- | :--- | :--- | :--- |
| `2d49c23b-5f65-48da-aa68-28b155dd4022` (`cust-admin-001`) | `admin@ledgerbank.com` | `ADMIN` | System Admin | `ACTIVE` |
| `7bd20304-dd6a-4e45-850b-58df8b535be4` (`cust-user-001`) | `juan.delacruz@example.com` | `CUSTOMER` | Juan Dela Cruz | `ACTIVE` |
| `66e07d02-05c9-4deb-87d5-10e23cf5968f` (`cust-user-002`) | `maria.santos@example.com` | `CUSTOMER` | Maria Santos | `ACTIVE` |

*Default password for all seed accounts:* `password123` (BCrypt hash)  
*Admin password:* `admin123` (BCrypt hash)

---

## 2. Customer Pockets & Accounts (Oracle XE: `account_master`)

| Account ID | Customer ID | Account Number | Account Type | Currency | Normal Balance | Is Primary | Balance Amount | Status |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `3722f77f-3d1b-4d55-8f83-b71bac91d095` (`acct-juan-php-01`) | `7bd20304-dd6a-4e45-850b-58df8b535be4` | `100000000001` | `SAVINGS` | `PHP` | `CR` | `Y` | `17300.0000` | `ACTIVE` |
| `a86c97cc-1ea5-4767-bd5b-148968354f9c` (`acct-juan-php-02`) | `7bd20304-dd6a-4e45-850b-58df8b535be4` | `100000000001` | `SAVINGS` | `PHP` | `CR` | `N` | `3500.0000` | `ACTIVE` |
| `acct-juan-eur-01` | `7bd20304-dd6a-4e45-850b-58df8b535be4` | `100000000001` | `WALLET` | `EUR` | `CR` | `N` | `100.0000` | `ACTIVE` |
| `f95e7e52-3259-4b4d-ac66-b186a893cf66` (`acct-maria-php-01`) | `66e07d02-05c9-4deb-87d5-10e23cf5968f` | `100000000002` | `SAVINGS` | `PHP` | `CR` | `Y` | `9000.0000` | `ACTIVE` |
| `86cabb96-61d5-462a-b33b-5a6435649eb4` (`acct-maria-php-02`) | `66e07d02-05c9-4deb-87d5-10e23cf5968f` | `100000000002` | `WALLET` | `PHP` | `CR` | `N` | `2950.0000` | `ACTIVE` |
| `acct-maria-eur-01` | `66e07d02-05c9-4deb-87d5-10e23cf5968f` | `100000000002` | `WALLET` | `EUR` | `CR` | `N` | `50.0000` | `ACTIVE` |

---

## 3. Bank Internal FX & Fee Accounts (Oracle XE: `account_master`)

Used for double-entry settlement of cross-currency transfers and FX spread fee bookings (customer_id is `NULL`):

| Account ID | Account Number | Account Type | Currency | Normal Balance | Balance Amount | Status |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `bank-fx-eur-01` | `BANKFXEUR` | `BANK_FX_POSITION` | `EUR` | `DR` | `1000000.0000` | `ACTIVE` |
| `bank-fx-php-01` | `BANKFXPHP` | `BANK_FX_POSITION` | `PHP` | `DR` | `50000000.0000` | `ACTIVE` |
| `bank-fee-income-php-01` | `BANKFEEPHP` | `BANK_FEE_INCOME` | `PHP` | `CR` | `50000.0000` | `ACTIVE` |
| `bank-cash-clearing-php-01` | `BANKCLEARING` | `BANK_CASH_CLEARING` | `PHP` | `DR` | `100000000.0000` | `ACTIVE` |

---

## 4. Virtual Cards (Oracle XE: `card_master`)

| Card ID | Customer ID | Account ID (Linked) | Masked PAN | Ecom Enabled | Status | Daily Limit |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `c4a1b2c3-d4e5-4f6a-8b9c-0d1e2f3a4b5c` (`card-juan-01`) | `7bd20304-dd6a-4e45-850b-58df8b535be4` | `3722f77f-3d1b-4d55-8f83-b71bac91d095` | `4532-XXXX-XXXX-8821` | `1` | `ACTIVE` | `50000.00` |
| `d5b2c3d4-e5f6-4a7b-9c0d-1e2f3a4b5c6d` (`card-maria-01`) | `66e07d02-05c9-4deb-87d5-10e23cf5968f` | `f95e7e52-3259-4b4d-ac66-b186a893cf66` | `4532-XXXX-XXXX-9932` | `1` | `ACTIVE` | `50000.00` |

---

## 5. Cached FX Rates (PostgreSQL: `fx_rate_cache`)

| Base Currency | Quote Currency | Rate | Source |
| :--- | :--- | :--- | :--- |
| `EUR` | `PHP` | `62.00000000` | `frankfurter.dev` |
| `USD` | `PHP` | `58.00000000` | `frankfurter.dev` |
| `EUR` | `USD` | `1.06000000` | `frankfurter.dev` |
| `PHP` | `EUR` | `0.01612903` | `frankfurter.dev` |
| `PHP` | `USD` | `0.01724138` | `frankfurter.dev` |
