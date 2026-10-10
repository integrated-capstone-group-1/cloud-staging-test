import json
import time
import uuid
import sys
import os
import urllib.request
import urllib.error
import subprocess
from datetime import datetime

ENTRY_POINT = "http://localhost:8080"
RUN_DIR = "evidence/loop/cross-domain/run-02"
os.makedirs(RUN_DIR, exist_ok=True)
TRACE_FILE = os.path.join(RUN_DIR, "trace.jsonl")
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
        "timestamp": datetime.now().isoformat() + "Z",
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
    
    # Pacing: MAX_RPS (10)
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

print("=== Starting Breaker Run 02: Retesting F-008 (Domain cross-domain) ===")

# Baseline invariant check
pre_code, pre_out = run_invariant("global")
print(f"Preflight Invariant Check: exit={pre_code}")
assert pre_code == 0, f"Baseline invariants failed:\n{pre_out}"

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

JUAN_PHP_ACCT = "3722f77f-3d1b-4d55-8f83-b71bac91d095"
MARIA_PHP_ACCT = "f95e7e52-3259-4b4d-ac66-b186a893cf66"

# --- Retesting Prior Verified Findings ---
print("\n--- Retesting Prior Findings F-001 through F-007 ---")

# F-001
s_f1, _ = http_req("POST", "/api/v1/ledger/mutate", headers={"Authorization": f"Bearer {juan_jwt}", "X-Idempotency-Key": str(uuid.uuid4())}, body={"accountId": JUAN_PHP_ACCT, "amount": "10.0000", "currency": "PHP", "txnType": "DEPOSIT", "idempotencyKey": str(uuid.uuid4())}, customer_label="juan")
print(f"Retest F-001: status={s_f1} (Expected 403)")
retest_f1 = (s_f1 == 403)

# F-002
s_f2, _ = http_req("POST", "/api/v1/payments", headers={"Authorization": f"Bearer {juan_jwt}", "X-Idempotency-Key": str(uuid.uuid4())}, body={"recipient_account_id": MARIA_PHP_ACCT, "amount": "10.0000", "currency": "PHP", "funding_pocket_id": "a86c97cc-1ea5-4767-bd5b-148968354f9c"}, customer_label="juan")
print(f"Retest F-002: status={s_f2} (Expected 400)")
retest_f2 = (s_f2 == 400)

# F-003
s_f3, _ = http_req("POST", "/api/v1/payments", headers={"Authorization": f"Bearer {juan_jwt}", "X-Idempotency-Key": str(uuid.uuid4())}, body={"recipient_account_id": MARIA_PHP_ACCT, "amount": "10.123456", "currency": "PHP"}, customer_label="juan")
print(f"Retest F-003: status={s_f3} (Expected 400)")
retest_f3 = (s_f3 == 400)

# Create Juan payment
s_pay, b_pay = http_req("POST", "/api/v1/payments", headers={"Authorization": f"Bearer {juan_jwt}", "X-Idempotency-Key": str(uuid.uuid4())}, body={"recipient_account_id": MARIA_PHP_ACCT, "amount": "10.0000", "currency": "PHP"}, customer_label="juan")
assert s_pay in (200, 201), f"Payment creation failed: {s_pay} {b_pay}"
juan_txn_id = b_pay.get("transaction_id")
print(f"Created Juan payment transaction: {juan_txn_id}")

# F-004
idemp_key_test = str(uuid.uuid4())
s_idem1, _ = http_req("POST", "/api/v1/payments", headers={"Authorization": f"Bearer {juan_jwt}", "X-Idempotency-Key": idemp_key_test}, body={"recipient_account_id": MARIA_PHP_ACCT, "amount": "1.0000", "currency": "PHP"}, customer_label="juan")
s_idem2, _ = http_req("POST", "/api/v1/payments", headers={"Authorization": f"Bearer {juan_jwt}", "X-Idempotency-Key": idemp_key_test}, body={"recipient_account_id": MARIA_PHP_ACCT, "amount": "1.0000", "currency": "PHP"}, customer_label="juan")
retest_f4 = (s_idem1 in (200, 201) and s_idem2 in (200, 201, 409))
print(f"Retest F-004: status={retest_f4}")

# F-005
s_f5, _ = http_req("GET", f"/api/v1/payments/{juan_txn_id}", headers={"Authorization": f"Bearer {maria_jwt}"}, customer_label="maria")
print(f"Retest F-005: status={s_f5} (Expected 403)")
retest_f5 = (s_f5 == 403)

