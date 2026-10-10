import json
import time
import uuid
import sys
import urllib.request
import urllib.error
import subprocess
from datetime import datetime

ENTRY_POINT = "http://localhost:8080"
TRACE_FILE = "evidence/loop/payments/run-03/trace.jsonl"
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

print("=== Starting Breaker Run 03: Domain payments ===")

# Authenticate Juan
stat, res = http_req("POST", "/api/auth/login", body={"email": "juan.delacruz@example.com", "password": "password123"}, customer_label="juan")
juan_jwt = res["data"]["token"] if stat == 200 and isinstance(res, dict) and "data" in res else None
assert juan_jwt, "Juan login failed"

# Authenticate Maria
stat, res = http_req("POST", "/api/auth/login", body={"email": "maria.santos@example.com", "password": "password123"}, customer_label="maria")
maria_jwt = res["data"]["token"] if stat == 200 and isinstance(res, dict) and "data" in res else None
assert maria_jwt, "Maria login failed"

MARIA_PHP_ACCT = "f95e7e52-3259-4b4d-ac66-b186a893cf66"

# --- Step 1: Retesting F-005 ---
print("\n--- Step 1: Retesting F-005 (IDOR on GET /api/v1/payments/{id}) ---")
retest_f5_statuses = []

for i in range(3):
    # Juan creates a payment
    s_pay, b_pay = http_req(
        "POST",
        "/api/v1/payments",
        headers={"Authorization": f"Bearer {juan_jwt}", "X-Idempotency-Key": str(uuid.uuid4())},
        body={"recipient_account_id": MARIA_PHP_ACCT, "amount": f"{10 + i}.0000", "currency": "PHP"},
        customer_label="juan"
    )
    tid = b_pay.get("transaction_id") if isinstance(b_pay, dict) else None
    assert tid, f"Payment creation failed at iteration {i}"
    
    # Maria attempts IDOR query
    s_idor, b_idor = http_req("GET", f"/api/v1/payments/{tid}", headers={"Authorization": f"Bearer {maria_jwt}"}, customer_label="maria_idor_attempt")
    print(f"Retest F-005 attempt {i+1}: status={s_idor}")
    retest_f5_statuses.append(s_idor)

if all(s == 403 for s in retest_f5_statuses):
    print("RETEST PASS F-005: Cross-customer payment query strictly blocked with 403 ACCESS_DENIED.")
else:
    print(f"RETEST FAIL F-005: {retest_f5_statuses}")
    findings.append({"title": "REGRESSION of F-005: Cross-customer IDOR still permitted", "sev": "S1", "oracle": "F7"})

# Re-verify F-003
s_f3, _ = http_req(
    "POST",
    "/api/v1/payments",
    headers={"Authorization": f"Bearer {juan_jwt}", "X-Idempotency-Key": str(uuid.uuid4())},
    body={"recipient_account_id": MARIA_PHP_ACCT, "amount": "10.123456", "currency": "PHP"},
    customer_label="juan"
)
print(f"Re-verify F-003 status: {s_f3} (Expected 400)")

# Re-verify F-004
race_key = str(uuid.uuid4())
s_r1, _ = http_req("POST", "/api/v1/payments", headers={"Authorization": f"Bearer {juan_jwt}", "X-Idempotency-Key": race_key}, body={"recipient_account_id": MARIA_PHP_ACCT, "amount": "10.0000", "currency": "PHP"}, customer_label="juan_race1")
s_r2, b_r2 = http_req("POST", "/api/v1/payments", headers={"Authorization": f"Bearer {juan_jwt}", "X-Idempotency-Key": race_key}, body={"recipient_account_id": MARIA_PHP_ACCT, "amount": "10.0000", "currency": "PHP"}, customer_label="juan_race2")
print(f"Re-verify F-004 replay status: {s_r2} (Cached replay={s_r1})")

# Final Global Invariant Check
print("\n--- Final Global Invariant Check ---")
final_code, final_out = run_invariant("global")
print(f"Final Invariant Check: exit={final_code}\n{final_out}")

# Save summary
with open("evidence/loop/payments/run-03/results_summary.json", "w", encoding="utf-8") as f:
    json.dump({
        "requests_total": REQUEST_COUNT,
        "retest_F005": "PASS" if all(s == 403 for s in retest_f5_statuses) else "FAIL",
        "findings": findings,
        "contract_queries": contract_queries,
        "observations": observations,
        "final_invariant_exit": final_code,
        "final_invariant_output": final_out
    }, f, indent=2)

print("\n=== Breaker Run 03 (payments) Finished ===")

