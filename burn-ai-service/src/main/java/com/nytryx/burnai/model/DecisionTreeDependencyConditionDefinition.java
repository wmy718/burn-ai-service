package com.nytryx.burnai.model;

public record DecisionTreeDependencyConditionDefinition(
        long id,
        long dependencyId,
        int cardGroup,
        String conditionMode,
        String indicatorCode,
        String comparisonOperator,
        String expectedValue,
        String conditionText,
        int sortOrder
) {
}
