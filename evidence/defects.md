# Defects Ledger

| ID | Severity | Class | Owner | Root Cause | Commit | Tests Added | Docs Changed | Status |
|---|---|---|---|---|---|---|---|---|
| F-001 | S1 | CODE_BUG | Russel | Edge gateway GatewayRouteIsolationFilter omitted /api/v1/ledger/mutate and /api/v1/accounts/ mutation patterns | b7a4b53 | GatewayRouteIsolationFilterTest (3 scenarios), GatewayPerimeterSecurityIntegrationTest | None | VERIFIED |
| F-002 | S2 | CODE_BUG | Rohan | Missing funding_pocket_id ownership and quote_id validation in PaymentForwardingController; unhandled error statuses in TransactionClient; unsanitized 500 detail | 5045d3a | PaymentForwardingControllerTest (3 scenarios), ChannelOrchestratorIntegrationTest (2 scenarios) | None | VERIFIED |
| F-003 | S1 | CODE_BUG | Rohan | PaymentRequest accepted arbitrary decimal scale and scientific notation | f92e401 | MonetaryAmountDeserializerTest (4 scenarios), ChannelOrchestratorIntegrationTest (2 scenarios) | None | VERIFIED |
| F-004 | S1 | CODE_BUG | Rohan | PaymentOrchestrator bypassed Redis fast-lock, causing DB unique constraint collisions (ORA-00001) and raw SQL schema leakage | 9a36147 | PaymentOrchestratorTest (2 scenarios), Blackbox Concurrency Replay | None | VERIFIED |
| F-005 | S1 | CODE_BUG | Rohan | PaymentController lacked customer ownership checks on GET /payments/{id} and POST /payments/{id}/cancel | 779ed49 | PaymentControllerSecurityTest (4 scenarios), Blackbox IDOR Replay | None | VERIFIED |
| F-006 | S2 | CODE_BUG | Russel | SecurityHeadersFilter mutated ReadOnlyHttpHeaders causing UnsupportedOperationException and Gateway HTTP 500 crash on client-supplied X-Cust-Id | e2ac92b | HeaderSanitizationAndCorsTest, SecurityHeadersFilterTest, Blackbox Replay | None | VERIFIED |
| F-007 | S1 | CODE_BUG | Rohan | TransactionService allowed non-initiating leg owners to view transaction ledger audit trails (cross-customer IDOR) | 7529f67 | AuditServiceTest, Blackbox IDOR Replay | None | VERIFIED |


