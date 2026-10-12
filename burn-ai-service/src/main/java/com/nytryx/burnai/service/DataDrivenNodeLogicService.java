package com.nytryx.burnai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nytryx.burnai.model.DecisionNodeDefinition;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 执行数据库配置的受限规则表和数值表达式，不加载动态代码。 */
@Service
public class DataDrivenNodeLogicService {

    private static final Set<String> CLAUSE_OPERATORS = Set.of(
            "EQ", "NE", "IN", "NOT_IN", "GT", "GTE", "LT", "LTE",
            "EXISTS", "MISSING", "CONTAINS"
    );
    private static final Pattern TOKEN = Pattern.compile(
            "\\s*([A-Za-z_][A-Za-z0-9_]*|(?:\\d+(?:\\.\\d+)?)|[()+\\-*/])\\s*"
    );

    private final ObjectMapper objectMapper;

    public DataDrivenNodeLogicService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void validate(DecisionNodeDefinition node) {
        if (!"DATA_DRIVEN_RULE".equals(node.executionMode())) {
            return;
        }
        JsonNode definition = definition(node);
        String kind = requiredText(definition, "kind");
        Set<String> declaredInputs = new HashSet<>(readDeclaredInputs(node));
        if (declaredInputs.isEmpty()) {
            throw badRequest("数据规则节点必须声明输入指标编码");
        }
        if ("RULE_TABLE".equals(kind)) {
            validateRuleTable(definition, declaredInputs);
            return;
        }
        if ("NUMERIC_FORMULA".equals(kind)) {
            String expression = requiredText(definition, "expression");
            Set<String> variables = formulaVariables(tokenize(expression));
            if (!declaredInputs.containsAll(variables)) {
                variables.removeAll(declaredInputs);
                throw badRequest("公式引用了未绑定的输入指标：" + String.join(", ", variables));
            }
            toRpn(tokenize(expression));
            return;
        }
        throw badRequest("logicDefinitionJson.kind 仅支持 RULE_TABLE 或 NUMERIC_FORMULA");
    }

    public LogicResult evaluate(DecisionNodeDefinition node, Map<String, Object> values) {
        try {
            validate(node);
            JsonNode definition = definition(node);
            String kind = definition.path("kind").asText();
            if ("RULE_TABLE".equals(kind)) {
                for (JsonNode rule : definition.path("rules")) {
                    if (rule.path("default").asBoolean(false)
                            || matches(rule.path("when"), values)) {
                        JsonNode output = rule.get("output");
                        if (output == null || output.isNull()) {
                            return LogicResult.failed("命中的规则缺少 output");
                        }
                        return LogicResult.success(
                                objectMapper.convertValue(output, Object.class),
                                rule.path("explanation").asText("")
                        );
                    }
                }
                return LogicResult.failed("没有规则命中且未配置 default 兜底规则");
            }
            BigDecimal value = evaluateFormula(definition.path("expression").asText(), values);
            int scale = definition.path("roundScale").isInt()
                    ? Math.max(0, Math.min(8, definition.path("roundScale").asInt()))
                    : 4;
            BigDecimal normalized = value.setScale(scale, RoundingMode.HALF_UP).stripTrailingZeros();
            Object output = normalized.scale() <= 0 ? normalized.longValueExact() : normalized;
            return LogicResult.success(
                    output,
                    definition.path("explanation").asText("按结构化数值公式计算")
            );
        } catch (IllegalArgumentException exception) {
            return LogicResult.failed(exception.getMessage());
        } catch (RuntimeException exception) {
            return LogicResult.failed("数据规则执行失败：" + safeMessage(exception));
        }
    }

