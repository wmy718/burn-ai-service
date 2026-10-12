package com.nytryx.burnai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nytryx.burnai.model.AggregateExtractionRequest;
import com.nytryx.burnai.model.ReportGenerationRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfSystemProperty(named = "externalEval", matches = "true")
class ExternalCaseEvaluationTest {
    @Autowired IndicatorExtractionService extraction;
    @Autowired ReportGenerationService reports;
    @Autowired ObjectMapper objectMapper;

    record Case(
            String id,
            String diseaseCode,
            String source,
            String clinicalText,
            Map<String, Object> featureResult,
            Map<String, Object> expectedExtracted,
            Map<String, Object> expectedDecisions
    ) {}

    @Test
    void evaluatePublishedCases() throws Exception {
        List<Case> cases = cases();
        List<Map<String, Object>> details = new ArrayList<>();
        int extractionCorrect = 0, extractionTotal = 0, decisionCorrect = 0, decisionTotal = 0;

        for (Case c : cases) {
            long started = System.nanoTime();
            var extracted = extraction.extract(new AggregateExtractionRequest(
                    "external-extract-" + c.id() + "-" + UUID.randomUUID(),
                    c.diseaseCode(), c.clinicalText(), c.featureResult()));
            var report = reports.generate(new ReportGenerationRequest(
                    c.diseaseCode(), "external-report-" + c.id() + "-" + UUID.randomUUID(), extracted.indicatorCodes()));

            Map<String, Boolean> extractionChecks = compare(c.expectedExtracted(), extracted.indicatorCodes());
            Map<String, Boolean> decisionChecks = compare(c.expectedDecisions(), report.decisionOutputCodes());
            extractionCorrect += extractionChecks.values().stream().filter(Boolean::booleanValue).count();
            extractionTotal += extractionChecks.size();
            decisionCorrect += decisionChecks.values().stream().filter(Boolean::booleanValue).count();
            decisionTotal += decisionChecks.size();

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", c.id());
            row.put("diseaseCode", c.diseaseCode());
            row.put("source", c.source());
            row.put("expectedExtracted", c.expectedExtracted());
            row.put("actualExtracted", select(extracted.indicatorCodes(), c.expectedExtracted().keySet()));
            row.put("extractionChecks", extractionChecks);
            row.put("expectedDecisions", c.expectedDecisions());
            row.put("actualDecisions", select(report.decisionOutputCodes(), c.expectedDecisions().keySet()));
            row.put("decisionChecks", decisionChecks);
            row.put("allDecisionOutputs", report.decisionOutputCodes());
            row.put("report", report.report());
            row.put("reportChecks", reportChecks(report.report()));
            row.put("extractionErrors", extracted.errors());
            row.put("extractionMs", extracted.elapsedMs());
            row.put("reportMs", report.elapsedMs());
            row.put("totalMs", (System.nanoTime() - started) / 1_000_000L);
            details.add(row);
            System.out.println("EXTERNAL CASE " + c.id() + " extraction=" + extractionChecks + " decisions=" + decisionChecks);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("caseCount", cases.size());
        result.put("extractionCorrect", extractionCorrect);
        result.put("extractionTotal", extractionTotal);
        result.put("extractionAccuracy", extractionTotal == 0 ? 0 : extractionCorrect * 1.0 / extractionTotal);
        result.put("decisionCorrect", decisionCorrect);
        result.put("decisionTotal", decisionTotal);
        result.put("decisionAccuracy", decisionTotal == 0 ? 0 : decisionCorrect * 1.0 / decisionTotal);
        result.put("limitations", List.of(
                "小样本公开病例验证，不代表临床准确率",
                "只评价论文明确给出的字段和诊断，未披露字段不计分",
                "中文测试文本是对公开英文病例事实的忠实结构化转述",
                "图像特征 JSON 根据论文明确描述构造，用于测试 8091 后端，不评价 Python 图像模型"
        ));
        result.put("cases", details);
        Path output = Path.of("test-results", "external-case-evaluation.json");
        Files.createDirectories(output.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), result);
        assertFalse(details.isEmpty());
    }

    private Map<String, Boolean> compare(Map<String, Object> expected, Map<String, Object> actual) {
        Map<String, Boolean> result = new LinkedHashMap<>();
        expected.forEach((code, value) -> result.put(code, equivalent(value, actual.get(code))));
        return result;
    }

    private boolean equivalent(Object expected, Object actual) {
        if (expected instanceof Number left && actual instanceof Number right) {
            return Math.abs(left.doubleValue() - right.doubleValue()) < 0.000001;
        }
        return java.util.Objects.equals(expected, actual);
    }

    private Map<String, Object> select(Map<String, Object> actual, java.util.Set<String> codes) {
        Map<String, Object> result = new LinkedHashMap<>();
        codes.forEach(code -> result.put(code, actual.get(code)));
        return result;
    }

