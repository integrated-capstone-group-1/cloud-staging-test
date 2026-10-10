import json
import time
import uuid
import sys
import os
import urllib.request
import urllib.error
import subprocess
from concurrent.futures import ThreadPoolExecutor, as_completed

ENTRY_POINT = "http://localhost:8080"
RUN_DIR = "evidence/loop/nfr/run-01"
os.makedirs(RUN_DIR, exist_ok=True)
LOG_FILE = os.path.join(RUN_DIR, "track1_concurrency_evidence.json")

def http_json(method, path, headers=None, body=None):
    if headers is None:
        headers = {}
    url = ENTRY_POINT + path if path.startswith("/") else path
    req_data = None
    if body is not None:
        req_data = json.dumps(body).encode("utf-8")
        if "Content-Type" not in headers:
            headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=req_data, headers=headers, method=method)
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=15) as res:
            res_raw = res.read().decode("utf-8", errors="replace")
            try:
                res_data = json.loads(res_raw)
            except Exception:
                res_data = res_raw
            return res.status, res_data, (time.time() - t0) * 1000
    except urllib.error.HTTPError as e:
        res_raw = e.read().decode("utf-8", errors="replace")
        try:
            res_data = json.loads(res_raw)
        except Exception:
            res_data = res_raw
        return e.code, res_data, (time.time() - t0) * 1000
    except Exception as e:
        return 0, {"error": str(e)}, (time.time() - t0) * 1000

def query_oracle_sql(sql_cmd):
    full_script = f"SET HEADING OFF;\nSET FEEDBACK OFF;\n{sql_cmd}\nEXIT;\n"
    res = subprocess.run(
        ["docker", "exec", "-i", "oracle-db", "sqlplus", "-s", "ledger_app/LedgerAppPass123@//localhost:1521/XEPDB1"],
        input=full_script,
        text=True,
        capture_output=True
    )
    return res.stdout.strip()

print("======================================================================")
print(" TRACK 1: GYM - CONCURRENCY RACES & IDEMPOTENCY FLOOD (SECT 3.1)")
print("======================================================================")

# 1. Login Juan and Maria
s, res, _ = http_json("POST", "/api/auth/login", body={"email": "juan.delacruz@example.com", "password": "password123"})
assert s == 200, f"Juan login failed: {res}"
juan_jwt = res["data"]["token"]

s, res, _ = http_json("POST", "/api/auth/login", body={"email": "maria.santos@example.com", "password": "password123"})
assert s == 200, f"Maria login failed: {res}"
maria_jwt = res["data"]["token"]

JUAN_PHP_ACCT = "3722f77f-3d1b-4d55-8f83-b71bac91d095"
MARIA_PHP_ACCT = "f95e7e52-3259-4b4d-ac66-b186a893cf66"

# --------------------------------------------------------------------
# NFR-CONC-01: Parallel Concurrent Withdrawals Race Sampler
# --------------------------------------------------------------------
print("\n--- Executing NFR-CONC-01: Parallel Withdrawals Race Sampler ---")

# Check initial balance of Juan via SQL
sql_bal = f"SELECT balance_amount FROM account_master WHERE account_id = '{JUAN_PHP_ACCT}';"
initial_bal_str = query_oracle_sql(sql_bal)
print(f"Juan initial balance in Oracle: {initial_bal_str} PHP")
initial_bal = float(initial_bal_str)

# Fire 10 simultaneous transfer withdrawals of 10.00 PHP each
withdrawal_count = 10
withdraw_amt = "10.0000"

def fire_withdrawal(idx):
    headers = {
        "Authorization": f"Bearer {juan_jwt}",
        "X-Idempotency-Key": str(uuid.uuid4())
    }
    body = {
        "recipient_account_id": MARIA_PHP_ACCT,
        "amount": withdraw_amt,
        "currency": "PHP"
    }
    status, data, rtt = http_json("POST", "/api/v1/payments", headers=headers, body=body)
    return idx, status, data, rtt

t0 = time.time()
with ThreadPoolExecutor(max_workers=withdrawal_count) as pool:
    futures = [pool.submit(fire_withdrawal, i) for i in range(withdrawal_count)]
    results_conc1 = [f.result() for f in as_completed(futures)]
elapsed_conc1 = time.time() - t0

success_count_c1 = sum(1 for _, st, _, _ in results_conc1 if st in (200, 201))
failed_count_c1 = sum(1 for _, st, _, _ in results_conc1 if st not in (200, 201))
print(f"NFR-CONC-01 Completed in {elapsed_conc1*1000:.1f}ms: {success_count_c1} Success, {failed_count_c1} Failed")

final_bal_str = query_oracle_sql(sql_bal)
final_bal = float(final_bal_str)
print(f"Juan final balance in Oracle: {final_bal_str} PHP")
expected_deduction = success_count_c1 * float(withdraw_amt)
actual_deduction = initial_bal - final_bal
print(f"Expected balance deduction: {expected_deduction:.4f} PHP | Actual deduction: {actual_deduction:.4f} PHP")

