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
RUN_DIR = "evidence/loop/cross-domain/run-01"
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

print("=== Starting Breaker Run 01: Domain cross-domain ===")

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

# --- Step 1: Retesting Prior Findings F-001 through F-007 ---
print("\n--- Step 1: Retesting Prior Findings F-001 through F-007 ---")

# F-001: Direct ledger mutate -> expect 403
s_f1, _ = http_req("POST", "/api/v1/ledger/mutate", headers={"Authorization": f"Bearer {juan_jwt}", "X-Idempotency-Key": str(uuid.uuid4())}, body={"accountId": JUAN_PHP_ACCT, "amount": "10.0000", "currency": "PHP", "txnType": "DEPOSIT", "idempotencyKey": str(uuid.uuid4())}, customer_label="juan")
print(f"Retest F-001: status={s_f1} (Expected 403)")

# F-002: Missing quote_id when using funding_pocket_id -> expect 400
s_f2, _ = http_req("POST", "/api/v1/payments", headers={"Authorization": f"Bearer {juan_jwt}", "X-Idempotency-Key": str(uuid.uuid4())}, body={"recipient_account_id": MARIA_PHP_ACCT, "amount": "10.0000", "currency": "PHP", "funding_pocket_id": "a86c97cc-1ea5-4767-bd5b-148968354f9c"}, customer_label="juan")
print(f"Retest F-002: status={s_f2} (Expected 400)")

# F-003: Scale > 4 -> expect 400
s_f3, _ = http_req("POST", "/api/v1/payments", headers={"Authorization": f"Bearer {juan_jwt}", "X-Idempotency-Key": str(uuid.uuid4())}, body={"recipient_account_id": MARIA_PHP_ACCT, "amount": "10.123456", "currency": "PHP"}, customer_label="juan")
print(f"Retest F-003: status={s_f3} (Expected 400)")

# Create a baseline payment transaction for Juan
s_pay, b_pay = http_req("POST", "/api/v1/payments", headers={"Authorization": f"Bearer {juan_jwt}", "X-Idempotency-Key": str(uuid.uuid4())}, body={"recipient_account_id": MARIA_PHP_ACCT, "amount": "10.0000", "currency": "PHP"}, customer_label="juan")
assert s_pay in (200, 201), f"Payment creation failed: {s_pay} {b_pay}"
juan_txn_id = b_pay.get("transaction_id")
print(f"Created Juan payment transaction: {juan_txn_id}")

# F-004: Duplicate idempotency key concurrent/re-send
idemp_key_test = str(uuid.uuid4())
s_idem1, b_idem1 = http_req("POST", "/api/v1/payments", headers={"Authorization": f"Bearer {juan_jwt}", "X-Idempotency-Key": idemp_key_test}, body={"recipient_account_id": MARIA_PHP_ACCT, "amount": "1.0000", "currency": "PHP"}, customer_label="juan")
s_idem2, b_idem2 = http_req("POST", "/api/v1/payments", headers={"Authorization": f"Bearer {juan_jwt}", "X-Idempotency-Key": idemp_key_test}, body={"recipient_account_id": MARIA_PHP_ACCT, "amount": "1.0000", "currency": "PHP"}, customer_label="juan")
print(f"Retest F-004: first={s_idem1}, second={s_idem2} (No raw SQL leak, consistent return)")

# F-005: Cross-customer payment read -> expect 403
s_f5, _ = http_req("GET", f"/api/v1/payments/{juan_txn_id}", headers={"Authorization": f"Bearer {maria_jwt}"}, customer_label="maria")
print(f"Retest F-005: status={s_f5} (Expected 403)")

# F-006: Client X-Cust-Id header -> expect 200 without 500
s_f6, _ = http_req("GET", "/api/v1/payments/history", headers={"Authorization": f"Bearer {juan_jwt}", "X-Cust-Id": "cust-admin-001"}, customer_label="juan")
print(f"Retest F-006: status={s_f6} (Expected 200)")

# F-007: Cross-customer audit query -> expect 403
s_f7, _ = http_req("GET", f"/api/v1/ledger/audit/{juan_txn_id}", headers={"Authorization": f"Bearer {maria_jwt}"}, customer_label="maria")
print(f"Retest F-007: status={s_f7} (Expected 403)")

# --- Step 2: Cross-Domain Probing ---
print("\n--- Step 2: Cross-Domain Probing ---")

# Attack Probe 2.1: Cross-customer cancellation
# Maria attempts to cancel Juan's transaction
print("\n[Probe 2.1] Maria attempts to cancel Juan's transaction:")
s_canc, b_canc = http_req("POST", f"/api/v1/payments/{juan_txn_id}/cancel", headers={"Authorization": f"Bearer {maria_jwt}"}, customer_label="maria")
print(f"Maria cancel Juan's txn: status={s_canc}, body={b_canc}")
if s_canc in (200, 202, 204):
    findings.append({
        "title": f"Cross-customer payment cancellation permitted on /api/v1/payments/{juan_txn_id}/cancel",
        "sev": "S1",
        "oracle": "F7"
    })

