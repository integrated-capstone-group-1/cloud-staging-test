package com.capstone.orchestrator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * Tier 3 Channel Orchestrator &amp; BFF Application.
 *
 * [W-H-Y MODEL &amp; ARCHITECTURAL TRACEABILITY]:
 * - WHAT (Definition): Stateless Backend-For-Frontend (BFF) orchestrating authenticated customer journeys.
 * - HOW (Mechanism): Inspects incoming Gateway JWTs, enforces customer account ownership guards,
 *                     sanitizes Tier 4 domain errors into RFC 7807 ProblemDetails, and forwards valid requests
 *                     to Transaction Service (:8084) and Accounts Service (:8083).
 * - WHY (Rationale): Decouples presentation/channel validation from core balance mutation, preventing
 *                    Transaction Service from becoming an overloaded "God Service".
 *
 * TRACEABILITY CALL PATH:
 * Client -> Gateway (:8080) -> Thin Orchestrator (:8089) -> Transaction Service (:8084) -> Mock CBS (:8083).
 */
@SpringBootApplication
@ComponentScan(basePackages = {"com.capstone.orchestrator", "com.capstone.common"})
public class OrchestratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrchestratorApplication.class, args);
    }
}

