# Full-System Wiring & Integration Guide (v1.1)
## Multi-Client (Flutter Mobile/Web & Thymeleaf Admin) to Gateway Ingress & Microservices
### Verified Consistent with `CONTRACTS.md`, `openapi.yaml`, and `SEED.md`

---

## 1. System Topology & Network Map

During full-system and E2E testing, all client traffic enters exclusively through two designated perimeter ingress points. Internal microservices and datastores reside on a private bridge network (`banking-net`) and are cloaked from direct host access per zero-trust architecture (`CH-03`).

```mermaid
flowchart TD
    subgraph Clients["Frontend Clients"]
        subgraph Mobile["Flutter Client (banking-client-monorepo)"]
            Emu["Android Emulator<br/>(10.0.2.2:8080)"]
            Dev["Physical Android<br/>(adb reverse / LAN IP)"]
            Web["Flutter Web (Chrome)<br/>(localhost:8080)"]
        end
        subgraph Admin["Web Operations"]
            Thymeleaf["Thymeleaf Admin Portal<br/>(:8090)"]
        end
    end

    subgraph Perimeter["Perimeter Ingress (Exposed Ports)"]
        GW["Spring Cloud Gateway (:8080)<br/>JWT Validation &bull; Route Cloaking &bull; X-Cust-Id Injection"]
        AdminPort["Admin Portal (:8090)<br/>Staff UI &bull; Reverse Proxy /grafana/"]
    end

    subgraph InternalServices["Private Internal Network (banking-net &bull; Cloaked Ports)"]
        direction TB
        Auth["login-service (:8082)<br/>registration-service (:8081)"]
        Txn["transaction-service (:8084)"]
        Acct["accounts-service (:8083)"]
        Risk["risk-engine (:8088)"]
        FX["forex-service (:8087)"]
        Notif["notification-service (:8085)"]
        Recon["reconciliation-service (:8086)"]
    end

    subgraph Datastores["Datastores & Infrastructure"]
        Oracle[("Oracle XE 21c (:1521)")]
        PG[("PostgreSQL 15 (:5432)")]
        Redis[("Redis 7 (:6379)")]
        Kafka[("Kafka Broker (:9092)")]
        Loki["Loki & Promtail"]
        Grafana["Grafana (:3000)"]
    end

    Emu & Dev & Web -->|HTTP + Bearer JWT| GW
    Thymeleaf -->|HTTP + Bearer JWT| GW
    Thymeleaf -.->|Internal Proxy| Grafana

    GW --> Auth
    GW --> Txn
    GW --> Acct
    GW --> Risk
    GW --> FX
    GW --> Notif
    GW --> Recon

    Txn --> Risk & FX & Acct
    Acct --> Oracle & Redis & Kafka
    Txn --> Oracle & Redis & Kafka
    Auth --> PG & Redis
    Recon --> Oracle & PG
    Notif --> PG & Kafka
```

---

## 2. Ingress & Address Resolution Matrix

Because client runtimes resolve host networking differently, use the exact base URL corresponding to the execution target:

| Client Target | Environment | Target Base URL | How Host Machine Port `:8080` is Resolved |
| :--- | :--- | :--- | :--- |
| **Android Emulator** | Android Studio / AVD | `http://10.0.2.2:8080` | Android QEMU virtual router maps `10.0.2.2` directly to host machine loopback `127.0.0.1`. |
| **Physical Android Phone** | USB Debugging Cable | `http://localhost:8080` | Requires running `adb reverse tcp:8080 tcp:8080` prior to app launch. |
| **Physical Android Phone** | Wi-Fi / Local LAN | `http://<HOST_LAN_IP>:8080` | e.g. `http://192.168.1.50:8080`. Host firewall must permit port 8080 inbound. |
| **Flutter Web (Chrome)** | Browser | `http://localhost:8080` | Browser shares host network stack directly. |
| **Thymeleaf Admin (Docker)** | Docker Compose | `http://api-gateway:8080` | Container DNS inside bridge network `banking-net`. |
| **Thymeleaf Admin (Local)** | Local JVM | `http://localhost:8080` | Local process targeting exposed gateway port. |

---

## 3. Global Conventions & Data Formats (from `CONTRACTS.md`)

