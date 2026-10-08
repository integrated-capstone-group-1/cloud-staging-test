# Integration Test Plan (v1.0)

## 1. Objective
Verify the operational integrity of all system seams, ensuring that databases, message brokers, caching layers, and background workers communicate without data loss, deadlocks, or state corruption.

## 2. Scope & Sub-Suites
This phase covers Tier 2 of the testing pyramid, specifically targeting real components rather than mocks.
- **CBS & Persistence**: Oracle XE row-level locking, sequence atomicity, and constraint enforcement.
- **Messaging & Audit**: Kafka outbox polling, event delivery, and PostgreSQL audit immutability.
- **Caching**: Redis fallback mechanisms and data expiration.

## 3. Test Cases & Expected Outcomes

| ID | Case | Expected Result |
|---|---|---|
| INT-01 | **Settlement Atomicity** | `account_master`, `ledger_entry`, and `outbox_master` commit together. DB failure rolls back all. |
| INT-02 | **Audit Immutability** | PostgreSQL triggers block `UPDATE`/`DELETE` on `ledger_mutation_audit`. |
| INT-03 | **Kafka Outbox Relay** | `PENDING` outbox records are published and marked `PUBLISHED`. |
| INT-04 | **Audit Parity** | `ReconciliationEngine` detects 0 drift between Oracle core and Postgres audit. |

## 4. Execution
Integration tests are executed against active database and broker containers.

**Execution Command:**
```bash
# Start backend infrastructure dependencies
docker compose up -d oracle-db postgres-audit-db kafka redis

# Run integration slice tests
./mvnw test -Dgroups="integration"
```

## 5. Acceptance Gate
- 100% pass rate across the integration test suite.
- Zero `ORA-00060` (deadlock) errors during concurrent DB operations.
- Zero data loss verified by the reconciliation engine.

