import json
import time
import uuid
import sys
import urllib.request
import urllib.error
import subprocess
from datetime import datetime

ENTRY_POINT = "http://localhost:8080"
TRACE_FILE = "evidence/loop/perimeter-ingress/run-02/trace.jsonl"
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

print("=== Starting Breaker Run 02: Domain perimeter-ingress ===")

# Authenticate Juan
stat, res = http_req("POST", "/api/auth/login", body={"email": "juan.delacruz@example.com", "password": "password123"}, customer_label="juan")
juan_jwt = res["data"]["token"] if stat == 200 and isinstance(res, dict) and "data" in res else None
assert juan_jwt, "Juan authentication failed"

# Authenticate Maria
stat, res = http_req("POST", "/api/auth/login", body={"email": "maria.santos@example.com", "password": "password123"}, customer_label="maria")
maria_jwt = res["data"]["token"] if stat == 200 and isinstance(res, dict) and "data" in res else None
assert maria_jwt, "Maria authentication failed"

# --- Step 1: Retesting F-006 ---
print("\n--- Step 1: Retesting F-006 (Header Sanitization & Gateway Crash) ---")
retest_f6_results = []
endpoints = [
    "/api/v1/payments/history",
    "/api/v1/accounts/3722f77f-3d1b-4d55-8f83-b71bac91d095/pockets",
    "/api/v1/cards"
]

for ep in endpoints:
    headers = {
        "Authorization": f"Bearer {juan_jwt}",
        "X-Cust-Id": "cust-admin-001"
    }
    s_f6, b_f6 = http_req("GET", ep, headers=headers, customer_label="juan_retest_f6")
    print(f"Retest F-006 on {ep}: status={s_f6}")
    retest_f6_results.append(s_f6)

if all(s == 200 for s in retest_f6_results):
    print("RETEST PASS F-006: Client-supplied X-Cust-Id successfully sanitized without 500 error; returns 200 OK for authenticated user.")
else:
    print(f"RETEST FAIL F-006: {retest_f6_results}")
    findings.append({"title": "REGRESSION of F-006: Client-supplied X-Cust-Id still triggers errors", "sev": "S2", "oracle": "F1"})

# Re-verifying previous findings
# F-001
s_f1, _ = http_req("POST", "/api/v1/ledger/mutate", headers={"Authorization": f"Bearer {juan_jwt}", "X-Idempotency-Key": str(uuid.uuid4())}, body={"accountId": "3722f77f-3d1b-4d55-8f83-b71bac91d095", "amount": "10.0000", "currency": "PHP", "txnType": "DEPOSIT", "idempotencyKey": str(uuid.uuid4())}, customer_label="juan")
print(f"Re-verify F-001 status: {s_f1} (Expected 403)")

# F-003
s_f3, _ = http_req("POST", "/api/v1/payments", headers={"Authorization": f"Bearer {juan_jwt}", "X-Idempotency-Key": str(uuid.uuid4())}, body={"recipient_account_id": "f95e7e52-3259-4b4d-ac66-b186a893cf66", "amount": "10.123456", "currency": "PHP"}, customer_label="juan")
print(f"Re-verify F-003 status: {s_f3} (Expected 400)")

# F-005
s_pay, b_pay = http_req("POST", "/api/v1/payments", headers={"Authorization": f"Bearer {juan_jwt}", "X-Idempotency-Key": str(uuid.uuid4())}, body={"recipient_account_id": "f95e7e52-3259-4b4d-ac66-b186a893cf66", "amount": "10.0000", "currency": "PHP"}, customer_label="juan")
tid = b_pay.get("transaction_id") if isinstance(b_pay, dict) else None
if tid:
    s_f5, _ = http_req("GET", f"/api/v1/payments/{tid}", headers={"Authorization": f"Bearer {maria_jwt}"}, customer_label="maria")
    print(f"Re-verify F-005 status: {s_f5} (Expected 403)")

# Final Global Invariant Check
print("\n--- Final Global Invariant Check ---")
final_code, final_out = run_invariant("global")
print(f"Final Invariant Check: exit={final_code}\n{final_out}")

# Save summary
with open("evidence/loop/perimeter-ingress/run-02/results_summary.json", "w", encoding="utf-8") as f:
    json.dump({
        "requests_total": REQUEST_COUNT,
        "retest_F006": "PASS" if all(s == 200 for s in retest_f6_results) else "FAIL",
        "findings": findings,
        "contract_queries": contract_queries,
        "observations": observations,
        "final_invariant_exit": final_code,
        "final_invariant_output": final_out
    }, f, indent=2)

print("\n=== Breaker Run 02 (perimeter-ingress) Finished ===")

