package com.nytryx.burnai.model;

import java.util.Map;

public record TreeRunSummary(
        String treeCode,
        String treeName,
        String status,
        Map<String, Object> outputs,
        long elapsedMs,
        String message
) {
}
