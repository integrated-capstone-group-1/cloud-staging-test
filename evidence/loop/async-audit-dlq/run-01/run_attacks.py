import json
import time
import uuid
import sys
import urllib.request
import urllib.error
import subprocess
from datetime import datetime

ENTRY_POINT = "http://localhost:8080"
TRACE_FILE = "evidence/loop/async-audit-dlq/run-01/trace.jsonl"
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
    
    # Stay strictly under MAX_RPS (10)
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
    cmd = [sys.executable, "tests/invariants/check_invariants.py", "--scope", scope_arg]
    res = subprocess.run(cmd, capture_output=True, text=True)
    return res.returncode, res.stdout.strip()

print("=== Starting Breaker Run 01: Domain async-audit-dlq ===")

# Authenticate Admin
stat, res = http_req("POST", "/api/auth/login", body={"email": "admin@ledgerbank.com", "password": "admin123"}, customer_label="admin")
admin_jwt = res["data"]["token"] if stat == 200 and isinstance(res, dict) and "data" in res else None
assert admin_jwt, "Admin login failed"

# Authenticate Juan
stat, res = http_req("POST", "/api/auth/login", body={"email": "juan.delacruz@example.com", "password": "password123"}, customer_label="juan")
juan_jwt = res["data"]["token"] if stat == 200 and isinstance(res, dict) and "data" in res else None
assert juan_jwt, "Juan login failed"

# Authenticate Maria
stat, res = http_req("POST", "/api/auth/login", body={"email": "maria.santos@example.com", "password": "password123"}, customer_label="maria")
maria_jwt = res["data"]["token"] if stat == 200 and isinstance(res, dict) and "data" in res else None
assert maria_jwt, "Maria login failed"

MARIA_PHP_ACCT = "f95e7e52-3259-4b4d-ac66-b186a893cf66"

# --- Step 1: Retesting VERIFIED Findings ---
print("\n--- Step 1: Retesting Findings F-001, F-002, F-003, F-005, F-006 ---")
# F-001
s_f1, _ = http_req("POST", "/api/v1/ledger/mutate", headers={"Authorization": f"Bearer {juan_jwt}", "X-Idempotency-Key": str(uuid.uuid4())}, body={"accountId": "3722f77f-3d1b-4d55-8f83-b71bac91d095", "amount": "10.0000", "currency": "PHP", "txnType": "DEPOSIT", "idempotencyKey": str(uuid.uuid4())}, customer_label="juan")
print(f"Retest F-001: {s_f1} (Expected 403)")

# F-002
s_f2, _ = http_req("POST", "/api/v1/payments", headers={"Authorization": f"Bearer {juan_jwt}", "X-Idempotency-Key": str(uuid.uuid4())}, body={"recipient_account_id": MARIA_PHP_ACCT, "amount": "10.0000", "currency": "PHP", "funding_pocket_id": "a86c97cc-1ea5-4767-bd5b-148968354f9c"}, customer_label="juan")
print(f"Retest F-002: {s_f2} (Expected 400)")

# F-003
s_f3, _ = http_req("POST", "/api/v1/payments", headers={"Authorization": f"Bearer {juan_jwt}", "X-Idempotency-Key": str(uuid.uuid4())}, body={"recipient_account_id": MARIA_PHP_ACCT, "amount": "10.123456", "currency": "PHP"}, customer_label="juan")
print(f"Retest F-003: {s_f3} (Expected 400)")

# F-005
s_pay, b_pay = http_req("POST", "/api/v1/payments", headers={"Authorization": f"Bearer {juan_jwt}", "X-Idempotency-Key": str(uuid.uuid4())}, body={"recipient_account_id": MARIA_PHP_ACCT, "amount": "10.0000", "currency": "PHP"}, customer_label="juan")
t_id = b_pay.get("transaction_id") if isinstance(b_pay, dict) else None
if t_id:
    s_f5, _ = http_req("GET", f"/api/v1/payments/{t_id}", headers={"Authorization": f"Bearer {maria_jwt}"}, customer_label="maria")
    print(f"Retest F-005: {s_f5} (Expected 403)")