    private Map<String, Object> reportChecks(Map<String, Object> report) {
        List<String> expected = List.of("病史回顾", "临床表现分析", "诊断意见", "治疗建议", "预后评估", "后续建议", "注意事项", "总结");
        boolean fieldsComplete = new ArrayList<>(report.keySet()).equals(expected);
        boolean allNonBlank = expected.stream().allMatch(key -> report.get(key) != null && !report.get(key).toString().isBlank());
        List<String> leakedInternalCodes = report.values().stream()
                .map(Object::toString)
                .flatMap(text -> java.util.regex.Pattern.compile("\\b(?:burn|scar|tbsa|vss|posas)_[a-z0-9_]+\\b", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(text).results())
                .map(java.util.regex.MatchResult::group).distinct().toList();
        Map<String, Object> checks = new LinkedHashMap<>();
        checks.put("fieldsCompleteAndOrdered", fieldsComplete);
        checks.put("allFieldsNonBlank", allNonBlank);
        checks.put("noInternalCodeLeak", leakedInternalCodes.isEmpty());
        checks.put("leakedInternalCodes", leakedInternalCodes);
        return checks;
    }

    private List<Case> cases() {
        return List.of(
                new Case(
                        "burn-gas-95pct",
                        "burn",
                        "https://pmc.ncbi.nlm.nih.gov/articles/PMC8898555/",
                        "患者24岁男性，煤气火焰烧伤，烧伤总面积95%TBSA，诊断为浅Ⅱ度烧伤。患者意识清楚、自主呼吸，无呼吸窘迫，无吸入伤表现。",
                        Map.of(
                                "burn_depth_visual_estimate", "浅Ⅱ度烧伤",
                                "burn_color_pattern", "粉红色",
                                "burn_surface_moisture", "湿润",
                                "burn_blister_presence", "存在"
                        ),
                        Map.of("patient_age_years", 24, "injury_mechanism", "火焰烧伤", "tbsa_percent_total", 95),
                        Map.of("burn_cause_class", "火焰烧伤", "burn_depth_class", "浅Ⅱ度烧伤")
                ),
                new Case(
                        "burn-electric-80pct",
                        "burn",
                        "https://pmc.ncbi.nlm.nih.gov/articles/PMC11041882/",
                        "患者20岁女性，接触15000伏架空电线产生电弧后发生电烧伤。烧伤总面积80%TBSA，其中Ⅲ度全层烧伤60%，同时存在吸入伤和呼吸功能不全。烧伤累及躯干、头部、双上肢和右下肢。",
                        Map.of(
                                "burn_depth_visual_estimate", "混合",
                                "burn_color_pattern", "混合",
                                "burn_surface_moisture", "干燥",
                                "superficial_partial_local_percent", 25,
                                "deep_partial_local_percent", 15,
                                "full_thickness_local_percent", 60
                        ),
                        Map.of("patient_age_years", 20, "injury_mechanism", "电烧伤", "electrical_exposure_presence", "存在", "tbsa_percent_total", 80),
                        Map.of("burn_cause_class", "电损伤未分型", "burn_depth_class", "混合")
                ),
                new Case(
                        "scar-keloid-posas",
                        "scar",
                        "https://pmc.ncbi.nlm.nih.gov/articles/PMC10314751/",
                        "患者69岁女性，双侧肩胛部多发瘢痕疙瘩，病程18年，持续增大并超出原始创缘，近两周瘙痒明显加重。最大病灶长8.5厘米、宽5.5厘米、厚1厘米。VSS总分11分，患者POSAS总分50分。",
                        Map.of("scar_length_cm", 8.5, "scar_width_cm", 5.5, "scar_height_mm", 10, "scar_boundary_extension_mm", 5, "scar_shape_pattern", "蟹足状"),
                        Map.of("patient_age_years", 69, "posas_total_score", 50, "scar_pruritus_presence", "存在"),
                        Map.of("scar_morphology_class", "瘢痕疙瘩", "hypertrophic_keloid_class", "瘢痕疙瘩可能", "vss_total_score", 11)
                ),
                new Case(
                        "scar-burn-graft-hypertrophic",
                        "scar",
                        "https://pmc.ncbi.nlm.nih.gov/articles/PMC12681260/",
                        "患者56岁女性，全层烧伤并植皮后2年，左侧腰背部形成约20乘10厘米的增生性、色素沉着性植皮瘢痕，瘢痕位于原始创缘内。治疗前改良VSS总分约10分，其中色素3分、血管2分、柔软度3分、高度2分。",
                        Map.of("scar_length_cm", 20, "scar_width_cm", 10, "scar_height_mm", 2, "scar_boundary_extension_mm", 0, "scar_shape_pattern", "片状"),
                        Map.of("patient_age_years", 56),
                        Map.of("hypertrophic_keloid_class", "增生性瘢痕可能", "vss_total_score", 10)
                )
        );
    }
}
