package com.nytryx.burnai.model;

import java.util.List;
import java.util.Map;

public record DecisionTreeExecutionRequest(
        Map<String, Object> indicatorValues,
        Map<String, Object> indicatorEvidence,
        Map<String, String> edgeSelections,
        Map<String, List<String>> edgeSetSelections
) {
}