All clients communicating with `:8080` must strictly observe these contract standards:

1. **Authentication:** `Authorization: Bearer <jwt>` on all protected endpoints.
2. **Idempotency Key:** `X-Idempotency-Key: <uuid>` header required for all mutating endpoints (`/api/v1/payments`, `/api/v1/ledger/mutate`, `/api/v1/fx/convert`). Scoped per customer with 24-hour Redis TTL.
3. **Monetary Representation:** Strings formatted as fixed-point decimals with scale 4 (e.g. `"50.0000"`, `"120000.0000"`). Floats are strictly prohibited to avoid precision loss.
4. **Timestamps:** ISO-8601 UTC string (`"2026-10-05T11:15:00.000Z"`).
5. **RFC 7807 Error Envelope:** All error responses adhere to standard JSON:
   ```json
   {
     "error_code": "INSUFFICIENT_FUNDS",
     "message": "Primary pocket balance is insufficient for this payment",
     "status": 422,
     "timestamp": "2026-10-05T11:15:00.000Z"
   }
   ```
6. **Network Boundary Protection (B-06):**
   - Core settlement (`/internal/core/settlement/**`), direct balance mutations (`/api/accounts/*/credit`, `/api/accounts/*/debit`), and risk internal routes (`/internal/risk/**`) are strictly blocked at Gateway `:8080` with `403 Forbidden` (`ACCESS_DENIED`). Clients must always route mutations through `transaction-service` (`/api/v1/payments`).

---

## 4. Client Wiring Configurations

### 4.1 Flutter Mobile & Web Client (`banking-client-monorepo`)

The client repository features compile-time and runtime environment injection in `lib/core/network/api_client.dart`:

```dart
static const String defaultGatewayUrl = String.fromEnvironment(
  'API_BASE_URL',
  defaultValue: kIsWeb ? 'http://localhost:8080' : 'http://10.0.2.2:8080',
);
```

#### Launch Commands by Target

* **Android Emulator (Default):**
  ```powershell
  cd "banking-client-monorepo"
  flutter run -d emulator-5554
  ```
  *(Automatically resolves to `http://10.0.2.2:8080`)*

* **Physical Android Device via USB Port Forwarding:**
  ```powershell
  # 1. Reverse port 8080 from device to host
  adb reverse tcp:8080 tcp:8080

  # 2. Run app overriding API_BASE_URL to localhost
  cd "banking-client-monorepo"
  flutter run -d <DEVICE_ID> --dart-define=API_BASE_URL=http://localhost:8080
  ```

* **Flutter Web (Chrome Browser):**
  ```powershell
  cd "banking-client-monorepo"
  flutter run -d chrome --dart-define=API_BASE_URL=http://localhost:8080
  ```

* **Network Security Verification:**
  `android/app/src/main/AndroidManifest.xml` is configured with:
  ```xml
  <application
      android:label="North South Bank"
      android:usesCleartextTraffic="true"
      ... >
  ```
  This allows cleartext `http://` traffic for local test execution without TLS certificates.

---

### 4.2 Thymeleaf Admin Portal (`banking-backend-monorepo/frontend-web`)

The Thymeleaf portal runs on port **`:8090`** to prevent port collisions with the API Gateway on **`:8080`**.

#### Configuration (`frontend-web/src/main/resources/application.yml`):
```yaml
server:
  port: 8090

banking:
  backend:
    mode: ${BANKING_BACKEND_MODE:gateway} # Options: 'mock' (standalone) or 'gateway' (live backend)
    gateway-url: ${BANKING_GATEWAY_URL:http://localhost:8080}
    connect-timeout-ms: 3000
    read-timeout-ms: 5000
```

* **Running inside Docker Compose:**
  Managed automatically. Environment variables are set to:
  ```yaml
  BANKING_BACKEND_MODE: gateway
  BANKING_GATEWAY_URL: http://api-gateway:8080
  ```
* **Running Standalone (Local JVM):**
  ```powershell
  cd "banking-backend-monorepo"
  ./mvnw spring-boot:run -pl frontend-web -Dspring-boot.run.arguments="--banking.backend.mode=gateway --banking.backend.gateway-url=http://localhost:8080"
  ```

---

## 5. API Gateway Routing & Contract Seams

