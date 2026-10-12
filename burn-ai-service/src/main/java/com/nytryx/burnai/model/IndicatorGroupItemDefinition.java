package com.nytryx.burnai.model;

public record IndicatorGroupItemDefinition(
        long id,
        long groupId,
        Long indicatorId,
        boolean required,
        int cardGroup,
        String comparisonOperator,
        String expectedValue,
        String conditionExpression,
        int sortOrder
) {
}
