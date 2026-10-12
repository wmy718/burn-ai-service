package com.nytryx.burnai.service;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
public class DiseaseCodeResolver {
    private static final List<String> PAYLOAD_KEYS = List.of(
            "diseaseCode", "disease_code", "disease", "scenario", "taskType", "task_type", "caseType", "case_type", "类型", "病种"
    );

    public String requireDiseaseCode(String explicit) {
        return normalize(explicit);
    }

    public String requireDiseaseCode(String explicit, Map<String, Object> payload) {
        String direct = tryNormalize(explicit);
        if (direct != null) {
            return direct;
        }
        String fromPayload = findInPayload(payload);
        if (fromPayload != null) {
            return fromPayload;
        }
        throw new IllegalArgumentException("必须明确传入病种：burn/烧伤 或 scar/瘢痕；当前服务不再支持 all 混合计算。 ");
    }

    private String findInPayload(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        for (String key : PAYLOAD_KEYS) {
            Object value = payload.get(key);
            String normalized = tryNormalize(value == null ? null : Objects.toString(value));
            if (normalized != null) {
                return normalized;
            }
        }
        for (Object value : payload.values()) {
            if (value instanceof Map<?, ?> nested) {
                @SuppressWarnings("unchecked")
                String nestedValue = findInPayload((Map<String, Object>) nested);
                if (nestedValue != null) {
                    return nestedValue;
                }
            }
        }
        return null;
    }

    private String normalize(String value) {
        String normalized = tryNormalize(value);
        if (normalized == null) {
            throw new IllegalArgumentException("必须明确传入病种：burn/烧伤 或 scar/瘢痕；当前服务不再支持 all 混合计算。 ");
        }
        return normalized;
    }

    private String tryNormalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String lower = value.trim().toLowerCase(Locale.ROOT);
        if (lower.equals("burn") || lower.equals("burns") || lower.contains("烧伤") || lower.contains("燒傷")) {
            return "burn";
        }
        if (lower.equals("scar") || lower.equals("scars") || lower.contains("瘢痕") || lower.contains("疤痕")) {
            return "scar";
        }
        if (lower.equals("all") || lower.equals("全部") || lower.equals("混合")) {
            throw new IllegalArgumentException("烧伤和瘢痕必须分开计算，不能使用 all 混合病种。 ");
        }
        return null;
    }
}