All requests entering `:8080` map to downstream microservices per `openapi.yaml` and `CONTRACTS.md`:

| Route Prefix / Endpoints | Target Microservice | Internal Port | Auth Filter | Associated Spec & Contract Functions |
| :--- | :--- | :--- | :--- | :--- |
| `/api/auth/register`, `/api/auth/register/**` | `registration-service` | `8081` | None (Public) | Customer onboarding & auto-provisioned PHP pocket (`openapi.yaml`) |
| `/api/auth/login`, `/api/auth/logout`, `/api/auth/refresh`, `/api/auth/otp/**` | `login-service` | `8082` | None / Public | BCrypt login, JWT issuance, Redis blacklist revocation, Step-up OTP |
| `/api/accounts`, `/api/accounts/{id}`, `/api/accounts/{id}/balance`, `/api/accounts/customer/{id}` | `accounts-service` | `8083` | `JwtAuthentication` | Account lookup, balance inquiry, multi-currency pockets (`openapi.yaml`) |
| `/api/v1/accounts/{id}/lock`, `/api/v1/accounts/{id}/unlock` | `accounts-service` | `8083` | `JwtAuthentication` | Customer account self-lock & OTP unlock (`CONTRACTS.md §2.3`) |
| `/api/v1/dashboard/home` | `orchestrator-service` | `8089` | `JwtAuthentication` | BFF customer home screen aggregation (pockets, recent txns, alerts) |
| `/api/v1/payments`, `/api/v1/payments/**` | `orchestrator-service` / `transaction-service` | `8089` / `8084` | `JwtAuthentication` | BFF ownership verification & 7-status transaction saga settlement |
| `/api/v1/cards`, `/api/v1/cards/**` | `transaction-service` | `8084` | `JwtAuthentication` | Virtual card controls, e-commerce toggle, status (`CONTRACTS.md §2.3`) |
| `/api/v1/recipients/lookup` | `accounts-service` | `8083` | `JwtAuthentication` | Masked recipient name lookup before confirmation (`CONTRACTS.md §2.3`) |
| `/api/v1/ledger/mutate` | `transaction-service` | `8084` | `JwtAuthentication` | Legacy Sprint 0 adapter route for atomic CBS mutation (`openapi.yaml`) |
| `/api/v1/ledger/audit/{txnId}` | `transaction-service` | `8084` | `JwtAuthentication` | PostgreSQL append-only audit trail inquiry (`openapi.yaml`) |
| `/api/v1/fx/quote`, `/api/v1/fx/convert` | `forex-service` | `8087` | `JwtAuthentication` | 30s locked quote & own-pocket FX conversion (`CONTRACTS.md §2.2`) |
| `/api/v1/fx-rate`, `/api/v1/forex/rates` | `forex-service` | `8087` | None (Public) | Cached FX rate listing from `frankfurter.dev` (`openapi.yaml`) |
| `/api/notifications/**` | `notification-service` | `8085` | `JwtAuthentication` | Customer notifications & Kafka alert consumers |
| `/api/recon/runs`, `/api/recon/breaks` | `reconciliation-service` | `8086` | `JwtAuthentication` | Dual-DS CBS vs Audit drift detection engine (`openapi.yaml`) |
| `/api/admin/accounts/**` | `accounts-service` | `8083` | `JwtAuthentication` (`ROLE_ADMIN`) | Administrative freeze/unfreeze governance (`openapi.yaml`) |
| `/api/admin/customers/**` | `registration-service` | `8081` | `JwtAuthentication` (`ROLE_ADMIN`) | Staff customer KYC verification (`openapi.yaml`) |
| `/api/v1/admin/transfers/{id}/reverse` | `transaction-service` | `8084` | `JwtAuthentication` (`ROLE_ADMIN`) | Maker-checker reversal with linked compensating legs (`CONTRACTS.md §2.5`) |

### Token & Identity Forwarding Protocol
1. **Client Header:** Client sends `Authorization: Bearer <JWT>`.
2. **Gateway Interception:** `JwtAuthenticationGatewayFilterFactory` validates JWT signature (`jwt.secret`), checks expiration, and verifies token against Redis blacklist (`blacklist:<token>`).
3. **Identity Injection:** Gateway extracts `customerId` from JWT claims and injects downstream headers:
   ```http
   X-Cust-Id: cust-user-001
   X-User-Role: ROLE_CUSTOMER
   ```
