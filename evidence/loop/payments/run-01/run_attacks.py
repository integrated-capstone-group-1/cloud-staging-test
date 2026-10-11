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
TRACE_FILE = "evidence/loop/payments/run-01/trace.jsonl"
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
    
    # Stay strictly under MAX_RPS (10) -> >= 0.12s
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

print("=== Starting Breaker Run 01: Domain payments ===")

# Retest Step 1: VERIFIED findings (F-001, F-002)
print("\n--- Step 1: Retesting VERIFIED findings ---")
# Authenticate Juan
stat, res = http_req("POST", "/api/auth/login", body={"email": "juan.delacruz@example.com", "password": "password123"}, customer_label="juan")
juan_jwt = res["data"]["token"] if stat == 200 and isinstance(res, dict) and "data" in res else None
assert juan_jwt, "Juan authentication failed"

# Retest F-001
stat_f1, res_f1 = http_req(
    "POST",
    "/api/v1/ledger/mutate",
    headers={"Authorization": f"Bearer {juan_jwt}", "X-Idempotency-Key": str(uuid.uuid4())},
    body={"accountId": "3722f77f-3d1b-4d55-8f83-b71bac91d095", "amount": "10.0000", "currency": "PHP", "txnType": "DEPOSIT", "idempotencyKey": str(uuid.uuid4())},
    customer_label="juan"
)
print(f"Retest F-001 status: {stat_f1} (Expected 403)")
if stat_f1 != 403:
    findings.append({"title": "REGRESSION of F-001", "sev": "S1", "oracle": "F7"})

# Retest F-002
stat_f2, res_f2 = http_req(
    "POST",
    "/api/v1/payments",
    headers={"Authorization": f"Bearer {juan_jwt}", "X-Idempotency-Key": str(uuid.uuid4())},
    body={"recipient_account_id": "f95e7e52-3259-4b4d-ac66-b186a893cf66", "amount": "100.0000", "currency": "PHP", "funding_pocket_id": "a86c97cc-1ea5-4767-bd5b-148968354f9c"},
    customer_label="juan"
)
print(f"Retest F-002 status: {stat_f2} (Expected 400)")
if stat_f2 != 400:
    findings.append({"title": "REGRESSION of F-002", "sev": "S2", "oracle": "F1"})

# Authenticate Maria
stat, res = http_req("POST", "/api/auth/login", body={"email": "maria.santos@example.com", "password": "password123"}, customer_label="maria")
maria_jwt = res["data"]["token"] if stat == 200 and isinstance(res, dict) and "data" in res else None
assert maria_jwt, "Maria authentication failed"

# Seed account IDs
JUAN_PHP_ACCT = "3722f77f-3d1b-4d55-8f83-b71bac91d095"
MARIA_PHP_ACCT = "f95e7e52-3259-4b4d-ac66-b186a893cf66"
JUAN_EUR_ACCT = "acct-juan-eur-01"
MARIA_EUR_ACCT = "acct-maria-eur-01"

# --- Seed Journey Execution & Breadth Attacks ---
print("\n--- Seed Journey E2E-02: Same-Currency Transfer & Idempotency ---")
# E2E-02 Step 2: Post payment transferring PHP 25.00
e2e02_key = str(uuid.uuid4())
pay_payload = {
    "recipient_account_id": MARIA_PHP_ACCT,
    "amount": "25.0000",
    "currency": "PHP"
}
stat_p1, res_p1 = http_req("POST", "/api/v1/payments", headers={"Authorization": f"Bearer {juan_jwt}", "X-Idempotency-Key": e2e02_key}, body=pay_payload, customer_label="juan")
print(f"E2E-02 Initial Payment: status={stat_p1}, res={res_p1}")

# E2E-02 Step 4: Replay with same idempotency key and same payload
stat_p1_replay, res_p1_replay = http_req("POST", "/api/v1/payments", headers={"Authorization": f"Bearer {juan_jwt}", "X-Idempotency-Key": e2e02_key}, body=pay_payload, customer_label="juan")
print(f"E2E-02 Replay: status={stat_p1_replay}")
if stat_p1 != stat_p1_replay:
    findings.append({"title": "Idempotent replay returned different HTTP status", "sev": "S1", "oracle": "F5", "actual": f"initial={stat_p1}, replay={stat_p1_replay}"})

# Area 2: Idempotency with different payload
diff_payload = dict(pay_payload)
diff_payload["amount"] = "999.0000"
stat_idemp_diff, res_idemp_diff = http_req("POST", "/api/v1/payments", headers={"Authorization": f"Bearer {juan_jwt}", "X-Idempotency-Key": e2e02_key}, body=diff_payload, customer_label="juan")
print(f"Idempotency altered payload: status={stat_idemp_diff}")
if stat_idemp_diff not in (409, 422, 400):
    contract_queries.append({"endpoint": "POST /api/v1/payments", "status": stat_idemp_diff, "detail": "Reusing idempotency key with different payload did not return 409/422"})

