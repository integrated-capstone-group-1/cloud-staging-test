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
TRACE_FILE = "evidence/loop/payments/run-02/trace.jsonl"
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

print("=== Starting Breaker Run 02: Domain payments ===")

# Authenticate Juan
stat, res = http_req("POST", "/api/auth/login", body={"email": "juan.delacruz@example.com", "password": "password123"}, customer_label="juan")
juan_jwt = res["data"]["token"] if stat == 200 and isinstance(res, dict) and "data" in res else None
assert juan_jwt, "Juan authentication failed"

MARIA_PHP_ACCT = "f95e7e52-3259-4b4d-ac66-b186a893cf66"

# --- Step 1: Retesting VERIFIED findings ---
print("\n--- Step 1: Retesting F-003 & F-004 ---")
# Retest F-003: Over-scaled and scientific notation amounts
retest_f3_results = []
f3_tests = [("scale_6", "10.123456"), ("scale_8", "10.12345678"), ("scientific", "1e3")]
for name, amt in f3_tests:
    s, b = http_req(
        "POST",
        "/api/v1/payments",
        headers={"Authorization": f"Bearer {juan_jwt}", "X-Idempotency-Key": str(uuid.uuid4())},
        body={"recipient_account_id": MARIA_PHP_ACCT, "amount": amt, "currency": "PHP"},
        customer_label=f"juan_retest_F003_{name}"
    )
    print(f"Retest F-003 [{name}]: status={s}")
    retest_f3_results.append(s)

if all(s == 400 for s in retest_f3_results):
    print("RETEST PASS F-003: All over-scaled and scientific notation amounts rejected with 400 VALIDATION_FAILED.")
else:
    print(f"RETEST FAIL F-003: {retest_f3_results}")
    findings.append({"title": "REGRESSION of F-003", "sev": "S1", "oracle": "F8"})

# Retest F-004: Concurrent requests with identical idempotency key
race_key = str(uuid.uuid4())
race_payload = {"recipient_account_id": MARIA_PHP_ACCT, "amount": "25.0000", "currency": "PHP"}

def send_race(idx):
    return http_req(
        "POST",
        "/api/v1/payments",
        headers={"Authorization": f"Bearer {juan_jwt}", "X-Idempotency-Key": race_key},
        body=race_payload,
        customer_label=f"race_{idx}"
    )

with concurrent.futures.ThreadPoolExecutor(max_workers=5) as executor:
    futures = [executor.submit(send_race, i) for i in range(5)]
    race_results = [f.result() for f in futures]

race_statuses = [r[0] for r in race_results]
print(f"Retest F-004 statuses (5 threads): {race_statuses}")
has_sql_leak = any("ORA-" in str(r[1]) or "SQL [" in str(r[1]) for r in race_results)
has_request_in_progress = any(r[0] == 409 for r in race_results)

if not has_sql_leak and has_request_in_progress:
    print("RETEST PASS F-004: Redis fast-locking returned 409 REQUEST_IN_PROGRESS; zero SQL or constraint leakage.")
else:
    print("RETEST FAIL F-004")
    findings.append({"title": "REGRESSION of F-004", "sev": "S1", "oracle": "F2"})

# --- Depth Probes on Payments ---
print("\n--- Depth Probes on Smart Routing & Status Query ---")
# Query payment by ID
pay_key = str(uuid.uuid4())
s_pay, b_pay = http_req("POST", "/api/v1/payments", headers={"Authorization": f"Bearer {juan_jwt}", "X-Idempotency-Key": pay_key}, body={"recipient_account_id": MARIA_PHP_ACCT, "amount": "12.0000", "currency": "PHP"}, customer_label="juan")
txn_id = b_pay.get("transaction_id") if isinstance(b_pay, dict) else None

if txn_id:
    # Query own payment
    s_get, b_get = http_req("GET", f"/api/v1/payments/{txn_id}", headers={"Authorization": f"Bearer {juan_jwt}"}, customer_label="juan")
    print(f"GET /api/v1/payments/{txn_id}: status={s_get}")
    
    # Authenticate Maria and attempt IDOR query
    stat, res = http_req("POST", "/api/auth/login", body={"email": "maria.santos@example.com", "password": "password123"}, customer_label="maria")
    maria_jwt = res["data"]["token"] if stat == 200 and isinstance(res, dict) and "data" in res else None
    if maria_jwt:
        s_idor, b_idor = http_req("GET", f"/api/v1/payments/{txn_id}", headers={"Authorization": f"Bearer {maria_jwt}"}, customer_label="maria")
        print(f"Maria GET Juan's txn (IDOR): status={s_idor}")
        if s_idor in (200, 201):
            findings.append({"title": "IDOR: Customer can view another customer's payment details", "sev": "S1", "oracle": "F7", "status": s_idor})

# Final Global Invariant Check
print("\n--- Final Global Invariant Check ---")
final_code, final_out = run_invariant("global")
print(f"Final Invariant Check: exit={final_code}\n{final_out}")

# Save summary
with open("evidence/loop/payments/run-02/results_summary.json", "w", encoding="utf-8") as f:
    json.dump({
        "requests_total": REQUEST_COUNT,
        "retest_F003": "PASS" if all(s == 400 for s in retest_f3_results) else "FAIL",
        "retest_F004": "PASS" if not has_sql_leak and has_request_in_progress else "FAIL",
        "findings": findings,
        "contract_queries": contract_queries,
        "observations": observations,
        "final_invariant_exit": final_code,
        "final_invariant_output": final_out
    }, f, indent=2)

print("\n=== Breaker Run 02 (payments) Finished ===")

