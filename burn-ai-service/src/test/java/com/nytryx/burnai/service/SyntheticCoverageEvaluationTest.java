package com.nytryx.burnai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nytryx.burnai.model.AggregateExtractionRequest;
import com.nytryx.burnai.model.IndicatorDefinition;
import com.nytryx.burnai.model.ReportGenerationRequest;
import com.nytryx.burnai.repo.DecisionConfigRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfSystemProperty(named = "syntheticEval", matches = "true")
class SyntheticCoverageEvaluationTest {
    @Autowired QwenClient qwen;
    @Autowired DecisionConfigRepository repository;
    @Autowired IndicatorExtractionService extraction;
    @Autowired ReportGenerationService reports;
    @Autowired ObjectMapper objectMapper;

    @Test
    void generatedCasesCoverDecisionFlows() throws Exception {
        assertTrue(qwen.available(), "合成病例覆盖测试需要启用 Qwen");
        List<Map<String, Object>> caseResults = new ArrayList<>();
        Set<String> completedTrees = new LinkedHashSet<>();
        Set<String> producedDecisionCodes = new LinkedHashSet<>();
        Set<String> configuredTrees = new LinkedHashSet<>();
        Set<String> configuredDecisionCodes = new LinkedHashSet<>();

        for (String disease : List.of("burn", "scar")) {
            var snapshot = repository.load(disease);
            snapshot.trees().forEach(tree -> configuredTrees.add(tree.code()));
            snapshot.indicators().stream().filter(IndicatorDefinition::isDecision).forEach(i -> configuredDecisionCodes.add(i.code()));
            for (Map<String, Object> generated : generate(disease, snapshot)) {
                String id = Objects.toString(generated.get("id"), disease + "-generated-" + UUID.randomUUID());
                String text = Objects.toString(generated.get("clinicalText"), "");
                Map<String, Object> featureResult = generated.get("featureResult") instanceof Map<?, ?> map
                        ? objectMapper.convertValue(map, Map.class) : Map.of();
                var extracted = extraction.extract(new AggregateExtractionRequest(
                        "synthetic-extract-" + id + "-" + UUID.randomUUID(), disease, text, featureResult));
                var report = reports.generate(new ReportGenerationRequest(
                        disease, "synthetic-report-" + id + "-" + UUID.randomUUID(), extracted.indicatorCodes()));
                report.trees().stream().filter(t -> "COMPLETED".equals(t.status())).forEach(t -> completedTrees.add(t.treeCode()));
                producedDecisionCodes.addAll(report.decisionOutputCodes().keySet());

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", id);
                row.put("diseaseCode", disease);
                row.put("scenario", generated.get("scenario"));
                row.put("clinicalText", text);
                row.put("featureResult", featureResult);
                row.put("indicatorCodes", extracted.indicatorCodes());
                row.put("extractionErrors", extracted.errors());
                row.put("decisionOutputCodes", report.decisionOutputCodes());
                row.put("trees", report.trees());
                row.put("report", report.report());
                row.put("reportStructureValid", report.report().size() == 8 && report.report().values().stream().allMatch(v -> v != null && !v.toString().isBlank()));
                row.put("extractionMs", extracted.elapsedMs());
                row.put("reportMs", report.elapsedMs());
                caseResults.add(row);
                System.out.println("SYNTHETIC CASE " + id + " decisions=" + report.decisionOutputCodes().keySet());
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("caseCount", caseResults.size());
        result.put("configuredTreeCount", configuredTrees.size());
        result.put("completedTreeCount", completedTrees.size());
        result.put("treeCoverage", configuredTrees.isEmpty() ? 0 : completedTrees.size() * 1.0 / configuredTrees.size());
        result.put("completedTrees", completedTrees);
        result.put("uncoveredTrees", configuredTrees.stream().filter(code -> !completedTrees.contains(code)).toList());
        result.put("configuredDecisionCodeCount", configuredDecisionCodes.size());
        result.put("producedDecisionCodeCount", producedDecisionCodes.size());
        result.put("decisionCodeCoverage", configuredDecisionCodes.isEmpty() ? 0 : producedDecisionCodes.size() * 1.0 / configuredDecisionCodes.size());
        result.put("producedDecisionCodes", producedDecisionCodes);
        result.put("uncoveredDecisionCodes", configuredDecisionCodes.stream().filter(code -> !producedDecisionCodes.contains(code)).toList());
        result.put("limitations", List.of(
                "合成病例只用于分支覆盖、健壮性和报告可读性检查，不用于计算临床准确率",
                "病例由同一类大模型生成，可能存在分布偏差",
                "图像 JSON 是合成结构化特征，不评价 Python 图像识别准确率"
        ));
        result.put("cases", caseResults);
        Path output = Path.of("test-results", "synthetic-coverage-evaluation.json");
        Files.createDirectories(output.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), result);
        assertFalse(caseResults.isEmpty());
    }

    private List<Map<String, Object>> generate(String disease, DecisionConfigRepository.ConfigSnapshot snapshot) {
        List<Map<String, Object>> definitions = snapshot.rawIndicators().stream().map(i -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", i.code());
            item.put("name", i.name());
            item.put("sourceType", i.sourceType());
            item.put("required", i.required());
            item.put("valueType", i.valueType());
            item.put("valueDomain", i.valueDomain());
            item.put("options", i.options().stream().map(o -> o.valueLabel()).toList());
            return item;
        }).toList();
        String scenarios = "burn".equals(disease)
                ? "生成3例差异明显的病例，尽量覆盖：大面积火焰/吸入伤、小面积深度烧伤、化学或电损伤。"
                : "生成3例差异明显的病例，尽量覆盖：增生性瘢痕、瘢痕疙瘩、挛缩或成熟低活动瘢痕。";
        String prompt = scenarios + "\n"
                + "每例必须给出自然、连贯的中文护士病情描述 clinicalText，以及只含 IMAGE_JSON 指标编码的 featureResult。"
                + "CASE_REPORT 信息写入 clinicalText，IMAGE_JSON 信息写入 featureResult。严格使用下面候选指标和值域，不得生成患者姓名、电话、住址等身份信息。"
                + "返回 JSON 对象：{\"cases\":[{\"id\":\"唯一英文短名\",\"scenario\":\"中文场景\",\"clinicalText\":\"中文描述\",\"featureResult\":{}}]}。"
                + "每种病生成恰好3例。指标定义：" + writeJson(definitions);
        Map<String, Object> response = qwen.askJson(
                "你是医学软件测试数据生成器。生成的是完全虚构、无身份信息的测试病例，只能使用给定指标和值域。",
                prompt);
        if (!(response.get("cases") instanceof List<?> rows)) {
            throw new IllegalArgumentException("合成病例响应缺少 cases");
        }
        return rows.stream().filter(Map.class::isInstance).map(row -> (Map<String, Object>) row).toList();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException("指标定义序列化失败", exception);
        }
    }
}
