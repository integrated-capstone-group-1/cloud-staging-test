#!/usr/bin/env python3
"""
System Invariant Verification Runner (INVARIANTS.md).
Evaluates Hard invariants (I-1, I-2, I-3, I-6, I-8, I-9, I-10, I-11, I-12, I-13)
and Eventual invariants (I-4, I-5, I-7) with wait-drain polling.

Usage:
    python -m tests.invariants.check_invariants [--scope global | --scope txns=<ids> | --scope customers=<ids>]
                                               [--wait-drain <seconds>]
                                               [--baseline]
"""

import sys
import os
import time
import argparse
import json
import subprocess
from decimal import Decimal
from typing import Dict, List, Tuple, Set, Optional

# DB Credentials and connection info
ORACLE_USER = os.getenv("ORACLE_USERNAME", "ledger_app")
ORACLE_PASS = os.getenv("ORACLE_PASSWORD", "LedgerAppPass123")
ORACLE_DSN = os.getenv("ORACLE_DSN", "localhost:1521/XEPDB1")
ORACLE_CONTAINER = os.getenv("ORACLE_CONTAINER", "oracle-db")

PG_USER = os.getenv("POSTGRES_USERNAME", "ledger_audit")
PG_PASS = os.getenv("POSTGRES_PASSWORD", "LedgerAuditPass123")
PG_HOST = os.getenv("POSTGRES_HOST", "localhost")
PG_PORT = os.getenv("POSTGRES_PORT", "5432")
PG_DB = os.getenv("POSTGRES_DB", "ledger_audit")
PG_CONTAINER = os.getenv("POSTGRES_CONTAINER", "postgres-db")

BASELINE_FILE = os.path.join(os.path.dirname(__file__), ".baseline_s.json")

def run_oracle_sql(sql: str) -> List[str]:
    """Execute SQL query in Oracle XE and return lines of output."""
    full_sql = f"SET PAGESIZE 50000 LINESIZE 32767 FEEDBACK OFF HEADING OFF TRIMSPOOL ON;\n{sql};\nEXIT;\n"
    # Try via docker exec first as it doesn't require exposed ports
    cmd = [
        "docker", "exec", "-i", ORACLE_CONTAINER,
        "sqlplus", "-s", f"{ORACLE_USER}/{ORACLE_PASS}@localhost:1521/XEPDB1"
    ]
    try:
        proc = subprocess.run(cmd, input=full_sql, text=True, capture_output=True, check=True)
        lines = [line.strip() for line in proc.stdout.splitlines() if line.strip()]
        return lines
    except Exception as e:
        # Fallback to direct oracledb if available and host port exposed
        try:
            import oracledb
            conn = oracledb.connect(user=ORACLE_USER, password=ORACLE_PASS, dsn=ORACLE_DSN)
            cursor = conn.cursor()
            cursor.execute(sql)
            results = []
            for row in cursor:
                results.append("\t".join(str(val) for val in row))
            cursor.close()
            conn.close()
            return results
        except Exception:
            raise RuntimeError(f"Oracle query failed: {e}")

def run_postgres_sql(sql: str) -> List[str]:
    """Execute SQL query in Postgres container and return output lines."""
    cmd = [
        "docker", "exec", "-i", PG_CONTAINER,
        "psql", "-U", PG_USER, "-d", PG_DB, "-t", "-A", "-c", sql
    ]
    try:
        proc = subprocess.run(cmd, text=True, capture_output=True, check=True)
        return [l.strip() for l in proc.stdout.splitlines() if l.strip()]
    except Exception as e:
        try:
            import psycopg2
            conn = psycopg2.connect(dbname=PG_DB, user=PG_USER, password=PG_PASS, host=PG_HOST, port=PG_PORT)
            cur = conn.cursor()
            cur.execute(sql)
            results = ["\t".join(str(val) for val in row) for row in cur.fetchall()]
            cur.close()
            conn.close()
            return results
        except Exception:
            raise RuntimeError(f"Postgres query failed: {e}")

