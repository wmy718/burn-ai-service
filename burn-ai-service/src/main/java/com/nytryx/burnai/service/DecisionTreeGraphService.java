package com.nytryx.burnai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import com.nytryx.burnai.model.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 决策图节点、边、根节点和无环结构校验。 */
@Service
public class DecisionTreeGraphService {

    private final DataDrivenNodeLogicService dataDrivenLogicService;
    private final ObjectMapper objectMapper;

    public DecisionTreeGraphService(
            DataDrivenNodeLogicService dataDrivenLogicService,
            ObjectMapper objectMapper
    ) {
        this.dataDrivenLogicService = dataDrivenLogicService;
        this.objectMapper = objectMapper;
    }

    public void validateNode(DecisionNodeDefinition node) {
        if ("INDICATOR".equals(node.nodeType())
                && isBlank(node.indicatorCode())) {
            throw badRequest("指标节点必须绑定指标编码");
        }
        if ("JUDGMENT".equals(node.nodeType())) {
            if (isBlank(node.indicatorCode())) {
                throw badRequest("判断节点必须归属于一个指标");
            }
            if (isBlank(node.conditionExpression())) {
                throw badRequest("判断节点必须填写判断条件");
            }
            validateJudgmentDefinition(node.logicDefinitionJson());
        }
        if ("INDICATOR_VALUE".equals(node.nodeType())
                && isBlank(node.valueCode())) {
            throw badRequest("指标值节点必须填写指标值编码");
        }
        if ("DETERMINISTIC_FORMULA".equals(node.executionMode()) && !new DeterministicFormulaExecutor(objectMapper).supports(node.indicatorCode())) throw badRequest("未注册确定性公式");
        dataDrivenLogicService.validate(node);
    }