# F-006
s_f6, _ = http_req("GET", "/api/v1/payments/history", headers={"Authorization": f"Bearer {juan_jwt}", "X-Cust-Id": "cust-admin-001"}, customer_label="juan")
print(f"Retest F-006: {s_f6} (Expected 200)")

# --- Journey E2E-10: Async Audit & DLQ Replay Probes ---
print("\n--- Journey E2E-10 Probes ---")

# Probing DLQ endpoints
dlq_endpoints = [
    ("GET", "/api/v1/admin/dlq", None),
    ("GET", "/api/admin/dlq", None),
    ("GET", "/admin/dlq", None),
    ("POST", "/api/v1/admin/dlq/replay", {"topic": "transaction.events.dlq"}),
    ("POST", "/api/admin/dlq/replay", {"topic": "transaction.events.dlq"}),
]

for method, path, b in dlq_endpoints:
    # 1. Unauthenticated
    s_anon, r_anon = http_req(method, path, body=b, customer_label="anon")
    print(f"Anon {method} {path}: status={s_anon}")
    if s_anon in (200, 201, 202):
        findings.append({"title": f"Unauthenticated access permitted to DLQ route {path}", "sev": "S1", "oracle": "F7", "status": s_anon})
    
    # 2. Customer token (privilege escalation)
    s_cust, r_cust = http_req(method, path, headers={"Authorization": f"Bearer {juan_jwt}"}, body=b, customer_label="juan_as_admin")
    print(f"Customer {method} {path}: status={s_cust}")
    if s_cust in (200, 201, 202):
        findings.append({"title": f"Customer token permitted access to DLQ route {path}", "sev": "S1", "oracle": "F7", "status": s_cust})
    
    # 3. Admin token
    s_adm, r_adm = http_req(method, path, headers={"Authorization": f"Bearer {admin_jwt}"}, body=b, customer_label="admin")
    print(f"Admin {method} {path}: status={s_adm}")
    if s_adm >= 500:
        findings.append({"title": f"DLQ route {path} crashed with 5xx", "sev": "S2", "oracle": "F1", "status": s_adm})

# Probing Audit Trail Endpoints
print("\nProbing Audit Trail Endpoints:")
if t_id:
    audit_paths = [
        f"/api/v1/ledger/audit/{t_id}",
        f"/api/v1/audit/{t_id}",
        f"/api/audit/{t_id}"
    ]
    for ap in audit_paths:
        # Customer
        s_ca, r_ca = http_req("GET", ap, headers={"Authorization": f"Bearer {juan_jwt}"}, customer_label="juan")
        print(f"Customer GET {ap}: status={s_ca}")
        # Maria (Cross-customer audit query)
        s_ma, r_ma = http_req("GET", ap, headers={"Authorization": f"Bearer {maria_jwt}"}, customer_label="maria")
        print(f"Maria GET {ap} (cross-customer): status={s_ma}")
        if s_ma == 200:
            findings.append({"title": f"Cross-customer access permitted to audit route {ap}", "sev": "S1", "oracle": "F7"})

# Final Global Invariant Check (waiting drain 30s to verify eventual invariants I-4, I-5, I-7)
print("\n--- Final Global Invariant Check ---")
final_code, final_out = run_invariant("global")
print(f"Final Invariant Check: exit={final_code}\n{final_out}")

# Save summary
with open("evidence/loop/async-audit-dlq/run-01/results_summary.json", "w", encoding="utf-8") as f:
    json.dump({
        "requests_total": REQUEST_COUNT,
        "retest_F001": "PASS" if s_f1 == 403 else "FAIL",
        "retest_F002": "PASS" if s_f2 == 400 else "FAIL",
        "retest_F003": "PASS" if s_f3 == 400 else "FAIL",
        "retest_F005": "PASS" if (t_id and s_f5 == 403) else "FAIL",
        "retest_F006": "PASS" if s_f6 == 200 else "FAIL",
        "findings": findings,
        "contract_queries": contract_queries,
        "observations": observations,
        "final_invariant_exit": final_code,
        "final_invariant_output": final_out
    }, f, indent=2)

print("\n=== Breaker Run 01 (async-audit-dlq) Finished ===")

