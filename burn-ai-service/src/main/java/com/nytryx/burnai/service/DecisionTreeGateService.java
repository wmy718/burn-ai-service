package com.nytryx.burnai.service;

import com.nytryx.burnai.model.DecisionTreeDefinition;
import com.nytryx.burnai.model.DecisionTreeDependencyConditionDefinition;
import com.nytryx.burnai.model.DecisionTreeDependencyDefinition;
import com.nytryx.burnai.model.IndicatorDefinition;
import com.nytryx.burnai.model.IndicatorGroupItemDefinition;
import com.nytryx.burnai.repo.DecisionConfigRepository.ConfigSnapshot;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class DecisionTreeGateService {
    private final TextConditionEvaluationService textEvaluator;
    public DecisionTreeGateService(TextConditionEvaluationService textEvaluator) { this.textEvaluator=textEvaluator; }


    public GateResult evaluateTrigger(DecisionTreeDefinition tree, ConfigSnapshot snapshot, Map<String, Object> facts) {
        if (tree.indicatorGroupId() == null) {
            return GateResult.run("未配置进入指标组，直接运行");
        }
        List<IndicatorGroupItemDefinition> items = snapshot.groupItems().stream()
                .filter(item -> Objects.equals(item.groupId(), tree.indicatorGroupId()))
                .sorted(Comparator.comparing(IndicatorGroupItemDefinition::cardGroup)
                        .thenComparing(IndicatorGroupItemDefinition::sortOrder)
                        .thenComparing(IndicatorGroupItemDefinition::id))
                .toList();
        if (items.isEmpty()) {
            return GateResult.run("进入指标组为空，直接运行");
        }
        Map<Long, IndicatorDefinition> indicatorById = snapshot.indicators().stream()
                .collect(Collectors.toMap(IndicatorDefinition::id, i -> i, (l, r) -> l, LinkedHashMap::new));
        Truth truth = evaluateCardItems(items, indicatorById, facts);
        return switch (truth) {
            case MET -> GateResult.run("进入条件满足");
            case NOT_MET -> GateResult.skip("进入条件不满足");
            case UNKNOWN -> GateResult.skip("进入条件证据不足");
        };
    }

    public GateResult evaluatePrerequisite(
            DecisionTreeDefinition tree,
            ConfigSnapshot snapshot,
            Map<String, Object> facts,
            Map<Long, String> treeStatuses
    ) {
        List<DecisionTreeDependencyDefinition> deps = snapshot.treeDependencies().stream()
                .filter(dep -> dep.treeId() == tree.id())
                .sorted(Comparator.comparing(DecisionTreeDependencyDefinition::sortOrder)
                        .thenComparing(DecisionTreeDependencyDefinition::id))
                .toList();
        if (deps.isEmpty()) {
            return GateResult.run("无前置树限制");
        }
        Map<Long, DecisionTreeDefinition> treeById = snapshot.trees().stream()
                .collect(Collectors.toMap(DecisionTreeDefinition::id, t -> t, (l, r) -> l, LinkedHashMap::new));
        Map<Long, List<DecisionTreeDependencyConditionDefinition>> conditionsByDep = snapshot.dependencyConditions().stream()
                .collect(Collectors.groupingBy(DecisionTreeDependencyConditionDefinition::dependencyId, LinkedHashMap::new, Collectors.toList()));
        List<String> reasons = new ArrayList<>();
        boolean unknown = false;
        for (DecisionTreeDependencyDefinition dep : deps) {
            DecisionTreeDefinition prerequisite = treeById.get(dep.prerequisiteTreeId());
            String prerequisiteName = prerequisite == null ? String.valueOf(dep.prerequisiteTreeId()) : prerequisite.name();
            String status = treeStatuses.getOrDefault(dep.prerequisiteTreeId(), "PENDING");
            List<DecisionTreeDependencyConditionDefinition> cardConditions = conditionsByDep.getOrDefault(dep.id(), List.of());
            boolean completionOnly = cardConditions.isEmpty()
                    && (dep.conditionMode() == null || dep.conditionMode().isBlank() || "COMPLETION".equals(dep.conditionMode()));
            if ("PENDING".equals(status)) {
                unknown = true;
                reasons.add("前置树“" + prerequisiteName + "”尚未处理");
                continue;
            }
            if ("SKIPPED".equals(status) && completionOnly) {
                reasons.add("前置树“" + prerequisiteName + "”未触发，视为已处理");
                continue;
            }
            if ("SKIPPED".equals(status)) {
                unknown = true;
                reasons.add("前置树“" + prerequisiteName + "”未触发，无法满足指标依赖");
                continue;
            }
            if (!"COMPLETED".equals(status) && !"EXECUTION_REJECTED".equals(status) && !"MODEL_EXECUTION_FAILED".equals(status)) {
                unknown = true;
                reasons.add("前置树“" + prerequisiteName + "”状态为" + status);
                continue;
            }
            if (!cardConditions.isEmpty()) {
                Truth truth = evaluateDependencyConditions(cardConditions, facts);
                reasons.add("前置树“" + prerequisiteName + "”指标条件" + truth.text());
                if (truth == Truth.NOT_MET) return GateResult.skip(String.join("；", reasons));
                if (truth == Truth.UNKNOWN) unknown = true;
                continue;
            }
            if (dep.conditionMode() == null || dep.conditionMode().isBlank() || "COMPLETION".equals(dep.conditionMode())) {
                reasons.add("前置树“" + prerequisiteName + "”已完成");
                continue;
            }
            Truth truth = TextConditionEvaluationService.isText(dep.conditionMode()) || TextConditionEvaluationService.isText(dep.comparisonOperator())
                    ? Truth.valueOf(textEvaluator.truth(dep.conditionText(), facts))
                    : compare(dep.indicatorCode(), dep.comparisonOperator(), dep.expectedValue(), facts);
            reasons.add("前置树“" + prerequisiteName + "”指标条件" + truth.text());
            if (truth == Truth.NOT_MET) return GateResult.skip(String.join("；", reasons));
            if (truth == Truth.UNKNOWN) unknown = true;
        }
        return unknown ? GateResult.skip(String.join("；", reasons)) : GateResult.run(String.join("；", reasons));
    }

    private Truth evaluateCardItems(
            List<IndicatorGroupItemDefinition> items,
            Map<Long, IndicatorDefinition> indicatorById,
            Map<String, Object> facts
    ) {
        List<TextConditionEvaluationService.Request> textRequests=new ArrayList<>();
        for(var item:items) if(TextConditionEvaluationService.isText(item.comparisonOperator())) {
            var indicator=indicatorById.get(item.indicatorId());
            Map<String,Object> scoped=new LinkedHashMap<>();
            if(indicator==null) scoped.putAll(facts); else scoped.put(indicator.code(),facts.get(indicator.code()));
            textRequests.add(new TextConditionEvaluationService.Request("item:"+item.id(),item.expectedValue()==null?item.conditionExpression():item.expectedValue(),scoped));
        }
        var textResults=textEvaluator.evaluate(textRequests);
        Map<Integer, List<IndicatorGroupItemDefinition>> byCard = items.stream()
                .collect(Collectors.groupingBy(IndicatorGroupItemDefinition::cardGroup, LinkedHashMap::new, Collectors.toList()));
        boolean hasUnknown = false;
        for (List<IndicatorGroupItemDefinition> card : byCard.values()) {
            Truth cardTruth = Truth.MET;
            for (IndicatorGroupItemDefinition item : card) {
                IndicatorDefinition indicator = item.indicatorId() == null ? null : indicatorById.get(item.indicatorId());
                if (TextConditionEvaluationService.isText(item.comparisonOperator())) {
                    Truth row=Truth.valueOf(textResults.get("item:"+item.id()).result());
                    if(row==Truth.NOT_MET) { cardTruth=Truth.NOT_MET; break; }
                    if(row==Truth.UNKNOWN) cardTruth=Truth.UNKNOWN;
                    continue;
                }
                if (indicator == null || item.comparisonOperator() == null || item.comparisonOperator().isBlank()) {
                    cardTruth=Truth.UNKNOWN;
                    continue;
                }
                Truth condition = compare(indicator.code(), item.comparisonOperator(), item.expectedValue(), facts);
                if (condition == Truth.NOT_MET) {
                    cardTruth = Truth.NOT_MET;
                    break;
                }
                if (condition == Truth.UNKNOWN) cardTruth = Truth.UNKNOWN;
            }
            if (cardTruth == Truth.MET) return Truth.MET;
            if (cardTruth == Truth.UNKNOWN) hasUnknown = true;
        }
        return hasUnknown ? Truth.UNKNOWN : Truth.NOT_MET;
    }

    private Truth evaluateDependencyConditions(List<DecisionTreeDependencyConditionDefinition> conditions, Map<String, Object> facts) {
        var textRequests=conditions.stream()
                .filter(c -> TextConditionEvaluationService.isText(c.conditionMode()) || TextConditionEvaluationService.isText(c.comparisonOperator()))
                .map(c -> new TextConditionEvaluationService.Request("dependency:"+c.id(),c.conditionText(),facts)).toList();
        var textResults=textEvaluator.evaluate(textRequests);
        Map<Integer, List<DecisionTreeDependencyConditionDefinition>> byCard = conditions.stream()
                .collect(Collectors.groupingBy(DecisionTreeDependencyConditionDefinition::cardGroup, LinkedHashMap::new, Collectors.toList()));
        boolean hasUnknown = false;
        for (List<DecisionTreeDependencyConditionDefinition> card : byCard.values()) {
            Truth cardTruth = Truth.MET;
            for (DecisionTreeDependencyConditionDefinition condition : card) {
                if (TextConditionEvaluationService.isText(condition.conditionMode()) || TextConditionEvaluationService.isText(condition.comparisonOperator())) {
                    Truth row=Truth.valueOf(textResults.get("dependency:"+condition.id()).result());
                    if(row==Truth.NOT_MET) { cardTruth=Truth.NOT_MET; break; }
                    if(row==Truth.UNKNOWN) cardTruth=Truth.UNKNOWN;
                    continue;
                }
                Truth truth = compare(condition.indicatorCode(), condition.comparisonOperator(), condition.expectedValue(), facts);
                if (truth == Truth.NOT_MET) {
                    cardTruth = Truth.NOT_MET;
                    break;
                }
                if (truth == Truth.UNKNOWN) cardTruth = Truth.UNKNOWN;
            }
            if (cardTruth == Truth.MET) return Truth.MET;
            if (cardTruth == Truth.UNKNOWN) hasUnknown = true;
        }
        return hasUnknown ? Truth.UNKNOWN : Truth.NOT_MET;
    }

    private Truth compare(String code, String operator, String expected, Map<String, Object> facts) {
        if (code == null || code.isBlank()) return Truth.UNKNOWN;
        Object actual = facts.get(code);
        boolean available = actual != null && !String.valueOf(actual).isBlank();
        if (operator == null || operator.isBlank()) {
            return available ? Truth.MET : Truth.UNKNOWN;
        }
        if ("IS_AVAILABLE".equals(operator) || "EXISTS".equals(operator)) {
            return available ? Truth.MET : Truth.UNKNOWN;
        }
        if ("IS_NOT_EMPTY".equals(operator)) {
            return available && !isSemanticallyEmpty(actual) ? Truth.MET : Truth.UNKNOWN;
        }
        if ("IS_MISSING".equals(operator) || "MISSING".equals(operator)) {
            return !available ? Truth.MET : Truth.NOT_MET;
        }
        if ("IS_EMPTY".equals(operator)) {
            return !available || isSemanticallyEmpty(actual) ? Truth.MET : Truth.NOT_MET;
        }
        if (!available) return Truth.UNKNOWN;
        if ("CONTAINS".equals(operator) || "NOT_CONTAINS".equals(operator)) {
            boolean contains = contains(actual, expected);
            return contains == "CONTAINS".equals(operator) ? Truth.MET : Truth.NOT_MET;
        }
        if ("IN".equals(operator) || "NOT_IN".equals(operator)) {
            boolean contains = containsExpectedList(expected, String.valueOf(actual));
            return contains == "IN".equals(operator) ? Truth.MET : Truth.NOT_MET;
        }
        if (List.of("GT", "GTE", "LT", "LTE").contains(operator)) {
            BigDecimal left = number(actual);
            BigDecimal right = number(expected);
            if (left == null || right == null) return Truth.UNKNOWN;
            int cmp = left.compareTo(right);
            boolean met = switch (operator) {
                case "GT" -> cmp > 0;
                case "GTE" -> cmp >= 0;
                case "LT" -> cmp < 0;
                default -> cmp <= 0;
            };
            return met ? Truth.MET : Truth.NOT_MET;
        }
        boolean equal = String.valueOf(actual).trim().equals(String.valueOf(expected).trim());
        if ("EQ".equals(operator)) return equal ? Truth.MET : Truth.NOT_MET;
        if ("NE".equals(operator)) return equal ? Truth.NOT_MET : Truth.MET;
        return Truth.UNKNOWN;
    }

    private boolean isSemanticallyEmpty(Object value) {
        if (isEmptyCollection(value)) return true;
        if (value instanceof Collection<?> collection) {
            Set<String> explicitNone = Set.of("明确无", "无", "不存在", "否", "none", "无相关因素");
            return collection.stream().map(String::valueOf).map(String::trim).allMatch(explicitNone::contains);
        }
        String text = Objects.toString(value, "").trim();
        return Set.of("明确无", "无", "不存在", "否", "none", "无相关因素").contains(text);
    }

    private boolean contains(Object actual, String expected) {
        if (actual instanceof Collection<?> collection) {
            return collection.stream().map(String::valueOf).anyMatch(String.valueOf(expected)::equals);
        }
        return String.valueOf(actual).contains(String.valueOf(expected));
    }

    private boolean containsExpectedList(String expected, String actual) {
        if (expected == null) return false;
        String cleaned = expected.trim();
        if (cleaned.startsWith("[") && cleaned.endsWith("]")) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
        }
        for (String item : cleaned.split(",")) {
            String value = item.trim().replace("\"", "");
            if (value.equals(actual)) return true;
        }
        return false;
    }

    private boolean isEmptyCollection(Object actual) {
        return actual instanceof Collection<?> collection && collection.isEmpty();
    }

    private BigDecimal number(Object value) {
        try {
            return value == null ? null : new BigDecimal(String.valueOf(value).trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    private enum Truth {
        MET("满足"), NOT_MET("不满足"), UNKNOWN("证据不足");
        private final String text;
        Truth(String text) { this.text = text; }
        String text() { return text; }
    }

    public record GateResult(boolean runnable, String reason) {
        static GateResult run(String reason) { return new GateResult(true, reason); }
        static GateResult skip(String reason) { return new GateResult(false, reason); }
    }
}