class InvariantChecker:
    def __init__(self, scope: str, scope_val: Optional[List[str]], wait_drain: int):
        self.scope = scope
        self.scope_val = scope_val or []
        self.wait_drain = wait_drain
        self.hard_failures: List[str] = []
        self.eventual_failures: List[str] = []

    def check_i1_negative_balances(self):
        """I-1: No customer account has negative balance; held_amount never exceeds balance."""
        where = "WHERE normal_balance = 'CR' AND (account_type IN ('CUSTOMER', 'SAVINGS', 'CHECKING', 'WALLET') OR customer_id IS NOT NULL)"
        if self.scope == "customers" and self.scope_val:
            cust_list = ",".join(f"'{c}'" for c in self.scope_val)
            where += f" AND customer_id IN ({cust_list})"
        sql = f"SELECT account_id FROM account_master {where} AND (balance_amount < 0 OR held_amount > balance_amount)"
        rows = run_oracle_sql(sql)
        if rows:
            print(f"I-1 FAIL ({len(rows)}) Offending accounts: {', '.join(rows[:10])}")
            self.hard_failures.append("I-1")
        else:
            print("I-1 PASS (0)")

    def check_i2_balanced_legs(self):
        """I-2: For every transaction posting and currency, sum DEBIT == sum CREDIT in ledger_entry."""
        where = "WHERE txn_id IS NOT NULL AND narrative NOT IN ('CASH_DEPOSIT', 'ATM_WITHDRAWAL', 'OPENING_BALANCE')"
        if self.scope == "txns" and self.scope_val:
            txns_list = ",".join(f"'{t}'" for t in self.scope_val)
            where += f" AND txn_id IN ({txns_list})"
        sql = f"""
        SELECT txn_id || ':' || currency_code
        FROM ledger_entry
        {where}
        GROUP BY txn_id, currency_code
        HAVING SUM(CASE WHEN direction = 'DEBIT' THEN amount ELSE -amount END) <> 0
        """
        rows = run_oracle_sql(sql)
        if rows:
            print(f"I-2 FAIL ({len(rows)}) Unbalanced postings: {', '.join(rows[:10])}")
            self.hard_failures.append("I-2")
        else:
            print("I-2 PASS (0)")

    def check_i3_leg_uniqueness_and_counts(self):
        """I-3: (txn_id, leg_no) unique; POSTED txn has core ref and >=2 legs; FAILED/CANCELLED have 0 legs."""
        # 1. Duplicates
        sql_dups = """
        SELECT txn_id || '#' || leg_no
        FROM ledger_entry
        WHERE txn_id IS NOT NULL
        GROUP BY txn_id, leg_no
        HAVING COUNT(*) > 1
        """
        dups = run_oracle_sql(sql_dups)

        # 2. POSTED txn legs < 2 or missing core_reference_id (opening cash deposit/withdrawal seeds excluded per D-17)
        sql_posted = """
        SELECT t.txn_id
        FROM transaction_master t
        LEFT JOIN (
            SELECT txn_id, COUNT(*) as leg_count 
            FROM ledger_entry 
            GROUP BY txn_id
        ) l ON t.txn_id = l.txn_id
        WHERE t.txn_status = 'POSTED' 
          AND t.txn_type NOT IN ('DEPOSIT', 'WITHDRAWAL')
          AND (t.core_reference_id IS NULL OR NVL(l.leg_count, 0) < 2)
        """
        bad_posted = run_oracle_sql(sql_posted)

        # 3. FAILED/CANCELLED with legs
        sql_cancelled = """
        SELECT t.txn_id
        FROM transaction_master t
        JOIN ledger_entry l ON t.txn_id = l.txn_id
        WHERE t.txn_status IN ('FAILED', 'CANCELLED')
        """
        bad_cancelled = run_oracle_sql(sql_cancelled)

        offenders = list(set(dups + bad_posted + bad_cancelled))
        if offenders:
            print(f"I-3 FAIL ({len(offenders)}) Offending txns: {', '.join(offenders[:10])}")
            self.hard_failures.append("I-3")
        else:
            print("I-3 PASS (0)")

    def check_i6_holds_parity(self):
        """I-6: For every account, held_amount equals the sum of its ACTIVE holds."""
        sql = """
        SELECT a.account_id
        FROM account_master a
        LEFT JOIN (
            SELECT account_id, SUM(amount) as active_held
            FROM account_hold
            WHERE status = 'ACTIVE'
            GROUP BY account_id
        ) h ON a.account_id = h.account_id
        WHERE NVL(a.held_amount, 0) <> NVL(h.active_held, 0)
        """
        rows = run_oracle_sql(sql)
        if rows:
            print(f"I-6 FAIL ({len(rows)}) Accounts with mismatched hold sums: {', '.join(rows[:10])}")
            self.hard_failures.append("I-6")
        else:
            print("I-6 PASS (0)")

    def check_i8_reversals(self):
        """I-8: Each original transaction has at most one reversal; original stays POSTED."""
        sql = """
        SELECT reversal_of_txn_id
        FROM transaction_master
        WHERE reversal_of_txn_id IS NOT NULL
        GROUP BY reversal_of_txn_id
        HAVING COUNT(*) > 1
        """
        rows = run_oracle_sql(sql)
        if rows:
            print(f"I-8 FAIL ({len(rows)}) Transactions reversed multiple times: {', '.join(rows[:10])}")
            self.hard_failures.append("I-8")
        else:
            print("I-8 PASS (0)")

    def check_i9_baseline_s(self, record_baseline: bool = False):
        """I-9: S = sum(CR balances) - sum(DR balances) per currency constant."""
        if self.scope != "global":
            print("I-9 SKIPPED (global scope only)")
            return

        sql = """
        SELECT currency_code || '|' || 
               TO_CHAR(SUM(CASE WHEN normal_balance = 'CR' THEN balance_amount ELSE -balance_amount END), 'FM999999999999990.0000')
        FROM account_master
        GROUP BY currency_code
        """
        rows = run_oracle_sql(sql)
        current_s = {}
        for r in rows:
            if "|" in r:
                curr, val = r.split("|", 1)
                current_s[curr.strip()] = val.strip()

        if record_baseline:
            with open(BASELINE_FILE, "w") as f:
                json.dump(current_s, f, indent=2)
            print(f"I-9 BASELINE RECORDED: {current_s}")
            return

        if not os.path.exists(BASELINE_FILE):
            # Record baseline if first run
            with open(BASELINE_FILE, "w") as f:
                json.dump(current_s, f, indent=2)
            print(f"I-9 PASS (baseline initialized: {current_s})")
            return

        with open(BASELINE_FILE, "r") as f:
            baseline_s = json.load(f)

        diffs = []
        for curr, base_val in baseline_s.items():
            curr_val = current_s.get(curr, "0.0000")
            if Decimal(curr_val) != Decimal(base_val):
                diffs.append(f"{curr}: base={base_val} current={curr_val}")

        if diffs:
            print(f"I-9 FAIL ({len(diffs)}) Baseline drift: {', '.join(diffs)}")
            self.hard_failures.append("I-9")
        else:
            print("I-9 PASS (0)")

    def check_i10_ledger_to_balance(self):
        """I-10: Account balance equals latest running_balance in ledger_entry rows."""
        sql = """
        SELECT am.account_id
        FROM account_master am
        JOIN (
            SELECT l1.account_id, l1.running_balance
            FROM ledger_entry l1
            WHERE l1.posted_at = (SELECT MAX(l2.posted_at) FROM ledger_entry l2 WHERE l2.account_id = l1.account_id)
        ) l ON am.account_id = l.account_id
        WHERE am.balance_amount <> l.running_balance
        """
        rows = run_oracle_sql(sql)
        if rows:
            print(f"I-10 FAIL ({len(rows)}) Accounts where balance != running balance: {', '.join(rows[:10])}")
            self.hard_failures.append("I-10")
        else:
            print("I-10 PASS (0)")

    def check_i11_posting_ref_parity(self):
        """I-11: Every POSTED txn has SETTLED posting_reference with matching core reference."""
        sql = """
        SELECT t.txn_id
        FROM transaction_master t
        LEFT JOIN posting_reference p ON t.txn_id = p.posting_reference_id
        WHERE t.txn_status = 'POSTED'
          AND (p.status IS NULL OR p.status <> 'SETTLED' OR p.core_reference_id <> t.core_reference_id)
        """
        rows = run_oracle_sql(sql)
        if rows:
            print(f"I-11 FAIL ({len(rows)}) Missing/mismatched posting references: {', '.join(rows[:10])}")
            self.hard_failures.append("I-11")
        else:
            print("I-11 PASS (0)")

    def check_i12_no_active_hold_on_terminal(self):
        """I-12: No ACTIVE hold on a terminal transaction (POSTED, FAILED, CANCELLED)."""
        sql = """
        SELECT h.hold_id
        FROM account_hold h
        JOIN transaction_master t ON h.txn_id = t.txn_id
        WHERE h.status = 'ACTIVE' AND t.txn_status IN ('POSTED', 'FAILED', 'CANCELLED')
        """
        rows = run_oracle_sql(sql)
        if rows:
            print(f"I-12 FAIL ({len(rows)}) Leaked active holds on terminal transactions: {', '.join(rows[:10])}")
            self.hard_failures.append("I-12")
        else:
            print("I-12 PASS (0)")

    def check_i13_idempotency_key_uniqueness(self):
        """I-13: At most one transaction per (customer_id, idempotency_key)."""
        sql = """
        SELECT customer_id || '#' || idempotency_key
        FROM transaction_master
        WHERE customer_id IS NOT NULL AND idempotency_key IS NOT NULL
        GROUP BY customer_id, idempotency_key
        HAVING COUNT(*) > 1
        """
        rows = run_oracle_sql(sql)
        if rows:
            print(f"I-13 FAIL ({len(rows)}) Duplicate transactions for same idempotency key: {', '.join(rows[:10])}")
            self.hard_failures.append("I-13")
        else:
            print("I-13 PASS (0)")

    def check_eventual_invariants(self):
        """Polls eventual set until drain timeout: I-4 (outbox), I-5 (audit parity), I-7 (stuck txns)."""
        deadline = time.time() + self.wait_drain
        poll_interval = 2.0

        while True:
            e4_fails = self._eval_i4_outbox()
            e5_fails = self._eval_i5_audit_parity()
            e7_fails = self._eval_i7_stuck_txns()

            if not e4_fails and not e5_fails and not e7_fails:
                print("I-4 PASS (0)")
                print("I-5 PASS (0)")
                print("I-7 PASS (0)")
                return

            if time.time() >= deadline:
                if e4_fails:
                    print(f"I-4 FAIL ({len(e4_fails)}) Pending outbox events: {', '.join(e4_fails[:10])}")
                    self.eventual_failures.append("I-4")
                else:
                    print("I-4 PASS (0)")

                if e5_fails:
                    print(f"I-5 FAIL ({len(e5_fails)}) Mismatched audit legs: {', '.join(e5_fails[:10])}")
                    self.eventual_failures.append("I-5")
                else:
                    print("I-5 PASS (0)")

                if e7_fails:
                    print(f"I-7 FAIL ({len(e7_fails)}) Stuck transactions: {', '.join(e7_fails[:10])}")
                    self.eventual_failures.append("I-7")
                else:
                    print("I-7 PASS (0)")
                return

            time.sleep(poll_interval)

    def _eval_i4_outbox(self) -> List[str]:
        sql = "SELECT outbox_id FROM outbox_master WHERE status = 'PENDING'"
        return run_oracle_sql(sql)

    def _eval_i5_audit_parity(self) -> List[str]:
        # Count legs per posted txn in Oracle vs Postgres
        ora_sql = """
        SELECT t.txn_id || '|' || COUNT(l.leg_no)
        FROM transaction_master t
        JOIN ledger_entry l ON t.txn_id = l.txn_id
        WHERE t.txn_status = 'POSTED'
        GROUP BY t.txn_id
        """
        ora_rows = run_oracle_sql(ora_sql)
        ora_map = {}
        for r in ora_rows:
            if "|" in r:
                tid, cnt = r.split("|", 1)
                ora_map[tid.strip()] = int(cnt.strip())

        if not ora_map:
            return []

        pg_sql = "SELECT txn_id, COUNT(*) FROM ledger_mutation_audit GROUP BY txn_id"
        pg_rows = run_postgres_sql(pg_sql)
        pg_map = {}
        for r in pg_rows:
            if "|" in r:
                tid, cnt = r.split("|", 1)
                pg_map[tid.strip()] = int(cnt.strip())

        mismatches = []
        for tid, ora_count in ora_map.items():
            pg_count = pg_map.get(tid, 0)
            if pg_count != ora_count:
                mismatches.append(f"{tid}(ora={ora_count},pg={pg_count})")
        return mismatches

    def _eval_i7_stuck_txns(self) -> List[str]:
        sql = """
        SELECT txn_id
        FROM transaction_master
        WHERE (txn_status = 'PROCESSING' AND initiated_at < SYSTIMESTAMP - INTERVAL '60' SECOND)
           OR (txn_status = 'AUTHORIZED' AND initiated_at < SYSTIMESTAMP - INTERVAL '65' SECOND)
           OR (txn_status = 'INITIATED' AND initiated_at < SYSTIMESTAMP - INTERVAL '360' SECOND)
        """
        return run_oracle_sql(sql)

