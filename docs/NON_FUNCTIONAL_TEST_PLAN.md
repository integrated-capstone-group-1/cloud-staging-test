# Non-Functional Testing (NFR) Plan (v1.0)

## 1. Objective
Prove the system's resilience under extreme conditions and verify it meets operational baseline requirements. This covers concurrency stability, fail-safe degradation, and high-load performance.

## 2. Scope & Sub-Suites
Covers Tier 4 of the testing pyramid.
- **Concurrency & Races**: Prove zero overdrafts and exact idempotency under parallel attack.
- **Chaos Engineering**: Network latency, container death, and broker outages.
- **Performance / Capacity**: Verify the 800 TPS throughput and <= 50ms latency SLAs.

## 3. Test Cases & Expected Outcomes

| ID | Scenario | Expected Resilience |
|---|---|---|
| TS-01 | **Concurrent Withdrawals** | 50 concurrent hits. Debits succeed until 0.00. No negative overdrafts. |
| TS-04 | **Cancel vs Process Race** | Compare-and-Set guarantees one winner. Tx is either `CANCELLED` or `POSTED`. |
| CH-01 | **Oracle Latency (800ms)** | Circuit breaker trips. Cache reads succeed. Writes fail-safe with 503. |
| CH-02 | **Risk Engine Outage** | SLA limit (<200ms) fails closed. Payments return 503 `SECURITY_CHECK_UNAVAILABLE`. |
| CH-04 | **Audit DB Outage** | Payments continue normally. Kafka consumer pauses, then catches up on recovery. |

## 4. Execution
Tests are split between JMeter scripts and automated bash chaos injectors.

**Execution Commands:**

*For Chaos Suite:*
```bash
# Execute graded resilience attacks
bash scripts/chaos/run_all_chaos_tests.sh
```

*For Concurrency and Load:*
```powershell
# Run JMeter baseline throughput tests
.\tests\jmeter\run-baseline-test.ps1
```

## 5. Acceptance Gate
- **Chaos**: All graded chaos scenarios (CH-01 to CH-04) pass and recover to 100% data parity.
- **Concurrency**: Zero overdrafts, zero double-bookings, zero deadlocks under parallel load.
- **Performance**: Sustained baseline load achieves >= 800 TPS with p95 latency <= 50ms (or limits documented if hardware capped).

