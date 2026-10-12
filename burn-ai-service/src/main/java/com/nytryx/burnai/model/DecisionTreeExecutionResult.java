package com.nytryx.burnai.model;

import java.util.List;
import java.util.Map;

public record DecisionTreeExecutionResult(
        String status,
        long treeId,
        String treeCode,
        List<String> visitedNodeKeys,
        Map<String, Object> decisionOutputs,
        List<Map<String, Object>> treatmentActions,
        List<ModelDecisionRequest> pendingDecisions,
        List<ModelDecisionTrace> modelDecisions,
        List<String> validationErrors
) {
    public record ModelDecisionRequest(
            String nodeKey,
            String title,
            String executionMode,
            Map<String, Object> inputValues,
            List<EdgeCandidate> candidates,
            String constrainedPrompt
    ) {
    }

    public record EdgeCandidate(
            String edgeKey,
            String branchLabel,
            String conditionExpression,
            Integer priority,
            List<String> dependsOn
    ) {
    }

    public record ModelDecisionTrace(
            String nodeKey,
            String edgeKey,
            List<String> edgeKeys,
            String reasoning,
            List<String> evidenceCodes,
            String confidence,
            String model,
            Integer promptTokens,
            Integer completionTokens
    ) {
    }
}
