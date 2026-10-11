import sys
import os
import uuid
import time
import json
import concurrent.futures

# Add run-01 to path
sys.path.insert(0, os.path.dirname(__file__))
from harness import http_req, run_invariant, REQUEST_COUNT, MAX_REQUESTS

print("=== Starting Breaker Run 01: Domain identity-admin ===")

observations = []
findings = []
contract_queries = []
created_customer_ids = []
created_txn_ids = []

# --- Step 3: Baseline Journeys ---
print("\n--- Executing Baseline Journeys ---")

# Seed personas from SEED.md
ADMIN_EMAIL = "admin@ledgerbank.com"
ADMIN_PASS = "admin123"
JUAN_EMAIL = "juan.delacruz@example.com"
JUAN_PASS = "password123"

# Baseline Admin Login
status, res = http_req("POST", "/api/auth/login", body={"email": ADMIN_EMAIL, "password": ADMIN_PASS}, customer_label="admin_seed")
admin_jwt = None
if status == 200 and isinstance(res, dict):
    admin_jwt = res.get("access_token") or res.get("token") or res.get("jwt")
print(f"Baseline Admin Login: status={status}, got_token={bool(admin_jwt)}")

# Baseline Customer 1 Registration
c1_rand = uuid.uuid4().hex[:8]
c1_email = f"breaker.c1.{c1_rand}@example.com"
c1_payload = {
    "firstName": "Breaker",
    "lastName": "Alpha",
    "email": c1_email,
    "contactNo": "+639171112233",
    "birthDate": "1990-01-01",
    "password": "Password123!"
}
status, res = http_req("POST", "/api/auth/register", body=c1_payload, customer_label="c1")
c1_id = None
c1_account_id = None
if status in (200, 201) and isinstance(res, dict):
    c1_id = res.get("customerId") or res.get("customer_id")
    c1_account_id = res.get("accountId") or res.get("account_id") or res.get("primaryPocketId")
    if c1_id:
        created_customer_ids.append(c1_id)
print(f"Baseline C1 Registration: status={status}, c1_id={c1_id}, c1_account_id={c1_account_id}")

# Baseline Customer 1 Login
status, res = http_req("POST", "/api/auth/login", body={"email": c1_email, "password": "Password123!"}, customer_label="c1")
c1_jwt = None
c1_refresh = None
if status == 200 and isinstance(res, dict):
    c1_jwt = res.get("access_token") or res.get("token") or res.get("jwt")
    c1_refresh = res.get("refresh_token")
print(f"Baseline C1 Login: status={status}, got_token={bool(c1_jwt)}")

# Baseline Customer 2 Registration
c2_rand = uuid.uuid4().hex[:8]
c2_email = f"breaker.c2.{c2_rand}@example.com"
c2_payload = {
    "firstName": "Breaker",
    "lastName": "Beta",
    "email": c2_email,
    "contactNo": "+639174445566",
    "birthDate": "1992-02-02",
    "password": "Password123!"
}
status, res = http_req("POST", "/api/auth/register", body=c2_payload, customer_label="c2")
c2_id = None
c2_account_id = None
if status in (200, 201) and isinstance(res, dict):
    c2_id = res.get("customerId") or res.get("customer_id")
    c2_account_id = res.get("accountId") or res.get("account_id")
    if c2_id:
        created_customer_ids.append(c2_id)
print(f"Baseline C2 Registration: status={status}, c2_id={c2_id}, c2_account_id={c2_account_id}")

# Baseline Customer 2 Login
status, res = http_req("POST", "/api/auth/login", body={"email": c2_email, "password": "Password123!"}, customer_label="c2")
c2_jwt = None
if status == 200 and isinstance(res, dict):
    c2_jwt = res.get("access_token") or res.get("token") or res.get("jwt")

# Check Invariant scoped to created customers after baseline
if created_customer_ids:
    inv_code, inv_out = run_invariant(f"customers={','.join(created_customer_ids)}")
    print(f"Invariant check post-baseline (code={inv_code}):\n{inv_out}")

# --- Step 4 & 5: Breadth and Depth Attacks ---
print("\n--- Executing Attack Areas ---")

