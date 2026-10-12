package com.nytryx.burnai.model;

import java.util.List;
import java.util.Map;

public record ReportGenerationResponse(
        String requestId,
        String diseaseCode,
        Map<String, Object> normalizedIndicators,
        Map<String, Object> normalizedIndicatorCodes,
        Map<String, Object> decisionOutputs,
        Map<String, Object> decisionOutputCodes,
        Map<String, Object> report,
        List<TreeRunSummary> trees,
        List<StageTiming> timings,
        long elapsedMs,
        List<String> warnings
) {
}
