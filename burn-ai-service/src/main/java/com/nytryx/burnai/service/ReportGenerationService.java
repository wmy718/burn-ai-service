package com.nytryx.burnai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nytryx.burnai.model.DecisionNodeDefinition;
import com.nytryx.burnai.model.DecisionTreeDefinition;
import com.nytryx.burnai.model.DecisionTreeExecutionRequest;
import com.nytryx.burnai.model.DecisionTreeExecutionResult;
import com.nytryx.burnai.model.IndicatorDefinition;
import com.nytryx.burnai.model.ReportGenerationRequest;
import com.nytryx.burnai.model.ReportGenerationResponse;
import com.nytryx.burnai.model.TreeRunSummary;
import com.nytryx.burnai.repo.DecisionConfigRepository;
import com.nytryx.burnai.repo.DecisionConfigRepository.ConfigSnapshot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class ReportGenerationService {
    private final IndicatorValueValidator validator = new IndicatorValueValidator();
    private final DecisionConfigRepository repository;
    private final ChineseIndicatorMapper mapper;
    private final ObjectMapper objectMapper;
    private final RunLogBroker logBroker;
    private final AiModelClient AiModelClient;
    private final DecisionTreeRuntimeService runtimeService;
    private final DecisionTreeGateService gateService;
    private final long slowStageWarningMs;
    private final long hardStageLimitMs;
    private final DiseaseCodeResolver diseaseCodeResolver;

    public ReportGenerationService(
            DecisionConfigRepository repository,
            ChineseIndicatorMapper mapper,
            ObjectMapper objectMapper,
            RunLogBroker logBroker,
            AiModelClient AiModelClient,
            DecisionTreeRuntimeService runtimeService,
            DecisionTreeGateService gateService,
            DiseaseCodeResolver diseaseCodeResolver,
            @Value("${burn-ai.performance.slow-stage-warning-ms:180000}") long slowStageWarningMs,
            @Value("${burn-ai.performance.hard-stage-limit-ms:300000}") long hardStageLimitMs
    ) {
        this.repository = repository;
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.logBroker = logBroker;
        this.AiModelClient = AiModelClient;
        this.runtimeService = runtimeService;
        this.gateService = gateService;
        this.diseaseCodeResolver = diseaseCodeResolver;
        this.slowStageWarningMs = slowStageWarningMs;
        this.hardStageLimitMs = hardStageLimitMs;
    }

    public ReportGenerationResponse generate(ReportGenerationRequest request) {
        PerformanceTimer timer = new PerformanceTimer();
        String requestId = request.requestId() == null || request.requestId().isBlank()
                ? UUID.randomUUID().toString()
                : request.requestId();
        String diseaseCode = diseaseCodeResolver.requireDiseaseCode(request.diseaseCode());
        ConfigSnapshot snapshot;
        try (PerformanceTimer.Stage ignored = timer.stage("load-config")) {
            snapshot = repository.load(diseaseCode);
        }
        Map<String, Object> normalizedInputs;
        try (PerformanceTimer.Stage ignored = timer.stage("normalize-chinese-indicators")) {
            normalizedInputs = mapper.toCodeValues(snapshot, request.indicators());
        }
        Map<String, Object> reportConfirmedInputs = reportConfirmedInputs(snapshot, normalizedInputs);

        logBroker.publish(requestId, "正在汇总您确认的文字指标和图片指标");

        Map<String, Object> facts = completeFactsWithDefaults(snapshot, normalizedInputs);
        Map<String, Object> explicitDecisionInputs = explicitDecisionInputs(snapshot, normalizedInputs);
        Map<String, Object> decisionOutputs = new LinkedHashMap<>(explicitDecisionInputs);
        Map<Long, String> treeStatuses = new LinkedHashMap<>();
        List<TreeRunSummary> treeSummaries = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        try (PerformanceTimer.Stage ignored = timer.stage("run-decision-trees")) {
            for (DecisionTreeDefinition tree : snapshot.trees()) {
                long treeStarted = System.nanoTime();
                DecisionTreeGateService.GateResult trigger = gateService.evaluateTrigger(tree, snapshot, facts);
                DecisionTreeGateService.GateResult prerequisite = trigger.runnable()
                        ? gateService.evaluatePrerequisite(tree, snapshot, facts, treeStatuses)
                        : trigger;
                if (!trigger.runnable() || !prerequisite.runnable()) {
                    long elapsedMs = (System.nanoTime() - treeStarted) / 1_000_000L;
                    String reason = trigger.runnable() ? prerequisite.reason() : trigger.reason();
                    treeStatuses.put(tree.id(), "SKIPPED");
                    TreeRunSummary summary = new TreeRunSummary(
                            tree.code(),
                            tree.name(),
                            "SKIPPED",
                            Map.of(),
                            elapsedMs,
                            reason
                    );
                    treeSummaries.add(summary);
                    continue;
                }
                logBroker.publish(requestId, "正在评估：" + clinicalStageName(tree.name()));
                DecisionTreeExecutionResult execution = runtimeService.execute(
                        tree,
                        new DecisionTreeExecutionRequest(facts, Map.of(), Map.of(), Map.of())
                );
                treeStatuses.put(tree.id(), execution.status());
                Map<String, Object> outputs = new LinkedHashMap<>(execution.decisionOutputs());
                explicitDecisionInputs.keySet().forEach(outputs::remove);
                facts.putAll(outputs);
                decisionOutputs.putAll(outputs);
                long elapsedMs = (System.nanoTime() - treeStarted) / 1_000_000L;
                if (elapsedMs > slowStageWarningMs) {
                    warnings.add(tree.code() + " 运行超过建议时间：" + elapsedMs + "ms");
                }
                if (elapsedMs > hardStageLimitMs) {
                    warnings.add(tree.code() + " 运行超过 5 分钟硬性目标：" + elapsedMs + "ms");
                }
                TreeRunSummary summary = new TreeRunSummary(
                        tree.code(),
                        tree.name(),
                        execution.status(),
                        mapper.toChineseValues(snapshot, outputs),
                        elapsedMs,
                        execution.validationErrors().isEmpty()
                                ? "得到 " + outputs.size() + " 个结论，访问 " + execution.visitedNodeKeys().size() + " 个节点"
                                : String.join("；", execution.validationErrors())
                );
                treeSummaries.add(summary);
                for (Map.Entry<String, Object> output : outputs.entrySet()) {
                    IndicatorDefinition definition = snapshot.indicatorByCode().get(output.getKey());
                    if (definition != null) logBroker.publish(requestId, "当前判断：" + definition.name() + "为" + output.getValue());
                }
            }
        }
        logBroker.publish(requestId, "正在核对各项诊断判断是否一致");
        enforceDecisionConsistency(facts, decisionOutputs, explicitDecisionInputs.keySet());
        Map<String, Object> reportDecisionOutputs = reportDecisionOutputs(reportConfirmedInputs, decisionOutputs);

        if (AiModelClient.available()) {
            try (PerformanceTimer.Stage ignored = timer.stage("qwen-clinical-reasoning-stream")) {
                streamClinicalReasoning(snapshot, requestId, reportConfirmedInputs, reportDecisionOutputs);
            } catch (RuntimeException exception) {
                warnings.add("临床判断摘要流式生成失败，已继续生成正式报告");
                logBroker.publish(requestId, "临床判断摘要暂未生成，正在继续整理正式报告");
            }
        }

        Map<String, Object> report;
        try (PerformanceTimer.Stage ignored = timer.stage("compose-structured-report")) {
            report = composeReport(snapshot, reportConfirmedInputs, reportDecisionOutputs, treeSummaries);
        }
        if (AiModelClient.available()) {
            try (PerformanceTimer.Stage ignored = timer.stage("qwen-report-generation")) {
                logBroker.publish(requestId, "正在把诊断结果整理为医生报告");
                report = composeReportWithQwen(snapshot, diseaseCode, requestId, reportConfirmedInputs, facts, reportDecisionOutputs, treeSummaries, report);
            }
        }
        logBroker.publish(requestId, "报告生成完成");
        return new ReportGenerationResponse(
                requestId,
                diseaseCode,
                mapper.toChineseValues(snapshot, normalizedInputs),
                normalizedInputs,
                mapper.toChineseValues(snapshot, decisionOutputs),
                decisionOutputs,
                report,
                treeSummaries,
                timer.timings(),
                timer.elapsedMs(),
                warnings
        );
    }

    void streamClinicalReasoning(
            ConfigSnapshot snapshot,
            String requestId,
            Map<String, Object> confirmedInputs,
            Map<String, Object> decisionOutputs
    ) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("医生已确认或图片检测得到的指标", mapper.toChineseValues(snapshot, confirmedInputs));
        input.put("规则诊断得到的结论", mapper.toChineseValues(snapshot, decisionOutputs));
        String prompt = """
                请根据下面已经确认的临床资料，生成面向医生的实时临床判断摘要。
                只写3至6句简短中文，每句表达一个可核对的判断步骤，并以句号结尾。
                推荐顺序：概括主要客观表现；说明关键指标之间的联系；指出当前倾向；说明资料限制或需复核项。
                只能使用输入中明确给出的事实，不得猜测，不得给出药物剂量或确定性治疗指令。
                不得出现数据库字段名、代码、决策树、节点、算法、大模型、JSON等技术词语。
                这是给医生看的判断摘要，不要输出隐含思维链、标题、编号、Markdown或最终报告正文。

                临床资料：
                %s
                """.formatted(writeJson(input));
        ClinicalSentencePublisher publisher = new ClinicalSentencePublisher(requestId);
        AiModelClient.askTextStreaming(
                "你是烧伤与瘢痕辅助诊断助手，只输出简洁、审慎、可核对的临床判断摘要。",
                prompt,
                publisher::accept
        );
        publisher.finish();
    }

    private final class ClinicalSentencePublisher {
        private static final int MAX_MESSAGES = 8;
        private final String requestId;
        private final StringBuilder pending = new StringBuilder();
        private int published;

        private ClinicalSentencePublisher(String requestId) {
            this.requestId = requestId;
        }

        private void accept(String delta) {
            if (published >= MAX_MESSAGES || delta == null || delta.isEmpty()) return;
            pending.append(delta);
            int boundary;
            while (published < MAX_MESSAGES && (boundary = nextSentenceBoundary(pending)) >= 0) {
                publish(pending.substring(0, boundary + 1));
                pending.delete(0, boundary + 1);
            }
        }

        private void finish() {
            if (published < MAX_MESSAGES && !pending.toString().isBlank()) publish(pending.toString());
        }

        private void publish(String sentence) {
            String cleaned = sentence
                    .replaceAll("(?m)^\\s*[-*#]+\\s*", "")
                    .replaceAll("(?m)^\\s*\\d+[.、)]\\s*", "")
                    .replaceAll("\\s+", " ")
                    .trim();
            if (cleaned.isBlank()) return;
            logBroker.publish(requestId, "临床判断：" + cleaned);
            published++;
        }

        private int nextSentenceBoundary(StringBuilder text) {
            for (int i = 0; i < text.length(); i++) {
                char ch = text.charAt(i);
                if (ch == '。' || ch == '！' || ch == '？' || ch == '\n') return i;
            }
            return -1;
        }
    }

    private Map<String, Object> composeReport(
            ConfigSnapshot snapshot,
            Map<String, Object> normalizedInputs,
            Map<String, Object> decisionOutputs,
            List<TreeRunSummary> treeSummaries
    ) {
        Map<String, Object> all = new LinkedHashMap<>(normalizedInputs);
        all.putAll(decisionOutputs);
        Map<String, Object> chineseOutputs = mapper.toChineseValues(snapshot, decisionOutputs);
        List<String> conclusions = chineseOutputs.entrySet().stream().limit(10)
                .map(entry -> entry.getKey() + "为" + entry.getValue()).toList();
        long completedTrees = treeSummaries.stream().filter(tree -> "COMPLETED".equals(tree.status())).count();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("病史回顾", linkedMap(
                "受伤时间", firstValue(all, "injury_date", "time_since_injury_hours", "scar_age_days"),
                "受伤原因", firstValue(all, "burn_cause_class", "injury_mechanism", "scar_origin"),
                "年龄", withUnit(firstValue(all, "patient_age_years"), "岁"),
                "治疗经过", firstValue(all, "medication_text", "scar_treatment_response_summary", "scar_treatment_modality_list"),
                "当前时间", java.time.LocalDate.now().toString()
        ));
        report.put("临床表现分析", "scar".equalsIgnoreCase(snapshot.indicators().stream().findFirst().map(IndicatorDefinition::diseaseCode).orElse(""))
                ? scarClinicalSection(all) : burnClinicalSection(all));
        report.put("诊断意见", Map.of("诊断", conclusions.isEmpty()
                ? "当前资料不足以形成明确诊断，请结合临床查体进一步确认。"
                : String.join("；", conclusions) + "。"));
        report.put("治疗建议", Map.of("当前阶段重点", List.of(
                "由医生结合当前病情确认主要诊断及处理优先级。",
                "根据创面或瘢痕变化选择适当的局部处理和功能保护措施。",
                "补充缺失的关键检查和量表信息后再决定具体治疗方案。",
                "治疗过程中定期复评，依据变化调整方案。"
        )));
        report.put("预后评估", "预后需结合后续创面愈合速度、感染控制、瘢痕增生趋势和功能恢复情况动态复评。若关键指标缺失，应在随访中补充记录。");
        report.put("后续建议", Map.of("table", linkedMap(
                "columns", List.of("时间节点", "推荐措施"),
                "rows", List.of(
                        linkedMap("时间节点", "近期", "推荐措施", "复核本次缺失或尚未明确的关键指标。"),
                        linkedMap("时间节点", "复诊时", "推荐措施", "比较创面或瘢痕变化，并重新评估治疗反应。"),
                        linkedMap("时间节点", "出现明显变化时", "推荐措施", "提前就诊，由临床医生重新评估。")
                )
        )));
        report.put("注意事项", "观察疼痛、红肿、渗出、异味、创面加深、瘢痕增厚及功能受限等变化；出现明显恶化或新的危险征象时，应及时由临床医生处理。");
        report.put("总结", completedTrees == 0
                ? "当前资料尚不足以形成完整诊断，本报告用于整理已录入信息，仍需结合临床查体和必要检查综合判断。"
                : "本次已结合结构化指标形成辅助诊疗意见，建议由医生核对关键指标后用于后续处置和随访。"
        );
        return report;
    }

    private Map<String, Object> composeReportWithQwen(
            ConfigSnapshot snapshot,
            String diseaseCode,
            String requestId,
            Map<String, Object> normalizedInputs,
            Map<String, Object> facts,
            Map<String, Object> decisionOutputs,
            List<TreeRunSummary> treeSummaries,
            Map<String, Object> fallback
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("confirmedIndicators", mapper.toChineseValues(snapshot, normalizedInputs));
        Map<String, Object> defaulted = new LinkedHashMap<>();
        snapshot.rawIndicators().stream()
                .filter(indicator -> !normalizedInputs.containsKey(indicator.code()))
                .forEach(indicator -> defaulted.put(indicator.code(), true));
        payload.put("internallyDefaultedIndicatorNames", mapper.toChineseValues(snapshot, defaulted).keySet());
        payload.put("decisionOutputs", mapper.toChineseValues(snapshot, decisionOutputs));
        payload.put("treeSummaries", treeSummaries);
        payload.put("fallbackDraft", fallback);
        String prompt = """
                请根据以下结构化指标和诊断结论，生成供医生阅读的固定八部分中文结构化报告。疾病类型为 %s。
                输出必须是 JSON 对象，只包含以下八个字段，并保持此顺序：
                病史回顾、临床表现分析、诊断意见、治疗建议、预后评估、后续建议、注意事项、总结。
                病史回顾必须是对象，字段依次为：受伤时间、受伤原因、年龄、治疗经过、当前时间。缺失内容填写空字符串，不得猜测。
                诊断意见必须是对象 {"诊断":"自然语言诊断"}。
                治疗建议必须是对象 {"当前阶段重点":["1. ...","2. ..."]}，建议使用医生能理解的自然语言。
                后续建议必须是对象 {"table":{"columns":["时间节点","推荐措施"],"rows":[{"时间节点":"...","推荐措施":"..."}]}}。
                如果疾病为 scar，临床表现分析必须包含：瘢痕分布与形态、颜色特征、表面形态、部位影响、温哥华瘢痕评分表、POSAS 2.0评分结果。
                温哥华瘢痕评分表必须使用 table，列为来源、色泽、厚度、血管分布、柔软度、评分、厚度估值；只能填已提供的分项，不能根据总分反推分项。
                POSAS 2.0评分结果必须使用 table，分别展示观察者量表辅助估算总分、患者量表换算总分、评分完整度和差异负担分层；两个分量表不得相加。
                如果疾病为 burn，临床表现分析必须包含：烧伤分布与形态、颜色特征、创面表面状态、特殊部位及功能影响、烧伤综合评估表。
                烧伤综合评估表必须使用 table，列为致伤原因、烧伤深度、烧伤面积、创面状态、特殊部位、风险等级。
                预后评估、注意事项、总结使用医生护士能看懂的自然语言。不能编造输入中没有的事实。
                confirmedIndicators 是前端确认提交的指标；internallyDefaultedIndicatorNames 中的指标只在程序内部使用了默认值。
                禁止把默认值及其否定含义写成患者病史、查体结果或“明确无”。只有 confirmedIndicators 中实际存在的内容才能表述为患者事实。
                决策输出若依赖缺失指标，只能写成“系统推理提示”或“需临床确认”，不得反推患者具有某项未确认表现。
                后续建议应说明复评、随访或需要补充的信息；注意事项应说明需要观察的变化和就医触发点；总结应概括本次主要判断及其资料限制。
                报告正文只能使用中文指标名称，不得出现数据库字段编码、内部 code、树节点编码或程序实现名称。
                严禁改写单位：创面局部面积 burn_wound_area_cm2 的单位是 cm²，烧伤总面积 tbsa_percent_total 的单位是 %%TBSA。
                瘢痕长度 scar_length_cm 和宽度 scar_width_cm 的单位是 cm；瘢痕高度 scar_height_mm 与超出边界距离 scar_boundary_extension_mm 的单位是 mm，严禁混淆。
                对没有决策输出的树，不要编造成阳性诊断或治疗指征。
                治疗建议必须使用“建议由医生评估”“可考虑”的辅助措辞。除非 confirmedIndicators 和 decisionOutputs 均明确支持，不得给出具体药名、剂量、给药途径、高压氧或侵入性操作的确定性医嘱。
                若形态分类、成熟阶段、活动水平或功能结论相互矛盾，必须说明资料冲突并建议复核，不得同时把矛盾结论都描述为确定事实。

                输入：
                %s
                """.formatted(diseaseCode, writeJson(payload));
        StringBuilder streamedText = new StringBuilder();
        java.util.Set<String> announcedSections = new java.util.HashSet<>();
        Map<String, Object> response = AiModelClient.askJsonStreaming(
                "你是烧伤与瘢痕辅助诊断报告生成助手，只输出符合要求的 JSON。",
                prompt,
                delta -> {
                    streamedText.append(delta);
                    for (String section : List.of("病史回顾", "临床表现分析", "诊断意见", "治疗建议", "预后评估", "后续建议", "注意事项", "总结")) {
                        if (streamedText.indexOf("\"" + section + "\"") >= 0 && announcedSections.add(section)) {
                            logBroker.publish(requestId, "正在整理" + section);
                        }
                    }
                }
        );
        Map<String, Object> report = new LinkedHashMap<>(fallback);
        for (String key : List.of("病史回顾", "临床表现分析", "诊断意见", "治疗建议", "预后评估", "后续建议", "注意事项", "总结")) {
            Object value = response.get(key);
            if (validReportSection(key, value)) {
                report.put(key, sanitizeReportValue(snapshot, value));
            }
        }
        return report;
    }

    private boolean validReportSection(String key, Object value) {
        if (value == null || Objects.toString(value).isBlank()) return false;
        if (java.util.Set.of("病史回顾", "临床表现分析", "诊断意见", "治疗建议", "后续建议").contains(key)) {
            return value instanceof Map<?, ?>;
        }
        return value instanceof String;
    }

    private Object sanitizeReportValue(ConfigSnapshot snapshot, Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> cleaned = new LinkedHashMap<>();
            map.forEach((key, item) -> cleaned.put(Objects.toString(key), sanitizeReportValue(snapshot, item)));
            return cleaned;
        }
        if (value instanceof List<?> list) return list.stream().map(item -> sanitizeReportValue(snapshot, item)).toList();
        return sanitizeReportText(snapshot, Objects.toString(value, ""));
    }

    private String sanitizeReportText(ConfigSnapshot snapshot, String text) {
        String sanitized = text;
        for (var indicator : snapshot.indicators()) {
            sanitized = sanitized.replace(indicator.code(), indicator.name());
        }
        return sanitized
                .replace("经验性干预", "进一步检查后由医生评估是否需要处理")
                .replace("无症状报告", "相关症状未确认");
    }

    private Map<String, Object> scarClinicalSection(Map<String, Object> all) {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("瘢痕分布与形态", sentence(all, "scar_morphology_class", "scar_shape_pattern", "scar_length_cm", "scar_width_cm"));
        section.put("颜色特征", sentence(all, "scar_color_pattern", "scar_redness_level", "scar_pigmentation_class"));
        section.put("表面形态", sentence(all, "scar_surface_pattern", "scar_height_mm", "scar_palpation_pliability"));
        section.put("部位影响", sentence(all, "scar_anatomic_site", "scar_functional_impact_presence", "scar_contracture_function_grade"));
        section.put("温哥华瘢痕评分表", Map.of("table", linkedMap(
                "columns", List.of("来源", "色泽", "厚度", "血管分布", "柔软度", "评分", "厚度估值"),
                "rows", List.of(linkedMap(
                        "来源", "AI辅助评估",
                        "色泽", display(all.get("vss_pigmentation_score")),
                        "厚度", display(all.get("vss_height_score")),
                        "血管分布", display(all.get("vss_vascularity_score")),
                        "柔软度", display(all.get("vss_pliability_score")),
                        "评分", display(all.get("vss_total_score")),
                        "厚度估值", withUnit(all.get("scar_height_mm"), "mm")
                ))
        )));
        section.put("POSAS 2.0评分结果", Map.of("table", linkedMap(
                "columns", List.of("观察者量表辅助估算总分", "患者量表换算总分", "评分完整度", "差异负担分层"),
                "rows", List.of(linkedMap(
                        "观察者量表辅助估算总分", withUnit(all.get("posas_observer_total_score"), "分"),
                        "患者量表换算总分", withUnit(all.get("posas_patient_total_score"), "分"),
                        "评分完整度", display(all.get("posas_completeness")),
                        "差异负担分层", display(all.get("posas_burden_grade"))
                ))
        )));
        return section;
    }

    private Map<String, Object> burnClinicalSection(Map<String, Object> all) {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("烧伤分布与形态", sentence(all, "clinician_burn_site_list", "burn_shape_pattern", "burn_wound_area_cm2"));
        section.put("颜色特征", sentence(all, "burn_color_pattern"));
        section.put("创面表面状态", sentence(all, "burn_surface_moisture", "burn_blister_presence", "burn_eschar_visual_presence", "burn_infection_visual_presence"));
        section.put("特殊部位及功能影响", sentence(all, "special_site_involvement", "burn_functional_impact_presence"));
        section.put("烧伤综合评估表", Map.of("table", linkedMap(
                "columns", List.of("致伤原因", "烧伤深度", "烧伤面积", "创面状态", "特殊部位", "风险等级"),
                "rows", List.of(linkedMap(
                        "致伤原因", display(firstValue(all, "burn_cause_class", "injury_mechanism")),
                        "烧伤深度", display(firstValue(all, "burn_depth_class", "burn_depth_visual_estimate")),
                        "烧伤面积", withUnit(all.get("tbsa_percent_total"), "%TBSA"),
                        "创面状态", sentence(all, "burn_color_pattern", "burn_surface_moisture", "burn_blister_presence"),
                        "特殊部位", display(firstValue(all, "special_site_involvement", "clinician_burn_site_list")),
                        "风险等级", display(firstValue(all, "burn_severity_level", "pathological_scar_risk_level"))
                ))
        )));
        return section;
    }

    private String sentence(Map<String, Object> values, String... codes) {
        List<String> parts = new ArrayList<>();
        for (String code : codes) {
            Object value = values.get(code);
            if (value != null && !display(value).isBlank()) parts.add(display(value));
        }
        return parts.isEmpty() ? "" : String.join("；", parts) + "。";
    }

    private Object firstValue(Map<String, Object> values, String... codes) {
        for (String code : codes) if (values.get(code) != null) return values.get(code);
        return "";
    }

    private String withUnit(Object value, String unit) {
        return value == null || display(value).isBlank() ? "" : display(value) + unit;
    }

    private String display(Object value) {
        if (value == null) return "";
        if (value instanceof List<?> list) return String.join("、", list.stream().map(Objects::toString).toList());
        return Objects.toString(value, "");
    }

    private Map<String, Object> linkedMap(Object... keyValues) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) result.put(Objects.toString(keyValues[i]), keyValues[i + 1]);
        return result;
    }

    private String clinicalStageName(String treeName) {
        return Objects.toString(treeName, "当前病情").replace("决策树", "").replace("树", "");
    }

    Map<String, Object> completeFactsWithDefaults(
            ConfigSnapshot snapshot,
            Map<String, Object> normalizedInputs
    ) {
        Map<String, Object> facts = new LinkedHashMap<>(normalizedInputs);
        for (var indicator : snapshot.rawIndicators()) {
            if (!facts.containsKey(indicator.code()) || facts.get(indicator.code()) == null) {
                var defaultValue = validator.resolveDefault(
                        indicator,
                        "default",
                        "前端未提交该指标，决策树内部使用数据库默认值"
                );
                if (defaultValue.value() != null && !"invalid".equals(defaultValue.status())) {
                    facts.put(indicator.code(), defaultValue.value());
                }
            }
        }
        return facts;
    }

    private Map<String, Object> explicitDecisionInputs(ConfigSnapshot snapshot, Map<String, Object> normalizedInputs) {
        Map<String, Object> result = new LinkedHashMap<>();
        normalizedInputs.forEach((code, value) -> {
            var indicator = snapshot.indicatorByCode().get(code);
            if (indicator != null && indicator.isDecision() && value != null) {
                result.put(code, value);
            }
        });
        return result;
    }

    private Map<String, Object> reportConfirmedInputs(ConfigSnapshot snapshot, Map<String, Object> normalizedInputs) {
        Map<String, Object> confirmed = new LinkedHashMap<>();
        normalizedInputs.forEach((code, value) -> {
            var indicator = snapshot.indicatorByCode().get(code);
            if (indicator == null || indicator.isDecision()) {
                confirmed.put(code, value);
                return;
            }
            Object configuredDefault = validator.resolveDefault(indicator, "default", "report provenance check").value();
            if (!equivalentValue(value, configuredDefault)) {
                confirmed.put(code, value);
            }
        });
        return confirmed;
    }

    private boolean equivalentValue(Object left, Object right) {
        if (left instanceof Number a && right instanceof Number b) {
            return Math.abs(a.doubleValue() - b.doubleValue()) < 0.000001;
        }
        return Objects.equals(left, right);
    }

    /**
     * Keeps the protocol-level decision outputs intact, but removes conclusions from the report draft when
     * every supporting observation came from an internal default. This prevents a missing symptom history
     * from being narrated as a confirmed absence.
     */
    private Map<String, Object> reportDecisionOutputs(
            Map<String, Object> confirmedInputs,
            Map<String, Object> decisionOutputs
    ) {
        Map<String, Object> reportOutputs = new LinkedHashMap<>(decisionOutputs);
        boolean hasConfirmedSymptom = java.util.Set.of(
                        "scar_pain_presence",
                        "scar_pruritus_presence",
                        "scar_functional_impact_presence",
                        "scar_pain_nrs",
                        "scar_pruritus_nrs",
                        "scar_stiffness_presence"
                ).stream().anyMatch(confirmedInputs::containsKey);
        if (!hasConfirmedSymptom) {
            reportOutputs.remove("scar_symptom_burden_grade");
        }
        boolean hasConfirmedTreatmentHistory = java.util.Set.of(
                        "scar_treatment_modality_list",
                        "scar_treatment_response_summary"
                ).stream().anyMatch(confirmedInputs::containsKey);
        if (!hasConfirmedTreatmentHistory) {
            reportOutputs.remove("scar_treatment_response_status");
            reportOutputs.remove("keloid_recurrence_risk");
            reportOutputs.remove("scar_management_path");
        }
        return reportOutputs;
    }

    private void enforceDecisionConsistency(Map<String, Object> facts, Map<String, Object> outputs, java.util.Set<String> protectedCodes) {
        if (("存在".equals(facts.get("scar_pain_presence"))
                || "存在".equals(facts.get("scar_pruritus_presence"))
                || "存在".equals(facts.get("scar_functional_impact_presence")))
                && "无".equals(outputs.get("scar_symptom_burden_grade"))) {
            outputs.put("scar_symptom_burden_grade", "轻度");
            facts.put("scar_symptom_burden_grade", "轻度");
        }
        if (!protectedCodes.contains("scar_morphology_class")) {
            if ("存在".equals(facts.get("scar_contracture_visual_presence"))
                    || "存在".equals(facts.get("scar_functional_impact_presence"))) {
                outputs.put("scar_morphology_class", "挛缩性瘢痕");
                facts.put("scar_morphology_class", "挛缩性瘢痕");
            } else if (numberGreaterThanZero(facts.get("scar_boundary_extension_mm"))
                    && "存在".equals(facts.get("scar_elevation_presence"))) {
                outputs.put("scar_morphology_class", "瘢痕疙瘩");
                facts.put("scar_morphology_class", "瘢痕疙瘩");
                if (!protectedCodes.contains("hypertrophic_keloid_class")) {
                    outputs.put("hypertrophic_keloid_class", "瘢痕疙瘩可能");
                    facts.put("hypertrophic_keloid_class", "瘢痕疙瘩可能");
                }
            } else if ("存在".equals(facts.get("scar_elevation_presence"))) {
                String morphology = "线状".equals(facts.get("scar_shape_pattern")) ? "线性增生性瘢痕" : "广泛增生性瘢痕";
                outputs.put("scar_morphology_class", morphology);
                facts.put("scar_morphology_class", morphology);
                if (!protectedCodes.contains("hypertrophic_keloid_class")) {
                    outputs.put("hypertrophic_keloid_class", "增生性瘢痕可能");
                    facts.put("hypertrophic_keloid_class", "增生性瘢痕可能");
                }
            } else if ("正常成熟瘢痕".equals(outputs.get("scar_morphology_class"))
                    && outputs.containsKey("scar_maturity_class")
                    && !"成熟".equals(outputs.get("scar_maturity_class"))) {
                outputs.put("scar_morphology_class", "未成熟瘢痕");
                facts.put("scar_morphology_class", "未成熟瘢痕");
            }
        }
        protectedCodes.forEach(code -> outputs.put(code, facts.get(code)));
    }

    private boolean numberGreaterThanZero(Object value) {
        return value instanceof Number number && number.doubleValue() > 0;
    }

    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception exception) {
            return Map.of();
        }
    }

    private List<String> readStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception exception) {
            return List.of();
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            return String.valueOf(value);
        }
    }
}
