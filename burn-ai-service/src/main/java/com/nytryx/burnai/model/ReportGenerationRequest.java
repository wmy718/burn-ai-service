package com.nytryx.burnai.model;

import jakarta.validation.constraints.NotEmpty;

import java.util.Map;

public record ReportGenerationRequest(
        String diseaseCode,
        String requestId,
        @NotEmpty Map<String, Object> indicators
) {}
