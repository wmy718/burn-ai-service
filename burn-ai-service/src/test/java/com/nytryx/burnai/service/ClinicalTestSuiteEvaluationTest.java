package com.nytryx.burnai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nytryx.burnai.model.AggregateExtractionRequest;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfSystemProperty(named = "live", matches = "true")
class ClinicalTestSuiteEvaluationTest {
    @Autowired IndicatorExtractionService extraction;
    @Autowired ReportGenerationService reports;
    @Autowired DecisionConfigRepository repository;
    @Autowired ObjectMapper objectMapper;

    @Test void runCompleteClinicalCases() throws Exception {
        Path source = Path.of("..", "integration-delivery", "烧伤与瘢痕测试用例.txt");
        List<Map<String, Object>> cases = objectMapper.readValue(source.toFile(), new TypeReference<>() {});
        String requestedCases = System.getProperty("suiteCases", "").trim();
        if (!requestedCases.isEmpty()) {
            var allowed = java.util.Set.of(requestedCases.split(","));
            cases = cases.stream().filter(item -> allowed.contains(item.get("caseId").toString())).toList();
        }
        List<Map<String, Object>> results = new ArrayList<>();
        Path output = Path.of("test-results", "clinical-test-suite-results.json");
        Files.createDirectories(output.getParent());

        for (Map<String, Object> testCase : cases) {
            String caseId = testCase.get("caseId").toString();
            String disease = testCase.get("diseaseCode").toString();
            String suffix = UUID.randomUUID().toString();
            @SuppressWarnings("unchecked") Map<String, Object> featureResult =
                    (Map<String, Object>) testCase.get("featureResult");
            String clinicalText = testCase.get("clinicalText").toString();

            var extractRequest = new AggregateExtractionRequest(
                    "suite-extract-" + caseId + "-" + suffix, disease, clinicalText, featureResult);
            long start = System.nanoTime();
            var extracted = extraction.extract(extractRequest);
            long extractionWallMs = (System.nanoTime() - start) / 1_000_000;

            var reportRequest = new ReportGenerationRequest(
                    disease, "suite-report-" + caseId + "-" + suffix, extracted.indicatorCodes());
            start = System.nanoTime();
            var report = reports.generate(reportRequest);
            long reportWallMs = (System.nanoTime() - start) / 1_000_000;

            var snapshot = repository.load(disease);
            List<String> requiredCodes = snapshot.rawIndicators().stream()
                    .filter(item -> item.isTextLike() && item.required()).map(item -> item.code()).toList();
            List<String> imageCodes = snapshot.rawIndicators().stream()
                    .filter(item -> item.isImageLike()).map(item -> item.code()).toList();
            List<String> missingRequired = requiredCodes.stream()
                    .filter(code -> !extracted.indicatorCodes().containsKey(code)).toList();
            List<String> missingImageInput = imageCodes.stream()
                    .filter(code -> !featureResult.containsKey(code)).toList();

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("caseId", caseId);
            row.put("name", testCase.get("name"));
            row.put("diseaseCode", disease);
            row.put("expectedFocus", testCase.get("expectedFocus"));
            row.put("extractionWallMs", extractionWallMs);
            row.put("reportWallMs", reportWallMs);
            row.put("missingRequiredTextCodes", missingRequired);
            row.put("missingImageInputCodes", missingImageInput);
            row.put("extractionErrors", extracted.errors());
            row.put("indicatorCodes", extracted.indicatorCodes());
            row.put("decisionOutputCodes", report.decisionOutputCodes());
            row.put("report", report.report());
            row.put("trees", report.trees());
            results.add(row);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), results);

            assertTrue(missingRequired.isEmpty(), caseId + " missing required text: " + missingRequired);
            assertTrue(missingImageInput.isEmpty(), caseId + " missing image input: " + missingImageInput);
            assertEquals(8, report.report().size(), caseId + " report section count");
            assertTrue(report.elapsedMs() < 300_000, caseId + " report timeout");
            if ("BURN-02".equals(caseId)) {
                assertEquals(List.of("疼痛加剧", "扩大性红斑", "局部皮温升高", "脓性分泌物"),
                        extracted.indicatorCodes().get("burn_local_infection_sign_list"));
            }
            if ("BURN-03".equals(caseId)) {
                assertEquals("电烧伤", extracted.indicatorCodes().get("injury_mechanism"));
                assertEquals(0, new java.math.BigDecimal(extracted.indicatorCodes().get("time_since_injury_hours").toString())
                        .compareTo(new java.math.BigDecimal("1440")));
                assertEquals("正常", extracted.indicatorCodes().get("patient_body_habitus"));
                assertEquals("存在", extracted.indicatorCodes().get("associated_trauma_presence"));
            }
            if ("SCAR-03".equals(caseId)) {
                assertEquals("中等", report.decisionOutputCodes().get("pathological_scar_risk_level"));
            }
            System.out.println("SUITE " + caseId + " extract=" + extractionWallMs + "ms report=" + reportWallMs + "ms outputs=" + report.decisionOutputCodes());
        }
    }
}
