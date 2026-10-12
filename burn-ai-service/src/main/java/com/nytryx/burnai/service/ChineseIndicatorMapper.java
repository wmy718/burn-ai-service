package com.nytryx.burnai.service;

import com.nytryx.burnai.model.IndicatorDefinition;
import com.nytryx.burnai.repo.DecisionConfigRepository.ConfigSnapshot;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class ChineseIndicatorMapper {
    public Map<String, Object> toCodeValues(ConfigSnapshot snapshot, Map<String, Object> incoming) {
        Map<String, IndicatorDefinition> byName = snapshot.indicatorByName();
        Map<String, IndicatorDefinition> byCode = snapshot.indicatorByCode();
        Map<String, Object> normalized = new LinkedHashMap<>();
        incoming.forEach((key, value) -> {
            IndicatorDefinition indicator = byCode.get(key);
            if (indicator == null) {
                indicator = byName.get(key);
            }
            if (indicator != null) {
                normalized.put(indicator.code(), normalizeIncomingValue(value));
            }
        });
        return normalized;
    }

    private Object normalizeIncomingValue(Object value) {
        if (!(value instanceof String text)) {
            return value;
        }
        String trimmed = text.trim();
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            String inner = trimmed.substring(1, trimmed.length() - 1).trim();
            if (!inner.isBlank()) {
                return java.util.Arrays.stream(inner.split("[,，]"))
                        .map(String::trim)
                        .filter(item -> !item.isBlank())
                        .toList();
            }
        }
        return value;
    }

    public Map<String, Object> toChineseValues(ConfigSnapshot snapshot, Map<String, Object> codeValues) {
        Map<String, IndicatorDefinition> byCode = snapshot.indicatorByCode();
        Map<String, Object> result = new LinkedHashMap<>();
        codeValues.forEach((code, value) -> {
            IndicatorDefinition indicator = byCode.get(code);
            result.put(indicator == null ? code : indicator.name(), value);
        });
        return result;
    }
}
