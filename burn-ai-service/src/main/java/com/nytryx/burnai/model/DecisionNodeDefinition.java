package com.nytryx.burnai.model;

public record DecisionNodeDefinition(
        long id,
        long treeId,
        String nodeKey,
        String title,
        String nodeType,
        String indicatorCode,
        String indicatorStage,
        String valueCode,
        String executionMode,
        String inputIndicatorCodes,
        String conditionExpression,
        String logicDescription,
        String logicDefinitionJson,
        String actionPayloadJson,
        String outputPayloadJson,
        int sortOrder
) {
}