# F-006
s_f6, _ = http_req("GET", "/api/v1/payments/history", headers={"Authorization": f"Bearer {juan_jwt}", "X-Cust-Id": "cust-admin-001"}, customer_label="juan")
print(f"Retest F-006: status={s_f6} (Expected 200)")
retest_f6 = (s_f6 == 200)

# F-007
s_f7, _ = http_req("GET", f"/api/v1/ledger/audit/{juan_txn_id}", headers={"Authorization": f"Bearer {maria_jwt}"}, customer_label="maria")
print(f"Retest F-007: status={s_f7} (Expected 403)")
retest_f7 = (s_f7 == 403)

# --- Retesting F-008: Cross-customer FX quote hijacking ---
print("\n--- Retesting F-008: Cross-customer FX quote hijacking on POST /api/v1/fx/convert ---")

# Step 1: Juan generates an FX quote
s_q, b_q = http_req("GET", "/api/v1/fx/quote?from=PHP&to=EUR&amount=5.0000", headers={"Authorization": f"Bearer {juan_jwt}"}, customer_label="juan")
assert s_q == 200, f"Quote generation failed: {s_q} {b_q}"
juan_quote_id = b_q["quote_id"]
print(f"Juan generated quote: {juan_quote_id}")

# Step 2: Maria attempts to convert using Juan's quote (Cross-customer hijacking)
s_f8_cross, r_f8_cross = http_req("POST", "/api/v1/fx/convert", headers={"Authorization": f"Bearer {maria_jwt}"}, body={"quote_id": juan_quote_id, "source_pocket_id": MARIA_PHP_ACCT, "target_pocket_id": MARIA_PHP_ACCT}, customer_label="maria")
print(f"Retest F-008 (Maria uses Juan's quote): status={s_f8_cross}, body={r_f8_cross}")

# Step 3: Anonymous conversion attempt
s_f8_anon, r_f8_anon = http_req("POST", "/api/v1/fx/convert", body={"quote_id": juan_quote_id, "source_pocket_id": MARIA_PHP_ACCT, "target_pocket_id": MARIA_PHP_ACCT}, customer_label="anon")
print(f"Retest F-008 (Anon conversion attempt): status={s_f8_anon}")

# Step 4: Juan converts using his OWN quote (Legitimate owner conversion)
s_f8_owner, r_f8_owner = http_req("POST", "/api/v1/fx/convert", headers={"Authorization": f"Bearer {juan_jwt}"}, body={"quote_id": juan_quote_id, "source_pocket_id": JUAN_PHP_ACCT, "target_pocket_id": JUAN_PHP_ACCT}, customer_label="juan")
print(f"Retest F-008 (Juan uses his own quote): status={s_f8_owner}, body={r_f8_owner}")

# Evaluate F-008
retest_f8_pass = (s_f8_cross in (400, 403, 404)) and (s_f8_anon == 401) and (s_f8_owner in (200, 201))
if retest_f8_pass:
    print(">>> RETEST PASS F-008: Cross-customer FX quote hijacking is strictly blocked! <<<")
else:
    print(f">>> RETEST FAIL F-008: Cross-customer returned {s_f8_cross}, owner returned {s_f8_owner} <<<")
    findings.append({
        "title": f"F-008 Retest Failure: Cross-customer conversion returned {s_f8_cross}",
        "sev": "S1",
        "oracle": "F7"
    })

# Final Global Invariant Check
print("\n--- Final Global Invariant Check ---")
final_code, final_out = run_invariant("global")
print(f"Final Invariant Check: exit={final_code}\n{final_out}")

# Save summary
with open(os.path.join(RUN_DIR, "results_summary.json"), "w", encoding="utf-8") as f:
    json.dump({
        "requests_total": REQUEST_COUNT,
        "retest_F001": "PASS" if retest_f1 else "FAIL",
        "retest_F002": "PASS" if retest_f2 else "FAIL",
        "retest_F003": "PASS" if retest_f3 else "FAIL",
        "retest_F004": "PASS" if retest_f4 else "FAIL",
        "retest_F005": "PASS" if retest_f5 else "FAIL",
        "retest_F006": "PASS" if retest_f6 else "FAIL",
        "retest_F007": "PASS" if retest_f7 else "FAIL",
        "retest_F008": "PASS" if retest_f8_pass else "FAIL",
        "retest_details": {
            "f8_cross_status": s_f8_cross,
            "f8_cross_body": r_f8_cross,
            "f8_anon_status": s_f8_anon,
            "f8_owner_status": s_f8_owner
        },
        "findings": findings,
        "contract_queries": contract_queries,
        "observations": observations,
        "final_invariant_exit": final_code,
        "final_invariant_output": final_out
    }, f, indent=2)

print("\n=== Breaker Run 02 Finished ===")