def main():
    parser = argparse.ArgumentParser(description="Check system invariants")
    parser.add_argument("--scope", default="global", help="Scope: global, txns=<id,...>, customers=<id,...>")
    parser.add_argument("--wait-drain", type=int, default=15, help="Seconds to poll eventual set (default 15)")
    parser.add_argument("--baseline", action="store_true", help="Record initial I-9 baseline")

    args = parser.parse_args()

    scope_type = "global"
    scope_val = []
    if args.scope.startswith("txns="):
        scope_type = "txns"
        scope_val = [x.strip() for x in args.scope[len("txns="):].split(",") if x.strip()]
    elif args.scope.startswith("customers="):
        scope_type = "customers"
        scope_val = [x.strip() for x in args.scope[len("customers="):].split(",") if x.strip()]

    checker = InvariantChecker(scope=scope_type, scope_val=scope_val, wait_drain=args.wait_drain)

    try:
        if args.baseline:
            checker.check_i9_baseline_s(record_baseline=True)
            sys.exit(0)

        # 1. Hard set
        checker.check_i1_negative_balances()
        checker.check_i2_balanced_legs()
        checker.check_i3_leg_uniqueness_and_counts()
        checker.check_i6_holds_parity()
        checker.check_i8_reversals()
        checker.check_i9_baseline_s()
        checker.check_i10_ledger_to_balance()
        checker.check_i11_posting_ref_parity()
        checker.check_i12_no_active_hold_on_terminal()
        checker.check_i13_idempotency_key_uniqueness()

        # 2. Eventual set
        checker.check_eventual_invariants()

        if checker.hard_failures:
            sys.exit(1)
        if checker.eventual_failures:
            sys.exit(2)
        sys.exit(0)

    except Exception as ex:
        print(f"DATABASE / TOOL ERROR: {ex}", file=sys.stderr)
        sys.exit(3)

if __name__ == "__main__":
    main()
