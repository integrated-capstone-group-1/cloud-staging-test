import json
import time
import uuid
import sys
import urllib.request
import urllib.error
import subprocess
from datetime import datetime

ENTRY_POINT = "http://localhost:8080"
TRACE_FILE = "evidence/loop/cbs-recon/run-01/trace.jsonl"
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

print("=== Starting Breaker Run 01: Domain cbs-recon ===")

# Authenticate Admin
s_adm, r_adm = http_req("POST", "/api/auth/login", body={"email": "admin@ledgerbank.com", "password": "admin123"}, customer_label="admin")
admin_jwt = r_adm["data"]["token"] if s_adm == 200 and isinstance(r_adm, dict) and "data" in r_adm else None
assert admin_jwt, "Admin authentication failed"

# Authenticate Customer (Juan)
s_juan, r_juan = http_req("POST", "/api/auth/login", body={"email": "juan.delacruz@example.com", "password": "password123"}, customer_label="juan")
juan_jwt = r_juan["data"]["token"] if s_juan == 200 and isinstance(r_juan, dict) and "data" in r_juan else None
assert juan_jwt, "Juan authentication failed"

# Authenticate Customer (Maria)
s_maria, r_maria = http_req("POST", "/api/auth/login", body={"email": "maria.santos@example.com", "password": "password123"}, customer_label="maria")
maria_jwt = r_maria["data"]["token"] if s_maria == 200 and isinstance(r_maria, dict) and "data" in r_maria else None
assert maria_jwt, "Maria authentication failed"

# --- Step 1: Retesting VERIFIED Findings ---
print("\n--- Step 1: Retesting Findings F-001 to F-005 ---")
# F-001 Retest
stat_f1, _ = http_req("POST", "/api/v1/ledger/mutate", headers={"Authorization": f"Bearer {juan_jwt}", "X-Idempotency-Key": str(uuid.uuid4())}, body={"accountId": "3722f77f-3d1b-4d55-8f83-b71bac91d095", "amount": "10.0000", "currency": "PHP", "txnType": "DEPOSIT", "idempotencyKey": str(uuid.uuid4())}, customer_label="juan")
print(f"Retest F-001: {stat_f1} (Expected 403)")

# F-002 Retest
stat_f2, _ = http_req("POST", "/api/v1/payments", headers={"Authorization": f"Bearer {juan_jwt}", "X-Idempotency-Key": str(uuid.uuid4())}, body={"recipient_account_id": "f95e7e52-3259-4b4d-ac66-b186a893cf66", "amount": "10.0000", "currency": "PHP", "funding_pocket_id": "a86c97cc-1ea5-4767-bd5b-148968354f9c"}, customer_label="juan")
print(f"Retest F-002: {stat_f2} (Expected 400)")

# F-003 Retest
stat_f3, _ = http_req("POST", "/api/v1/payments", headers={"Authorization": f"Bearer {juan_jwt}", "X-Idempotency-Key": str(uuid.uuid4())}, body={"recipient_account_id": "f95e7e52-3259-4b4d-ac66-b186a893cf66", "amount": "10.123456", "currency": "PHP"}, customer_label="juan")
print(f"Retest F-003: {stat_f3} (Expected 400)")

# F-005 Retest
# Create fresh payment by Juan
_, pay_b = http_req("POST", "/api/v1/payments", headers={"Authorization": f"Bearer {juan_jwt}", "X-Idempotency-Key": str(uuid.uuid4())}, body={"recipient_account_id": "f95e7e52-3259-4b4d-ac66-b186a893cf66", "amount": "10.0000", "currency": "PHP"}, customer_label="juan")
fresh_tid = pay_b.get("transaction_id") if isinstance(pay_b, dict) else None
if fresh_tid:
    stat_f5, _ = http_req("GET", f"/api/v1/payments/{fresh_tid}", headers={"Authorization": f"Bearer {maria_jwt}"}, customer_label="maria")
    print(f"Retest F-005: {stat_f5} (Expected 403)")

# --- Step 2 & 3: CBS & Reconciliation Probes ---
print("\n--- Step 2 & 3: Reconciliation & Batch Probes ---")

recon_paths = [
    ("POST", "/api/v1/recon/trigger", {}),
    ("POST", "/api/recon/runs", {"auditDate": "2026-10-10", "initiatedBy": "ADMIN"}),
    ("GET", "/api/v1/recon/runs", None),
    ("GET", "/api/recon/runs", None),
    ("GET", "/api/v1/recon/status", None),
    ("GET", "/api/recon/status", None)
]