    public List<String> validateTree(DecisionTreeDefinition tree) {
        List<DecisionNodeDefinition> nodes = tree.nodes();
        List<DecisionEdgeDefinition> edges = tree.edges();
        List<String> errors = new ArrayList<>();
        if (nodes.isEmpty()) {
            return List.of("决策树尚未构建节点");
        }

        Map<Long, DecisionNodeDefinition> nodeMap = nodes.stream()
                .collect(Collectors.toMap(DecisionNodeDefinition::id, Function.identity(), (a, b) -> a));
        if (nodeMap.size() != nodes.size()) errors.add("节点 ID 重复");
        Set<String> nodeKeys = new HashSet<>();
        for (DecisionNodeDefinition node : nodes) {
            try {
                validateNode(node);
                readCodes(node.inputIndicatorCodes(), "输入编码");
                validatePayload(node.outputPayloadJson(), false);
                validatePayload(node.actionPayloadJson(), true);
                if (expectedSelectorMode(node.executionMode()) == null) throw badRequest("执行模式无效");
                if (!Set.of("INDICATOR", "JUDGMENT", "INDICATOR_VALUE").contains(node.nodeType())) throw badRequest("节点类型无效");
            } catch (IllegalArgumentException ex) { errors.add(node.nodeKey() + ": " + ex.getMessage()); }

            if (!nodeKeys.add(node.nodeKey())) {
                errors.add("节点编码重复：" + node.nodeKey());
            }
            if ("INDICATOR".equals(node.nodeType())
                    && isBlank(node.indicatorCode())) {
                errors.add("指标节点未绑定指标编码：" + node.nodeKey());
            }
            if ("JUDGMENT".equals(node.nodeType())) {
                if (isBlank(node.conditionExpression())) {
                    errors.add("判断节点未填写条件：" + node.title());
                }
                try {
                    validateJudgmentDefinition(node.logicDefinitionJson());
                } catch (IllegalArgumentException exception) {
                    errors.add("判断节点条件卡片不完整：" + node.title());
                }
            }
            if ("INDICATOR_VALUE".equals(node.nodeType())
                    && isBlank(node.valueCode())) {
                errors.add("指标值节点未填写值编码：" + node.nodeKey());
            }
        }

        Set<String> edgeKeys = new HashSet<>();
        Map<Long, List<DecisionEdgeDefinition>> outgoing = new HashMap<>();
        Set<Long> incomingTargets = new HashSet<>();
        for (DecisionEdgeDefinition edge : edges) {
            try { readCodes(edge.inputIndicatorCodes(), "边输入编码"); }
            catch (IllegalArgumentException ex) { errors.add(edge.edgeKey() + ": " + ex.getMessage()); }

            if (!edgeKeys.add(edge.edgeKey())) {
                errors.add("边编码重复：" + edge.edgeKey());
            }
            DecisionNodeDefinition source = nodeMap.get(edge.sourceNodeId());
            DecisionNodeDefinition target = nodeMap.get(edge.targetNodeId());
            if (source == null || target == null) {
                errors.add("存在引用已删除节点的连线：" + edge.edgeKey());
                continue;
            }
            if (!isAllowedTransition(source, target)) {
                errors.add("节点类型连接非法：" + edge.edgeKey());
            }
            if ("INDICATOR".equals(source.nodeType())
                    && "JUDGMENT".equals(target.nodeType())
                    && isBlank(target.conditionExpression())) {
                errors.add("指标节点连接了空白判断节点：" + target.title());
            }
            if ("JUDGMENT".equals(source.nodeType())
                    && "INDICATOR_VALUE".equals(target.nodeType())
                    && isBlank(target.outputPayloadJson())) {
                errors.add("判断节点没有连接固定指标值：" + source.title());
            }
            String expectedSelectorMode = expectedSelectorMode(source.executionMode());
            if (expectedSelectorMode == null || !expectedSelectorMode.equals(edge.selectorMode())) {
                errors.add("边选择模式与源节点执行模式不匹配：" + edge.edgeKey());
            }
            outgoing.computeIfAbsent(source.id(), ignored -> new ArrayList<>()).add(edge);
            incomingTargets.add(target.id());
        }
        validateIntraTreeDependencyLinks(nodes, edges, nodeMap, errors);

        for (DecisionNodeDefinition node : nodes) {
            List<DecisionEdgeDefinition> next = outgoing.getOrDefault(node.id(), List.of());
            if ("INDICATOR".equals(node.nodeType()) && next.isEmpty()) {
                errors.add("指标节点还没有判断节点：" + node.title());
            }
            if ("JUDGMENT".equals(node.nodeType()) && next.size() != 1) {
                errors.add("判断节点必须连接一个指标值：" + node.title());
            }
            if ("INDICATOR_VALUE".equals(node.nodeType())
                    && next.isEmpty()
                    && isBlank(node.actionPayloadJson())
                    && isBlank(node.outputPayloadJson())) {
                errors.add("终末指标值节点既无治疗措施也无输出：" + node.nodeKey());
            }
        }

        boolean hasIndicatorRoot = nodes.stream().anyMatch(node ->
                !incomingTargets.contains(node.id())
                        && "INDICATOR".equals(node.nodeType()));
        if (!hasIndicatorRoot) {
            errors.add("决策树缺少可执行的根指标节点");
        }
        if (containsCycle(edges)) {
            errors.add("决策树节点连线存在环路");
        }
        return errors;
    }