4. **Header Stripping:** Gateway strips any incoming client-forged `X-Cust-Id` or `X-User-Role` before forwarding.

---

## 6. Execution & Verification Runbooks

### 6.1 Manual Local Execution Runbook

Follow these ordered steps to run and interact with the system manually across all channels:

#### Step 1: Start Backend Infrastructure & Microservices
```powershell
cd "c:\Users\RBS83790\OneDrive - EastWest Banking Corporation\Documents\training\integrated-capstone\banking-backend-monorepo"

# Stop existing containers and reset volumes for a clean state
docker compose down -v

# Start complete multi-service stack
docker compose up -d

# Verify perimeter ingress
Invoke-RestMethod -Uri "http://localhost:8080/actuator/health" -Method Get
```

#### Step 2: Access Thymeleaf Admin Web Portal (:8090)
Open browser to `http://localhost:8090`:
- **Credentials:** `admin@ledgerbank.com` / `admin123`.
- **Operator Verification:** Review Executive Liquidity KPIs, inspect Held Transfers Queue (`/admin/transfers/held`), test Account Freeze/Unfreeze, and inspect Dual-DS Reconciliation runs.

#### Step 3: Run Flutter Web Client (Chrome)
```powershell
cd "c:\Users\RBS83790\OneDrive - EastWest Banking Corporation\Documents\training\integrated-capstone\banking-client-monorepo"

# Run in Chrome targeting API Gateway :8080
flutter run -d chrome --dart-define=API_BASE_URL=http://localhost:8080
```
- **Customer Verification:** Log in as `juan.delacruz@example.com` / `Password123!` (or `maria.santos@example.com`).
- Verify multi-currency pockets hydrate from `GET /api/v1/dashboard/home`.
- Submit same-currency transfer or shortfall cross-currency payment.

#### Step 4: Run Emulated Mobile App (Android Emulator)
```powershell
cd "c:\Users\RBS83790\OneDrive - EastWest Banking Corporation\Documents\training\integrated-capstone\banking-client-monorepo"

# Run on Android Emulator (resolves to Gateway via http://10.0.2.2:8080 by default)
flutter run -d emulator-5554
```

---

### 6.2 Headless Automated Full-System Verification (Breaker-Fixer)

To execute the entire breaker-fixer regression loop headlessly without launching an emulator window or desktop browser:

#### Phase A: Headless Backend & Gateway Ingress Probe
Executes live HTTP API functional suites and transaction saga edge cases through API Gateway `:8080`:
```powershell
cd "c:\Users\RBS83790\OneDrive - EastWest Banking Corporation\Documents\training\integrated-capstone\banking-backend-monorepo"

# 1. Run live functional probe script across all Gateway endpoints
powershell -ExecutionPolicy Bypass -File .\scripts\test_api_functional_e2e.ps1

# 2. Run backend Python E2E test suite
python -m pytest tests/e2e/ -v --html=evidence/e2e_report.html
```

#### Phase B: Headless Client Layer Seam Verification
Executes the Flutter client network stack, Dio JWT interceptor, circuit breaker retry loops, RFC 7807 error catalog deserialization, and UI widget flows headlessly:
```powershell
cd "c:\Users\RBS83790\OneDrive - EastWest Banking Corporation\Documents\training\integrated-capstone\banking-client-monorepo"

# 1. Run client-gateway integration seams headlessly
flutter test test/integration/

# 2. Run complete client test suite (90 tests across core, features, and widgets)
flutter test
```

#### Phase C: Headless Resilience & Chaos Injection
Proves system resilience under infrastructure failures (Oracle 800ms latency, risk engine shutdown, PostgreSQL audit outage) with automatic recovery:
```powershell
cd "c:\Users\RBS83790\OneDrive - EastWest Banking Corporation\Documents\training\integrated-capstone\banking-backend-monorepo"

# Run graded chaos injection runbook
bash scripts/chaos/run_all_chaos_tests.sh
```

---

## 7. Pre-Seeded Personas for E2E Verification (Aligned with `SEED.md`)

The following seeded users and credentials from [`docs/SEED.md`](SEED.md) are pre-loaded in the databases:

