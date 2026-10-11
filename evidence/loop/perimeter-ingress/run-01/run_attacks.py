import json
import time
import uuid
import sys
import urllib.request
import urllib.error
import subprocess
import concurrent.futures
from datetime import datetime

ENTRY_POINT = "http://localhost:8080"
TRACE_FILE = "evidence/loop/perimeter-ingress/run-01/trace.jsonl"
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

def http_req(method, path, headers=None, body=None, customer_label="anon", pace=True):
    global LAST_REQ_TIME
    if headers is None:
        headers = {}
    
    if pace:
        now = time.time()
        elapsed = now - LAST_REQ_TIME
        if elapsed < 0.11:
            time.sleep(0.11 - elapsed)
    
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
    res_headers = {}
    try:
        with urllib.request.urlopen(req, timeout=10) as response:
            status = response.status
            res_headers = dict(response.headers)
            res_raw = response.read().decode("utf-8", errors="replace")
            try:
                res_data = json.loads(res_raw)
            except Exception:
                res_data = res_raw
    except urllib.error.HTTPError as e:
        status = e.code
        res_headers = dict(e.headers)
        res_raw = e.read().decode("utf-8", errors="replace")
        try:
            res_data = json.loads(res_raw)
        except Exception:
            res_data = res_raw
    except Exception as e:
        status = 0
        res_data = {"client_error": str(e)}
    latency_ms = (time.time() - t0) * 1000
    if pace:
        LAST_REQ_TIME = time.time()

    record_trace(method, url, headers, body, status, res_data, latency_ms, customer_label)
    return status, res_data, res_headers

def run_invariant(scope_arg):
    cmd = [sys.executable, "tests/invariants/check_invariants.py", "--scope", scope_arg]
    res = subprocess.run(cmd, capture_output=True, text=True)
    return res.returncode, res.stdout.strip()

print("=== Starting Breaker Run 01: Domain perimeter-ingress ===")

# Authenticate Juan
stat, res, _ = http_req("POST", "/api/auth/login", body={"email": "juan.delacruz@example.com", "password": "password123"}, customer_label="juan")
juan_jwt = res["data"]["token"] if stat == 200 and isinstance(res, dict) and "data" in res else None
assert juan_jwt, "Juan login failed"

# Authenticate Maria
stat, res, _ = http_req("POST", "/api/auth/login", body={"email": "maria.santos@example.com", "password": "password123"}, customer_label="maria")
maria_jwt = res["data"]["token"] if stat == 200 and isinstance(res, dict) and "data" in res else None
assert maria_jwt, "Maria login failed"

# --- Step 1: Retesting VERIFIED Findings ---
print("\n--- Step 1: Retesting Findings F-001, F-002, F-003, F-005 ---")
# F-001
s_f1, _, _ = http_req("POST", "/api/v1/ledger/mutate", headers={"Authorization": f"Bearer {juan_jwt}", "X-Idempotency-Key": str(uuid.uuid4())}, body={"accountId": "3722f77f-3d1b-4d55-8f83-b71bac91d095", "amount": "10.0000", "currency": "PHP", "txnType": "DEPOSIT", "idempotencyKey": str(uuid.uuid4())}, customer_label="juan")
print(f"Retest F-001: {s_f1} (Expected 403)")

# F-002
s_f2, _, _ = http_req("POST", "/api/v1/payments", headers={"Authorization": f"Bearer {juan_jwt}", "X-Idempotency-Key": str(uuid.uuid4())}, body={"recipient_account_id": "f95e7e52-3259-4b4d-ac66-b186a893cf66", "amount": "10.0000", "currency": "PHP", "funding_pocket_id": "a86c97cc-1ea5-4767-bd5b-148968354f9c"}, customer_label="juan")
print(f"Retest F-002: {s_f2} (Expected 400)")

# F-003
s_f3, _, _ = http_req("POST", "/api/v1/payments", headers={"Authorization": f"Bearer {juan_jwt}", "X-Idempotency-Key": str(uuid.uuid4())}, body={"recipient_account_id": "f95e7e52-3259-4b4d-ac66-b186a893cf66", "amount": "10.123456", "currency": "PHP"}, customer_label="juan")
print(f"Retest F-003: {s_f3} (Expected 400)")

# F-005
s_pay, b_pay, _ = http_req("POST", "/api/v1/payments", headers={"Authorization": f"Bearer {juan_jwt}", "X-Idempotency-Key": str(uuid.uuid4())}, body={"recipient_account_id": "f95e7e52-3259-4b4d-ac66-b186a893cf66", "amount": "10.0000", "currency": "PHP"}, customer_label="juan")
t_id = b_pay.get("transaction_id") if isinstance(b_pay, dict) else None
if t_id:
    s_f5, _, _ = http_req("GET", f"/api/v1/payments/{t_id}", headers={"Authorization": f"Bearer {maria_jwt}"}, customer_label="maria")
    print(f"Retest F-005: {s_f5} (Expected 403)")

