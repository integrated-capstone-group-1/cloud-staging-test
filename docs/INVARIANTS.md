# Invariants and the `check_invariants` Interface

Single source for invariants I-1 to I-11. Lines tagged `[VERIFY H-nn]` are not confirmed against the schema yet. Table and column names are deliberately not fixed here; they are confirmed in H-12 and then written into the script.

## 1. Invariants

**Hard set** (must hold at all times; checked immediately):

| ID | Statement |
|---|---|
| I-1 | No customer account has a negative balance, and `held_amount` never exceeds the balance. Internal bank accounts (DR-normal clearing accounts) are excluded. |
| I-2 | For every transaction posting and every currency, the sum of DEBIT legs equals the sum of CREDIT legs in `ledger_entry`. Opening-balance postings are excluded (D-17). |
| I-3 | `(txn_id, leg_no)` is unique. A `POSTED` transaction has a core reference and at least two legs. `FAILED` and `CANCELLED` transactions have no legs. |
| I-6 | For every account, `held_amount` equals the sum of its `ACTIVE` holds. |
| I-8 | Each original transaction has at most one reversal; the original stays `POSTED`. |
| I-9 | Per currency, S = (sum of CR-normal balances) minus (sum of DR-normal balances) equals the baseline recorded at stack reset (D-16). Because every posting is balanced (I-2), S never changes. |
| I-10 | For every account, the balance equals the signed sum of its `ledger_entry` rows, opening entries included, using `normal_balance`. |
| I-11 | Every `POSTED` transaction has a `posting_reference` in `SETTLED` with the same core reference, and every `SETTLED` posting reference belongs to a `POSTED` or reversed transaction (legacy adapter and opening postings excluded). |
| I-12 | No `ACTIVE` hold exists on a terminal transaction (`POSTED`, `FAILED`, `CANCELLED`). Holds must be `CAPTURED`, `RELEASED`, or `EXPIRED`. |
| I-13 | Idempotency key uniqueness per customer: at most one transaction per `(customer_id, idempotency_key)`. |

**Eventual set** (hold once the system has drained; polled until a timeout):

| ID | Statement |
|---|---|
| I-4 | Every posted transaction has an outbox event, and no outbox row stays `PENDING` longer than the drain timeout. |
| I-5 | Audit parity: for every posted transaction, the legs and amounts in the PostgreSQL audit store match Oracle. |
| I-7 | No transaction is stuck: `PROCESSING` older than the sweeper threshold (60 s), `AUTHORIZED` older than `cancel_window_seconds` plus the sweeper threshold, or `INITIATED` older than the OTP expiry (300 s) plus the sweeper threshold. |

## 2. Interface

```
check_invariants [--scope global | --scope txns=<id,id,...> | --scope customers=<id,id,...>]
                 [--wait-drain <seconds>]      # eventual set polls up to this long (default 30)
                 [--baseline]                  # record the I-9 baseline (run once after a clean reset)
```

* **Scopes.** `txns=` and `customers=` apply the per-transaction invariants (I-2, I-3, I-4, I-5, I-7, I-8, I-11) to those transactions and the per-account invariants (I-1, I-6, I-10) to the accounts they touch. I-9 is global only and is reported `SKIPPED` in other scopes.
* **Exit codes.** `0` clean. `1` hard-set failure. `2` eventual-set failure after the drain timeout. `3` tool or database error (not a finding; treat as an unhealthy stack).
* **Output.** One line per invariant: `I-n PASS|FAIL|SKIPPED (count)` followed, on failure, by up to 10 offending IDs. The output never contains balances, names, tokens or other personal data.
* **Access.** Read-only database credentials.
* **Harness.** Python under `tests/invariants/` using the existing pytest, oracledb and psycopg2 setup (AGENTS exception, D-18).

## 3. Preconditions

* The script must be **green on a freshly reset stack**. The seed's opening balances are single-leg ledger entries with no transaction, outbox or audit rows; they would fail I-2 to I-5 unless excluded as D-17 states. Confirm in H-12 and fix the exclusion predicate or the seed.
* `--baseline` runs once after each reset, before any test traffic.
* `[VERIFY H-12]` which accounts are DR-normal in the seed, and the baseline S per currency.

## 4. How the loop uses it

* The BREAKER runs it scoped to the customers it created, after each journey group, and once globally at the end of a run. It reads only the exit code and the output lines.
* The FIXER runs the global scope in Step G (verify), after the module and gate tests.
