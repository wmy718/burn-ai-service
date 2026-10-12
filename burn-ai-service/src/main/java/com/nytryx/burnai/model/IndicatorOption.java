package com.nytryx.burnai.model;

public record IndicatorOption(
        long id,
        String valueLabel,
        boolean defaultValue,
        String inferenceBasis,
        int sortOrder
) {
}