# --- Journey E2E-09: Perimeter Ingress, Rate Limiting & Header Sanitization ---
print("\n--- Journey E2E-09 Probes ---")

# Step 3 & 4: Header Sanitization (Forged X-Cust-Id)
print("\nTesting Header Sanitization (Forged Identity Headers):")
forged_headers = {
    "Authorization": f"Bearer {juan_jwt}",
    "X-Cust-Id": "cust-admin-001",
    "X-Customer-Id": "cust-admin-001",
    "X-Roles": "ROLE_ADMIN",
    "X-User-Role": "ROLE_ADMIN"
}
s_pockets, b_pockets, h_pockets = http_req("GET", "/api/v1/accounts/pockets", headers=forged_headers, customer_label="juan_forged_header")
print(f"GET pockets with forged X-Cust-Id: status={s_pockets}")
if s_pockets == 200 and isinstance(b_pockets, list):
    for p in b_pockets:
        if p.get("customerId") == "cust-admin-001":
            findings.append({"title": "Forged X-Cust-Id was honored by backend", "sev": "S1", "oracle": "F7"})

# Step 5: Route Cloaking
print("\nTesting Route Cloaking across internal endpoints:")
cloaked_routes = [
    "/internal/core/settlement/execute",
    "/internal/core/settlement",
    "/internal/risk/score",
    "/internal/risk/rules",
    "/internal/batch/standing-instructions/execute",
    "/api/accounts/1/credit",
    "/api/accounts/1/debit",
    "/api/v1/accounts/1/credit",
    "/api/v1/accounts/1/debit",
    "/api/v1/ledger/mutate"
]
for cr in cloaked_routes:
    s_cr, b_cr, _ = http_req("POST", cr, headers={"Authorization": f"Bearer {juan_jwt}"}, body={}, customer_label="juan_cloaking")
    print(f"Route cloaking probe {cr}: status={s_cr}")
    if s_cr not in (403, 404):
        findings.append({"title": f"Protected route {cr} accessible through Gateway with status {s_cr}", "sev": "S1", "oracle": "F7"})

# Security Headers Inspection
print("\nInspecting Security Headers on Gateway Responses:")
sec_headers_to_check = ["x-content-type-options", "x-frame-options", "strict-transport-security"]
print(f"Response headers on /api/v1/accounts/pockets: {list(h_pockets.keys())}")

# Step 1 & 2: Rate Limiter Burst Testing (CONTRACTS 1.2: Redis token-bucket 15/s, burst 30/s)
print("\nTesting Rate Limiter Burst (35 rapid requests without pacing):")
burst_statuses = []
for i in range(35):
    s_burst, b_burst, h_burst = http_req("GET", "/api/v1/accounts/pockets", headers={"Authorization": f"Bearer {juan_jwt}"}, customer_label=f"rate_burst_{i}", pace=False)
    burst_statuses.append(s_burst)
    if s_burst == 429:
        print(f"Hit 429 at request {i+1}: detail={b_burst}, retry-after={h_burst.get('retry-after')}")

c_200 = burst_statuses.count(200)
c_429 = burst_statuses.count(429)
print(f"Burst results: 200 OK count = {c_200}, 429 Too Many Requests count = {c_429}")

# Final Global Invariant Check
print("\n--- Final Global Invariant Check ---")
final_code, final_out = run_invariant("global")
print(f"Final Invariant Check: exit={final_code}\n{final_out}")

# Save summary
with open("evidence/loop/perimeter-ingress/run-01/results_summary.json", "w", encoding="utf-8") as f:
    json.dump({
        "requests_total": REQUEST_COUNT,
        "retest_F001": "PASS" if s_f1 == 403 else "FAIL",
        "retest_F002": "PASS" if s_f2 == 400 else "FAIL",
        "retest_F003": "PASS" if s_f3 == 400 else "FAIL",
        "retest_F005": "PASS" if s_f5 == 403 else "FAIL",
        "rate_limiter_burst_tested": True,
        "rate_limiter_429_observed": c_429 > 0,
        "findings": findings,
        "contract_queries": contract_queries,
        "observations": observations,
        "final_invariant_exit": final_code,
        "final_invariant_output": final_out
    }, f, indent=2)

print("\n=== Breaker Run 01 (perimeter-ingress) Finished ===")