    private void validateIntraTreeDependencyLinks(
            List<DecisionNodeDefinition> nodes,
            List<DecisionEdgeDefinition> edges,
            Map<Long, DecisionNodeDefinition> nodeMap,
            List<String> errors
    ) {
        Set<String> outputCodes = nodes.stream()
                .filter(node -> "INDICATOR".equals(node.nodeType()))
                .filter(node -> "OUTPUT_INDICATOR".equals(node.indicatorStage()))
                .map(DecisionNodeDefinition::indicatorCode)
                .filter(code -> !isBlank(code))
                .collect(Collectors.toSet());
        Set<String> declared = new HashSet<>();
        for (DecisionNodeDefinition judgment : nodes) {
            if (!"JUDGMENT".equals(judgment.nodeType())
                    || isBlank(judgment.indicatorCode())) {
                continue;
            }
            for (String sourceCode : judgmentConditionCodes(judgment)) {
                if (!sourceCode.equals(judgment.indicatorCode())
                        && outputCodes.contains(sourceCode)) {
                    declared.add(dependencyPair(sourceCode, judgment.indicatorCode()));
                }
            }
        }
        Set<String> actual = edges.stream()
                .filter(edge -> {
                    DecisionNodeDefinition source = nodeMap.get(edge.sourceNodeId());
                    DecisionNodeDefinition target = nodeMap.get(edge.targetNodeId());
                    return source != null && target != null
                            && "INDICATOR_VALUE".equals(source.nodeType())
                            && "DECISION".equals(source.indicatorStage())
                            && "INDICATOR".equals(target.nodeType())
                            && "OUTPUT_INDICATOR".equals(target.indicatorStage());
                })
                .map(edge -> dependencyPair(
                        nodeMap.get(edge.sourceNodeId()).indicatorCode(),
                        nodeMap.get(edge.targetNodeId()).indicatorCode()))
                .collect(Collectors.toSet());
        actual.stream().filter(link -> !declared.contains(link)).sorted()
                .forEach(link -> errors.add("存在没有条件引用的树内指标连线：" + link));
        declared.stream().filter(link -> !actual.contains(link)).sorted()
                .forEach(link -> errors.add("判断条件引用了本树指标但缺少依赖连线：" + link));
    }