    private void validateRuleTable(JsonNode definition, Set<String> declaredInputs) {
        JsonNode rules = definition.path("rules");
        if (!rules.isArray() || rules.isEmpty()) {
            throw badRequest("RULE_TABLE 必须包含至少一条 rules");
        }
        boolean hasDefault = false;
        for (JsonNode rule : rules) {
            if (!rule.has("output")) {
                throw badRequest("每条规则都必须声明 output");
            }
            if (rule.path("default").asBoolean(false)) {
                hasDefault = true;
                continue;
            }
            JsonNode when = rule.path("when");
            String join = when.has("all") ? "all" : (when.has("any") ? "any" : null);
            if (join == null || !when.path(join).isArray() || when.path(join).isEmpty()) {
                throw badRequest("非默认规则必须使用 when.all 或 when.any 声明条件");
            }
            for (JsonNode clause : when.path(join)) {
                String field = requiredText(clause, "field");
                String op = requiredText(clause, "op");
                if (!declaredInputs.contains(field)) {
                    throw badRequest("规则引用了未绑定的输入指标：" + field);
                }
                if (!CLAUSE_OPERATORS.contains(op)) {
                    throw badRequest("不支持的规则操作符：" + op);
                }
            }
        }
        if (!hasDefault) {
            throw badRequest("RULE_TABLE 必须包含一条 default=true 的兜底规则，避免缺失证据被误判");
        }
    }

    private boolean matches(JsonNode when, Map<String, Object> values) {
        JsonNode clauses = when.has("all") ? when.path("all") : when.path("any");
        boolean all = when.has("all");
        for (JsonNode clause : clauses) {
            boolean matched = matchesClause(clause, values.get(clause.path("field").asText()));
            if (all && !matched) {
                return false;
            }
            if (!all && matched) {
                return true;
            }
        }
        return all;
    }

    private boolean matchesClause(JsonNode clause, Object actual) {
        String op = clause.path("op").asText();
        JsonNode expected = clause.get("value");
        if ("EXISTS".equals(op)) {
            return actual != null && hasText(String.valueOf(actual));
        }
        if ("MISSING".equals(op)) {
            return actual == null || !hasText(String.valueOf(actual));
        }
        if (actual == null) {
            return false;
        }
        Object expectedValue = expected == null || expected.isNull()
                ? null
                : objectMapper.convertValue(expected, Object.class);
        if ("EQ".equals(op)) {
            return String.valueOf(actual).equals(String.valueOf(expectedValue));
        }
        if ("NE".equals(op)) {
            return !String.valueOf(actual).equals(String.valueOf(expectedValue));
        }
        if ("IN".equals(op) || "NOT_IN".equals(op)) {
            boolean contains = expected != null && expected.isArray()
                    && java.util.stream.StreamSupport.stream(expected.spliterator(), false)
                    .anyMatch(item -> item.asText().equals(String.valueOf(actual)));
            return "IN".equals(op) ? contains : !contains;
        }
        if ("CONTAINS".equals(op)) {
            if (actual instanceof Iterable<?> iterable) {
                for (Object value : iterable) {
                    if (String.valueOf(value).equals(String.valueOf(expectedValue))) {
                        return true;
                    }
                }
                return false;
            }
            return String.valueOf(actual).contains(String.valueOf(expectedValue));
        }
        BigDecimal left = number(actual);
        BigDecimal right = number(expectedValue);
        int comparison = left.compareTo(right);
        return switch (op) {
            case "GT" -> comparison > 0;
            case "GTE" -> comparison >= 0;
            case "LT" -> comparison < 0;
            case "LTE" -> comparison <= 0;
            default -> false;
        };
    }

    private BigDecimal evaluateFormula(String expression, Map<String, Object> values) {
        List<String> rpn = toRpn(tokenize(expression));
        Deque<BigDecimal> stack = new ArrayDeque<>();
        for (String token : rpn) {
            if (isOperator(token)) {
                if (stack.size() < 2) {
                    throw badRequest("公式操作数不足");
                }
                BigDecimal right = stack.pop();
                BigDecimal left = stack.pop();
                stack.push(switch (token) {
                    case "+" -> left.add(right);
                    case "-" -> left.subtract(right);
                    case "*" -> left.multiply(right);
                    case "/" -> {
                        if (right.compareTo(BigDecimal.ZERO) == 0) {
                            throw badRequest("公式不能除以 0");
                        }
                        yield left.divide(right, MathContext.DECIMAL64);
                    }
                    default -> throw badRequest("非法公式操作符");
                });
            } else if (token.matches("\\d+(?:\\.\\d+)?")) {
                stack.push(new BigDecimal(token));
            } else {
                Object raw = values.get(token);
                if (raw == null) {
                    throw badRequest("公式缺少输入指标：" + token);
                }
                stack.push(number(raw));
            }
        }
        if (stack.size() != 1) {
            throw badRequest("公式结构不完整");
        }
        return stack.pop();
    }

