package com.nytryx.burnai.model;

import java.util.List;
import java.util.Map;

public record ExtractionResponse(
        String requestId,
        String diseaseCode,
        Map<String, Object> indicators,
        Map<String, Object> indicatorCodes,
        List<StageTiming> timings,
        long elapsedMs,
        List<String> errors
) {
}