    private Set<String> judgmentConditionCodes(DecisionNodeDefinition judgment) {
        Set<String> result = new HashSet<>();
        try {
            JsonNode root = objectMapper.readTree(judgment.logicDefinitionJson());
            for (JsonNode group : root.path("groups")) {
                for (JsonNode condition : group.path("conditions")) {
                    if ("INDICATOR".equals(condition.path("mode").asText())) {
                        String code = condition.path("indicatorCode").asText().trim();
                        if (!code.isBlank()) {
                            result.add(code);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // The malformed judgment is already reported by validateJudgmentDefinition.
        }
        return result;
    }

    private String dependencyPair(String sourceCode, String targetCode) {
        return sourceCode + " → " + targetCode;
    }

    private boolean containsCycle(List<DecisionEdgeDefinition> edges) {
        Map<Long, List<Long>> adjacency = new HashMap<>();
        Set<Long> nodes = new HashSet<>();
        for (DecisionEdgeDefinition edge : edges) {
            adjacency.computeIfAbsent(edge.sourceNodeId(), ignored -> new ArrayList<>())
                    .add(edge.targetNodeId());
            nodes.add(edge.sourceNodeId());
            nodes.add(edge.targetNodeId());
        }
        Set<Long> visiting = new HashSet<>();
        Set<Long> visited = new HashSet<>();
        for (Long node : nodes) {
            if (hasCycle(node, adjacency, visiting, visited)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasCycle(
            Long node,
            Map<Long, List<Long>> adjacency,
            Set<Long> visiting,
            Set<Long> visited
    ) {
        if (visiting.contains(node)) {
            return true;
        }
        if (!visited.add(node)) {
            return false;
        }
        visiting.add(node);
        for (Long next : adjacency.getOrDefault(node, List.of())) {
            if (hasCycle(next, adjacency, visiting, visited)) {
                return true;
            }
        }
        visiting.remove(node);
        return false;
    }

    private boolean isAllowedTransition(DecisionNodeDefinition source, DecisionNodeDefinition target) {
        return ("INDICATOR".equals(source.nodeType())
                && "JUDGMENT".equals(target.nodeType()))
                || ("JUDGMENT".equals(source.nodeType())
                && "INDICATOR_VALUE".equals(target.nodeType()))
                || ("INDICATOR_VALUE".equals(source.nodeType())
                && "INDICATOR".equals(target.nodeType()));
    }

    private void validateJudgmentDefinition(String json) {
        if (isBlank(json)) {
            throw badRequest("判断节点必须至少包含一条条件");
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            if (!"CONDITION_GROUPS".equals(root.path("kind").asText())
                    || !root.path("groups").isArray()
                    || root.path("groups").isEmpty()) {
                throw badRequest("判断条件卡片格式不完整");
            }
            for (JsonNode group : root.path("groups")) {
                if (!group.path("conditions").isArray() || group.path("conditions").isEmpty()) {
                    throw badRequest("每个“或”条件组至少需要一条条件");
                }
                for (JsonNode condition : group.path("conditions")) {
                    String mode = condition.path("mode").asText();
                    if (TextConditionEvaluationService.isText(mode)) {
                        if (condition.path("text").asText().isBlank()) {
                            throw badRequest("文字表示条件不能为空");
                        }
                    } else if ("INDICATOR".equals(mode)) {
                        if (condition.path("indicatorCode").asText().isBlank()
                                || condition.path("operator").asText().isBlank()) {
                            throw badRequest("指标条件必须选择指标和判断方式");
                        }
                        String operator = condition.path("operator").asText();
                        if (!Set.of("TEXT", "TEXT_OPERATOR", "EQ", "NE", "GT", "GTE", "LT", "LTE", "CONTAINS", "NOT_CONTAINS", "IS_AVAILABLE", "IS_MISSING", "IS_NOT_EMPTY", "IS_EMPTY").contains(operator)) throw badRequest("未知条件操作符");
                        if (TextConditionEvaluationService.isText(operator)) {
                            if (condition.path("text").asText().isBlank()) {
                                throw badRequest("指标的文字表示不能为空");
                            }
                        } else if (!Set.of(
                                "IS_AVAILABLE", "IS_MISSING", "IS_NOT_EMPTY", "IS_EMPTY"
                        ).contains(operator)
                                && (condition.path("expectedValue").isMissingNode()
                                || condition.path("expectedValue").isNull()
                                || (condition.path("expectedValue").isTextual()
                                && condition.path("expectedValue").asText().isBlank()))) {
                            throw badRequest("该判断方式必须填写指标值");
                        }
                    } else {
                        throw badRequest("判断条件类型无效");
                    }
                }
            }
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw badRequest("判断条件卡片无法读取");
        }
    }

    private void validatePayload(String json, boolean actions) {
        if(isBlank(json)) return;
        try {
            JsonNode root=objectMapper.readTree(json);
            if(root==null || (!root.isObject() && !(actions && root.isArray()))) throw badRequest("输出或动作 JSON 类型不合法");
        } catch(Exception ex) { throw badRequest("输出或动作 JSON 无效"); }
    }

    private List<String> readCodes(String json, String fieldName) {
        if (isBlank(json)) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            if (!root.isArray()) {
                throw badRequest(fieldName + "必须是 JSON 数组");
            }
            List<String> codes = new ArrayList<>();
            root.forEach(item -> {
                if (!item.isTextual() || item.asText().isBlank()) {
                    throw badRequest(fieldName + "只能包含非空指标编码字符串");
                }
                codes.add(item.asText());
            });
            if (new HashSet<>(codes).size() != codes.size()) {
                throw badRequest(fieldName + "不能包含重复编码");
            }
            return codes;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw badRequest(fieldName + "不是合法 JSON");
        }
    }

    private String expectedSelectorMode(String executionMode) {
        if (executionMode == null) {
            return null;
        }
        return switch (executionMode) {
            case "DETERMINISTIC_FORMULA" -> "DETERMINISTIC_FORMULA";
            case "DETERMINISTIC_VALUE_MATCH" -> "DETERMINISTIC_VALUE_MATCH";
            case "MODEL_EDGE_SELECTION" -> "MODEL_EDGE_SELECTION";
            case "MODEL_SET_SELECTION" -> "MODEL_SET_SELECTION";
            case "DATA_DRIVEN_RULE" -> "DATA_DRIVEN_RULE";
            case "FIXED_INPUT_VALUE", "FIXED_OUTPUT" -> "FIXED";
            default -> null;
        };
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private IllegalArgumentException badRequest(String message) {
        return new IllegalArgumentException(message);
    }
}
