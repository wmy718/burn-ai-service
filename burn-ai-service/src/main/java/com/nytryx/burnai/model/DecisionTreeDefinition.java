package com.nytryx.burnai.model;

import java.util.List;

public record DecisionTreeDefinition(
        long id,
        long diseaseId,
        String diseaseCode,
        String code,
        String name,
        String description,
        Long indicatorGroupId,
        String prerequisiteCondition,
        String unlockCondition,
        int sortOrder,
        List<DecisionNodeDefinition> nodes,
        List<DecisionEdgeDefinition> edges
) {
}
