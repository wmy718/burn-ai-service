package com.nytryx.burnai.repo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nytryx.burnai.model.DecisionEdgeDefinition;
import com.nytryx.burnai.model.DecisionNodeDefinition;
import com.nytryx.burnai.model.DecisionTreeDefinition;
import com.nytryx.burnai.model.DecisionTreeDependencyConditionDefinition;
import com.nytryx.burnai.model.DecisionTreeDependencyDefinition;
import com.nytryx.burnai.model.IndicatorGroupDefinition;
import com.nytryx.burnai.model.IndicatorGroupItemDefinition;
import com.nytryx.burnai.model.IndicatorDefinition;
import com.nytryx.burnai.model.IndicatorOption;
import com.nytryx.burnai.mapper.DecisionConfigMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.stream.Collectors;

@Repository
public class DecisionConfigRepository {
    private final DecisionConfigMapper decisionConfigMapper;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final Map<String, ConfigSnapshot> cache = new ConcurrentHashMap<>();

    public DecisionConfigRepository(ObjectMapper objectMapper,
                                   DecisionConfigMapper decisionConfigMapper,
                                   JdbcTemplate jdbcTemplate) {
        this.objectMapper = objectMapper;
        this.decisionConfigMapper = decisionConfigMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    public void invalidateCache() { cache.clear(); }

    public ConfigSnapshot load(String requestedDiseaseCode) {
        String diseaseCode = requestedDiseaseCode == null || requestedDiseaseCode.isBlank()
                ? "all"
                : requestedDiseaseCode.toLowerCase(Locale.ROOT);
        return cache.computeIfAbsent(diseaseCode, this::loadFresh);
    }

    private ConfigSnapshot loadFresh(String diseaseCode) {
        List<Map<String, Object>> diseases = decisionConfigMapper.selectEnabledDiseases();
        Map<Long, String> diseaseCodeById = diseases.stream().collect(Collectors.toMap(
                row -> ((Number) row.get("id")).longValue(),
                row -> Objects.toString(row.get("code"), ""),
                (left, right) -> left,
                LinkedHashMap::new
        ));

        List<IndicatorDefinition> indicators = decisionConfigMapper.selectEnabledIndicators(diseaseCode).stream()
                .map(row -> new IndicatorDefinition(
                        number(row, "id"), number(row, "disease_id"), Objects.toString(row.get("disease_code"), ""),
                        Objects.toString(row.get("code"), ""), Objects.toString(row.get("name"), ""),
                        Objects.toString(row.get("type"), ""), Objects.toString(row.get("value_type"), ""),
                        Objects.toString(row.get("source_type"), ""), Objects.toString(row.get("unit"), null),
                        Objects.toString(row.get("default_value"), null), Objects.toString(row.get("json_path"), null),
                        Objects.toString(row.get("inference_basis"), null), Objects.toString(row.get("description"), null),
                        Objects.toString(row.get("value_domain"), null), bool(row, "required"), integer(row, "sort_order"), List.of()))
                .toList();

        if (indicators.isEmpty()) {
            throw new IllegalArgumentException("未找到可用指标配置：" + diseaseCode);
        }

        Map<Long, List<IndicatorOption>> optionsByIndicator = loadOptions(indicators);
        List<IndicatorDefinition> withOptions = indicators.stream()
                .map(indicator -> new IndicatorDefinition(
                        indicator.id(), indicator.diseaseId(), indicator.diseaseCode(), indicator.code(),
                        indicator.name(), indicator.type(), indicator.valueType(), indicator.sourceType(),
                        indicator.unit(), indicator.defaultValue(), indicator.jsonPath(), indicator.inferenceBasis(),
                        indicator.description(), indicator.valueDomain(), indicator.required(), indicator.sortOrder(),
                        optionsByIndicator.getOrDefault(indicator.id(), List.of())))
                .toList();

        List<DecisionTreeDefinition> trees = loadTreesMybatis(diseaseCode);
        List<IndicatorGroupDefinition> groups = loadIndicatorGroupsMybatis(diseaseCode);
        List<IndicatorGroupItemDefinition> groupItems = loadIndicatorGroupItemsMybatis(groups);
        List<DecisionTreeDependencyDefinition> dependencies = loadTreeDependenciesMybatis(trees);
        List<DecisionTreeDependencyConditionDefinition> dependencyConditions = loadTreeDependencyConditionsMybatis(dependencies);
        return new ConfigSnapshot(withOptions, trees, groups, groupItems, dependencies, dependencyConditions);
    }

    private Map<Long, List<IndicatorOption>> loadOptions(List<IndicatorDefinition> indicators) {
        List<Long> ids = indicators.stream().map(IndicatorDefinition::id).toList();
        String inSql = ids.stream().map(ignored -> "?").collect(Collectors.joining(","));
        List<IndicatorOptionRow> rows = decisionConfigMapper.selectIndicatorOptions(ids).stream()
                .map(row -> new IndicatorOptionRow(number(row, "indicator_id"), new IndicatorOption(
                        number(row, "id"), Objects.toString(row.get("value_label"), ""), bool(row, "is_default"),
                        Objects.toString(row.get("inference_basis"), null), integer(row, "sort_order"))))
                .toList();
        return rows.stream().collect(Collectors.groupingBy(
                IndicatorOptionRow::indicatorId,
                LinkedHashMap::new,
                Collectors.mapping(IndicatorOptionRow::option, Collectors.toList())
        ));
    }

    /*
     * The legacy JdbcTemplate loaders were removed after the MyBatis migration.
     * The active loaders are the *Mybatis methods below.
     */
    /*
        String diseaseWhere = "all".equals(diseaseCode) ? "" : " and d.code = ?";
        Object[] args = "all".equals(diseaseCode) ? new Object[]{} : new Object[]{diseaseCode};
        List<DecisionTreeRow> rows = jdbcTemplate.query("""
                select t.id, t.disease_id, d.code as disease_code, t.code, t.name, t.description,
                       t.indicator_group_id, t.prerequisite_condition, t.unlock_condition, t.sort_order
                from llm_decision_tree t
                join llm_disease d on d.id = t.disease_id
                where t.status = 'ENABLED' and d.status = 'ENABLED'
                """ + diseaseWhere + """
                order by d.sort_order, t.sort_order, t.id
                """, (rs, rowNum) -> new DecisionTreeRow(
                rs.getLong("id"),
                rs.getLong("disease_id"),
                rs.getString("disease_code"),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("description"),
                nullableLong(rs.getObject("indicator_group_id")),
                rs.getString("prerequisite_condition"),
                rs.getString("unlock_condition"),
                rs.getInt("sort_order")
        ), args);
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> treeIds = rows.stream().map(DecisionTreeRow::id).toList();
        String inSql = treeIds.stream().map(ignored -> "?").collect(Collectors.joining(","));
        List<DecisionNodeDefinition> nodes = jdbcTemplate.query("""
                select id, tree_id, node_key, title, node_type, indicator_code, indicator_stage,
                       value_code, execution_mode, input_indicator_codes, condition_expression,
                       logic_description, logic_definition_json, action_payload_json,
                       output_payload_json, sort_order
                from llm_decision_node
                where tree_id in (""" + inSql + """
                )
                order by tree_id, sort_order, id
                """, (rs, rowNum) -> new DecisionNodeDefinition(
                rs.getLong("id"),
                rs.getLong("tree_id"),
                rs.getString("node_key"),
                rs.getString("title"),
                rs.getString("node_type"),
                rs.getString("indicator_code"),
                rs.getString("indicator_stage"),
                rs.getString("value_code"),
                rs.getString("execution_mode"),
                rs.getString("input_indicator_codes"),
                rs.getString("condition_expression"),
                rs.getString("logic_description"),
                rs.getString("logic_definition_json"),
                rs.getString("action_payload_json"),
                rs.getString("output_payload_json"),
                rs.getInt("sort_order")
        ), treeIds.toArray());
        Map<Long, List<DecisionNodeDefinition>> nodesByTree = nodes.stream()
                .collect(Collectors.groupingBy(
                        DecisionNodeDefinition::treeId,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));
        List<DecisionEdgeDefinition> edges = jdbcTemplate.query("""
                select id, tree_id, source_node_id, target_node_id, edge_key, branch_label,
                       condition_expression, input_indicator_codes, selector_mode,
                       priority_order, is_default
                from llm_decision_node_edge
                where tree_id in (""" + inSql + """
                )
                order by tree_id, priority_order, id
                """, (rs, rowNum) -> new DecisionEdgeDefinition(
                rs.getLong("id"),
                rs.getLong("tree_id"),
                rs.getLong("source_node_id"),
                rs.getLong("target_node_id"),
                rs.getString("edge_key"),
                rs.getString("branch_label"),
                rs.getString("condition_expression"),
                rs.getString("input_indicator_codes"),
                rs.getString("selector_mode"),
                rs.getInt("priority_order"),
                rs.getBoolean("is_default")
        ), treeIds.toArray());
        Map<Long, List<DecisionEdgeDefinition>> edgesByTree = edges.stream()
                .collect(Collectors.groupingBy(
                        DecisionEdgeDefinition::treeId,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));
        return rows.stream()
                .map(row -> new DecisionTreeDefinition(
                        row.id(), row.diseaseId(), row.diseaseCode(), row.code(), row.name(), row.description(),
                        row.indicatorGroupId(), row.prerequisiteCondition(), row.unlockCondition(), row.sortOrder(),
                        nodesByTree.getOrDefault(row.id(), List.of()),
                        edgesByTree.getOrDefault(row.id(), List.of())))
                .toList();
    }

    private List<IndicatorGroupDefinition> loadIndicatorGroups(String diseaseCode) {
        String diseaseWhere = "all".equals(diseaseCode) ? "" : " and d.code = ?";
        Object[] args = "all".equals(diseaseCode) ? new Object[]{} : new Object[]{diseaseCode};
        return jdbcTemplate.query("""
                select g.id, g.disease_id, g.name, g.trigger_condition, g.sort_order
                from llm_indicator_group g
                join llm_disease d on d.id = g.disease_id
                where d.status = 'ENABLED'
                """ + diseaseWhere + """
                order by d.sort_order, g.sort_order, g.id
                """, (rs, rowNum) -> new IndicatorGroupDefinition(
                rs.getLong("id"),
                rs.getLong("disease_id"),
                rs.getString("name"),
                rs.getString("trigger_condition"),
                rs.getInt("sort_order")
        ), args);
    }

    private List<IndicatorGroupItemDefinition> loadIndicatorGroupItems(List<IndicatorGroupDefinition> groups) {
        if (groups.isEmpty()) return List.of();
        List<Long> ids = groups.stream().map(IndicatorGroupDefinition::id).toList();
        String inSql = ids.stream().map(ignored -> "?").collect(Collectors.joining(","));
        return jdbcTemplate.query("""
                select id, group_id, indicator_id, required, card_group, comparison_operator,
                       expected_value, condition_expression, sort_order
                from llm_indicator_group_item
                where group_id in (""" + inSql + """
                )
                order by group_id, card_group, sort_order, id
                """, (rs, rowNum) -> new IndicatorGroupItemDefinition(
                rs.getLong("id"),
                rs.getLong("group_id"),
                nullableLong(rs.getObject("indicator_id")),
                rs.getBoolean("required"),
                rs.getInt("card_group"),
                rs.getString("comparison_operator"),
                rs.getString("expected_value"),
                rs.getString("condition_expression"),
                rs.getInt("sort_order")
        ), ids.toArray());
    }

    private List<DecisionTreeDependencyDefinition> loadTreeDependencies(List<DecisionTreeDefinition> trees) {
        if (trees.isEmpty()) return List.of();
        List<Long> ids = trees.stream().map(DecisionTreeDefinition::id).toList();
        String inSql = ids.stream().map(ignored -> "?").collect(Collectors.joining(","));
        return jdbcTemplate.query("""
                select id, tree_id, prerequisite_tree_id, sort_order, condition_mode,
                       indicator_code, comparison_operator, expected_value, condition_text
                from llm_decision_tree_dependency
                where tree_id in (""" + inSql + """
                )
                order by tree_id, sort_order, id
                """, (rs, rowNum) -> new DecisionTreeDependencyDefinition(
                rs.getLong("id"),
                rs.getLong("tree_id"),
                rs.getLong("prerequisite_tree_id"),
                rs.getInt("sort_order"),
                rs.getString("condition_mode"),
                rs.getString("indicator_code"),
                rs.getString("comparison_operator"),
                rs.getString("expected_value"),
                rs.getString("condition_text")
        ), ids.toArray());
    }

    private List<DecisionTreeDependencyConditionDefinition> loadTreeDependencyConditions(List<DecisionTreeDependencyDefinition> dependencies) {
        if (dependencies.isEmpty()) return List.of();
        List<Long> ids = dependencies.stream().map(DecisionTreeDependencyDefinition::id).toList();
        String inSql = ids.stream().map(ignored -> "?").collect(Collectors.joining(","));
        return jdbcTemplate.query("""
                select id, dependency_id, card_group, condition_mode, indicator_code,
                       comparison_operator, expected_value, condition_text, sort_order
                from llm_decision_tree_dependency_condition
                where dependency_id in (""" + inSql + """
                )
                order by dependency_id, card_group, sort_order, id
                """, (rs, rowNum) -> new DecisionTreeDependencyConditionDefinition(
                rs.getLong("id"),
                rs.getLong("dependency_id"),
                rs.getInt("card_group"),
                rs.getString("condition_mode"),
                rs.getString("indicator_code"),
                rs.getString("comparison_operator"),
                rs.getString("expected_value"),
                rs.getString("condition_text"),
                rs.getInt("sort_order")
        ), ids.toArray());
    }

    */

    public Map<String, Object> readObject(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    public record ConfigSnapshot(
            List<IndicatorDefinition> indicators,
            List<DecisionTreeDefinition> trees,
            List<IndicatorGroupDefinition> indicatorGroups,
            List<IndicatorGroupItemDefinition> groupItems,
            List<DecisionTreeDependencyDefinition> treeDependencies,
            List<DecisionTreeDependencyConditionDefinition> dependencyConditions
    ) {
        public Map<String, IndicatorDefinition> indicatorByCode() {
            return indicators.stream().collect(Collectors.toMap(
                    IndicatorDefinition::code,
                    indicator -> indicator,
                    (left, right) -> left,
                    LinkedHashMap::new
            ));
        }

        public Map<String, IndicatorDefinition> indicatorByName() {
            return indicators.stream().collect(Collectors.toMap(
                    IndicatorDefinition::name,
                    indicator -> indicator,
                    (left, right) -> left,
                    LinkedHashMap::new
            ));
        }

        public List<IndicatorDefinition> rawIndicators() {
            return indicators.stream()
                    .filter(indicator -> !indicator.isDecision())
                    .sorted(Comparator.comparing(IndicatorDefinition::sortOrder))
                    .toList();
        }
    }

    private Long nullableLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }
    private List<IndicatorGroupDefinition> loadIndicatorGroupsMybatis(String diseaseCode) {
        return decisionConfigMapper.selectIndicatorGroups(diseaseCode).stream().map(row -> new IndicatorGroupDefinition(
                number(row, "id"), number(row, "disease_id"), Objects.toString(row.get("name"), ""),
                Objects.toString(row.get("trigger_condition"), null), integer(row, "sort_order"))).toList();
    }

    private List<IndicatorGroupItemDefinition> loadIndicatorGroupItemsMybatis(List<IndicatorGroupDefinition> groups) {
        if (groups.isEmpty()) return List.of();
        List<Long> ids = groups.stream().map(IndicatorGroupDefinition::id).toList();
        return decisionConfigMapper.selectIndicatorGroupItems(ids).stream().map(row -> new IndicatorGroupItemDefinition(
                number(row, "id"), number(row, "group_id"), nullableLong(row.get("indicator_id")), bool(row, "required"),
                integer(row, "card_group"), Objects.toString(row.get("comparison_operator"), null),
                Objects.toString(row.get("expected_value"), null), Objects.toString(row.get("condition_expression"), null),
                integer(row, "sort_order"))).toList();
    }

    private List<DecisionTreeDependencyDefinition> loadTreeDependenciesMybatis(List<DecisionTreeDefinition> trees) {
        if (trees.isEmpty()) return List.of();
        List<Long> ids = trees.stream().map(DecisionTreeDefinition::id).toList();
        return decisionConfigMapper.selectTreeDependencies(ids).stream().map(row -> new DecisionTreeDependencyDefinition(
                number(row, "id"), number(row, "tree_id"), number(row, "prerequisite_tree_id"), integer(row, "sort_order"),
                Objects.toString(row.get("condition_mode"), null), Objects.toString(row.get("indicator_code"), null),
                Objects.toString(row.get("comparison_operator"), null), Objects.toString(row.get("expected_value"), null),
                Objects.toString(row.get("condition_text"), null))).toList();
    }

    private List<DecisionTreeDependencyConditionDefinition> loadTreeDependencyConditionsMybatis(
            List<DecisionTreeDependencyDefinition> dependencies) {
        if (dependencies.isEmpty()) return List.of();
        List<Long> ids = dependencies.stream().map(DecisionTreeDependencyDefinition::id).toList();
        return decisionConfigMapper.selectTreeDependencyConditions(ids).stream().map(row ->
                new DecisionTreeDependencyConditionDefinition(number(row, "id"), number(row, "dependency_id"),
                        integer(row, "card_group"), Objects.toString(row.get("condition_mode"), null),
                        Objects.toString(row.get("indicator_code"), null), Objects.toString(row.get("comparison_operator"), null),
                        Objects.toString(row.get("expected_value"), null), Objects.toString(row.get("condition_text"), null),
                        integer(row, "sort_order"))).toList();
    }

    private List<DecisionTreeDefinition> loadTreesMybatis(String diseaseCode) {
        List<DecisionTreeRow> rows = decisionConfigMapper.selectEnabledTrees(diseaseCode).stream()
                .map(row -> new DecisionTreeRow(number(row, "id"), number(row, "disease_id"),
                        Objects.toString(row.get("disease_code"), ""), Objects.toString(row.get("code"), ""),
                        Objects.toString(row.get("name"), ""), Objects.toString(row.get("description"), null),
                        nullableLong(row.get("indicator_group_id")), Objects.toString(row.get("prerequisite_condition"), null),
                        Objects.toString(row.get("unlock_condition"), null), integer(row, "sort_order")))
                .toList();
        if (rows.isEmpty()) return List.of();
        List<Long> treeIds = rows.stream().map(DecisionTreeRow::id).toList();
        Map<Long, List<DecisionNodeDefinition>> nodesByTree = decisionConfigMapper.selectTreeNodes(treeIds).stream()
                .map(row -> new DecisionNodeDefinition(number(row, "id"), number(row, "tree_id"),
                        Objects.toString(row.get("node_key"), ""), Objects.toString(row.get("title"), ""),
                        Objects.toString(row.get("node_type"), ""), Objects.toString(row.get("indicator_code"), null),
                        Objects.toString(row.get("indicator_stage"), null), Objects.toString(row.get("value_code"), null),
                        Objects.toString(row.get("execution_mode"), null), Objects.toString(row.get("input_indicator_codes"), null),
                        Objects.toString(row.get("condition_expression"), null), Objects.toString(row.get("logic_description"), null),
                        Objects.toString(row.get("logic_definition_json"), null), Objects.toString(row.get("action_payload_json"), null),
                        Objects.toString(row.get("output_payload_json"), null), integer(row, "sort_order")))
                .collect(Collectors.groupingBy(DecisionNodeDefinition::treeId, LinkedHashMap::new, Collectors.toList()));
        Map<Long, List<DecisionEdgeDefinition>> edgesByTree = decisionConfigMapper.selectTreeEdges(treeIds).stream()
                .map(row -> new DecisionEdgeDefinition(number(row, "id"), number(row, "tree_id"), number(row, "source_node_id"),
                        number(row, "target_node_id"), Objects.toString(row.get("edge_key"), ""),
                        Objects.toString(row.get("branch_label"), ""), Objects.toString(row.get("condition_expression"), null),
                        Objects.toString(row.get("input_indicator_codes"), null), Objects.toString(row.get("selector_mode"), null),
                        integer(row, "priority_order"), bool(row, "is_default")))
                .collect(Collectors.groupingBy(DecisionEdgeDefinition::treeId, LinkedHashMap::new, Collectors.toList()));
        return rows.stream().map(row -> new DecisionTreeDefinition(row.id(), row.diseaseId(), row.diseaseCode(), row.code(),
                row.name(), row.description(), row.indicatorGroupId(), row.prerequisiteCondition(), row.unlockCondition(),
                row.sortOrder(), nodesByTree.getOrDefault(row.id(), List.of()), edgesByTree.getOrDefault(row.id(), List.of()))).toList();
    }

    private static long number(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? 0L : ((Number) value).longValue();
    }

    private static int integer(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? 0 : ((Number) value).intValue();
    }

    private static boolean bool(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof Boolean b ? b : value != null && ((Number) value).intValue() != 0;
    }

    private record IndicatorOptionRow(long indicatorId, IndicatorOption option) {
    }

    private record DecisionTreeRow(
            long id,
            long diseaseId,
            String diseaseCode,
            String code,
            String name,
            String description,
            Long indicatorGroupId,
            String prerequisiteCondition,
            String unlockCondition,
            int sortOrder
    ) {
    }
}