    private List<String> tokenize(String expression) {
        if (!hasText(expression)) {
            throw badRequest("数值公式不能为空");
        }
        List<String> tokens = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(expression);
        int position = 0;
        while (matcher.find()) {
            if (matcher.start() != position
                    && !expression.substring(position, matcher.start()).isBlank()) {
                throw badRequest("公式包含非法字符：" + expression.substring(position, matcher.start()));
            }
            tokens.add(matcher.group(1));
            position = matcher.end();
        }
        if (position != expression.length() && !expression.substring(position).isBlank()) {
            throw badRequest("公式包含非法字符：" + expression.substring(position));
        }
        return tokens;
    }

    private List<String> toRpn(List<String> tokens) {
        List<String> output = new ArrayList<>();
        Deque<String> operators = new ArrayDeque<>();
        for (String token : tokens) {
            if ("(".equals(token)) {
                operators.push(token);
            } else if (")".equals(token)) {
                while (!operators.isEmpty() && !"(".equals(operators.peek())) {
                    output.add(operators.pop());
                }
                if (operators.isEmpty()) {
                    throw badRequest("公式括号不匹配");
                }
                operators.pop();
            } else if (isOperator(token)) {
                while (!operators.isEmpty()
                        && isOperator(operators.peek())
                        && precedence(operators.peek()) >= precedence(token)) {
                    output.add(operators.pop());
                }
                operators.push(token);
            } else {
                output.add(token);
            }
        }
        while (!operators.isEmpty()) {
            if ("(".equals(operators.peek())) {
                throw badRequest("公式括号不匹配");
            }
            output.add(operators.pop());
        }
        return output;
    }

    private Set<String> formulaVariables(List<String> tokens) {
        Set<String> result = new HashSet<>();
        for (String token : tokens) {
            if (token.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                result.add(token);
            }
        }
        return result;
    }

    private List<String> readDeclaredInputs(DecisionNodeDefinition node) {
        try {
            if (!hasText(node.inputIndicatorCodes())) {
                return List.of();
            }
            return objectMapper.readValue(
                    node.inputIndicatorCodes(),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)
            );
        } catch (Exception exception) {
            throw badRequest("输入指标编码必须是 JSON 字符串数组");
        }
    }

    private JsonNode definition(DecisionNodeDefinition node) {
        try {
            if (!hasText(node.logicDefinitionJson())) {
                throw badRequest("数据规则节点必须填写 logicDefinitionJson");
            }
            return objectMapper.readTree(node.logicDefinitionJson());
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw badRequest("logicDefinitionJson 不是合法 JSON");
        }
    }

    private String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText("").trim();
        if (!hasText(value)) {
            throw badRequest("缺少字段：" + field);
        }
        return value;
    }

    private BigDecimal number(Object value) {
        try {
            return new BigDecimal(String.valueOf(value).trim());
        } catch (Exception exception) {
            throw badRequest("需要数值，实际为：" + value);
        }
    }

    private boolean isOperator(String value) {
        return Set.of("+", "-", "*", "/").contains(value);
    }

    private int precedence(String value) {
        return Set.of("*", "/").contains(value) ? 2 : 1;
    }

    private IllegalArgumentException badRequest(String message) {
        return new IllegalArgumentException(message);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String safeMessage(Exception exception) {
        return hasText(exception.getMessage())
                ? exception.getMessage()
                : exception.getClass().getSimpleName();
    }

    public record LogicResult(boolean success, Object value, String explanation, String error) {
        static LogicResult success(Object value, String explanation) {
            return new LogicResult(true, value, explanation, null);
        }

        static LogicResult failed(String error) {
            return new LogicResult(false, null, null, error);
        }
    }
}
