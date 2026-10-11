import json
import time
import uuid
import urllib.request
import urllib.error
import subprocess
from datetime import datetime

ENTRY_POINT = "http://localhost:8080"
TRACE_FILE = "evidence/loop/identity-admin/run-02/trace.jsonl"
REQUEST_COUNT = 0
MAX_REQUESTS = 500
LAST_REQ_TIME = 0.0

findings = []
contract_queries = []
observations = []

def redact(data):
    if isinstance(data, dict):
        redacted = {}
        for k, v in data.items():
            if k.lower() in ("password", "pin", "otp", "otp_code", "jwt", "token", "refresh_token", "authorization"):
                redacted[k] = "[REDACTED]"
            else:
                redacted[k] = redact(v)
        return redacted
    elif isinstance(data, list):
        return [redact(item) for item in data]
    return data

def record_trace(method, url, headers, req_body, status, res_body, latency_ms, customer_label="anon"):
    global REQUEST_COUNT
    REQUEST_COUNT += 1
    safe_headers = {k: ("[REDACTED]" if k.lower() in ("authorization", "cookie") else v) for k, v in headers.items()}
    entry = {
        "timestamp": datetime.utcnow().isoformat() + "Z",
        "method": method,
        "url": url,
        "headers": safe_headers,
        "body": redact(req_body),
        "status": status,
        "response_body": redact(res_body),
        "latency_ms": round(latency_ms, 2),
        "customer_label": customer_label
    }
    with open(TRACE_FILE, "a", encoding="utf-8") as f:
        f.write(json.dumps(entry) + "\n")

def http_req(method, path, headers=None, body=None, customer_label="anon"):
    global LAST_REQ_TIME
    if headers is None:
        headers = {}
    
    # Stay strictly under MAX_RPS (10) -> at least 0.12s between calls
    now = time.time()
    elapsed = now - LAST_REQ_TIME
    if elapsed < 0.12:
        time.sleep(0.12 - elapsed)
    
    url = ENTRY_POINT + path if path.startswith("/") else path
    req_data = None
    if body is not None:
        if isinstance(body, (dict, list)):
            req_data = json.dumps(body).encode("utf-8")
            if "Content-Type" not in headers:
                headers["Content-Type"] = "application/json"
        elif isinstance(body, str):
            req_data = body.encode("utf-8")
        else:
            req_data = body

    req = urllib.request.Request(url, data=req_data, headers=headers, method=method)
    t0 = time.time()
    status = 0
    res_data = None
    try:
        with urllib.request.urlopen(req, timeout=10) as response:
            status = response.status
            res_raw = response.read().decode("utf-8", errors="replace")
            try:
                res_data = json.loads(res_raw)
            except Exception:
                res_data = res_raw
    except urllib.error.HTTPError as e:
        status = e.code
        res_raw = e.read().decode("utf-8", errors="replace")
        try:
            res_data = json.loads(res_raw)
        except Exception:
            res_data = res_raw
    except Exception as e:
        status = 0
        res_data = {"client_error": str(e)}
    latency_ms = (time.time() - t0) * 1000
    LAST_REQ_TIME = time.time()

    record_trace(method, url, headers, body, status, res_data, latency_ms, customer_label)
    return status, res_data

def run_invariant(scope_arg):
    cmd = ["python", "tests/invariants/check_invariants.py", "--scope", scope_arg]
    res = subprocess.run(cmd, capture_output=True, text=True)
    return res.returncode, res.stdout.strip()

print("=== Starting Breaker Run 02: Domain identity-admin ===")

# --- Step 1: Retest VERIFIED Findings ---
print("\n--- Step 1: Retesting F-001 ---")
# Authenticate Juan
status, res = http_req("POST", "/api/auth/login", body={"email": "juan.delacruz@example.com", "password": "password123"}, customer_label="juan")
juan_jwt = res["data"]["token"] if status == 200 and isinstance(res, dict) and "data" in res else None
assert juan_jwt, "Juan login failed during retest"

# Retest F-001 reproduction
retest_status, retest_body = http_req(
    "POST",
    "/api/v1/ledger/mutate",
    headers={"Authorization": f"Bearer {juan_jwt}", "X-Idempotency-Key": str(uuid.uuid4())},
    body={
        "accountId": "3722f77f-3d1b-4d55-8f83-b71bac91d095",
        "amount": "10.0000",
        "currency": "PHP",
        "txnType": "DEPOSIT",
        "idempotencyKey": str(uuid.uuid4())
    },
    customer_label="juan_retest_F001"
)
print(f"F-001 Retest: status={retest_status}")
if retest_status == 403:
    print("RETEST PASS F-001: Gateway correctly returned 403 Forbidden.")
else:
    print(f"RETEST FAIL F-001: Expected 403, got {retest_status}")
    findings.append({"title": "REGRESSION of F-001: /api/v1/ledger/mutate not returning 403", "sev": "S1", "oracle": "F7"})

