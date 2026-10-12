package com.nytryx.burnai.model;

public record DecisionTreeDependencyDefinition(
        long id,
        long treeId,
        long prerequisiteTreeId,
        int sortOrder,
        String conditionMode,
        String indicatorCode,
        String comparisonOperator,
        String expectedValue,
        String conditionText
) {
}