# Area 2: Cross-customer idempotency key isolation (Maria uses Juan's key)
stat_maria_key, res_maria_key = http_req("POST", "/api/v1/payments", headers={"Authorization": f"Bearer {maria_jwt}", "X-Idempotency-Key": e2e02_key}, body={"recipient_account_id": JUAN_PHP_ACCT, "amount": "10.0000", "currency": "PHP"}, customer_label="maria")
print(f"Maria reusing Juan's idempotency key: status={stat_maria_key}")
# Scoped per customer, should succeed or not clash with Juan's cached response

# Area 2: Missing Idempotency Key header
stat_no_key, res_no_key = http_req("POST", "/api/v1/payments", headers={"Authorization": f"Bearer {juan_jwt}"}, body=pay_payload, customer_label="juan")
print(f"Missing X-Idempotency-Key: status={stat_no_key}")
if stat_no_key != 400:
    observations.append(f"Missing X-Idempotency-Key returned {stat_no_key} instead of 400")

# --- Seed Journey E2E-03: FX Shortfall & Quotes ---
print("\n--- Seed Journey E2E-03: FX Shortfall & Quotes ---")
# Step 2: Fetch quote for EUR conversion
stat_q, res_q = http_req("GET", "/api/v1/fx/quote?from=PHP&to=EUR&amount=10.00", headers={"Authorization": f"Bearer {juan_jwt}"}, customer_label="juan")
print(f"GET FX quote: status={stat_q}, res={res_q}")
quote_id = res_q.get("quote_id") if stat_q == 200 and isinstance(res_q, dict) else None

# Area 5: Using another customer's quote (Maria tries to use Juan's quote)
if quote_id:
    stat_quote_hijack, res_quote_hijack = http_req(
        "POST",
        "/api/v1/payments",
        headers={"Authorization": f"Bearer {maria_jwt}", "X-Idempotency-Key": str(uuid.uuid4())},
        body={
            "recipient_account_id": JUAN_EUR_ACCT,
            "amount": "10.0000",
            "currency": "EUR",
            "funding_pocket_id": "86cabb96-61d5-462a-b33b-5a6435649eb4",
            "quote_id": quote_id
        },
        customer_label="maria"
    )
    print(f"Cross-customer quote hijack: status={stat_quote_hijack}")
    if stat_quote_hijack in (200, 201, 202):
        findings.append({"title": "Cross-customer quote usage permitted", "sev": "S1", "oracle": "F7", "status": stat_quote_hijack})

# Area 5: Tampered quote amount
if quote_id:
    stat_quote_tamper, res_quote_tamper = http_req(
        "POST",
        "/api/v1/payments",
        headers={"Authorization": f"Bearer {juan_jwt}", "X-Idempotency-Key": str(uuid.uuid4())},
        body={
            "recipient_account_id": MARIA_EUR_ACCT,
            "amount": "5000.0000", # Mismatched amount
            "currency": "EUR",
            "funding_pocket_id": JUAN_PHP_ACCT,
            "quote_id": quote_id
        },
        customer_label="juan"
    )
    print(f"Quote with mismatched amount: status={stat_quote_tamper}")

# --- Seed Journey E2E-04: CAS Lifecycle & Cancel Window ---
print("\n--- Seed Journey E2E-04: Cancellation & CAS Lifecycle ---")
# Submit a payment and immediately cancel
cancel_key = str(uuid.uuid4())
stat_c_pay, res_c_pay = http_req("POST", "/api/v1/payments", headers={"Authorization": f"Bearer {juan_jwt}", "X-Idempotency-Key": cancel_key}, body={"recipient_account_id": MARIA_PHP_ACCT, "amount": "10.0000", "currency": "PHP"}, customer_label="juan")
txn_to_cancel = res_c_pay.get("transaction_id") if isinstance(res_c_pay, dict) else None
print(f"Payment initiated for cancel: txn_id={txn_to_cancel}, status={stat_c_pay}")