# Retest sibling perimeter routes
perimeter_sibling_routes = [
    "/api/accounts/1/credit",
    "/api/accounts/1/debit",
    "/api/v1/accounts/1/credit",
    "/api/v1/accounts/1/debit",
    "/internal/core/settlement/execute",
    "/internal/risk/score"
]
for p in perimeter_sibling_routes:
    stat, b = http_req("POST", p, headers={"Authorization": f"Bearer {juan_jwt}"}, body={}, customer_label="juan_perimeter")
    print(f"Perimeter {p}: status={stat}")
    if stat not in (403, 404):
        findings.append({"title": f"Perimeter route {p} returned {stat} (expected 403/404)", "sev": "S1", "oracle": "F7"})

# Invariant check post-retest
code, out = run_invariant("global")
print(f"Invariant check post-retest: exit={code}\n{out}")
assert code == 0, f"Invariant baseline dirty after retest: exit={code}"

# --- Step 2 & 3: Identity & Admin Deep Probe ---
print("\n--- Step 2 & 3: Admin & Identity Deep Probe ---")

# Authenticate Admin
status, res = http_req("POST", "/api/auth/login", body={"email": "admin@ledgerbank.com", "password": "admin123"}, customer_label="admin")
admin_jwt = res["data"]["token"] if status == 200 and isinstance(res, dict) and "data" in res else None
assert admin_jwt, "Admin login failed"

# Authenticate Maria (Customer 2)
status, res = http_req("POST", "/api/auth/login", body={"email": "maria.santos@example.com", "password": "password123"}, customer_label="maria")
maria_jwt = res["data"]["token"] if status == 200 and isinstance(res, dict) and "data" in res else None

# Area 3: Authorization & IDOR Attacks on Admin Routes
print("\nTesting Customer on Admin Routes (Privilege Escalation):")
admin_endpoints = [
    ("GET", "/api/admin/accounts", None),
    ("GET", "/api/admin/customers", None),
    ("GET", "/api/v1/admin/held-transfers", None),
    ("POST", f"/api/v1/admin/held-transfers/{uuid.uuid4()}/review", {"decision": "APPROVE"}),
    ("POST", f"/api/v1/admin/transfers/{uuid.uuid4()}/reverse", {"reason": "Test"}),
    ("POST", f"/api/v1/admin/transactions/{uuid.uuid4()}/reverse", {"reason": "Test"}),
    ("PUT", f"/api/v1/admin/accounts/3722f77f-3d1b-4d55-8f83-b71bac91d095/freeze", {"reason": "Test"}),
    ("POST", "/api/recon/runs", {"auditDate": "2026-10-09", "initiatedBy": "ATTACKER"})
]

for method, path, body in admin_endpoints:
    # 1. Without Token
    stat_anon, res_anon = http_req(method, path, body=body, customer_label="anon")
    if stat_anon not in (401, 403, 404):
        findings.append({"title": f"Unauthenticated access allowed on {path}", "sev": "S1", "oracle": "F7", "status": stat_anon})
    
    # 2. With Customer Token (Juan)
    stat_cust, res_cust = http_req(method, path, headers={"Authorization": f"Bearer {juan_jwt}"}, body=body, customer_label="juan_as_admin")
    if stat_cust in (200, 201, 204):
        findings.append({"title": f"Customer token permitted access to admin route {path}", "sev": "S1", "oracle": "F7", "status": stat_cust})
    elif stat_cust not in (401, 403, 404):
        contract_queries.append({"path": path, "status": stat_cust, "expected": "403 ACCESS_DENIED"})

# Admin legitimate access
print("\nTesting Legitimate Admin Actions:")
stat_adm, res_adm = http_req("GET", "/api/admin/accounts", headers={"Authorization": f"Bearer {admin_jwt}"}, customer_label="admin")
print(f"Admin GET /api/admin/accounts: status={stat_adm}")

stat_custs, res_custs = http_req("GET", "/api/admin/customers", headers={"Authorization": f"Bearer {admin_jwt}"}, customer_label="admin")
print(f"Admin GET /api/admin/customers: status={stat_custs}")

stat_held, res_held = http_req("GET", "/api/v1/admin/held-transfers", headers={"Authorization": f"Bearer {admin_jwt}"}, customer_label="admin")
print(f"Admin GET /api/v1/admin/held-transfers: status={stat_held}")

# Area 4: State Machine Attacks on Admin Review & Reversal
print("\nTesting State Machine Attacks:")
# Review non-existent held transfer
stat_rev, res_rev = http_req(
    "POST",
    f"/api/v1/admin/held-transfers/{uuid.uuid4()}/review",
    headers={"Authorization": f"Bearer {admin_jwt}"},
    body={"decision": "APPROVE"},
    customer_label="admin"
)
print(f"Admin review non-existent held transfer: status={stat_rev}")

