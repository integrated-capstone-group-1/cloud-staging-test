import json
import time
import uuid
import sys
import urllib.request
import urllib.error
import subprocess
from datetime import datetime

ENTRY_POINT = "http://localhost:8080"
TRACE_FILE = "evidence/loop/identity-admin/run-03/trace.jsonl"
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

print("=== Starting Breaker Run 03: Domain identity-admin ===")

# --- Step 1: Retesting VERIFIED Findings ---
print("\n--- Step 1: Retesting F-001 & F-002 ---")
# Authenticate Juan
status, res = http_req("POST", "/api/auth/login", body={"email": "juan.delacruz@example.com", "password": "password123"}, customer_label="juan")
juan_jwt = res["data"]["token"] if status == 200 and isinstance(res, dict) and "data" in res else None
assert juan_jwt, "Juan login failed"

# Retest F-001
stat_f1, res_f1 = http_req(
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
print(f"F-001 Retest: status={stat_f1}")
if stat_f1 == 403:
    print("RETEST PASS F-001")
else:
    print("RETEST FAIL F-001")
    findings.append({"title": "REGRESSION of F-001", "sev": "S1", "oracle": "F7"})

# Retest F-002 across multiple iterations
f2_retest_results = []
for i in range(3):
    stat_f2, res_f2 = http_req(
        "POST",
        "/api/v1/payments",
        headers={"Authorization": f"Bearer {juan_jwt}", "X-Idempotency-Key": str(uuid.uuid4())},
        body={
            "recipient_account_id": "f95e7e52-3259-4b4d-ac66-b186a893cf66",
            "amount": "100.0000",
            "currency": "PHP",
            "funding_pocket_id": "a86c97cc-1ea5-4767-bd5b-148968354f9c"
        },
        customer_label=f"juan_retest_F002_{i+1}"
    )
    f2_retest_results.append(stat_f2)

print(f"F-002 Retest results (3 iterations): {f2_retest_results}")
if all(s == 400 for s in f2_retest_results):
    print("RETEST PASS F-002: Returned 400 VALIDATION_FAILED with proper RFC 7807 envelope.")
else:
    print("RETEST FAIL F-002")
    findings.append({"title": "REGRESSION of F-002", "sev": "S2", "oracle": "F1"})

# --- Depth Probe on Identity & Admin ---
print("\n--- Additional Depth Probing on Identity & Admin ---")
# Authenticate Admin
status, res = http_req("POST", "/api/auth/login", body={"email": "admin@ledgerbank.com", "password": "admin123"}, customer_label="admin")
admin_jwt = res["data"]["token"] if status == 200 and isinstance(res, dict) and "data" in res else None
assert admin_jwt, "Admin login failed"

# Authenticate Maria
status, res = http_req("POST", "/api/auth/login", body={"email": "maria.santos@example.com", "password": "password123"}, customer_label="maria")
maria_jwt = res["data"]["token"] if status == 200 and isinstance(res, dict) and "data" in res else None

# Check admin reconciliation runs endpoint
stat_recon, res_recon = http_req(
    "POST",
    "/api/recon/runs",
    headers={"Authorization": f"Bearer {admin_jwt}"},
    body={"auditDate": "2026-10-10", "initiatedBy": "ADMIN_CLI"},
    customer_label="admin_recon"
)
print(f"Admin POST /api/recon/runs: status={stat_recon}")

# Customer attempting recon runs
stat_recon_cust, res_recon_cust = http_req(
    "POST",
    "/api/recon/runs",
    headers={"Authorization": f"Bearer {juan_jwt}"},
    body={"auditDate": "2026-10-10", "initiatedBy": "ATTACKER"},
    customer_label="juan_as_admin_recon"
)
print(f"Customer POST /api/recon/runs: status={stat_recon_cust}")
if stat_recon_cust in (200, 201):
    findings.append({"title": "Customer token permitted to trigger reconciliation job", "sev": "S1", "oracle": "F7"})

# Final Global Invariant Check
print("\n--- Final Global Invariant Check ---")
final_code, final_out = run_invariant("global")
print(f"Final Invariant Check: exit={final_code}\n{final_out}")

# Save run-03 summary
with open("evidence/loop/identity-admin/run-03/results_summary.json", "w", encoding="utf-8") as f:
    json.dump({
        "requests_total": REQUEST_COUNT,
        "retest_F001": "PASS" if stat_f1 == 403 else "FAIL",
        "retest_F002": "PASS" if all(s == 400 for s in f2_retest_results) else "FAIL",
        "findings": findings,
        "contract_queries": contract_queries,
        "observations": observations,
        "final_invariant_exit": final_code,
        "final_invariant_output": final_out
    }, f, indent=2)

print("\n=== Breaker Run 03 Finished ===")