# Area 1: Input Attacks on Registration & Auth
print("\nArea 1: Input Attacks")
# 1.1 Malformed registration: Missing required fields
status, res = http_req("POST", "/api/auth/register", body={"firstName": "Incomplete"}, customer_label="anon")
if status != 400:
    observations.append(f"Registration missing fields returned {status} (expected 400)")

# 1.2 SQL Injection in Registration Email
sqli_payload = dict(c1_payload)
sqli_payload["email"] = f"sqli'{uuid.uuid4().hex[:4]}@example.com"
status, res = http_req("POST", "/api/auth/register", body=sqli_payload, customer_label="anon")

# 1.3 SQL Injection in Login Email
status, res = http_req("POST", "/api/auth/login", body={"email": "admin@ledgerbank.com' OR '1'='1", "password": "wrong"}, customer_label="anon")
if status == 200:
    findings.append({"title": "SQL Injection bypass in login", "sev": "S1", "oracle": "F7", "status": status, "res": res})

# 1.4 Malformed JSON
status, res = http_req("POST", "/api/auth/login", headers={"Content-Type": "application/json"}, body="{\"email\": \"bad json...", customer_label="anon")
if status >= 500:
    findings.append({"title": "500 Internal Server Error on Malformed JSON", "sev": "S3", "oracle": "F1", "status": status, "res": res})

# 1.5 Huge payload (oversized body)
huge_payload = {"email": "a"*50000 + "@example.com", "password": "password123"}
status, res = http_req("POST", "/api/auth/login", body=huge_payload, customer_label="anon")
if status >= 500:
    findings.append({"title": "500 Internal Server Error on oversized login payload", "sev": "S3", "oracle": "F1", "status": status, "res": res})

# 1.6 Non-string types in registration fields
bad_types = dict(c1_payload)
bad_types["email"] = 123456
bad_types["contactNo"] = ["not", "a", "phone"]
status, res = http_req("POST", "/api/auth/register", body=bad_types, customer_label="anon")
if status >= 500:
    findings.append({"title": "500 Internal Server Error on type confusion in registration", "sev": "S3", "oracle": "F1", "status": status, "res": res})

# Area 2: Idempotency Attacks
print("\nArea 2: Idempotency Attacks")
# 2.1 Duplicate registration with identical email
status, res = http_req("POST", "/api/auth/register", body=c1_payload, customer_label="anon")
if status != 409:
    observations.append(f"Duplicate registration returned {status} (expected 409)")

# Area 3: Authorization & IDOR Attacks
print("\nArea 3: Authorization & IDOR Attacks")
# 3.1 Customer accessing Admin endpoints
if c1_jwt:
    admin_paths = [
        "/api/admin/accounts",
        "/api/admin/customers",
        "/api/v1/admin/held-transfers",
        "/api/recon/runs"
    ]
    for path in admin_paths:
        status, res = http_req("GET", path, headers={"Authorization": f"Bearer {c1_jwt}"}, customer_label="c1_as_admin")
        if status in (200, 201, 204):
            findings.append({"title": f"Customer token granted access to admin route {path}", "sev": "S1", "oracle": "F7", "path": path, "status": status, "res": res})
        elif status not in (401, 403):
            contract_queries.append({"path": path, "status": status, "expected": "403 ACCESS_DENIED"})

# 3.2 Forged X-Cust-Id Header injection
if c1_jwt and c2_id:
    # Attempting to read or act as c2 by passing X-Cust-Id
    headers = {
        "Authorization": f"Bearer {c1_jwt}",
        "X-Cust-Id": c2_id
    }
    status, res = http_req("GET", "/api/v1/accounts/pockets", headers=headers, customer_label="c1_forging_c2")
    if status == 200 and isinstance(res, list):
        # Check if pockets returned belong to c2 instead of c1
        for p in res:
            if p.get("customerId") == c2_id or p.get("customer_id") == c2_id:
                findings.append({"title": "Forged X-Cust-Id header honored by backend", "sev": "S1", "oracle": "F7", "status": status, "res": res})

