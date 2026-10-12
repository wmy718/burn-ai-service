package com.nytryx.burnai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nytryx.burnai.model.DecisionEdgeDefinition;
import com.nytryx.burnai.model.DecisionNodeDefinition;
import com.nytryx.burnai.model.DecisionTreeDefinition;
import com.nytryx.burnai.model.DecisionTreeExecutionRequest;
import com.nytryx.burnai.model.DecisionTreeExecutionResult;
import com.nytryx.burnai.model.DecisionTreeExecutionResult.EdgeCandidate;
import com.nytryx.burnai.model.DecisionTreeExecutionResult.ModelDecisionRequest;
import com.nytryx.burnai.model.DecisionTreeExecutionResult.ModelDecisionTrace;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class DecisionTreeRuntimeService {
    private static final Set<String> MODEL_EXECUTION_MODES = Set.of("MODEL_EDGE_SELECTION", "MODEL_SET_SELECTION");

    private final DeterministicFormulaExecutor formulaExecutor;
    private final EdgeSelectionModel edgeSelectionModel;
    private final DataDrivenNodeLogicService dataDrivenNodeLogicService;
    private final ObjectMapper objectMapper;
    private final TextConditionEvaluationService textEvaluator;

    public DecisionTreeRuntimeService(
            DeterministicFormulaExecutor formulaExecutor,
            EdgeSelectionModel edgeSelectionModel,
            DataDrivenNodeLogicService dataDrivenNodeLogicService,
            ObjectMapper objectMapper,
            TextConditionEvaluationService textEvaluator
    ) {
        this.formulaExecutor = formulaExecutor;
        this.edgeSelectionModel = edgeSelectionModel;
        this.dataDrivenNodeLogicService = dataDrivenNodeLogicService;
        this.objectMapper = objectMapper;
        this.textEvaluator = textEvaluator;
    }

    public DecisionTreeExecutionResult execute(DecisionTreeDefinition tree, DecisionTreeExecutionRequest request) {
        Map<String, Object> values = request.indicatorValues() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(request.indicatorValues());
        Map<String, Object> evidence = request.indicatorEvidence() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(request.indicatorEvidence());
        Map<String, String> edgeSelections = request.edgeSelections() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(request.edgeSelections());
        Map<String, List<String>> edgeSetSelections = request.edgeSetSelections() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(request.edgeSetSelections());
        List<ModelDecisionTrace> traces = new ArrayList<>();

        DecisionTreeExecutionResult current = null;
        for (int round = 0; round < 64; round++) {
            current = interpret(tree, new DecisionTreeExecutionRequest(values, evidence, edgeSelections, edgeSetSelections));
            if (!"AWAITING_MODEL_DECISION".equals(current.status())) {
                return withTraces(current, traces, current.status(), current.validationErrors());
            }
            boolean progressed = false;
            for (ModelDecisionRequest pending : current.pendingDecisions()) {
                if (!MODEL_EXECUTION_MODES.contains(pending.executionMode())) {
                    return failure(current, traces, "节点 " + pending.nodeKey() + " 的运行模式不能交给模型选边：" + pending.executionMode());
                }
                boolean setSelection = "MODEL_SET_SELECTION".equals(pending.executionMode());
                if ((!setSelection && edgeSelections.containsKey(pending.nodeKey()))
                        || (setSelection && edgeSetSelections.containsKey(pending.nodeKey()))) {
                    continue;
                }
                EdgeSelectionModel.Selection selection = edgeSelectionModel.select(pending);
                if (setSelection) {
                    edgeSetSelections.put(pending.nodeKey(), selection.edgeKeys());
                } else if (selection.edgeKey() != null) {
                    edgeSelections.put(pending.nodeKey(), selection.edgeKey());
                }
                traces.add(new ModelDecisionTrace(
                        pending.nodeKey(),
                        selection.edgeKey(),
                        selection.edgeKeys(),
                        selection.reasoning(),
                        selection.evidenceCodes(),
                        selection.confidence(),
                        selection.model(),
                        selection.promptTokens(),
                        selection.completionTokens()
                ));
                progressed = true;
            }
            if (!progressed) {
                return failure(current, traces, "模型选边没有产生新的节点选择，已停止运行");
            }
        }
        return failure(current, traces, "模型选边超过最大轮数，已停止运行");
    }

    private DecisionTreeExecutionResult interpret(DecisionTreeDefinition tree, DecisionTreeExecutionRequest request) {
        List<String> graphErrors = validateTree(tree);
        if (!graphErrors.isEmpty()) {
            return result("INVALID_GRAPH", tree, List.of(), Map.of(), List.of(), List.of(), graphErrors);
        }
        List<DecisionNodeDefinition> nodes = tree.nodes();
        List<DecisionEdgeDefinition> edges = tree.edges();
        Map<Long, DecisionNodeDefinition> nodeMap = nodes.stream()
                .collect(Collectors.toMap(DecisionNodeDefinition::id, Function.identity()));
        Map<Long, List<DecisionEdgeDefinition>> outgoing = edges.stream()
                .collect(Collectors.groupingBy(DecisionEdgeDefinition::sourceNodeId, LinkedHashMap::new, Collectors.toList()));
        Set<Long> incomingTargets = edges.stream().map(DecisionEdgeDefinition::targetNodeId).collect(Collectors.toSet());
        Deque<DecisionNodeDefinition> queue = nodes.stream()
                .filter(node -> "INDICATOR".equals(node.nodeType()))
                .filter(node -> !incomingTargets.contains(node.id()))
                .sorted(Comparator.comparing(DecisionNodeDefinition::sortOrder).thenComparing(DecisionNodeDefinition::id))
                .collect(Collectors.toCollection(ArrayDeque::new));

        Map<String, Object> values = new LinkedHashMap<>(request.indicatorValues());
        Map<String, Object> evidence = new LinkedHashMap<>(request.indicatorEvidence());
        Map<String, String> edgeSelections = request.edgeSelections();
        Map<String, List<String>> edgeSetSelections = request.edgeSetSelections();
        Set<Long> visitedIds = new LinkedHashSet<>();
        List<String> visitedNodeKeys = new ArrayList<>();
        Map<String, Object> outputs = new LinkedHashMap<>();
        List<Map<String, Object>> actions = new ArrayList<>();
        List<ModelDecisionRequest> pending = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        while (!queue.isEmpty()) {
            DecisionNodeDefinition node = queue.removeFirst();
            if (!visitedIds.add(node.id())) continue;
            visitedNodeKeys.add(node.nodeKey());
            List<DecisionEdgeDefinition> nextEdges = outgoing.getOrDefault(node.id(), List.of()).stream()
                    .sorted(Comparator.comparing(DecisionEdgeDefinition::priorityOrder).thenComparing(DecisionEdgeDefinition::id))
                    .toList();

            if ("INDICATOR".equals(node.nodeType())) {
                if (hasPrecomputedFormula(node) && MODEL_EXECUTION_MODES.contains(nullToEmpty(node.executionMode()))) {
                    DeterministicFormulaExecutor.FormulaResult formula = formulaExecutor.compute(node, values);
                    if (formula.success()) {
                        values.put(node.indicatorCode(), formula.value());
                        evidence.put(node.indicatorCode(), Map.of("status", "calculated", "value", formula.value()));
                    }
                }
                if ("DATA_DRIVEN_RULE".equals(node.executionMode())) {
                    DataDrivenNodeLogicService.LogicResult logic = dataDrivenNodeLogicService.evaluate(node, values);
                    if (!logic.success()) {
                        errors.add("节点 " + node.nodeKey() + "：" + logic.error());
                        continue;
                    }
                    values.put(node.indicatorCode(), logic.value());
                    evidence.put(node.indicatorCode(), Map.of(
                            "status", "calculated",
                            "value", logic.value(),
                            "method", "data_driven_rule",
                            "explanation", logic.explanation() == null ? "" : logic.explanation()
                    ));
                    DecisionEdgeDefinition ruleEdge = resolveFormulaEdge(nextEdges, nodeMap, logic.value());
                    if (ruleEdge == null) {
                        errors.add("节点 " + node.nodeKey() + " 没有与规则输出匹配的候选边：" + logic.value());
                        continue;
                    }
                    queue.addLast(nodeMap.get(ruleEdge.targetNodeId()));
                    continue;
                }
                if ("DETERMINISTIC_FORMULA".equals(node.executionMode())) {
                    DeterministicFormulaExecutor.FormulaResult formula = formulaExecutor.compute(node, values);
                    if (!formula.success()) {
                        errors.add("节点 " + node.nodeKey() + "：" + formula.error());
                        continue;
                    }
                    values.put(node.indicatorCode(), formula.value());
                    DecisionEdgeDefinition formulaEdge = resolveFormulaEdge(nextEdges, nodeMap, formula.value());
                    if (formulaEdge == null) {
                        errors.add("节点 " + node.nodeKey() + " 没有与公式结果匹配的候选边：" + formula.value());
                        continue;
                    }
                    queue.addLast(nodeMap.get(formulaEdge.targetNodeId()));
                    continue;
                }
                if ("MODEL_SET_SELECTION".equals(node.executionMode())) {
                    if (!edgeSetSelections.containsKey(node.nodeKey())) {
                        pending.add(toModelDecisionRequest(node, nextEdges, nodeMap, values, evidence));
                        continue;
                    }
                    LinkedHashSet<String> selectedKeys = new LinkedHashSet<>(
                            edgeSetSelections.getOrDefault(node.nodeKey(), List.of()));
                    Map<String, DecisionEdgeDefinition> candidates = nextEdges.stream()
                            .collect(Collectors.toMap(DecisionEdgeDefinition::edgeKey, Function.identity(), (l, r) -> l, LinkedHashMap::new));
                    List<String> selectedValues = new ArrayList<>();
                    for (String key : selectedKeys) {
                        DecisionEdgeDefinition selected = candidates.get(key);
                        if (selected == null) continue;
                        DecisionNodeDefinition target = nodeMap.get(selected.targetNodeId());
                        if (target != null) {
                            selectedValues.add(target.valueCode());
                            queue.addLast(target);
                        }
                    }
                    outputs.put(node.indicatorCode(), List.copyOf(selectedValues));
                    values.put(node.indicatorCode(), List.copyOf(selectedValues));
                    continue;
                }
                String selectedEdgeKey = edgeSelections.get(node.nodeKey());
                DecisionEdgeDefinition selected = selectedEdgeKey == null || selectedEdgeKey.isBlank()
                        ? resolveInputValueEdge(node, nextEdges, values, nodeMap)
                        : nextEdges.stream().filter(edge -> edge.edgeKey().equals(selectedEdgeKey)).findFirst().orElse(null);
                if (selected == null && (selectedEdgeKey == null || selectedEdgeKey.isBlank())) {
                    selected = resolveStructuredJudgmentEdge(nextEdges, nodeMap, values);
                }
                if (selected == null && (selectedEdgeKey == null || selectedEdgeKey.isBlank())) {
                    if (hasAnyAvailableInput(node, values)) {
                        pending.add(toModelDecisionRequest(node, nextEdges, nodeMap, values, evidence));
                    }
                    continue;
                }
                if (selected != null) queue.addLast(nodeMap.get(selected.targetNodeId()));
                continue;
            }

            collectOutput(node, outputs, values, errors);
            collectActions(node, actions, errors);
            for (DecisionEdgeDefinition edge : nextEdges) {
                DecisionNodeDefinition target = nodeMap.get(edge.targetNodeId());
                if (target != null) queue.addLast(target);
            }
        }

        String status = !errors.isEmpty() ? "EXECUTION_REJECTED" : (!pending.isEmpty() ? "AWAITING_MODEL_DECISION" : "COMPLETED");
        return result(status, tree, visitedNodeKeys, outputs, actions, pending, errors);
    }

    private List<String> validateTree(DecisionTreeDefinition tree) {
        return new DecisionTreeGraphService(dataDrivenNodeLogicService, objectMapper).validateTree(tree);
    }

    private DecisionEdgeDefinition resolveFormulaEdge(List<DecisionEdgeDefinition> edges, Map<Long, DecisionNodeDefinition> nodeMap, Object result) {
        DecisionEdgeDefinition exact = findEdgeByTargetValue(edges, nodeMap, Set.of(String.valueOf(result)));
        return exact != null ? exact : (edges.size() == 1 ? edges.get(0) : null);
    }

    private DecisionEdgeDefinition resolveStructuredJudgmentEdge(
            List<DecisionEdgeDefinition> edges,
            Map<Long, DecisionNodeDefinition> nodeMap,
            Map<String, Object> values
    ) {
        List<DecisionEdgeDefinition> met = new ArrayList<>();
        for (DecisionEdgeDefinition edge : edges) {
            DecisionNodeDefinition judgment = nodeMap.get(edge.targetNodeId());
            if (judgment == null || isBlank(judgment.logicDefinitionJson())) {
                continue;
            }
            Truth truth = evaluateConditionGroups(judgment.logicDefinitionJson(), values);
            if (truth == Truth.MET) {
                met.add(edge);
            }
        }
        // Edges arrive in configured priority order. Clinical rule cards may intentionally overlap
        // (for example a flat linear scar also satisfies the broader linear-shape rule). In that
        // case the configured priority is the deterministic tie-breaker; asking the model to guess
        // can select a clinically unrelated branch.
        if (!met.isEmpty()) {
            DecisionEdgeDefinition severityEdge = highestSeverityEdge(met, nodeMap);
            return severityEdge != null ? severityEdge : met.get(0);
        }
        return null;
    }

    private DecisionEdgeDefinition highestSeverityEdge(
            List<DecisionEdgeDefinition> met,
            Map<Long, DecisionNodeDefinition> nodeMap
    ) {
        if (met.size() < 2) return null;
        List<DecisionNodeDefinition> targets = met.stream().map(edge -> nodeMap.get(edge.targetNodeId())).toList();
        String indicatorCode = targets.get(0) == null ? null : targets.get(0).indicatorCode();
        if (!"pathological_scar_risk_level".equals(indicatorCode)
                || targets.stream().anyMatch(target -> target == null || !Objects.equals(indicatorCode, target.indicatorCode()))) {
            return null;
        }
        Map<String, Integer> rank = Map.of("低", 1, "中等", 2, "高", 3, "极高", 4);
        return met.stream().max(Comparator.comparingInt(edge -> {
            DecisionNodeDefinition target = nodeMap.get(edge.targetNodeId());
            return rank.getOrDefault(target == null ? null : target.valueCode(), 0);
        })).orElse(null);
    }

    private boolean hasAnyAvailableInput(DecisionNodeDefinition node, Map<String, Object> values) {
        List<String> inputs = parseStringList(node.inputIndicatorCodes());
        if (inputs.isEmpty()) {
            return values.containsKey(node.indicatorCode());
        }
        return inputs.stream().anyMatch(code -> values.get(code) != null);
    }

    private DecisionEdgeDefinition resolveInputValueEdge(
            DecisionNodeDefinition node,
            List<DecisionEdgeDefinition> edges,
            Map<String, Object> values,
            Map<Long, DecisionNodeDefinition> nodeMap
    ) {
        if (Set.of("DECISION", "JUDGMENT", "OUTPUT_INDICATOR").contains(node.indicatorStage())) return null;
        Object rawValue = values.get(node.indicatorCode());
        if (rawValue == null) return findEdgeByTargetValue(edges, nodeMap, Set.of("$missing", "未知", "未评估", "unknown", "not_assessed"));
        String normalized = String.valueOf(rawValue).trim();
        DecisionEdgeDefinition exact = findEdgeByTargetValue(edges, nodeMap, Set.of(normalized));
        if (exact != null) return exact;
        if (rawValue instanceof Number || rawValue instanceof Boolean || rawValue instanceof Collection<?> || rawValue instanceof Map<?, ?>) {
            return findEdgeByTargetValue(edges, nodeMap, Set.of("$recorded"));
        }
        DecisionEdgeDefinition recorded = findEdgeByTargetValue(edges, nodeMap, Set.of("$recorded"));
        return recorded != null ? recorded : findEdgeByTargetValue(edges, nodeMap, Set.of("$missing"));
    }

    private DecisionEdgeDefinition findEdgeByTargetValue(List<DecisionEdgeDefinition> edges, Map<Long, DecisionNodeDefinition> nodeMap, Set<String> acceptedValues) {
        return edges.stream()
                .filter(edge -> {
                    DecisionNodeDefinition target = nodeMap.get(edge.targetNodeId());
                    return target != null && acceptedValues.contains(target.valueCode());
                })
                .findFirst()
                .orElse(null);
    }

    private ModelDecisionRequest toModelDecisionRequest(
            DecisionNodeDefinition node,
            List<DecisionEdgeDefinition> edges,
            Map<Long, DecisionNodeDefinition> nodeMap,
            Map<String, Object> values,
            Map<String, Object> evidence
    ) {
        List<String> inputCodes = parseStringList(node.inputIndicatorCodes());
        Map<String, Object> scopedValues = new LinkedHashMap<>();
        Map<String, Object> scopedEvidence = new LinkedHashMap<>();
        for (String code : inputCodes) {
            scopedValues.put(code, values.get(code));
            if (evidence.containsKey(code)) scopedEvidence.put(code, evidence.get(code));
        }
        List<EdgeCandidate> candidates = edges.stream().map(edge -> {
            DecisionNodeDefinition judgment = nodeMap.get(edge.targetNodeId());
            List<String> dependsOn = judgment == null || parseStringList(judgment.inputIndicatorCodes()).isEmpty()
                    ? inputCodes
                    : parseStringList(judgment.inputIndicatorCodes());
            return new EdgeCandidate(
                    edge.edgeKey(),
                    judgment == null ? edge.branchLabel() : judgment.title(),
                    judgment == null ? edge.conditionExpression() : judgment.conditionExpression(),
                    edge.priorityOrder(),
                    dependsOn
            );
        }).toList();
        Map<String, Object> promptPayload = new LinkedHashMap<>();
        promptPayload.put("task", "只能从 candidates 中选择 edgeKey；不得生成新指标或新结论");
        promptPayload.put("nodeKey", node.nodeKey());
        promptPayload.put("indicatorCode", node.indicatorCode());
        promptPayload.put("inputValues", scopedValues);
        promptPayload.put("inputEvidence", scopedEvidence);
        promptPayload.put("candidates", candidates);
        promptPayload.put("responseSchema", "单选返回 {\"edgeKey\":\"...\",\"reasoning\":\"...\",\"evidenceCodes\":[...],\"confidence\":\"high|moderate|low\"}；多选返回 edgeKeys");
        String prompt;
        try {
            prompt = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(promptPayload);
        } catch (JsonProcessingException exception) {
            prompt = promptPayload.toString();
        }
        return new ModelDecisionRequest(node.nodeKey(), node.title(), node.executionMode(), scopedValues, candidates, prompt);
    }

    private void collectOutput(DecisionNodeDefinition node, Map<String, Object> outputs, Map<String, Object> values, List<String> errors) {
        if (isBlank(node.outputPayloadJson())) return;
        try {
            Map<String, Object> payload = objectMapper.readValue(node.outputPayloadJson(), new TypeReference<>() {});
            Object code = payload.get("indicatorCode");
            if (code != null) {
                String indicatorCode = String.valueOf(code);
                Object value = payload.get("value");
                if (value instanceof Map<?, ?> map && "deterministic_formula".equals(map.get("valueSource"))) {
                    if (!values.containsKey(indicatorCode)) {
                        errors.add("节点 " + node.nodeKey() + " 需要公式结果，但当前输入不足");
                        return;
                    }
                    value = values.get(indicatorCode);
                }
                Object existing = outputs.get(indicatorCode);
                if (existing instanceof Collection<?> collection && !(value instanceof Collection<?>)) {
                    List<Object> merged = new ArrayList<>(collection);
                    if (value != null && !merged.contains(value)) merged.add(value);
                    value = List.copyOf(merged);
                }
                outputs.put(indicatorCode, value);
                values.put(indicatorCode, value);
            }
        } catch (JsonProcessingException exception) {
            errors.add("节点 " + node.nodeKey() + " 的输出 JSON 无法解析");
        }
    }

    private void collectActions(DecisionNodeDefinition node, List<Map<String, Object>> actions, List<String> errors) {
        if (isBlank(node.actionPayloadJson())) return;
        try {
            actions.addAll(objectMapper.readValue(node.actionPayloadJson(), new TypeReference<>() {}));
        } catch (JsonProcessingException exception) {
            errors.add("节点 " + node.nodeKey() + " 的治疗措施 JSON 无法解析");
        }
    }

    private boolean hasPrecomputedFormula(DecisionNodeDefinition node) {
        if (isBlank(node.logicDefinitionJson())) return false;
        try {
            return "PRECOMPUTED_FORMULA".equals(objectMapper.readTree(node.logicDefinitionJson()).path("kind").asText());
        } catch (Exception ignored) {
            return false;
        }
    }

    private Truth evaluateConditionGroups(String json, Map<String, Object> values) {
        try {
            var root = objectMapper.readTree(json);
            if (!"CONDITION_GROUPS".equals(root.path("kind").asText()) || !root.path("groups").isArray()) {
                return Truth.UNKNOWN;
            }
            boolean hasUnknown = false;
            for (var group : root.path("groups")) {
                Truth groupTruth = Truth.MET;
                for (var condition : group.path("conditions")) {
                    Truth row = evaluateCondition(condition, values);
                    if (row == Truth.NOT_MET) {
                        groupTruth = Truth.NOT_MET;
                        break;
                    }
                    if (row == Truth.UNKNOWN) {
                        groupTruth = Truth.UNKNOWN;
                    }
                }
                if (groupTruth == Truth.MET) {
                    return Truth.MET;
                }
                if (groupTruth == Truth.UNKNOWN) {
                    hasUnknown = true;
                }
            }
            return hasUnknown ? Truth.UNKNOWN : Truth.NOT_MET;
        } catch (Exception ignored) {
            return Truth.UNKNOWN;
        }
    }

    private Truth evaluateCondition(com.fasterxml.jackson.databind.JsonNode condition, Map<String, Object> values) {
        if (TextConditionEvaluationService.isText(condition.path("mode").asText()) || TextConditionEvaluationService.isText(condition.path("operator").asText())) {
            Map<String,Object> scoped=new LinkedHashMap<>();
            String code=condition.path("indicatorCode").asText();
            if("INDICATOR".equals(condition.path("mode").asText()) && !code.isBlank()) scoped.put(code,values.get(code)); else scoped.putAll(values);
            return Truth.valueOf(textEvaluator.truth(condition.path("text").asText(),scoped));
        }
        if (!"INDICATOR".equals(condition.path("mode").asText())) {
            return Truth.UNKNOWN;
        }
        String code = condition.path("indicatorCode").asText();
        String operator = condition.path("operator").asText();
        Object actual = values.get(code);
        boolean available = actual != null && !String.valueOf(actual).isBlank();
        if ("IS_AVAILABLE".equals(operator)) {
            return available ? Truth.MET : Truth.UNKNOWN;
        }
        if ("IS_MISSING".equals(operator)) {
            return available ? Truth.NOT_MET : Truth.UNKNOWN;
        }
        if (!available) {
            return Truth.UNKNOWN;
        }
        if ("IS_NOT_EMPTY".equals(operator) || "IS_EMPTY".equals(operator)) {
            boolean empty = semanticallyEmptyValue(actual);
            return empty == "IS_EMPTY".equals(operator) ? Truth.MET : Truth.NOT_MET;
        }
        Object expectedRaw = objectMapper.convertValue(condition.get("expectedValue"), Object.class);
        String expected = expectedRaw == null ? "" : String.valueOf(expectedRaw);
        if ("CONTAINS".equals(operator) || "NOT_CONTAINS".equals(operator)) {
            boolean contains = actual instanceof Collection<?> collection
                    ? collection.stream().map(String::valueOf).anyMatch(expected::equals)
                    : String.valueOf(actual).contains(expected);
            return contains == "CONTAINS".equals(operator) ? Truth.MET : Truth.NOT_MET;
        }
        if (Set.of("GT", "GTE", "LT", "LTE").contains(operator)) {
            try {
                double left = Double.parseDouble(String.valueOf(actual));
                double right = Double.parseDouble(expected);
                boolean met = switch (operator) {
                    case "GT" -> left > right;
                    case "GTE" -> left >= right;
                    case "LT" -> left < right;
                    default -> left <= right;
                };
                return met ? Truth.MET : Truth.NOT_MET;
            } catch (NumberFormatException ignored) {
                return Truth.UNKNOWN;
            }
        }
        boolean equal = String.valueOf(actual).equals(expected);
        if ("EQ".equals(operator)) {
            return equal ? Truth.MET : Truth.NOT_MET;
        }
        if ("NE".equals(operator)) {
            return equal ? Truth.NOT_MET : Truth.MET;
        }
        return Truth.UNKNOWN;
    }

    private boolean semanticallyEmpty(Collection<?> collection) {
        if (collection.isEmpty()) return true;
        Set<String> explicitNone = Set.of("明确无", "无", "不存在", "否", "none", "无相关因素");
        return collection.stream().map(String::valueOf).map(String::trim).allMatch(explicitNone::contains);
    }

    private boolean semanticallyEmptyValue(Object value) {
        if (value instanceof Collection<?> collection) return semanticallyEmpty(collection);
        if (value instanceof Map<?, ?> map) return map.isEmpty();
        String text = String.valueOf(value).trim();
        return text.isBlank() || Set.of("明确无", "无", "不存在", "否", "none", "无相关因素").contains(text);
    }

    private enum Truth {
        MET,
        NOT_MET,
        UNKNOWN
    }

    private List<String> parseStringList(String json) {
        if (isBlank(json)) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private DecisionTreeExecutionResult failure(DecisionTreeExecutionResult source, List<ModelDecisionTrace> traces, String message) {
        List<String> errors = new ArrayList<>(source == null ? List.of() : source.validationErrors());
        errors.add(message);
        return withTraces(source, traces, "MODEL_EXECUTION_FAILED", errors);
    }

    private DecisionTreeExecutionResult withTraces(DecisionTreeExecutionResult source, List<ModelDecisionTrace> traces, String status, List<String> errors) {
        if (source == null) {
            return new DecisionTreeExecutionResult(status, 0, null, List.of(), Map.of(), List.of(), List.of(), List.copyOf(traces), errors);
        }
        return new DecisionTreeExecutionResult(status, source.treeId(), source.treeCode(), source.visitedNodeKeys(),
                source.decisionOutputs(), source.treatmentActions(), source.pendingDecisions(), List.copyOf(traces), errors);
    }

    private DecisionTreeExecutionResult result(
            String status,
            DecisionTreeDefinition tree,
            List<String> visited,
            Map<String, Object> outputs,
            List<Map<String, Object>> actions,
            List<ModelDecisionRequest> pending,
            List<String> errors
    ) {
        return new DecisionTreeExecutionResult(status, tree.id(), tree.code(), visited, outputs, actions, pending, List.of(), errors);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
