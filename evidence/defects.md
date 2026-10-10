# Defects Ledger

| ID | Severity | Class | Owner | Root Cause | Commit | Tests Added | Docs Changed | Status |
|---|---|---|---|---|---|---|---|---|
| F-001 | S1 | CODE_BUG | Russel | Edge gateway GatewayRouteIsolationFilter omitted /api/v1/ledger/mutate and /api/v1/accounts/ mutation patterns | b7a4b53 | GatewayRouteIsolationFilterTest (3 scenarios), GatewayPerimeterSecurityIntegrationTest | None | VERIFIED |
| F-002 | S2 | CODE_BUG | Rohan | Missing funding_pocket_id ownership and quote_id validation in PaymentForwardingController; unhandled error statuses in TransactionClient; unsanitized 500 detail | 5045d3a | PaymentForwardingControllerTest (3 scenarios), ChannelOrchestratorIntegrationTest (2 scenarios) | None | VERIFIED |

