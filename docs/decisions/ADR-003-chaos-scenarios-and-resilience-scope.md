# ADR-003: Graded Chaos Scenarios and Resilience Suite Scope

## Context & Problem
BRD/TRD v2.3 Section 14 lists 10 chaos engineering scenarios. The capstone evaluation rubric requires demonstrated fault tolerance, but previous APD docs only listed 4 scenarios, while Jira `DIG-60` tasked Russel & Gym with an 8 SP story to deliver automated chaos testing.

## Decision
All 10 scenarios from BRD Section 14 plus the PostgreSQL audit outage scenario (11 scenarios total, `CH-01` through `CH-11`) are formally recognized in the resilience catalog:
1. **`CH-01`**: Oracle DB degradation (Resilience4j breaker trips, reads served from Redis cache with timestamp, writes fail-safe). *(Graded / Priority 1)*
2. **`CH-02`**: Risk engine killed/slow (<= 200ms timeout, fail-closed, zero balance mutations). *(Graded / Priority 1)*
3. **`CH-03`**: Perimeter port scan (All internal ports refused; only :8080 and :8090 reachable). *(Graded / Priority 1)*
4. **`CH-04`**: PostgreSQL audit DB outage (Consumer pauses/retries; zero message loss; 100% parity after recovery). *(Graded / Priority 1)*
5. **`CH-05`**: Kafka broker lag / outage (Outbox accumulates in Oracle, resumes drain on recovery).
6. **`CH-06`**: FX API outage (api.frankfurter.dev blocked, cached rates used; stale rate guard triggered if > 24h).
7. **`CH-07`**: Poison message / consumer crash (Retry topics then .dlq; consumer continues without halting).
8. **`CH-08`**: Service killed mid-transaction (Sweeper resolves stuck PROCESSING transaction via CBS inquiry).
9. **`CH-09`**: Dashboard feed loss (Admin UI detects SSE/WebSocket loss, displays stale banner and falls back to polling).
10. **`CH-10`**: Partner API outage (Integration circuit breaker opens; fail-closed rejection).
11. **`CH-11`**: EOD batch crash (Killed mid-run; restarts from last checkpointed step with zero duplicate executions).

For live evaluation, **`CH-01` through `CH-04` are the primary graded live demonstration scenarios**, while automated bash/PowerShell scripts execute the remaining scenarios in CI/local regression.

## Consequences
* Complete alignment with BRD Section 14 and the Jira Test & Chaos Plan.
* Clear priority order for the Cloud DevSecOps team (Russel & Gym).
