# Seed Data & Testing Fixtures

Aligned directly with [`sql/oracle/02_seed_data_oracle.sql`](file:///c:/Users/RBS83790/OneDrive%20-%20EastWest%20Banking%20Corporation/Documents/training/full-stack/capstone-project/capstone-banking-system/sql/oracle/02_seed_data_oracle.sql) and [`sql/postgres/02_seed_data_postgres.sql`](file:///c:/Users/RBS83790/OneDrive%20-%20EastWest%20Banking%20Corporation/Documents/training/full-stack/capstone-project/capstone-banking-system/sql/postgres/02_seed_data_postgres.sql).

---

## 1. Customers & Users (Oracle XE: `customer_master` & `app_user_master`)

| Customer ID | Username / Email | Role | Name | Initial Status |
| :--- | :--- | :--- | :--- | :--- |
| `cust-admin-001` | `admin@ledgerbank.com` | `ADMIN` | System Admin | `ACTIVE` |
| `cust-user-001` | `juan.delacruz@example.com` | `CUSTOMER` | Juan Dela Cruz | `ACTIVE` |
| `cust-user-002` | `maria.santos@example.com` | `CUSTOMER` | Maria Santos | `ACTIVE` |

*Default password for all seed accounts:* `password123` (BCrypt hash: `$2a$10$yPxSmEaD/2O6lRX.xPlL/OrFxdcv5MGklq.ExJ/IYcmOD.TA2eQB.`)  
*Admin password:* `admin123` (BCrypt hash: `$2a$10$Wj5Cqd6hjqjppB6IItquHOun3MVKhJZShmIPi0SmAJUDygytdipN6`)

---

## 2. Customer Pockets & Accounts (Oracle XE: `account_master`)

| Account ID | Customer ID | Account Type | Currency | Is Primary | Balance Amount | Status |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `acct-juan-php-01` | `cust-user-001` | `SAVINGS` | `PHP` | `Y` | `50000.0000` | `ACTIVE` |
| `acct-juan-usd-01` | `cust-user-001` | `CHECKING` | `USD` | `N` | `1500.0000` | `ACTIVE` |
| `acct-maria-php-01` | `cust-user-002` | `SAVINGS` | `PHP` | `Y` | `120000.0000` | `ACTIVE` |
| `acct-maria-eur-01` | `cust-user-002` | `WALLET` | `EUR` | `N` | `800.0000` | `ACTIVE` |

---

## 3. Bank Internal FX & Fee Accounts (Oracle XE: `account_master`)

Used for double-entry settlement of cross-currency transfers and FX spread fee bookings:

| Account ID | Customer ID | Account Type | Currency | Balance Amount | Status |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `bank-fx-php-01` | `cust-admin-001` | `INTERNAL_BANK_FX` | `PHP` | `10000000.0000` | `ACTIVE` |
| `bank-fx-usd-01` | `cust-admin-001` | `INTERNAL_BANK_FX` | `USD` | `200000.0000` | `ACTIVE` |
| `bank-fx-eur-01` | `cust-admin-001` | `INTERNAL_BANK_FX` | `EUR` | `200000.0000` | `ACTIVE` |
| `bank-fee-income-php-01` | `cust-admin-001` | `INTERNAL_BANK_FX` | `PHP` | `500000.0000` | `ACTIVE` |

---

## 4. Virtual Cards (Oracle XE: `card_master`)

| Card ID | Customer ID | Account ID (Linked) | Masked PAN | Ecom Enabled | Status | Daily Limit |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `card-juan-01` | `cust-user-001` | `acct-juan-php-01` | `4532-XXXX-XXXX-8821` | `1` | `ACTIVE` | `50000.00` |
| `card-maria-01` | `cust-user-002` | `acct-maria-php-01` | `4532-XXXX-XXXX-1194` | `1` | `ACTIVE` | `50000.00` |

---

## 5. Cached FX Rates (PostgreSQL: `fx_rate_cache`)

| Base Currency | Quote Currency | Rate | Source |
| :--- | :--- | :--- | :--- |
| `EUR` | `PHP` | `62.00000000` | `frankfurter.dev` |
| `USD` | `PHP` | `58.00000000` | `frankfurter.dev` |
| `EUR` | `USD` | `1.06000000` | `frankfurter.dev` |
| `PHP` | `EUR` | `0.01612903` | `frankfurter.dev` |
| `PHP` | `USD` | `0.01724138` | `frankfurter.dev` |