# Attack Probe 2.2: Cross-customer pocket querying (/api/v1/accounts/{id}/pockets)
print("\n[Probe 2.2] Maria queries Juan's pockets directly:")
s_pock, b_pock = http_req("GET", f"/api/v1/accounts/{JUAN_PHP_ACCT}/pockets", headers={"Authorization": f"Bearer {maria_jwt}"}, customer_label="maria")
print(f"Maria query Juan's pockets: status={s_pock}, body={b_pock}")
if s_pock == 200:
    findings.append({
        "title": f"Cross-customer pocket enumeration permitted on /api/v1/accounts/{JUAN_PHP_ACCT}/pockets",
        "sev": "S1",
        "oracle": "F7"
    })

# Attack Probe 2.3: Cross-customer pocket spending (Juan uses Maria's pocket as funding_pocket_id)
print("\n[Probe 2.3] Juan attempts to spend from Maria's pocket via funding_pocket_id:")
s_spend, b_spend = http_req("POST", "/api/v1/payments", headers={"Authorization": f"Bearer {juan_jwt}", "X-Idempotency-Key": str(uuid.uuid4())}, body={"recipient_account_id": MARIA_PHP_ACCT, "amount": "10.0000", "currency": "PHP", "funding_pocket_id": MARIA_PHP_ACCT, "quote_id": "any-quote"}, customer_label="juan")
print(f"Juan spend from Maria's pocket: status={s_spend}, body={b_spend}")
if s_spend in (200, 201):
    findings.append({
        "title": "Cross-customer pocket spending permitted via funding_pocket_id",
        "sev": "S1",
        "oracle": "F7"
    })

# Attack Probe 2.4: Cross-customer FX Quote hijacking
print("\n[Probe 2.4] Juan gets FX quote; Maria tries to execute conversion with Juan's quote:")
s_quote, b_quote = http_req("GET", "/api/v1/fx/quote?from=PHP&to=EUR&amount=10.0000", headers={"Authorization": f"Bearer {juan_jwt}"}, customer_label="juan")
juan_quote_id = b_quote.get("quote_id") if isinstance(b_quote, dict) else None
print(f"Juan quote generation: status={s_quote}, quote_id={juan_quote_id}")
if juan_quote_id:
    s_conv, b_conv = http_req("POST", "/api/v1/fx/convert", headers={"Authorization": f"Bearer {maria_jwt}"}, body={"quote_id": juan_quote_id, "source_pocket_id": MARIA_PHP_ACCT, "target_pocket_id": MARIA_PHP_ACCT}, customer_label="maria")
    print(f"Maria convert with Juan's quote: status={s_conv}, body={b_conv}")
    if s_conv in (200, 201):
        findings.append({
            "title": "Cross-customer FX quote hijacking permitted on /api/v1/fx/convert",
            "sev": "S1",
            "oracle": "F7"
        })

# Attack Probe 2.5: Cross-customer virtual card access & controls
print("\n[Probe 2.5] Juan gets his cards; Maria attempts to toggle or modify Juan's card:")
s_cards, b_cards = http_req("GET", "/api/v1/cards", headers={"Authorization": f"Bearer {juan_jwt}"}, customer_label="juan")
juan_card_id = None
if isinstance(b_cards, list) and len(b_cards) > 0:
    juan_card_id = b_cards[0].get("id") or b_cards[0].get("card_id")
elif isinstance(b_cards, dict) and "data" in b_cards and isinstance(b_cards["data"], list) and len(b_cards["data"]) > 0:
    juan_card_id = b_cards["data"][0].get("id") or b_cards["data"][0].get("card_id")
print(f"Juan cards: status={s_cards}, card_id={juan_card_id}")
if juan_card_id:
    s_ctoggle, b_ctoggle = http_req("PUT", f"/api/v1/cards/{juan_card_id}/ecom", headers={"Authorization": f"Bearer {maria_jwt}"}, body={"ecom_enabled": 0}, customer_label="maria")
    print(f"Maria toggle Juan card: status={s_ctoggle}, body={b_ctoggle}")
    if s_ctoggle in (200, 204):
        findings.append({
            "title": f"Cross-customer virtual card modification permitted on /api/v1/cards/{juan_card_id}/ecom",
            "sev": "S1",
            "oracle": "F7"
        })

