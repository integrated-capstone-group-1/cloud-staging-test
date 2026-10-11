# Frontend & Backend Alignment Audit Report

**Repository:** `banking-client-monorepo` (Flutter / Dart Client)  
**Backend Source of Truth:** `banking-backend-monorepo/docs` ([`CONTRACTS.md`](file:///c:/Users/LSL83757/integrated-capstone-group-1/banking-backend-monorepo/docs/CONTRACTS.md), [`INVARIANTS.md`](file:///c:/Users/LSL83757/integrated-capstone-group-1/banking-backend-monorepo/docs/INVARIANTS.md), [`SEED.md`](file:///c:/Users/LSL83757/integrated-capstone-group-1/banking-backend-monorepo/docs/SEED.md), [`openapi.yaml`](file:///c:/Users/LSL83757/integrated-capstone-group-1/banking-backend-monorepo/openapi.yaml))  
**Date of Audit:** October 9, 2026  
**Status:** **Action Required** — Critical API payload discrepancies, credential field mismatches, and mock bypasses identified.

---

## 1. Executive Summary & Root Cause: Username vs. Email

### The Co-Member's Observation
> *"My co-member said that the login is using username instead of an email based on the docs."*

### Why the Confusion Exists in the Documentation
There is a semantic difference between the **Database Schema / Documentation Tables** and the **Public REST API Contract**:
1. **In the Database Schema & Architecture Diagrams:**
   - In `banking-backend-monorepo/docs/ARCHITECTURE.md` (Channel DB entity diagram) and `SEED.md`, the credential table `APP_USER_MASTER` has a column named `username VARCHAR(50) UNIQUE`.
   - In `SEED.md` Table 1, the column header reads: `| Customer ID | Username / Email | Role | Name | Initial Status |`.
   - In Jira story card `T-006.md`: *"Enforce unique constraint violations gracefully (HTTP 409 if email or username exists)."*
   - In `CONTRACTS.md` §1.2: `"INVALID_CREDENTIALS: Invalid username/password or invalid/expired JWT."`
2. **In the Actual Backend Implementation (`login-service`):**
   - In `AppUser.java` (line 35):
     ```java
     // username == email set at registration; used as login identifier
     private String username;
     ```
     The backend stores the customer's **email address** inside the `username` column.
3. **In the Public REST API Contract (`CONTRACTS.md` §2.4, `LoginRequest.java`, `openapi.yaml`):**
   - The endpoint `POST /api/auth/login` **strictly expects the field name `email`**, **not `username`**:
     ```java
     public record LoginRequest(
         @NotBlank(message = "Email is required")
         @Email(message = "Email must be a valid address")
         String email,

         @NotBlank(message = "Password is required")
         String password,

         @JsonProperty("device_id")
         String deviceId
     )
     ```
   - In `openapi.yaml` line 258:
     ```json
     {
       "email": "alice.reyes@example.com",
       "password": "StrongPass123!"
     }
     ```
   - In `SEED.md`, all seed credentials use email format:
     - `juan.delacruz@example.com` (`password123`)
     - `maria.santos@example.com` (`password123`)
     - `admin@ledgerbank.com` / `admin@bank.com` (`admin123`)

### The Critical Frontend Misalignment
In [`banking_controller.dart`](file:///c:/Users/LSL83757/integrated-capstone-group-1/banking-client-monorepo/lib/core/state/banking_controller.dart#L381-L390) and the UI screens ([`lib/mobile/auth/auth_screen.dart`](file:///c:/Users/LSL83757/integrated-capstone-group-1/banking-client-monorepo/lib/mobile/auth/auth_screen.dart#L895-L905) and [`lib/web/auth/auth_screen.dart`](file:///c:/Users/LSL83757/integrated-capstone-group-1/banking-client-monorepo/lib/web/auth/auth_screen.dart#L966-L975)):
1. **Wrong JSON Key:** The client sends:
   ```dart
   final response = await apiClient.post<Map<String, dynamic>>(
     '/api/auth/login',
     data: {'username': username.trim(), 'password': password}, // ❌ Backend expects 'email'
   );
   ```
   Because `email` is missing from the payload, Spring Boot's Jakarta validation `@NotBlank(message = "Email is required")` immediately rejects the request with **`400 Bad Request / VALIDATION_FAILED`**.
2. **Non-Email Value Sent:** The UI defaults to `"juan.delacruz"` instead of `"juan.delacruz@example.com"`. Even if the field were renamed to `email`, `"juan.delacruz"` fails `@Email(message = "Email must be a valid address")`.
3. **Local Storage Gating Blocks Backend Seed Users:** In `banking_controller.dart:375-379`, `verifyCredentials()` checks local storage first (`_loadUsersMap()`). If a user attempts to log in with backend seed credentials (`juan.delacruz@example.com`), the client halts locally with *"Invalid username or password"* before sending any HTTP request!
4. **Mock Fallback Masks Connection Failures:** When `POST /api/auth/login` fails with a `DioException`, the catch block generates a mock token:
   ```dart
   accessToken: 'jwt-nsb-token-$normalized'
   ```
   This masks the 400 validation error, leaves the developer thinking login worked, but subsequent authenticated API calls fail with `401 Unauthorized` because the token is fake.
5. **Wrong Response Envelope Parsing:**
   The client expects `{ "access_token": "...", "refresh_token": "..." }` at the root.  
   The backend returns `ApiResponse<LoginResponse>`:
   ```json
   {
     "success": true,
     "message": "Login successful",
     "data": {
       "token": "eyJhbGciOi...",
       "tokenType": "Bearer",
       "expiresInSeconds": 3600,
       "refreshToken": "rt-...",
       "refreshExpiresInSeconds": 604800,
       "customerId": "cust-user-001",
       "email": "juan.delacruz@example.com",
       "role": "ROLE_CUSTOMER"
     }
   }
   ```
   The client reads `data['access_token']`, which is `null`, and falls back to `'jwt-nsb-token'`.

---

## 2. Complete Inventory of Alignment Issues

Below is the comprehensive matrix of all alignment discrepancies between `banking-client-monorepo` and the backend contracts.

| # | Feature / Domain | Backend Contract ([`CONTRACTS.md`](file:///c:/Users/LSL83757/integrated-capstone-group-1/banking-backend-monorepo/docs/CONTRACTS.md)) | Current Frontend Implementation | Severity | Required Client Adjustment |
|---|---|---|---|:---:|---|
| **1** | **Login Payload** | `POST /api/auth/login`<br>Body: `{"email": "...", "password": "...", "device_id": "..."}` | Sends `{"username": "...", "password": "..."}` | **BLOCKER** | Change payload key from `'username'` to `'email'`. Update UI labels to "Email / Username" and default text to `juan.delacruz@example.com`. |
| **2** | **Login Response Parsing** | Returns `{ "success": true, "data": { "token": "...", "refreshToken": "..." } }` | Reads `response.data['access_token']` and `['refresh_token']` | **BLOCKER** | Read `response.data['data']['token']` and `response.data['data']['refreshToken']`. |
| **3** | **Client Pre-Auth Local Storage Gating** | Backend verifies credentials via BCrypt in `login-service` against Oracle/Channel DB | Rejects input if `users[normalized]` is not found in local SharedPreferences | **BLOCKER** | Dispatch network call to `/api/auth/login` first. Populate local profile on HTTP `200 OK`. |
| **4** | **Customer Registration Payload** | `POST /api/auth/register`<br>Body: `{"firstName": "...", "lastName": "...", "email": "...", "contactNo": "...", "birthDate": "YYYY-MM-DD", "password": "..."}` | Sends snake_case `{"first_name": "...", "last_name": "...", "username": "..."}` and omits required `birthDate` | **CRITICAL** | Change to camelCase (`firstName`, `lastName`). Add required `birthDate` parameter (format `YYYY-MM-DD`). Remove `username`. |
| **5** | **Token Refresh (Auto-Refresh on 401)** | `POST /api/auth/refresh`<br>Body: `{"refresh_token": "..."}`<br>Access JWT expires in 15 mins (3600s/900s) | No refresh token handling in [`auth_interceptor.dart`](file:///c:/Users/LSL83757/integrated-capstone-group-1/banking-client-monorepo/lib/core/network/auth_interceptor.dart). Calls simply fail after expiry. | **HIGH** | Implement `onError` interceptor to call `/api/auth/refresh` on 401 and retry the original request. |
| **6** | **Live Multi-Currency Pockets Fetching** | `GET /api/v1/accounts/pockets` (or Orchestrator `GET /api/v1/dashboard/home`) returns real balances | [`banking_controller.dart`](file:///c:/Users/LSL83757/integrated-capstone-group-1/banking-client-monorepo/lib/core/state/banking_controller.dart) NEVER calls an endpoint to fetch pockets; uses hardcoded static `_defaultPockets()` | **CRITICAL** | Add `fetchPockets()` method in `BankingController` calling `GET /api/v1/accounts/pockets` or `/api/v1/dashboard/home` on login. |
| **7** | **Pockets & Seed Balance Discrepancy** | Seed DB ([`SEED.md`](file:///c:/Users/LSL83757/integrated-capstone-group-1/banking-backend-monorepo/docs/SEED.md)): Juan has PHP `17300.0000`, PHP `3500.0000`, EUR `100.0000` (no USD pocket). | Frontend hardcodes PHP `50000.0000`, USD `250.0000`, EUR `10.0000`. | **HIGH** | Align default fallback with `SEED.md` and fetch live balances from CBS. |
| **8** | **Self-Pocket Transfer Rejection Invariant** | [`CONTRACTS.md`](file:///c:/Users/LSL83757/integrated-capstone-group-1/banking-backend-monorepo/docs/CONTRACTS.md) §2.1: `POST /api/v1/payments` with own account is strictly rejected with `400 VALIDATION_FAILED`. Own pockets convert via `POST /api/v1/fx/convert`. | `banking_controller.dart:848-865` allows transferring to own pockets in `submitPayment` and simulates crediting them locally | **HIGH** | Prevent selecting own pocket in `SendMoneyScreen`. Guide user to `FxConvertScreen` for pocket-to-pocket moves. |
| **9** | **Virtual Cards Retrieval** | `GET /api/v1/cards` returns customer virtual cards (`card_id`, `masked_pan`, `card_status`, `ecom_enabled`, `daily_limit`) | Client NEVER calls `GET /api/v1/cards`. Statically defaults to hardcoded `card-juan-01`. | **HIGH** | Implement `fetchVirtualCards()` calling `GET /api/v1/cards` on login. |
| **10** | **Card Lock Request Body** | `PUT /api/v1/cards/{id}/status`<br>Body: `{"status": "FROZEN"}` or `{"status": "ACTIVE"}` | Sends `{"status": "...", "otp_code": "..."}` | **MEDIUM** | Remove `otp_code` from card status payload (only pocket account unlock expects `otp_code`). |
| **11** | **Backend PIN Fallback Service (DIG-70)** | `login-service` has `POST /api/auth/pin/setup` and `POST /api/auth/pin/verify` with BCrypt and Redis lockout | Client stores and verifies PIN 100% locally in SharedPreferences | **HIGH** | Wire `pin_setup_screen.dart` and `AuthScreen` to backend endpoints `/api/auth/pin/setup` and `/api/auth/pin/verify`. |
| **12** | **Direct Firebase Email Dispatches** | Backend services dispatch emails via SMTP/JavaMail. Server owns OTP generation and verification. | Frontend calls Firebase Identity Toolkit (`identitytoolkit.googleapis.com`) directly from Dart code | **HIGH** | Decommission client-side Firebase Auth/Firestore email triggers in favor of backend OTP endpoints (`/api/auth/otp/request`). |
| **13** | **OTP Verification Logic** | Server generates OTP and validates in Redis via `POST /api/auth/otp/verify` | Frontend validates entered OTP locally against `_generatedOtp` or `'849201'` in `otp_verification_sheet.dart:333` | **CRITICAL** | Remove local OTP check. Let `/api/auth/otp/verify` perform the verification. |
| **14** | **Error Code Mapping Catalog** | Backend returns `409 Conflict` with `REQUEST_IN_PROGRESS` on concurrent duplicate key | `BankingErrorMapper` checks for `IDEMPOTENCY_IN_PROGRESS` | **MEDIUM** | Add `'REQUEST_IN_PROGRESS'` to [`BankingErrorMapper`](file:///c:/Users/LSL83757/integrated-capstone-group-1/banking-client-monorepo/lib/core/network/api_client.dart#L26-L79). |
| **15** | **Cancellable Transaction Statuses** | [`CONTRACTS.md`](file:///c:/Users/LSL83757/integrated-capstone-group-1/banking-backend-monorepo/docs/CONTRACTS.md) §1.3: Cancellable statuses are `INITIATED`, `AUTHORIZED`, and `RESERVED` | `_isCancellable` in `transaction_status_screen.dart:42` only checks `INITIATED` and `AUTHORIZED` | **MEDIUM** | Include `RESERVED` in `_isCancellable` (customer can cancel a held transfer before admin approval). |
| **16** | **Payment Status Polling** | [`CONTRACTS.md`](file:///c:/Users/LSL83757/integrated-capstone-group-1/banking-backend-monorepo/docs/CONTRACTS.md) §2.1 line 155: Clients must poll `GET /api/v1/payments/{id}` until terminal status (`POSTED`, `FAILED`, `CANCELLED`) | `TransactionStatusScreen` only runs a client-side 5-second countdown timer. Never polls backend. | **HIGH** | Poll `GET /api/v1/payments/{id}` at 1-second intervals while in `INITIATED`, `AUTHORIZED`, or `PROCESSING`. |
| **17** | **Suspicious Dispute Reports (DIG-91 / DIG-48)** | `POST /api/v1/reports/suspicious`<br>Body: `{"transaction_id": "...", "reason": "...", "notes": "..."}` | `report_suspicious_screen.dart` is mock UI (`setState(() => _submitted = true)`). No API call made. | **HIGH** | Wire `Submit Dispute Report` button to `POST /api/v1/reports/suspicious`. |
| **18** | **PDF E-Statement Download (DIG-92 / DIG-99)** | `GET /api/v1/statements?from=...&to=...`<br>Returns PDF binary stream (`Content-Type: application/pdf`) | `history_screen.dart` only shows a SnackBar without downloading any file | **MEDIUM** | Invoke `apiClient.get('/api/v1/statements')` with binary response type and trigger browser download or mobile file save. |
| **19** | **Notifications / Alerts Sync (DIG-47)** | `GET /api/notifications/{customerId}` and `POST /api/notifications/{id}/read` | Alerts are purely mock in-memory objects. Marking as read does not update backend. | **MEDIUM** | Fetch alerts from backend `/api/notifications/{customerId}` and send mark-as-read updates. |
| **20** | **Idempotency Header on Public Endpoints** | `X-Idempotency-Key` is scoped to authenticated customer in Oracle (`UNIQUE(customer_id, idempotency_key)`) | `AuthInterceptor` attaches `X-Idempotency-Key` on ALL POST/PUT calls, including `/api/auth/login` and `/api/auth/register` | **LOW** | Only attach `X-Idempotency-Key` on business mutations (`/api/v1/payments`, `/api/v1/fx/convert`, `/api/v1/cards/**`). |

---

## 3. Deep-Dive Analysis by Domain

### 3.1 Authentication & Registration (`login-service` & `registration-service`)

#### Problem 1: Login Request & Response
- **Backend Contract:**
  - Route: `POST /api/auth/login`
  - Body:
    ```json
    {
      "email": "juan.delacruz@example.com",
      "password": "password123",
      "device_id": "optional-device-id"
    }
    ```
  - Response (200 OK):
    ```json
    {
      "success": true,
      "message": "Login successful",
      "data": {
        "token": "eyJhbGciOi...",
        "tokenType": "Bearer",
        "expiresInSeconds": 3600,
        "refreshToken": "rt-...",
        "refreshExpiresInSeconds": 604800,
        "customerId": "7bd20304-dd6a-4e45-850b-58df8b535be4",
        "email": "juan.delacruz@example.com",
        "role": "ROLE_CUSTOMER"
      },
      "timestamp": "2026-10-09T09:00:00Z"
    }
    ```
- **Frontend Current Code ([`banking_controller.dart:381-397`](file:///c:/Users/LSL83757/integrated-capstone-group-1/banking-client-monorepo/lib/core/state/banking_controller.dart#L381-L397)):**
  ```dart
  final response = await apiClient.post<Map<String, dynamic>>(
    '/api/auth/login',
    data: {'username': username.trim(), 'password': password}, // ❌ 'username' instead of 'email'
  );
  final data = response.data ?? {};
  await storageService.saveTokens(
    accessToken: (data['access_token'] ?? 'jwt-nsb-token').toString(), // ❌ expects 'access_token' at root
    refreshToken: (data['refresh_token'] ?? 'rt-nsb-token').toString(),
  );
  ```
- **Fix Required:**
  ```dart
  // Support both direct email input or fallback append for local demo usernames
  final emailPayload = username.contains('@') 
      ? username.trim() 
      : '${username.trim()}@example.com';

  final response = await apiClient.post<Map<String, dynamic>>(
    '/api/auth/login',
    data: {'email': emailPayload, 'password': password},
  );
  final body = response.data ?? {};
  final innerData = (body['data'] is Map<String, dynamic>) 
      ? body['data'] as Map<String, dynamic> 
      : body;

  final accessToken = innerData['token'] ?? innerData['access_token'];
  final refreshToken = innerData['refreshToken'] ?? innerData['refresh_token'];
  ```

#### Problem 2: Registration Field Types & Naming
- **Backend Contract (`RegisterRequest.java`):**
  - Fields: `firstName`, `lastName`, `email`, `contactNo`, `birthDate` (Required `LocalDate` format `YYYY-MM-DD`), `password`.
- **Frontend Current Code ([`banking_controller.dart:495-504`](file:///c:/Users/LSL83757/integrated-capstone-group-1/banking-client-monorepo/lib/core/state/banking_controller.dart#L495-L504)):**
  ```dart
  await apiClient.post<Map<String, dynamic>>(
    '/api/auth/register',
    data: {
      'first_name': firstName.trim(), // ❌ Snake_case
      'last_name': lastName.trim(),   // ❌ Snake_case
      'email': email.trim(),
      'username': username.trim(),     // ❌ Not recognized by backend
      'password': password,
      // ❌ Missing required 'birthDate'
    },
  );
  ```
- **Fix Required:**
  ```dart
  await apiClient.post<Map<String, dynamic>>(
    '/api/auth/register',
    data: {
      'firstName': firstName.trim(),
      'lastName': lastName.trim(),
      'email': email.trim(),
      'contactNo': '+639175550100',
      'birthDate': '1995-06-15', // or collect from date picker
      'password': password,
      'role': 'ROLE_CUSTOMER',
    },
  );
  ```

---

### 3.2 Account Pockets & Dashboard State

#### Problem: Static Hardcoded Pockets
- **Backend Contract:**
  - `GET /api/v1/accounts/pockets` or Orchestrator `GET /api/v1/dashboard/home`.
  - Returns actual multi-currency pocket list:
    ```json
    [
      {
        "account_id": "3722f77f-3d1b-4d55-8f83-b71bac91d095",
        "customer_id": "7bd20304-dd6a-4e45-850b-58df8b535be4",
        "currency": "PHP",
        "is_primary": "Y",
        "balance_amount": "17300.0000",
        "held_amount": "0.0000",
        "available_balance": "17300.0000",
        "account_status": "ACTIVE"
      }
    ]
    ```
- **Frontend Current Code:**
  - `pockets` is assigned in the constructor to static constant `_defaultPockets()`:
    ```dart
    static List<PocketAccount> _defaultPockets() => const [
      PocketAccount(accountId: 'acct-juan-php-01', currency: 'PHP', balanceAmount: '50000.0000', ...),
      PocketAccount(accountId: 'acct-juan-usd-01', currency: 'USD', balanceAmount: '250.0000', ...),
      PocketAccount(accountId: 'acct-juan-eur-01', currency: 'EUR', balanceAmount: '10.0000', ...),
    ];
    ```
  - **No method exists in `banking_controller.dart` to fetch or update pockets from the backend!**
- **Fix Required:**
  Implement `fetchPockets()`:
  ```dart
  Future<void> fetchPockets() async {
    try {
      final response = await apiClient.get<dynamic>('/api/v1/accounts/pockets', requiresAuth: true);
      final raw = response.data;
      List<dynamic> list = [];
      if (raw is List) {
        list = raw;
      } else if (raw is Map && raw['pockets'] is List) {
        list = raw['pockets'] as List;
      } else if (raw is Map && raw['data'] is List) {
        list = raw['data'] as List;
      }
      if (list.isNotEmpty) {
        pockets = list
            .whereType<Map<String, dynamic>>()
            .map((item) => PocketAccount.fromJson(item))
            .toList();
        notifyListeners();
      }
    } catch (_) {
      // Keep cached / default pockets on network failure
    }
  }
  ```

---

### 3.3 Virtual Card Controls

#### Problem: Static Card & Card Status API
- **Backend Contract (`CONTRACTS.md` §2.3):**
  - `GET /api/v1/cards` -> Returns list of virtual cards for the customer.
  - `PUT /api/v1/cards/{id}/status` -> Body `{"status": "FROZEN"}` or `{"status": "ACTIVE"}`.
  - `PUT /api/v1/cards/{id}/ecom` -> Body `{"ecom_enabled": 1}` or `{"ecom_enabled": 0}`.
- **Frontend Current Code:**
  - `virtualCard` is initialized to hardcoded `card-juan-01`.
  - Never fetches cards from `GET /api/v1/cards`.
  - In `toggleCardLock()` ([`banking_controller.dart:1088-1094`](file:///c:/Users/LSL83757/integrated-capstone-group-1/banking-client-monorepo/lib/core/state/banking_controller.dart#L1088-L1094)), sends `otp_code` in the payload:
    ```dart
    data: {
      'status': targetStatus,
      if (!lockCard) 'otp_code': otpCode, // ❌ Backend CardStatusRequest has no otp_code field
    }
    ```
- **Fix Required:**
  - Fetch card on login via `GET /api/v1/cards`.
  - Send only `{"status": targetStatus}` to `PUT /api/v1/cards/{id}/status`.

---

### 3.4 Payments, Cancellations & Error Handling

#### Problem 1: Error Code Mismatch on Duplicate Idempotency Key
- **Backend (`CONTRACTS.md` §1.2):**
  - Code: `REQUEST_IN_PROGRESS` (HTTP 409).
- **Frontend ([`api_client.dart:37`](file:///c:/Users/LSL83757/integrated-capstone-group-1/banking-client-monorepo/lib/core/network/api_client.dart#L37)):**
  - Mapped key: `'IDEMPOTENCY_IN_PROGRESS'`
- **Fix Required:**
  Add `'REQUEST_IN_PROGRESS'` to `BankingErrorMapper._errorMessages`.

#### Problem 2: Missing `RESERVED` Cancellation State
- **Backend (`CONTRACTS.md` §1.3):**
  - Cancellable statuses are `INITIATED`, `AUTHORIZED`, and `RESERVED`.
  - Cancelling `RESERVED` releases the `RISK_HOLD` in `accounts-service`.
- **Frontend ([`transaction_status_screen.dart:42`](file:///c:/Users/LSL83757/integrated-capstone-group-1/banking-client-monorepo/lib/mobile/transfer/transaction_status_screen.dart#L42)):**
  ```dart
  bool get _isCancellable =>
      _currentStatus == 'INITIATED' || _currentStatus == 'AUTHORIZED'; // ❌ Omits 'RESERVED'
  ```
- **Fix Required:**
  ```dart
  bool get _isCancellable =>
      _currentStatus == 'INITIATED' || 
      _currentStatus == 'AUTHORIZED' || 
      _currentStatus == 'RESERVED';
  ```

#### Problem 3: Missing Polling on In-Flight Payments
- **Backend (`CONTRACTS.md` §2.1 line 155):**
  - *"Clients must treat POSTED, FAILED and CANCELLED as the only final answers and poll `GET /api/v1/payments/{id}` until one appears."*
- **Frontend:**
  - Currently does not poll `GET /api/v1/payments/{id}`. `TransactionStatusScreen` only counts down a 5-second timer and assumes the status never updates from the server.
- **Fix Required:**
  - Add periodic polling in `TransactionStatusScreen` to query `GET /api/v1/payments/{transactionId}` every 1–2 seconds until the status becomes terminal.

---

### 3.5 Compliance & Reporting Features

#### Problem 1: Suspicious Transaction Dispute Submission (DIG-91)
- **Backend Contract (`CONTRACTS.md` §2.6):**
  - Endpoint: `POST /api/v1/reports/suspicious`
  - Body:
    ```json
    {
      "transaction_id": "txn-5541-uuid",
      "reason": "UNAUTHORIZED_TRANSACTION",
      "notes": "Did not recognize merchant charge"
    }
    ```
- **Frontend ([`report_suspicious_screen.dart:110`](file:///c:/Users/LSL83757/integrated-capstone-group-1/banking-client-monorepo/lib/mobile/security/report_suspicious_screen.dart#L110)):**
  - Only sets `setState(() => _submitted = true)`. No API call is made.
- **Fix Required:**
  Implement `submitSuspiciousReport()` in `BankingController` calling `POST /api/v1/reports/suspicious`.

#### Problem 2: Statement PDF Download (DIG-92)
- **Backend Contract (`CONTRACTS.md` §2.6):**
  - Endpoint: `GET /api/v1/statements?from=YYYY-MM-DD&to=YYYY-MM-DD`
  - Returns binary PDF (`application/pdf`).
- **Frontend ([`history_screen.dart:194-201`](file:///c:/Users/LSL83757/integrated-capstone-group-1/banking-client-monorepo/lib/mobile/history/history_screen.dart#L194-L201)):**
  - Shows SnackBar *"Account e-Statement PDF generated and downloaded"* without performing any HTTP request or saving any file.
- **Fix Required:**
  Call `apiClient.get('/api/v1/statements', options: Options(responseType: ResponseType.bytes))` and save/open the PDF.

---

## 4. Recommended Priority Action Plan

### Priority 1: High-Impact Blockers (Unblock Real Login & Integration)
1. **Fix Login Request Payload (`POST /api/auth/login`):**
   - In `lib/core/state/banking_controller.dart`, change `{'username': ...}` to `{'email': ...}`.
   - Parse `response.data['data']['token']` and `response.data['data']['refreshToken']`.
   - Remove client-side pre-auth storage block in `verifyCredentials()`.
2. **Update Auth UI Defaults:**
   - In `lib/mobile/auth/auth_screen.dart` and `lib/web/auth/auth_screen.dart`, update the username field label/hint to "Email Address (e.g. juan.delacruz@example.com)" and default test values to `juan.delacruz@example.com`.
3. **Fix Registration Payload (`POST /api/auth/register`):**
   - Align casing to camelCase (`firstName`, `lastName`).
   - Add required `birthDate` (format `YYYY-MM-DD`).

### Priority 2: Account Balances & Core Banking Alignment
4. **Fetch Live Pockets from Gateway:**
   - Implement `fetchPockets()` in `BankingController` calling `GET /api/v1/accounts/pockets` and invoke it immediately after login.
5. **Fetch Virtual Cards:**
   - Implement `fetchCards()` calling `GET /api/v1/cards` instead of hardcoding `card-juan-01`.
6. **Error Code Catalog Update:**
   - Add `REQUEST_IN_PROGRESS` to `BankingErrorMapper`.

### Priority 3: Complete Compliance & Polish Flows
7. **Wire `POST /api/v1/reports/suspicious`:** Connect `report_suspicious_screen.dart` to the backend.
8. **Add Polling to `TransactionStatusScreen`:** Poll `GET /api/v1/payments/{id}` until terminal status.
9. **Connect Statement Download:** Implement binary stream download for `GET /api/v1/statements`.
10. **Decommission Client Firebase Calls:** Transition OTP request/verify entirely to `login-service` endpoints.
