package com.nytryx.burnai.model;

public record IndicatorGroupDefinition(
        long id,
        long diseaseId,
        String name,
        String triggerCondition,
        int sortOrder
) {
}
