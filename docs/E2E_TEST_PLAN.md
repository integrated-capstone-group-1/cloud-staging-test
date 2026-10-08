# End-to-End (E2E) Test Plan (v1.0)

## 1. Objective
Validate the complete system lifecycle by simulating real user behavior through the API Gateway (`:8080`) and Admin Portal (`:8090`). Ensure all microservices collaborate correctly to fulfill the business requirements.

## 2. Scope & Sub-Suites
This phase covers Tier 3 of the testing pyramid, treating the backend as a complete black box.
- **Customer Journeys**: Onboarding, sending money, shortfall auto-conversion, cancellations.
- **Admin Operations**: Approving holds, reversing transactions, standing instructions.
- **Error Contracts**: Ensuring standard RFC 7807 responses across all endpoints.

## 3. The 8 Golden Journeys

| ID | Journey | Key Assertions |
|---|---|---|
| E2E-01 | **Auth & Onboarding** | Registration creates PHP pocket. Login returns valid JWT. Logout blacklists token. |
| E2E-02 | **Same-Currency Transfer** | `POSTED` status, balances updated, exact audit legs generated. Replays ignored safely. |
| E2E-03 | **Shortfall Auto-Conversion** | EUR shortage funded by PHP pocket. 6-leg settlement atomic execution. |
| E2E-04 | **CAS Lifecycle & Cancel** | 5s cancel window honored. Cancellation after `PROCESSING` returns `409 Conflict`. |
| E2E-05 | **Fraud & Admin Holds** | High risk (>0.85) held as `RESERVED`. Admin portal approval moves to `POSTED`. |
| E2E-06 | **Reversal** | Admin reversal triggers linked compensating legs. Original tx stays `POSTED`. |
| E2E-07 | **Standing Instructions** | Batch execution triggers correctly. Idempotency prevents double-charging. |
| E2E-08 | **Reconciliation** | E2E run ends with 0 variance between CBS and audit schemas. |

## 4. Execution
E2E tests require the entire Docker Compose stack to be fully operational.

**Execution Command:**
```bash
# Ensure full stack is running
docker compose up -d

# Execute Python E2E suite
python -m pytest tests/e2e/ -v --html=report.html
```

## 5. Acceptance Gate
- 100% of Golden Journeys pass successfully.
- API responses strictly adhere to OpenAPI specifications (no stack traces).
- Post-run invariant checks confirm zero negative balances and 100% audit parity.

