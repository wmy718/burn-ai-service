package com.nytryx.burnai.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

public record AggregateExtractionRequest(
        String requestId,
        String diseaseCode,
        @NotBlank String clinicalText,
        @NotNull Map<String, Object> featureResult
) {
}