# Review with invalid decision
stat_inv, res_inv = http_req(
    "POST",
    f"/api/v1/admin/held-transfers/{uuid.uuid4()}/review",
    headers={"Authorization": f"Bearer {admin_jwt}"},
    body={"decision": "MALICIOUS_DECISION"},
    customer_label="admin"
)
print(f"Admin review invalid decision: status={stat_inv}")

# Reverse non-existent transaction
stat_rev_fake, res_rev_fake = http_req(
    "POST",
    f"/api/v1/admin/transfers/{uuid.uuid4()}/reverse",
    headers={"Authorization": f"Bearer {admin_jwt}"},
    body={"reason": "Testing reversal"},
    customer_label="admin"
)
print(f"Admin reverse non-existent transaction: status={stat_rev_fake}")

# Area 6: Account Freeze & Customer Unlock Governance
print("\nTesting Account Freeze and Customer Unlock Governance:")
# Freeze Juan's secondary pocket (acct-juan-php-02: a86c97cc-1ea5-4767-bd5b-148968354f9c)
target_pocket = "a86c97cc-1ea5-4767-bd5b-148968354f9c"
stat_freeze, res_freeze = http_req(
    "PUT",
    f"/api/v1/admin/accounts/{target_pocket}/freeze",
    headers={"Authorization": f"Bearer {admin_jwt}"},
    body={"reason": "AML Audit Freeze"},
    customer_label="admin"
)
print(f"Admin freeze pocket: status={stat_freeze}, res={res_freeze}")

# Now attempt payment from the frozen pocket
stat_pay_frozen, res_pay_frozen = http_req(
    "POST",
    "/api/v1/payments",
    headers={"Authorization": f"Bearer {juan_jwt}", "X-Idempotency-Key": str(uuid.uuid4())},
    body={
        "recipient_account_id": "f95e7e52-3259-4b4d-ac66-b186a893cf66",
        "amount": "100.0000",
        "currency": "PHP",
        "funding_pocket_id": target_pocket
    },
    customer_label="juan_from_frozen"
)
print(f"Payment from frozen pocket: status={stat_pay_frozen}, res={res_pay_frozen}")
if stat_pay_frozen in (200, 201):
    findings.append({"title": "Payment permitted from FROZEN account", "sev": "S1", "oracle": "F3", "status": stat_pay_frozen})

# Attempt customer self-unlock of admin-frozen pocket (CONTRACTS 2.3: must return 422 ACCOUNT_FROZEN)
stat_unlock, res_unlock = http_req(
    "PUT",
    f"/api/v1/accounts/{target_pocket}/unlock",
    headers={"Authorization": f"Bearer {juan_jwt}"},
    body={"otp_code": "123456"},
    customer_label="juan_unlock_attempt"
)
print(f"Customer unlock attempt on admin-frozen pocket: status={stat_unlock}, res={res_unlock}")
if stat_unlock in (200, 204):
    findings.append({"title": "Customer self-unlock successfully unfreezes admin-frozen account", "sev": "S1", "oracle": "F3", "status": stat_unlock})

# Unfreeze / restore pocket via admin if endpoint exists or record observation
# (Check if PUT /api/v1/admin/accounts/{target_pocket}/unfreeze exists)
stat_unfreeze, res_unfreeze = http_req(
    "PUT",
    f"/api/v1/admin/accounts/{target_pocket}/unfreeze",
    headers={"Authorization": f"Bearer {admin_jwt}"},
    body={"reason": "Audit complete"},
    customer_label="admin"
)
print(f"Admin unfreeze attempt: status={stat_unfreeze}")

# Identity Header Poisoning / Forged Identity
print("\nTesting Forged Identity Headers & Injection:")
forged_headers = {
    "Authorization": f"Bearer {juan_jwt}",
    "X-Cust-Id": "66e07d02-05c9-4deb-87d5-10e23cf5968f", # Maria's customer ID
    "X-Roles": "ROLE_ADMIN",
    "X-Role": "ADMIN",
    "X-User-Role": "ROLE_ADMIN"
}
stat_forged_admin, res_forged_admin = http_req("GET", "/api/admin/accounts", headers=forged_headers, customer_label="juan_header_forgery")
if stat_forged_admin in (200, 201):
    findings.append({"title": "Forged X-Role/X-Roles header granted admin access", "sev": "S1", "oracle": "F7", "status": stat_forged_admin})

# Final Global Invariant Check
print("\n--- Final Global Invariant Check ---")
final_code, final_out = run_invariant("global")
print(f"Final Invariant Check: exit={final_code}\n{final_out}")

# Save run-02 summary
with open("evidence/loop/identity-admin/run-02/results_summary.json", "w", encoding="utf-8") as f:
    json.dump({
        "requests_total": REQUEST_COUNT,
        "retest_F001": "PASS" if retest_status == 403 else "FAIL",
        "findings": findings,
        "contract_queries": contract_queries,
        "observations": observations,
        "final_invariant_exit": final_code,
        "final_invariant_output": final_out
    }, f, indent=2)

print("\n=== Breaker Run 02 Finished ===")

