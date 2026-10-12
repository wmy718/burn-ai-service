package com.nytryx.burnai.model;

public record IndicatorValue(
        String code,
        String name,
        Object value,
        String status,
        String source,
        String reason
) {
}
