package com.nytryx.burnai.service;

import com.nytryx.burnai.model.DecisionTreeExecutionResult.ModelDecisionRequest;

import java.util.List;

public interface EdgeSelectionModel {
    Selection select(ModelDecisionRequest request);

    record Selection(
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
