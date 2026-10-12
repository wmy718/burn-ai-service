package com.nytryx.burnai.service;

import com.nytryx.burnai.model.*;
import java.util.*;
import java.math.BigDecimal;

/** Validate against the database contract and produce frontend-safe fallback values. */
public class IndicatorValueValidator {
    public IndicatorValue resolve(IndicatorDefinition i, Object raw, String status, String source, String reason) {
        String state = Objects.toString(status, "predicted").toLowerCase(Locale.ROOT);
        if (raw instanceof Map<?, ?> metric && metric.containsKey("status")) {
            state = Objects.toString(metric.get("status"), "unknown").toLowerCase(Locale.ROOT);
            raw = metric.get("value");
        }
        if (raw instanceof String text && Set.of("predicted", "provided").contains(state)) {
            String token=text.trim().toLowerCase(Locale.ROOT);
            if(Set.of("unknown","uncertain","unavailable","not_assessed","未知","未评估","证据不足").contains(token)) state="unknown";
            if(Set.of("not_applicable","不适用").contains(token)) state="not_applicable";
            if("invalid".equals(token)) state="invalid";
        }
        if (Set.of("unavailable", "not_assessed", "uncertain", "not_generated", "extraction_failed", "conflict").contains(state)) state = "unknown";
        if (!Set.of("predicted", "provided", "calculated", "unknown", "not_applicable", "invalid").contains(state)) state = "invalid";
        if (!Set.of("unknown", "not_applicable", "invalid").contains(state) && raw == null) state = "unknown";
        if (Set.of("unknown", "not_applicable", "invalid").contains(state)) return new IndicatorValue(i.code(), i.name(), fallback(i,state), state, source, reason);
        try { return new IndicatorValue(i.code(), i.name(), normalize(i,raw), state, source, reason); }
        catch (IllegalArgumentException ex) { return new IndicatorValue(i.code(),i.name(),fallback(i,"invalid"),"invalid",source,"值域校验失败："+ex.getMessage()); }
    }

    public IndicatorValue resolveDefault(IndicatorDefinition i, String source, String reason) {
        Object value = fallback(i, "unknown");
        return new IndicatorValue(i.code(), i.name(), value, "unknown", source, reason);
    }

    Object normalize(IndicatorDefinition i, Object raw) {
        String type=Objects.toString(i.valueType(), "").toUpperCase(Locale.ROOT);
        String domain=Objects.toString(i.valueDomain(), "").toLowerCase(Locale.ROOT);
        if (type.contains("NUMBER") || type.contains("NUMERIC")) {
            BigDecimal n=new BigDecimal(raw.toString().trim()).stripTrailingZeros();
            if (!Double.isFinite(n.doubleValue()) || (domain.contains("integer") && n.scale()>0)) throw new IllegalArgumentException("需要有限数值或整数");
            var m=java.util.regex.Pattern.compile("range\\s*=\\s*(-?\\d+(?:\\.\\d+)?)\\s*\\.\\.\\s*(-?\\d+(?:\\.\\d+)?)").matcher(domain);
            if(m.find() && (n.compareTo(new BigDecimal(m.group(1)))<0 || n.compareTo(new BigDecimal(m.group(2)))>0)) throw new IllegalArgumentException("超出配置范围");
            return n;
        }
        if (type.equals("BOOLEAN")) {
            if(!Set.of("true","false").contains(raw.toString().toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("需要 boolean");
            return Boolean.valueOf(raw.toString());
        }
        Set<String> options=new LinkedHashSet<>(); i.options().forEach(o->options.add(o.valueLabel()));
        if(type.equals("MULTI_SELECT")) {
            if(!(raw instanceof Collection<?> list) || options.isEmpty()) throw new IllegalArgumentException("需要已配置的枚举数组");
            LinkedHashSet<String> result=new LinkedHashSet<>();
            for(Object v:list) {
                String value=canonicalOptionLabel(String.valueOf(v), options);
                if(value==null) throw new IllegalArgumentException("枚举值越界或未配置选项");
                result.add(value);
            }
            return List.copyOf(result);
        }
        if(type.equals("SINGLE_SELECT")) {
            String value=raw.toString().trim();
            String mapped=switch(value.toLowerCase(Locale.ROOT)) { case "present","true","active" -> "存在"; case "absent","false" -> "不存在"; case "unknown","uncertain" -> "未知"; default -> value; };
            String canonical=canonicalOptionLabel(value, options);
            if(canonical!=null) return canonical;
            canonical=canonicalOptionLabel(mapped, options);
            if(canonical!=null) return canonical;
            throw new IllegalArgumentException("枚举值越界或未配置选项");
        }
        if(domain.startsWith("object") && !(raw instanceof Map)) throw new IllegalArgumentException("需要 object");
        if(domain.startsWith("array") && !(raw instanceof Collection)) throw new IllegalArgumentException("需要 array");
        if(domain.startsWith("string")) {
            if (!(raw instanceof String text)) throw new IllegalArgumentException("需要 string");
            var max=java.util.regex.Pattern.compile("maxlength\s*=\s*(\\d+)").matcher(domain);
            if(max.find() && text.length()>Integer.parseInt(max.group(1))) throw new IllegalArgumentException("超过最大文本长度");
        }
        return raw;
    }

    /** Return the exact database label when the model differs only in whitespace. */
    private String canonicalOptionLabel(String input, Set<String> options) {
        if(options.contains(input)) return input;
        String normalized=withoutWhitespace(input);
        String match=null;
        for(String option:options) {
            if(!withoutWhitespace(option).equals(normalized)) continue;
            if(match!=null) return null;
            match=option;
        }
        return match;
    }

    private String withoutWhitespace(String value) {
        return value.codePoints()
                .filter(codePoint -> !Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint))
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
    }
    private Object fallback(IndicatorDefinition i,String status) {
        Object configured = configuredDefault(i);
        if (configured != null) {
            return configured;
        }
        if(!"SINGLE_SELECT".equals(i.valueType())) return null;
        var options=i.options().stream().map(IndicatorOption::valueLabel).toList();
        for(String v: status.equals("not_applicable") ? List.of("不适用","not_applicable","未知") : List.of("未知","证据不足","未评估","unknown")) if(options.contains(v)) return v;
        return null;
    }

    private Object configuredDefault(IndicatorDefinition i) {
        String configured = Objects.toString(i.defaultValue(), "").trim();
        if (!configured.isBlank()) {
            try {
                return normalize(i, configured);
            } catch (IllegalArgumentException ignored) {
                return configured;
            }
        }
        if ("MULTI_SELECT".equals(i.valueType())) {
            List<String> defaults = i.options().stream()
                    .filter(IndicatorOption::defaultValue)
                    .map(IndicatorOption::valueLabel)
                    .toList();
            return defaults.isEmpty() ? null : defaults;
        }
        return i.options().stream()
                .filter(IndicatorOption::defaultValue)
                .map(IndicatorOption::valueLabel)
                .findFirst()
                .orElse(null);
    }
}
