package com.capstone.risk.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskEvaluationResponse {

    private double score;

    private String level;

    private String decision;

    @Builder.Default
    @JsonProperty("triggered_rules")
    private List<String> triggeredRules = new ArrayList<>();

    @JsonProperty("evaluation_latency_ms")
    private double evaluationLatencyMs;
}