for method, path, b in recon_paths:
    # 1. Unauthenticated
    stat_anon, res_anon = http_req(method, path, body=b, customer_label="anon")
    print(f"Anon {method} {path}: status={stat_anon}")
    if stat_anon in (200, 201, 202):
        findings.append({"title": f"Unauthenticated access permitted to reconciliation route {path}", "sev": "S1", "oracle": "F7", "status": stat_anon})
    
    # 2. Customer token (privilege escalation)
    stat_cust, res_cust = http_req(method, path, headers={"Authorization": f"Bearer {juan_jwt}"}, body=b, customer_label="juan_as_admin")
    print(f"Customer {method} {path}: status={stat_cust}")
    if stat_cust in (200, 201, 202):
        findings.append({"title": f"Customer token permitted access to reconciliation route {path}", "sev": "S1", "oracle": "F7", "status": stat_cust})
    
    # 3. Admin token (legitimate trigger / query)
    stat_adm, res_adm = http_req(method, path, headers={"Authorization": f"Bearer {admin_jwt}"}, body=b, customer_label="admin")
    print(f"Admin {method} {path}: status={stat_adm}")
    if stat_adm >= 500:
        findings.append({"title": f"Reconciliation route {path} crashed with 5xx", "sev": "S2", "oracle": "F1", "status": stat_adm, "res": res_adm})

# --- Hostile Inputs on Reconciliation ---
print("\n--- Hostile Inputs on Reconciliation Trigger ---")
bad_recon_inputs = [
    ("future_date", {"auditDate": "2099-12-31", "initiatedBy": "ADMIN"}),
    ("invalid_date", {"auditDate": "not-a-date", "initiatedBy": "ADMIN"}),
    ("sqli_initiator", {"auditDate": "2026-10-10", "initiatedBy": "admin' OR '1'='1"}),
    ("empty_payload", {}),
    ("malformed_json", "not json")
]

for name, payload in bad_recon_inputs:
    for target in ["/api/v1/recon/trigger", "/api/recon/runs"]:
        s, r = http_req("POST", target, headers={"Authorization": f"Bearer {admin_jwt}"}, body=payload, customer_label="admin")
        print(f"Bad input [{name}] on {target}: status={s}")
        if s >= 500:
            findings.append({"title": f"HTTP 5xx on reconciliation trigger with {name}", "sev": "S2", "oracle": "F1", "target": target, "status": s, "res": r})

# --- Perimeter & Internal Batch Cloaking ---
print("\n--- Perimeter & Internal Batch Cloaking ---")
batch_internal_routes = [
    "/internal/batch/standing-instructions/execute",
    "/internal/batch/standing-instructions",
    "/api/batch/standing-instructions/execute",
    "/api/v1/batch/standing-instructions/execute",
    "/internal/recon/execute",
    "/internal/recon/trigger"
]

for r in batch_internal_routes:
    # Try reaching internal routes through Gateway
    stat_b, res_b = http_req("POST", r, headers={"Authorization": f"Bearer {admin_jwt}"}, body={}, customer_label="admin_internal_probe")
    print(f"Perimeter internal probe {r}: status={stat_b}")
    if stat_b not in (403, 404):
        findings.append({"title": f"Internal batch/recon route {r} reachable through Gateway (status {stat_b})", "sev": "S1", "oracle": "F7", "status": stat_b})

# Final Global Invariant Check
print("\n--- Final Global Invariant Check ---")
final_code, final_out = run_invariant("global")
print(f"Final Invariant Check: exit={final_code}\n{final_out}")

# Save summary
with open("evidence/loop/cbs-recon/run-01/results_summary.json", "w", encoding="utf-8") as f:
    json.dump({
        "requests_total": REQUEST_COUNT,
        "retest_F001": "PASS" if stat_f1 == 403 else "FAIL",
        "retest_F002": "PASS" if stat_f2 == 400 else "FAIL",
        "retest_F003": "PASS" if stat_f3 == 400 else "FAIL",
        "retest_F005": "PASS" if (fresh_tid and stat_f5 == 403) else "FAIL",
        "findings": findings,
        "contract_queries": contract_queries,
        "observations": observations,
        "final_invariant_exit": final_code,
        "final_invariant_output": final_out
    }, f, indent=2)

print("\n=== Breaker Run 01 (cbs-recon) Finished ===")