assert abs(actual_deduction - expected_deduction) < 0.001, "Balance deduction mismatch! Lost update detected!"
print("[+] NFR-CONC-01 PASS: Zero lost updates, deterministic balance conservation under parallel load.")

# --------------------------------------------------------------------
# NFR-CONC-02: Idempotency-Key Duplicate Flood (100 Threads)
# --------------------------------------------------------------------
print("\n--- Executing NFR-CONC-02: Idempotency-Key Duplicate Flood (100 Threads) ---")

# Maria has clean velocity (< 3 transfers), ensuring risk engine ALLOW decision
flood_key = f"UUID-FLOOD-{uuid.uuid4().hex[:8].upper()}"
flood_payload = {
    "recipient_account_id": JUAN_PHP_ACCT,
    "amount": "10.0000",
    "currency": "PHP"
}

def fire_flood_request(idx):
    headers = {
        "Authorization": f"Bearer {maria_jwt}",
        "X-Idempotency-Key": flood_key
    }
    status, data, rtt = http_json("POST", "/api/v1/payments", headers=headers, body=flood_payload)
    return idx, status, data, rtt

flood_threads = 100
t0 = time.time()
with ThreadPoolExecutor(max_workers=flood_threads) as pool:
    futures = [pool.submit(fire_flood_request, i) for i in range(flood_threads)]
    flood_results = [f.result() for f in as_completed(futures)]
elapsed_flood = time.time() - t0

status_distribution = {}
for _, st, _, _ in flood_results:
    status_distribution[st] = status_distribution.get(st, 0) + 1

print(f"NFR-CONC-02 Completed in {elapsed_flood*1000:.1f}ms with 100 concurrent threads.")
print(f"Status distribution across 100 flood requests: {status_distribution}")

# Wait for 5-second undo cancellation window to elapse and background settlement worker to drain
print("Waiting 7 seconds for 5-second cancel window to expire and CBS settlement worker to commit...")
time.sleep(7)

# Query Oracle ledger legs for this idempotency key
sql_txn = f"SELECT txn_id FROM transaction_master WHERE idempotency_key = '{flood_key}';"
txn_id = query_oracle_sql(sql_txn)
print(f"Flood Transaction ID: {txn_id}")

sql_legs = f"SELECT COUNT(*) FROM ledger_entry WHERE txn_id = '{txn_id}';"
leg_count = int(query_oracle_sql(sql_legs))
print(f"Oracle Ledger legs created for flood key '{flood_key}': {leg_count} (Expected: exactly 2)")

sql_tx_count = f"SELECT COUNT(*) FROM transaction_master WHERE idempotency_key = '{flood_key}';"
tx_master_count = int(query_oracle_sql(sql_tx_count))
print(f"Oracle transaction_master records created: {tx_master_count} (Expected: exactly 1)")

assert tx_master_count == 1, f"Expected exactly 1 transaction_master record, found {tx_master_count}!"
assert leg_count == 2, f"Expected exactly 2 ledger legs (1 DEBIT, 1 CREDIT), found {leg_count}!"
print("[+] NFR-CONC-02 PASS: Exactly 1 transaction created, exactly 2 ledger legs posted, zero duplicate postings.")

# --------------------------------------------------------------------
# Global Invariant Verification Post-Test
# --------------------------------------------------------------------
print("\n--- Verifying Global Invariants Post-Test ---")
cmd = [sys.executable, "tests/invariants/check_invariants.py", "--scope", "global"]
inv_res = subprocess.run(cmd, capture_output=True, text=True)
print(inv_res.stdout.strip())
assert inv_res.returncode == 0, "Global invariants failed post Track 1 test!"

evidence_summary = {
    "track": "Track 1 - Gym",
    "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ"),
    "NFR-CONC-01": {
        "status": "PASS",
        "threads": withdrawal_count,
        "elapsed_ms": elapsed_conc1 * 1000,
        "success_count": success_count_c1,
        "initial_balance": initial_bal,
        "final_balance": final_bal,
        "expected_deduction": expected_deduction,
        "actual_deduction": actual_deduction
    },
    "NFR-CONC-02": {
        "status": "PASS",
        "flood_key": flood_key,
        "threads": flood_threads,
        "elapsed_ms": elapsed_flood * 1000,
        "status_distribution": status_distribution,
        "transaction_master_count": tx_master_count,
        "ledger_legs_count": leg_count
    },
    "final_invariants_exit": inv_res.returncode
}

with open(LOG_FILE, "w", encoding="utf-8") as f:
    json.dump(evidence_summary, f, indent=2)

print("\n=== Track 1 (Section 3.1) Concurrency Execution Complete ===")
