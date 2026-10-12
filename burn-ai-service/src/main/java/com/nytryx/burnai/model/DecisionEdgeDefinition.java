package com.nytryx.burnai.model;

public record DecisionEdgeDefinition(
        long id,
        long treeId,
        long sourceNodeId,
        long targetNodeId,
        String edgeKey,
        String branchLabel,
        String conditionExpression,
        String inputIndicatorCodes,
        String selectorMode,
        int priorityOrder,
        boolean defaultEdge
) {
}