| Persona | Role | Email / Username | Password | Default Accounts & Balances (Oracle XE) | Virtual Card |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Juan Dela Cruz** | Retail Customer (`7bd20304-dd6a-4e45-850b-58df8b535be4` / `cust-user-001`) | `juan.delacruz@example.com` | `password123` | • `3722f77f-3d1b-4d55-8f83-b71bac91d095` (`acct-juan-php-01`, Primary): **₱17,300.0000**<br/>• `a86c97cc-1ea5-4767-bd5b-148968354f9c` (`acct-juan-php-02`): **₱3,500.0000**<br/>• `acct-juan-eur-01` (EUR Wallet): **€100.0000** | `c4a1b2c3-d4e5-4f6a-8b9c-0d1e2f3a4b5c`<br/>`4532-XXXX-XXXX-8821`<br/>Ecom: `1` |
| **Maria Santos** | Retail Customer (`66e07d02-05c9-4deb-87d5-10e23cf5968f` / `cust-user-002`) | `maria.santos@example.com` | `password123` | • `f95e7e52-3259-4b4d-ac66-b186a893cf66` (`acct-maria-php-01`, Primary): **₱9,000.0000**<br/>• `86cabb96-61d5-462a-b33b-5a6435649eb4` (`acct-maria-php-02`): **₱2,950.0000**<br/>• `acct-maria-eur-01` (EUR Wallet): **€50.0000** | `d5b2c3d4-e5f6-4a7b-9c0d-1e2f3a4b5c6d`<br/>`4532-XXXX-XXXX-9932`<br/>Ecom: `1` |
| **System Admin** | Staff Operator (`2d49c23b-5f65-48da-aa68-28b155dd4022` / `cust-admin-001`) | `admin@ledgerbank.com` | `admin123` | Admin Portal access on `:8090` (Recon, Holds, DLQ, Freezes) | N/A |

---

## 8. Troubleshooting & Common Wiring Traps

| Symptom | Root Cause | Immediate Fix |
| :--- | :--- | :--- |
| **`DioException: SocketException: OS Error: Connection refused` on Android** | App is trying to access `http://localhost:8080` instead of `http://10.0.2.2:8080`. | Ensure `--dart-define=API_BASE_URL=http://10.0.2.2:8080` is passed, or rely on `ApiClient` default. |
| **`Connection refused` on Physical Android Device** | Device cannot resolve host loopback. | Run `adb reverse tcp:8080 tcp:8080` in PowerShell while phone is connected via USB. |
| **`Cleartext HTTP traffic to 10.0.2.2 not permitted`** | Android Network Security Policy blocking cleartext `http://`. | Verify `android:usesCleartextTraffic="true"` is set inside `<application>` in `AndroidManifest.xml`. |
| **CORS Error in Chrome (`XMLHttpRequest blocked by CORS policy`)** | Browser security blocks requests to `:8080` from `localhost:<flutter_web_port>`. | Launch Chrome with web security disabled for local testing: `flutter run -d chrome --web-browser-flag "--disable-web-security"`. |
| **`401 Unauthorized (TOKEN_REVOKED)`** | Token was invalidated via `/api/auth/logout` or present in Redis blacklist. | Re-authenticate via `/api/auth/login` to obtain fresh JWT. |
| **`401 Unauthorized (INVALID_CREDENTIALS)` after 15 min** | Access tokens have a 15-minute validity window. | Call `POST /api/auth/refresh` with refresh token, or re-authenticate persona. |
| **`409 Conflict (REQUEST_IN_PROGRESS)`** | Duplicate `X-Idempotency-Key` sent while initial transaction is still processing (`SETNX` returned 0). | Await original response or supply a distinct UUID (`Uuid().v4()`). |
| **`403 Forbidden (ACCESS_DENIED)` on `/internal/**` or balance mutations** | B-06 Boundary rule blocks internal routes (`/internal/core/**`, `/api/accounts/*/credit`) at Gateway. | Route payments through `/api/v1/payments`. |
| **`422 Unprocessable (FX_RATE_STALE)`** | Cached exchange rate in `fx_rate_cache` is older than 24 hours. | Run Forex rate refresh scheduler or trigger `/api/v1/fx-rate` update. |