# 3.3 Access without token to admin routes
for path in ["/api/admin/accounts", "/api/admin/customers", "/api/v1/admin/held-transfers"]:
    status, res = http_req("GET", path, customer_label="anon")
    if status not in (401, 403):
        findings.append({"title": f"Unauthenticated access permitted to admin route {path}", "sev": "S1", "oracle": "F7", "status": status, "res": res})

# 3.4 IDOR on customer pockets
if c1_jwt and c2_account_id:
    status, res = http_req("GET", f"/api/v1/accounts/{c2_account_id}/pockets", headers={"Authorization": f"Bearer {c1_jwt}"}, customer_label="c1_cross_c2")
    if status == 200:
        findings.append({"title": f"Customer A accessed Customer B pocket details {c2_account_id}", "sev": "S1", "oracle": "F7", "status": status, "res": res})

# Area 4: State Machine Attacks
print("\nArea 4: State Machine Attacks")
# 4.1 Token Revocation / Blacklist verification
# Log out C2 and check if token is revoked
if c2_jwt:
    status, res = http_req("POST", "/api/auth/logout", headers={"Authorization": f"Bearer {c2_jwt}"}, customer_label="c2")
    print(f"C2 Logout: status={status}")
    # Re-use logged-out token
    status, res = http_req("GET", "/api/v1/accounts/pockets", headers={"Authorization": f"Bearer {c2_jwt}"}, customer_label="c2_revoked")
    if status != 401:
        findings.append({"title": "Revoked token still accepted after logout", "sev": "S1", "oracle": "F3", "status": status, "res": res})

# 4.2 Refresh token handling
if c1_refresh:
    # Refresh token rotation
    status, res = http_req("POST", "/api/auth/refresh", body={"refresh_token": c1_refresh}, customer_label="c1")
    print(f"C1 Refresh token: status={status}")
    # Replay same refresh token
    status2, res2 = http_req("POST", "/api/auth/refresh", body={"refresh_token": c1_refresh}, customer_label="c1")
    print(f"C1 Replay refresh token: status={status2}")

# Area 6: Business Logic & Network Boundary Protection
print("\nArea 6: Business Logic & Perimeter Protection")
perimeter_routes = [
    "/internal/core/settlement/execute",
    "/internal/risk/score",
    "/api/v1/ledger/mutate",
    "/api/accounts/1/credit",
    "/api/accounts/1/debit",
    "/api/v1/accounts/1/credit",
    "/api/v1/accounts/1/debit"
]
for route in perimeter_routes:
    status, res = http_req("POST", route, body={"test": 1}, customer_label="anon")
    print(f"Perimeter probe {route}: status={status}")
    if status != 403 and status != 404:
        findings.append({"title": f"Internal boundary violated: {route} returned {status} (expected 403)", "sev": "S1", "oracle": "F7", "path": route, "status": status, "res": res})

# Area 7: Concurrency & Race Tests
print("\nArea 7: Concurrency & Race Tests")
# Concurrent registration with same email
race_email = f"breaker.race.{uuid.uuid4().hex[:6]}@example.com"
race_payload = dict(c1_payload)
race_payload["email"] = race_email

def do_reg(idx):
    return http_req("POST", "/api/auth/register", body=race_payload, customer_label=f"race_{idx}")

with concurrent.futures.ThreadPoolExecutor(max_workers=5) as executor:
    futures = [executor.submit(do_reg, i) for i in range(5)]
    results = [f.result() for f in futures]

successes = [r for r in results if r[0] in (200, 201)]
print(f"Concurrent registration race: {len(successes)} succeeded out of 5")
if len(successes) > 1:
    findings.append({"title": "Race condition: duplicate accounts created for identical email", "sev": "S1", "oracle": "F3", "details": f"{len(successes)} registrations succeeded simultaneously"})

print("\n=== Attack Execution Complete ===")
print(f"Total Requests: {REQUEST_COUNT}")
print(f"Findings: {len(findings)}")
print(f"Contract Queries: {len(contract_queries)}")
print(f"Observations: {len(observations)}")

with open("evidence/loop/identity-admin/run-01/results_summary.json", "w", encoding="utf-8") as f:
    json.dump({
        "findings": findings,
        "contract_queries": contract_queries,
        "observations": observations,
        "created_customers": created_customer_ids
    }, f, indent=2)