# Attack Probe 2.6: Privilege Escalation via Header Forgery / Injected Roles
print("\n[Probe 2.6] Customer attempting privilege escalation to Admin routes using header injection:")
priv_headers = [
    {"Authorization": f"Bearer {juan_jwt}", "X-Role": "ROLE_ADMIN"},
    {"Authorization": f"Bearer {juan_jwt}", "X-Roles": "ROLE_ADMIN"},
    {"Authorization": f"Bearer {juan_jwt}", "X-User-Roles": "ROLE_ADMIN"},
    {"Authorization": f"Bearer {juan_jwt}", "X-Cust-Id": "cust-admin-001"},
]
admin_paths = [
    "/api/v1/admin/held-transfers",
    f"/api/v1/admin/accounts/{JUAN_PHP_ACCT}/freeze",
]
for h in priv_headers:
    for ap in admin_paths:
        s_p, b_p = http_req("GET" if "held" in ap else "PUT", ap, headers=h, body={"reason": "Adversary test"} if "freeze" in ap else None, customer_label="juan_priv_esc")
        print(f"Priv probe {ap} with {list(h.keys())}: status={s_p}")
        if s_p in (200, 201, 202, 204):
            findings.append({
                "title": f"Privilege escalation to admin endpoint {ap} via header injection",
                "sev": "S1",
                "oracle": "F7"
            })

# Attack Probe 2.7: Idempotency Key Scoping across Customers
# Juan uses an idempotency key; Maria uses the EXACT SAME idempotency key for her own payment.
print("\n[Probe 2.7] Idempotency key scoping across customers:")
shared_idemp = str(uuid.uuid4())
s_j_idemp, b_j_idemp = http_req("POST", "/api/v1/payments", headers={"Authorization": f"Bearer {juan_jwt}", "X-Idempotency-Key": shared_idemp}, body={"recipient_account_id": MARIA_PHP_ACCT, "amount": "2.0000", "currency": "PHP"}, customer_label="juan")
s_m_idemp, b_m_idemp = http_req("POST", "/api/v1/payments", headers={"Authorization": f"Bearer {maria_jwt}", "X-Idempotency-Key": shared_idemp}, body={"recipient_account_id": JUAN_PHP_ACCT, "amount": "2.0000", "currency": "PHP"}, customer_label="maria")
print(f"Juan idemp payment: status={s_j_idemp}")
print(f"Maria same idemp payment: status={s_m_idemp}")
# Maria's request should succeed independently because idempotency must be scoped per customer
if s_m_idemp == 409:
    observations.append(f"Idempotency key collision between distinct customers (status={s_m_idemp}): idempotency key may be globally scoped instead of customer-scoped.")
elif s_m_idemp not in (200, 201):
    observations.append(f"Maria payment with shared idemp returned status={s_m_idemp}")

# Attack Probe 2.8: Terminal State Mutation & Double Action
print("\n[Probe 2.8] Chained state abuse on posted transaction:")
# Attempting to cancel an already-posted transaction
s_canc_post, b_canc_post = http_req("POST", f"/api/v1/payments/{juan_txn_id}/cancel", headers={"Authorization": f"Bearer {juan_jwt}"}, customer_label="juan")
print(f"Juan cancel posted txn: status={s_canc_post}, body={b_canc_post}")
if s_canc_post in (200, 202, 204):
    findings.append({
        "title": f"Terminal state corruption: cancelling POSTED transaction {juan_txn_id} succeeded",
        "sev": "S1",
        "oracle": "F2"
    })
elif s_canc_post not in (409, 422, 400):
    contract_queries.append(f"Cancelling POSTED transaction returned unexpected status {s_canc_post} instead of 409 STATE_CONFLICT")

# Final Global Invariant Check
print("\n--- Final Global Invariant Check ---")
final_code, final_out = run_invariant("global")
print(f"Final Invariant Check: exit={final_code}\n{final_out}")

# Save summary
with open(os.path.join(RUN_DIR, "results_summary.json"), "w", encoding="utf-8") as f:
    json.dump({
        "requests_total": REQUEST_COUNT,
        "retest_F001": "PASS" if s_f1 == 403 else "FAIL",
        "retest_F002": "PASS" if s_f2 == 400 else "FAIL",
        "retest_F003": "PASS" if s_f3 == 400 else "FAIL",
        "retest_F004": "PASS" if (s_idem1 in (200, 201) and s_idem2 in (200, 201, 409)) else "FAIL",
        "retest_F005": "PASS" if s_f5 == 403 else "FAIL",
        "retest_F006": "PASS" if s_f6 == 200 else "FAIL",
        "retest_F007": "PASS" if s_f7 == 403 else "FAIL",
        "findings": findings,
        "contract_queries": contract_queries,
        "observations": observations,
        "final_invariant_exit": final_code,
        "final_invariant_output": final_out
    }, f, indent=2)

print("\n=== Breaker Run 01 (cross-domain) Finished ===")

