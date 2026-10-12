package com.nytryx.burnai.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 决策配置查询入口。
 *
 * 这里先使用 MyBatis 的结果 Map 承接旧配置表，避免把数据库字段直接暴露给业务模型。
 * 后续如果需要后台增删改配置，再为每张表补充对应的实体和 BaseMapper。
 */
@Mapper
public interface DecisionConfigMapper {
    @Select("""
            select id, code, name
            from llm_disease
            where status = 'ENABLED'
            order by sort_order, id
            """)
    List<Map<String, Object>> selectEnabledDiseases();

    @Select("""
            <script>
            select i.id, i.disease_id, d.code as disease_code, i.code, i.name,
                   i.type, i.value_type, i.source_type, i.unit, i.default_value,
                   i.json_path, i.inference_basis, i.description, i.value_domain,
                   i.required, i.sort_order
            from llm_indicator i
            join llm_disease d on d.id = i.disease_id
            where i.status = 'ENABLED' and d.status = 'ENABLED'
            <if test="diseaseCode != null and diseaseCode != 'all'">
              and d.code = #{diseaseCode}
            </if>
            order by d.sort_order, i.sort_order, i.id
            </script>
            """)
    List<Map<String, Object>> selectEnabledIndicators(@Param("diseaseCode") String diseaseCode);

    @Select("""
            <script>
            select id, indicator_id, value_label, is_default, inference_basis, sort_order
            from llm_indicator_value_option
            where indicator_id in
            <foreach item="id" collection="indicatorIds" open="(" separator="," close=")">#{id}</foreach>
            order by indicator_id, sort_order, id
            </script>
            """)
    List<Map<String, Object>> selectIndicatorOptions(@Param("indicatorIds") List<Long> indicatorIds);

    @Select("""
            <script>
            select t.id, t.disease_id, d.code as disease_code, t.code, t.name, t.description,
                   t.indicator_group_id, t.prerequisite_condition, t.unlock_condition, t.sort_order
            from llm_decision_tree t
            join llm_disease d on d.id = t.disease_id
            where t.status = 'ENABLED' and d.status = 'ENABLED'
            <if test="diseaseCode != null and diseaseCode != 'all'">
              and d.code = #{diseaseCode}
            </if>
            order by d.sort_order, t.sort_order, t.id
            </script>
            """)
    List<Map<String, Object>> selectEnabledTrees(@Param("diseaseCode") String diseaseCode);

    @Select("""
            <script>
            select id, tree_id, node_key, title, node_type, indicator_code, indicator_stage,
                   value_code, execution_mode, input_indicator_codes, condition_expression,
                   logic_description, logic_definition_json, action_payload_json,
                   output_payload_json, sort_order
            from llm_decision_node
            where tree_id in
            <foreach item="id" collection="treeIds" open="(" separator="," close=")">#{id}</foreach>
            order by tree_id, sort_order, id
            </script>
            """)
    List<Map<String, Object>> selectTreeNodes(@Param("treeIds") List<Long> treeIds);

    @Select("""
            <script>
            select id, tree_id, source_node_id, target_node_id, edge_key, branch_label,
                   condition_expression, input_indicator_codes, selector_mode,
                   priority_order, is_default
            from llm_decision_node_edge
            where tree_id in
            <foreach item="id" collection="treeIds" open="(" separator="," close=")">#{id}</foreach>
            order by tree_id, priority_order, id
            </script>
            """)
    List<Map<String, Object>> selectTreeEdges(@Param("treeIds") List<Long> treeIds);

    @Select("""
            <script>
            select g.id, g.disease_id, g.name, g.trigger_condition, g.sort_order
            from llm_indicator_group g join llm_disease d on d.id = g.disease_id
            where d.status = 'ENABLED'
            <if test="diseaseCode != null and diseaseCode != 'all'">and d.code = #{diseaseCode}</if>
            order by d.sort_order, g.sort_order, g.id
            </script>
            """)
    List<Map<String, Object>> selectIndicatorGroups(@Param("diseaseCode") String diseaseCode);

    @Select("""
            <script>
            select id, group_id, indicator_id, required, card_group, comparison_operator,
                   expected_value, condition_expression, sort_order
            from llm_indicator_group_item
            where group_id in <foreach item="id" collection="groupIds" open="(" separator="," close=")">#{id}</foreach>
            order by group_id, card_group, sort_order, id
            </script>
            """)
    List<Map<String, Object>> selectIndicatorGroupItems(@Param("groupIds") List<Long> groupIds);

    @Select("""
            <script>
            select id, tree_id, prerequisite_tree_id, sort_order, condition_mode,
                   indicator_code, comparison_operator, expected_value, condition_text
            from llm_decision_tree_dependency
            where tree_id in <foreach item="id" collection="treeIds" open="(" separator="," close=")">#{id}</foreach>
            order by tree_id, sort_order, id
            </script>
            """)
    List<Map<String, Object>> selectTreeDependencies(@Param("treeIds") List<Long> treeIds);

    @Select("""
            <script>
            select id, dependency_id, card_group, condition_mode, indicator_code,
                   comparison_operator, expected_value, condition_text, sort_order
            from llm_decision_tree_dependency_condition
            where dependency_id in <foreach item="id" collection="dependencyIds" open="(" separator="," close=")">#{id}</foreach>
            order by dependency_id, card_group, sort_order, id
            </script>
            """)
    List<Map<String, Object>> selectTreeDependencyConditions(@Param("dependencyIds") List<Long> dependencyIds);
}
