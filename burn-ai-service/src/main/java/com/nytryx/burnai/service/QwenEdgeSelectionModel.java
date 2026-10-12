package com.nytryx.burnai.service;

import com.nytryx.burnai.model.DecisionTreeExecutionResult.ModelDecisionRequest;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class QwenEdgeSelectionModel implements EdgeSelectionModel {
    private final AiModelClient AiModelClient;

    public QwenEdgeSelectionModel(AiModelClient AiModelClient) {
        this.AiModelClient = AiModelClient;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Selection select(ModelDecisionRequest request) {
        if (!AiModelClient.available()) {
            return fallback(request, "千问未启用，使用默认候选边");
        }
        Map<String, Object> response;
        try {
            response = AiModelClient.askJson(
                    "你是受约束医学决策树选边器。必须只输出 JSON 对象，只能返回候选 edgeKey，不能发明新结论。",
                    request.constrainedPrompt() + "\n请输出 JSON。"
            );
        } catch (RuntimeException exception) {
            return fallback(request, "千问选边失败，使用默认候选边：" + exception.getMessage());
        }
        boolean setSelection = "MODEL_SET_SELECTION".equals(request.executionMode());
        if (setSelection) {
            Object raw = response.get("edgeKeys");
            List<String> keys = raw instanceof List<?> list
                    ? list.stream().map(Objects::toString).filter(s -> !s.isBlank()).toList()
                    : List.of();
            if (keys.isEmpty()) {
                String single = Objects.toString(response.get("edgeKey"), "").trim();
                if (!single.isBlank()) keys = List.of(single);
            }
            List<String> valid = request.candidates().stream().map(c -> c.edgeKey()).toList();
            keys = keys.stream().filter(valid::contains).distinct().toList();
            if (keys.isEmpty()) {
                return fallback(request, "千问未返回有效的多选候选边，使用默认候选边");
            }
            return new Selection(
                    keys.get(0),
                    keys,
                    Objects.toString(response.getOrDefault("reasoning", ""), ""),
                    listValue(response.get("evidenceCodes")),
                    Objects.toString(response.getOrDefault("confidence", "unknown"), "unknown"),
                    "qwen-plus",
                    null,
                    null
            );
        }
        String edgeKey = Objects.toString(response.get("edgeKey"), "");
        boolean valid = request.candidates().stream().anyMatch(candidate -> candidate.edgeKey().equals(edgeKey));
        if (!valid) {
            return fallback(request, "千问返回的 edgeKey 不在候选中，使用默认候选边");
        }
        return new Selection(
                edgeKey,
                List.of(edgeKey),
                Objects.toString(response.getOrDefault("reasoning", ""), ""),
                listValue(response.get("evidenceCodes")),
                Objects.toString(response.getOrDefault("confidence", "unknown"), "unknown"),
                "qwen-plus",
                null,
                null
        );
    }

    private Selection fallback(ModelDecisionRequest request, String reason) {
        String edgeKey = request.candidates().stream()
                .filter(candidate -> candidate.priority() != null && candidate.priority() >= 999)
                .findFirst()
                .or(() -> request.candidates().stream().findFirst())
                .map(candidate -> candidate.edgeKey())
                .orElse(null);
        return new Selection(edgeKey, edgeKey == null ? List.of() : List.of(edgeKey), reason, List.of(), "low", "fallback", null, null);
    }

    private List<String> listValue(Object raw) {
        if (raw instanceof List<?> list) {
            return list.stream().map(Objects::toString).toList();
        }
        return List.of();
    }
}
