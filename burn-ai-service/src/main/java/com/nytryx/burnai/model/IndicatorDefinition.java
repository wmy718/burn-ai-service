package com.nytryx.burnai.model;

import java.util.List;

public record IndicatorDefinition(
        long id,
        long diseaseId,
        String diseaseCode,
        String code,
        String name,
        String type,
        String valueType,
        String sourceType,
        String unit,
        String defaultValue,
        String jsonPath,
        String inferenceBasis,
        String description,
        String valueDomain,
        boolean required,
        int sortOrder,
        List<IndicatorOption> options
) {
    public boolean isDecision() {
        return "DECISION".equalsIgnoreCase(type);
    }

    public boolean isTextLike() {
        return "CASE_REPORT".equalsIgnoreCase(sourceType);
    }

    public boolean isImageLike() {
        return "IMAGE_JSON".equalsIgnoreCase(sourceType);
    }
}