if txn_to_cancel:
    # Immediately cancel within 5s window
    stat_cancel, res_cancel = http_req("POST", f"/api/v1/payments/{txn_to_cancel}/cancel", headers={"Authorization": f"Bearer {juan_jwt}"}, customer_label="juan")
    print(f"Immediate cancel attempt: status={stat_cancel}, res={res_cancel}")
    
    # Cancel again (Double cancel)
    stat_cancel_twice, res_cancel_twice = http_req("POST", f"/api/v1/payments/{txn_to_cancel}/cancel", headers={"Authorization": f"Bearer {juan_jwt}"}, customer_label="juan")
    print(f"Double cancel attempt: status={stat_cancel_twice}")
    if stat_cancel_twice not in (409, 422):
        observations.append(f"Double cancel returned {stat_cancel_twice} instead of 409 STATE_CONFLICT")

    # Cross-customer cancel: Maria attempts to cancel Juan's transaction (IDOR)
    stat_maria_cancel, res_maria_cancel = http_req("POST", f"/api/v1/payments/{txn_to_cancel}/cancel", headers={"Authorization": f"Bearer {maria_jwt}"}, customer_label="maria")
    print(f"Maria cancelling Juan's txn: status={stat_maria_cancel}")
    if stat_maria_cancel in (200, 204):
        findings.append({"title": "IDOR: Customer B can cancel Customer A's transaction", "sev": "S1", "oracle": "F7", "status": stat_maria_cancel})

# --- Area 1: Hostile Input Mutations on Payments ---
print("\n--- Area 1: Hostile Input Mutations ---")
hostile_inputs = [
    ("negative_amount", {"recipient_account_id": MARIA_PHP_ACCT, "amount": "-50.0000", "currency": "PHP"}),
    ("zero_amount", {"recipient_account_id": MARIA_PHP_ACCT, "amount": "0.0000", "currency": "PHP"}),
    ("overscaled_amount", {"recipient_account_id": MARIA_PHP_ACCT, "amount": "10.123456", "currency": "PHP"}),
    ("scientific_notation", {"recipient_account_id": MARIA_PHP_ACCT, "amount": "1e3", "currency": "PHP"}),
    ("nan_amount", {"recipient_account_id": MARIA_PHP_ACCT, "amount": "NaN", "currency": "PHP"}),
    ("string_amount", {"recipient_account_id": MARIA_PHP_ACCT, "amount": "fifty", "currency": "PHP"}),
    ("unicode_amount", {"recipient_account_id": MARIA_PHP_ACCT, "amount": "５０.００", "currency": "PHP"}),
    ("lowercase_currency", {"recipient_account_id": MARIA_PHP_ACCT, "amount": "50.0000", "currency": "php"}),
    ("invalid_currency", {"recipient_account_id": MARIA_PHP_ACCT, "amount": "50.0000", "currency": "XYZ"}),
    ("pay_self", {"recipient_account_id": JUAN_PHP_ACCT, "amount": "50.0000", "currency": "PHP"}),
    ("nonexistent_recipient", {"recipient_account_id": "00000000-0000-0000-0000-000000000000", "amount": "50.0000", "currency": "PHP"}),
    ("huge_amount", {"recipient_account_id": MARIA_PHP_ACCT, "amount": "999999999999999.0000", "currency": "PHP"}),
    ("sqli_recipient", {"recipient_account_id": f"acct' OR '1'='1", "amount": "50.0000", "currency": "PHP"})
]

for name, payload in hostile_inputs:
    stat_in, res_in = http_req(
        "POST",
        "/api/v1/payments",
        headers={"Authorization": f"Bearer {juan_jwt}", "X-Idempotency-Key": str(uuid.uuid4())},
        body=payload,
        customer_label="juan"
    )
    print(f"Input test [{name}]: status={stat_in}")
    if stat_in >= 500:
        findings.append({"title": f"HTTP {stat_in} on input mutation [{name}]", "sev": "S2", "oracle": "F1", "status": stat_in, "payload": payload, "res": res_in})
    elif stat_in in (200, 201, 202) and name in ("negative_amount", "zero_amount", "pay_self", "nan_amount", "string_amount"):
        findings.append({"title": f"Hostile input accepted [{name}]", "sev": "S1", "oracle": "F3", "status": stat_in})

# --- Area 7: Concurrency & Race Tests (Parallel identical submits) ---
print("\n--- Area 7: Concurrency & Race Tests ---")
race_key = str(uuid.uuid4())
race_payload = {
    "recipient_account_id": MARIA_PHP_ACCT,
    "amount": "15.0000",
    "currency": "PHP"
}

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
print(f"Parallel duplicate submit statuses (5 threads): {race_statuses}")
# All should either be 200/202 or 409 Conflict

# --- Final Invariant Verification ---
print("\n--- Final Global Invariant Check ---")
final_code, final_out = run_invariant("global")
print(f"Final Invariant Check: exit={final_code}\n{final_out}")

# Save results
with open("evidence/loop/payments/run-01/results_summary.json", "w", encoding="utf-8") as f:
    json.dump({
        "requests_total": REQUEST_COUNT,
        "retest_F001": "PASS" if stat_f1 == 403 else "FAIL",
        "retest_F002": "PASS" if stat_f2 == 400 else "FAIL",
        "findings": findings,
        "contract_queries": contract_queries,
        "observations": observations,
        "final_invariant_exit": final_code,
        "final_invariant_output": final_out
    }, f, indent=2)

print("\n=== Breaker Run 01 (payments) Finished ===")

