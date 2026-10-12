package com.nytryx.burnai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nytryx.burnai.model.ExtractionResponse;
import com.nytryx.burnai.model.AggregateExtractionRequest;
import com.nytryx.burnai.model.IndicatorDefinition;
import com.nytryx.burnai.model.IndicatorOption;
import com.nytryx.burnai.model.IndicatorValue;
import com.nytryx.burnai.repo.DecisionConfigRepository;
import com.nytryx.burnai.repo.DecisionConfigRepository.ConfigSnapshot;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class IndicatorExtractionService {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(IndicatorExtractionService.class);
    private final IndicatorValueValidator validator = new IndicatorValueValidator();
    private final DecisionConfigRepository repository;
    private final ChineseIndicatorMapper mapper;
    private final AiModelClient AiModelClient;
    private final ObjectMapper objectMapper;
    private final ObjectMapper tolerantJsonMapper;
    private final DiseaseCodeResolver diseaseCodeResolver;
    private final RunLogBroker logBroker;

    public IndicatorExtractionService(
            DecisionConfigRepository repository,
            ChineseIndicatorMapper mapper,
            AiModelClient AiModelClient,
            ObjectMapper objectMapper,
            DiseaseCodeResolver diseaseCodeResolver,
            RunLogBroker logBroker
    ) {
        this.repository = repository;
        this.mapper = mapper;
        this.AiModelClient = AiModelClient;
        this.objectMapper = objectMapper;
        this.tolerantJsonMapper = objectMapper.copy()
                .configure(JsonReadFeature.ALLOW_SINGLE_QUOTES.mappedFeature(), true)
                .configure(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES.mappedFeature(), true)
                .configure(JsonReadFeature.ALLOW_TRAILING_COMMA.mappedFeature(), true);
        this.diseaseCodeResolver = diseaseCodeResolver;
        this.logBroker = logBroker;
    }

    public ExtractionResponse extract(AggregateExtractionRequest request) {
        PerformanceTimer timer = new PerformanceTimer();
        String requestId = request.requestId() == null || request.requestId().isBlank()
                ? UUID.randomUUID().toString()
                : request.requestId();
        String diseaseCode = diseaseCodeResolver.requireDiseaseCode(request.diseaseCode(), request.featureResult());
        ConfigSnapshot snapshot;
        try (PerformanceTimer.Stage ignored = timer.stage("load-config")) {
            snapshot = repository.load(diseaseCode);
        }

        logBroker.publish(requestId, "开始统一指标提取");
        Map<String, IndicatorValue> textValues = new LinkedHashMap<>();
        Map<String, IndicatorValue> imageValues = new LinkedHashMap<>();
        Map<String, IndicatorValue> merged = new LinkedHashMap<>();
        List<String> errors = new java.util.ArrayList<>();

        try {
            logBroker.publish(requestId, "正在阅读护士录入的病情描述");
            try (PerformanceTimer.Stage ignored = timer.stage("text-extraction")) {
                textValues.putAll(extractTextDeterministic(snapshot, request.clinicalText()));
                textValues.putAll(extractExplicitClinicalFacts(snapshot, request.clinicalText()));
                publishRecognizedIndicators(requestId, snapshot, textValues, "文字中已识别");
                if (AiModelClient.available()) {
                    textValues.putAll(extractTextWithQwen(snapshot, request.clinicalText(), textValues, requestId));
                }
            }
            errors.addAll(extractionErrors(textValues));
            logBroker.publish(requestId, "文字信息分析完成，共识别 " + observedCount(textValues) + " 项有效指标");

            logBroker.publish(requestId, "开始解析检测后的图片");
            try (PerformanceTimer.Stage ignored = timer.stage("image-json-extraction")) {
                Map<String, Object> flat = new LinkedHashMap<>();
                flatten("", request.featureResult(), flat);
                imageValues.putAll(extractImageDeterministic(snapshot, flat, request.featureResult()));
                publishRecognizedIndicators(requestId, snapshot, imageValues, "图片中已识别");
                if (AiModelClient.available() && shouldAskQwenForImage(imageValues, request.featureResult())) {
                    imageValues.putAll(extractImageWithQwen(snapshot, request.featureResult(), imageValues, requestId));
                }
            }
            errors.addAll(extractionErrors(imageValues));
            logBroker.publish(requestId, "图片检测结果分析完成，共识别 " + observedCount(imageValues) + " 项有效指标；这些指标将返回前端供核对");

            logBroker.publish(requestId, "正在核对文字信息与图片检测结果");
            try (PerformanceTimer.Stage ignored = timer.stage("merge-validation")) {
                mergeObservedValues(snapshot, textValues, imageValues, merged, errors);
                copyExplicitDecisionValues(snapshot, textValues, merged);
            }

            logBroker.publish(requestId, "处理指标默认值");
            try (PerformanceTimer.Stage ignored = timer.stage("apply-defaults")) {
                applyPublishedDefaults(snapshot, merged);
            }
            logBroker.publish(requestId, "指标提取完成");
        } catch (RuntimeException exception) {
            logBroker.publish(requestId, "指标提取失败");
            throw exception;
        }

        Map<String, IndicatorValue> editable = new LinkedHashMap<>();
        merged.forEach((code, value) -> {
            IndicatorDefinition definition = snapshot.indicatorByCode().get(code);
            if (definition != null && (definition.isTextLike() || definition.isImageLike())) editable.put(code, value);
        });
        Map<String, Object> codeValues = toValueMap(editable);
        return new ExtractionResponse(
                requestId,
                diseaseCode,
                mapper.toChineseValues(snapshot, codeValues),
                codeValues,
                timer.timings(),
                timer.elapsedMs(),
                List.copyOf(errors)
        );
    }

    private void publishRecognizedIndicators(String requestId, ConfigSnapshot snapshot,
                                             Map<String, IndicatorValue> values, String prefix) {
        values.values().stream().filter(this::isObserved).forEach(value -> {
            IndicatorDefinition definition = snapshot.indicatorByCode().get(value.code());
            if (definition != null) {
                String unit = definition.unit() == null || definition.unit().isBlank() ? "" : " " + definition.unit();
                logBroker.publish(requestId, prefix + "：" + definition.name() + "为" + value.value() + unit);
            }
        });
    }

    private long observedCount(Map<String, IndicatorValue> values) {
        return values.values().stream().filter(this::isObserved).count();
    }

    private Map<String, IndicatorValue> extractTextDeterministic(ConfigSnapshot snapshot, String clinicalText) {
        Map<String, IndicatorValue> internal = new LinkedHashMap<>();
        String normalizedText = clinicalText.toLowerCase(Locale.ROOT);
        for (IndicatorDefinition indicator : snapshot.rawIndicators()) {
            if (!indicator.isTextLike()) {
                continue;
            }
            Object value = matchTextValue(indicator, normalizedText);
            if (value != null) {
                internal.put(indicator.code(), validator.resolve(indicator, value, "predicted", "clinical_text", "文本命中指标值或指标名称。"));
            }
        }
        return internal;
    }

    private Map<String, IndicatorValue> extractImageDeterministic(
            ConfigSnapshot snapshot,
            Map<String, Object> flat,
            Map<String, Object> featureResult
    ) {
        Map<String, IndicatorValue> internal = new LinkedHashMap<>();
        for (IndicatorDefinition indicator : snapshot.rawIndicators()) {
            if (!indicator.isImageLike()) {
                continue;
            }
            Object raw = findImageRawValue(indicator, flat, featureResult);
            if (raw != null) internal.put(indicator.code(), validator.resolve(indicator, raw, "predicted", "python_feature_json", "Python JSON 字段对齐"));
        }
        deriveImageRoutingFacts(snapshot, internal);
        return internal;
    }

    private Map<String, IndicatorValue> extractTextWithQwen(
            ConfigSnapshot snapshot,
            String clinicalText,
            Map<String, IndicatorValue> localHits,
            String requestId
    ) {
        return extractBatches(snapshot.rawIndicators().stream().filter(IndicatorDefinition::isTextLike).toList(),
                clinicalText, "clinical_text_qwen", localHits, requestId, "文字中已识别");
    }

    private Map<String, IndicatorValue> extractExplicitClinicalFacts(ConfigSnapshot snapshot, String clinicalText) {
        Map<String, IndicatorValue> result = new LinkedHashMap<>();
        String text = Objects.toString(clinicalText, "");
        putExplicitNumber(snapshot, result, "time_since_injury_hours", text,
                "(?:伤后|受伤后|烧伤后)[^。；;,，\\n]{0,8}?(\\d+(?:\\.\\d+)?)\\s*小时");
        putExplicitNumber(snapshot, result, "tbsa_percent_total", text,
                "(?i)(?:烧伤总面积|TBSA)[^。；;\\n]{0,16}?(\\d+(?:\\.\\d+)?)\\s*%?(?:\\s*TBSA)?",
                "(?i)(\\d+(?:\\.\\d+)?)\\s*%\\s*TBSA");
        putExplicitNumber(snapshot, result, "vss_total_score", text,
                "(?i)(?:VSS|温哥华瘢痕量表)(?:总分|评分|得分)?[^。；;\\n]{0,12}?(\\d+(?:\\.\\d+)?)\\s*分?");
        putExplicitNumber(snapshot, result, "patient_age_years", text,
                "(?:患者|病人)?[^。；;\\n]{0,6}?(\\d+(?:\\.\\d+)?)\\s*岁");
        putExplicitNumber(snapshot, result, "scar_pain_nrs", text,
                "(?:瘢痕)?疼痛(?:\\s*NRS|评分)?[^。；;,，\\n]{0,8}?(\\d+(?:\\.\\d+)?)\\s*分?");
        putExplicitNumber(snapshot, result, "scar_pruritus_nrs", text,
                "(?:瘢痕)?瘙痒(?:\\s*NRS|评分)?[^。；;,，\\n]{0,8}?(\\d+(?:\\.\\d+)?)\\s*分?");
        putExplicitNumber(snapshot, result, "scar_stiffness_nrs", text,
                "(?:瘢痕)?僵硬(?:\\s*NRS|评分)?[^。；;,，\\n]{0,8}?(\\d+(?:\\.\\d+)?)\\s*分?");
        putExplicitNumber(snapshot, result, "posas_patient_color_difference_score", text,
                "(?:患者感受的)?(?:瘢痕)?颜色差异评分[^。；;,，\\n]{0,8}?(\\d+(?:\\.\\d+)?)\\s*分?");
        putExplicitNumber(snapshot, result, "posas_patient_thickness_difference_score", text,
                "(?:患者感受的)?(?:瘢痕)?厚度差异评分[^。；;,，\\n]{0,8}?(\\d+(?:\\.\\d+)?)\\s*分?");
        putExplicitNumber(snapshot, result, "posas_patient_irregularity_score", text,
                "(?:患者感受的)?(?:瘢痕)?(?:表面)?不规则评分[^。；;,，\\n]{0,8}?(\\d+(?:\\.\\d+)?)\\s*分?");
        putExplicitNumber(snapshot, result, "scar_height_mm", text,
                "瘢痕(?:隆起)?高度[^。；;,，\\n]{0,8}?(\\d+(?:\\.\\d+)?)\\s*(?:毫米|mm)?");
        putExplicitNumber(snapshot, result, "scar_time_since_injury_days", text,
                "(?:伤后时长|受伤后|伤后)[^。；;,，\\n]{0,8}?(\\d+(?:\\.\\d+)?)\\s*天");

        putFirstExplicitOption(snapshot, result, "scar_origin", text,
                Map.ofEntries(
                        Map.entry("瘢痕来源为烧伤", "烧伤"), Map.entry("瘢痕来源为手术", "手术"),
                        Map.entry("瘢痕来源为创伤", "创伤"), Map.entry("瘢痕来源为粉刺", "粉刺"),
                        Map.entry("瘢痕来源为感染", "感染"), Map.entry("瘢痕来源为穿孔或穿刺", "穿孔或穿刺"),
                        Map.entry("瘢痕来源为疫苗接种", "疫苗接种"), Map.entry("瘢痕来源为咬伤", "咬伤"),
                        Map.entry("瘢痕来源为自发或损伤史不详", "自发或损伤史不详")
                ));
        putFirstExplicitOption(snapshot, result, "scar_growth_course", text,
                Map.ofEntries(
                        Map.entry("治疗后复发", "治疗后复发"), Map.entry("延伸到伤口之外", "延伸到伤口之外"),
                        Map.entry("逐渐消退", "逐渐消退"), Map.entry("缓慢增长", "缓慢增长"),
                        Map.entry("快速增长", "快速增长"), Map.entry("生长过程稳定", "稳定")
                ));
        putFirstExplicitOption(snapshot, result, "fitzpatrick_skin_type", text.toLowerCase(Locale.ROOT).replaceAll("\\s+", ""),
                Map.ofEntries(
                        Map.entry("fitzpatrick皮肤分型为一型", "一型"), Map.entry("fitzpatrick皮肤分型为二型", "二型"),
                        Map.entry("fitzpatrick皮肤分型为三型", "三型"), Map.entry("fitzpatrick皮肤分型为四型", "四型"),
                        Map.entry("fitzpatrick皮肤分型为五型", "五型"), Map.entry("fitzpatrick皮肤分型为六型", "六型")
                ));
        putFirstExplicitOption(snapshot, result, "hormonal_life_stage", text,
                Map.ofEntries(
                        Map.entry("青春期前", "青春期前"), Map.entry("青春期", "青春期"),
                        Map.entry("非妊娠成人", "非妊娠成人"), Map.entry("产后", "产后"),
                        Map.entry("绝经后", "绝经后"), Map.entry("妊娠", "孕")
                ));
        putExplicitScarTreatments(snapshot, result, text);
        putExplicitScarFunctionalTypes(snapshot, result, text);

        putFirstExplicitOption(snapshot, result, "injury_mechanism", text,
                Map.ofEntries(
                        Map.entry("热液烫伤", "热液烫伤"), Map.entry("蒸汽伤", "蒸汽伤"),
                        Map.entry("火焰烧伤", "火焰烧伤"), Map.entry("热接触伤", "热接触伤"),
                        Map.entry("热油烫伤", "热油烫伤"), Map.entry("化学烧伤", "化学烧伤"),
                        Map.entry("高压电烧伤", "电烧伤"), Map.entry("低压电烧伤", "电烧伤"),
                        Map.entry("电烧伤", "电烧伤"), Map.entry("电击伤", "电烧伤"),
                        Map.entry("雷击伤", "雷击伤"), Map.entry("冷伤", "冷伤"),
                        Map.entry("摩擦伤", "摩擦伤"), Map.entry("放射性损伤", "放射性损伤")
                ));
        putFirstExplicitOption(snapshot, result, "patient_body_habitus", text,
                Map.ofEntries(
                        Map.entry("正常体型", "正常"), Map.entry("体型正常", "正常"),
                        Map.entry("低体重", "低体重"), Map.entry("超重", "超重"),
                        Map.entry("肥胖", "肥胖"), Map.entry("恶病质", "恶病质"),
                        Map.entry("全身水肿", "全身水肿")
                ));
        if (text.matches("(?s).*(?:无合并创伤|合并创伤状态(?:为|是|：|:)?不存在).*")) {
            putExplicitValue(snapshot, result, "associated_trauma_presence", "不存在", "病例文本明确排除合并创伤");
        } else if (text.matches("(?s).*(?:合并创伤状态(?:为|是|：|:)?存在|合并.{0,18}(?:跌落|骨折|外伤|创伤)).*")) {
            putExplicitValue(snapshot, result, "associated_trauma_presence", "存在", "病例文本明确描述合并创伤");
        }
        putExplicitLocalInfectionSigns(snapshot, result, text);

        if (text.contains("瘢痕疙瘩")) {
            putExplicitValue(snapshot, result, "scar_morphology_class", "瘢痕疙瘩", "病例文本明确诊断为瘢痕疙瘩");
            putExplicitValue(snapshot, result, "hypertrophic_keloid_class", "瘢痕疙瘩可能", "病例文本明确诊断为瘢痕疙瘩");
        } else if (text.matches("(?s).*增生性.{0,8}瘢痕.*")) {
            String morphology = text.matches("(?s).*(线状|切口).*增生性瘢痕.*") ? "线性增生性瘢痕" : "广泛增生性瘢痕";
            putExplicitValue(snapshot, result, "scar_morphology_class", morphology, "病例文本明确诊断为增生性瘢痕");
            putExplicitValue(snapshot, result, "hypertrophic_keloid_class", "增生性瘢痕可能", "病例文本明确诊断为增生性瘢痕");
        } else if (text.contains("挛缩性瘢痕") || (text.contains("挛缩") && (text.contains("功能受限") || text.contains("功能障碍")))) {
            putExplicitValue(snapshot, result, "scar_morphology_class", "挛缩性瘢痕", "病例文本明确描述瘢痕挛缩并影响功能");
        }

        if (text.contains("烧伤后") || text.matches("(?s).*烧伤.{0,12}(?:瘢痕|挛缩).*")) {
            putExplicitValue(snapshot, result, "scar_origin", "烧伤", "病例文本明确为烧伤后瘢痕");
        }
        if ((text.contains("功能受限") || text.contains("功能障碍") || text.contains("影响梳头") || text.contains("影响进食"))
                && !text.matches("(?s).*(无功能受限|无功能障碍|功能受限(?:类别)?(?:为|是|：|:)?明确无|功能障碍(?:为|是|：|:)?明确无).*")) {
            putExplicitValue(snapshot, result, "scar_functional_impact_presence", "存在", "病例文本明确描述功能影响");
        }
        if (text.contains("瘙痒") && !text.matches("(?s).*(无瘙痒|否认瘙痒|瘙痒不存在|未见瘙痒).*")) {
            putExplicitValue(snapshot, result, "scar_pruritus_presence", "存在", "病例文本明确描述瘙痒");
        }
        if ((text.contains("疼痛") || text.contains("触痛"))
                && !text.matches("(?s).*(无疼痛|无触痛|否认疼痛|否认触痛|疼痛不存在|触痛不存在).*")) {
            putExplicitValue(snapshot, result, "scar_pain_presence", "存在", "病例文本明确描述疼痛或触痛");
        }
        if (text.contains("绳索样条带")) {
            putExplicitValue(snapshot, result, "scar_palpation_pliability", "绳索样条带", "病例文本明确触诊表现");
        } else if (text.contains("永久性缩短") || text.contains("永久缩短")) {
            putExplicitValue(snapshot, result, "scar_palpation_pliability", "挛缩并永久缩短", "病例文本明确触诊表现");
        } else if (text.contains("坚实，块状抗压") || text.contains("坚实、块状抗压")) {
            putExplicitValue(snapshot, result, "scar_palpation_pliability", "坚实，块状抗压", "病例文本明确触诊表现");
        } else if (text.contains("受压可屈服")) {
            putExplicitValue(snapshot, result, "scar_palpation_pliability", "受压可屈服", "病例文本明确触诊表现");
        } else if (text.contains("柔软，轻压可变形") || text.contains("柔软、轻压可变形")) {
            putExplicitValue(snapshot, result, "scar_palpation_pliability", "柔软，轻压可变形", "病例文本明确触诊表现");
        } else if (text.matches("(?s).*触诊(?:柔软度)?(?:为|是|：|:)?.{0,4}正常.*")) {
            putExplicitValue(snapshot, result, "scar_palpation_pliability", "正常", "病例文本明确触诊表现");
        }
        return result;
    }

    private void putFirstExplicitOption(
            ConfigSnapshot snapshot,
            Map<String, IndicatorValue> result,
            String code,
            String text,
            Map<String, String> phraseToValue
    ) {
        phraseToValue.entrySet().stream()
                .filter(entry -> text.contains(entry.getKey()))
                .max(java.util.Comparator.comparingInt(entry -> entry.getKey().length()))
                .ifPresent(entry -> putExplicitValue(snapshot, result, code, entry.getValue(), "病例文本明确描述该指标"));
    }

    private void putExplicitLocalInfectionSigns(
            ConfigSnapshot snapshot,
            Map<String, IndicatorValue> result,
            String text
    ) {
        String marker = "局部感染临床征象";
        int start = text.indexOf(marker);
        if (start < 0) return;
        String segment = text.substring(start, Math.min(text.length(), start + 100)).split("[。；;\\n]", 2)[0];
        if (segment.contains("明确无") || segment.contains("不存在") || segment.contains("未见")) {
            putExplicitValue(snapshot, result, "burn_local_infection_sign_list", List.of("明确无"), "病例文本明确排除局部感染征象");
            return;
        }
        List<String> allowed = List.of("疼痛加剧", "扩大性红斑", "局部皮温升高", "肿胀", "脓性分泌物", "恶臭", "创面加深", "植皮丢失", "坏死组织");
        List<String> matched = allowed.stream().filter(segment::contains).toList();
        if (!matched.isEmpty()) {
            putExplicitValue(snapshot, result, "burn_local_infection_sign_list", matched, "病例文本明确列出局部感染征象");
        }
    }

    private void putExplicitScarTreatments(
            ConfigSnapshot snapshot,
            Map<String, IndicatorValue> result,
            String text
    ) {
        int marker = Math.max(text.indexOf("既往治疗"), Math.max(text.indexOf("既往接受"), text.indexOf("正在")));
        if (marker < 0) return;
        String segment = text.substring(marker, Math.min(text.length(), marker + 100)).split("[。；;\\n]", 2)[0];
        List<String> options = List.of("留观", "硅酮凝胶", "硅酮贴片", "压力治疗", "胶带减张", "按摩", "支具康复",
                "外用糖皮质激素", "瘢痕内糖皮质激素注射", "5-氟尿嘧啶", "博来霉素", "冷冻治疗", "脉冲染料激光",
                "剥脱性点阵激光", "非剥脱性点阵激光", "手术", "放射治疗", "微针治疗");
        List<String> matched = options.stream().filter(segment::contains).toList();
        if (!matched.isEmpty()) {
            putExplicitValue(snapshot, result, "scar_treatment_modality_list", matched, "病例文本明确列出瘢痕治疗类别");
        } else if (text.matches("(?s).*(?:未接受.{0,8}瘢痕治疗|既往治疗(?:为|是|：|:)?明确无).*")) {
            putExplicitValue(snapshot, result, "scar_treatment_modality_list", List.of("明确无"), "病例文本明确未接受瘢痕治疗");
        }
    }

    private void putExplicitScarFunctionalTypes(
            ConfigSnapshot snapshot,
            Map<String, IndicatorValue> result,
            String text
    ) {
        int marker = text.indexOf("功能受限类别");
        if (marker < 0) return;
        String segment = text.substring(marker, Math.min(text.length(), marker + 60)).split("[。；;\\n]", 2)[0];
        if (segment.contains("明确无")) {
            putExplicitValue(snapshot, result, "scar_functional_impairment_type_list", List.of("明确无"), "病例文本明确无功能受限类别");
            return;
        }
        List<String> options = List.of("关节运动", "眼睑闭合", "张口", "颈部运动", "手抓握功能", "步态", "会阴功能", "呼吸胸廓扩张");
        List<String> matched = options.stream().filter(segment::contains).toList();
        if (!matched.isEmpty()) {
            putExplicitValue(snapshot, result, "scar_functional_impairment_type_list", matched, "病例文本明确列出功能受限类别");
        }
    }

    private void putExplicitNumber(ConfigSnapshot snapshot, Map<String, IndicatorValue> result, String code, String text, String... patterns) {
        for (String regex : patterns) {
            var matcher = java.util.regex.Pattern.compile(regex).matcher(text);
            if (matcher.find()) {
                putExplicitValue(snapshot, result, code, new java.math.BigDecimal(matcher.group(1)), "病例文本明确给出该数值");
                return;
            }
        }
    }

    private void putExplicitValue(ConfigSnapshot snapshot, Map<String, IndicatorValue> result, String code, Object value, String reason) {
        IndicatorDefinition indicator = snapshot.indicatorByCode().get(code);
        if (indicator != null) {
            result.put(code, validator.resolve(indicator, value, "provided", "clinical_text_explicit", reason));
        }
    }

    private void copyExplicitDecisionValues(ConfigSnapshot snapshot, Map<String, IndicatorValue> textValues, Map<String, IndicatorValue> merged) {
        for (IndicatorDefinition indicator : snapshot.indicators()) {
            IndicatorValue value = textValues.get(indicator.code());
            if (indicator.isDecision() && isObserved(value)) {
                merged.put(indicator.code(), value);
            }
        }
    }

    private void deriveImageRoutingFacts(ConfigSnapshot snapshot, Map<String, IndicatorValue> values) {
        IndicatorValue height = values.get("scar_height_mm");
        if (isObserved(height) && height.value() instanceof Number number && number.doubleValue() > 0) {
            IndicatorDefinition elevation = snapshot.indicatorByCode().get("scar_elevation_presence");
            if (elevation != null && !isObserved(values.get(elevation.code()))) {
                values.put(elevation.code(), validator.resolve(elevation, "存在", "calculated", "python_feature_json", "瘢痕高度大于0，推导隆起存在"));
            }
        }
    }

    private Map<String, IndicatorValue> extractImageWithQwen(ConfigSnapshot snapshot, Map<String,Object> input,
                                                              Map<String,IndicatorValue> hits, String requestId) {
        return extractBatches(snapshot.rawIndicators().stream().filter(IndicatorDefinition::isImageLike).toList(),
                writeJson(input), "python_feature_json_qwen", hits, requestId, "图片中已识别");
    }

    private Map<String, IndicatorValue> extractBatches(List<IndicatorDefinition> candidates, String input, String source,
                                                        Map<String,IndicatorValue> hits, String requestId, String logPrefix) {
        Map<String,IndicatorValue> result = new LinkedHashMap<>();
        Set<String> publishedCodes = new java.util.HashSet<>();
        List<IndicatorDefinition> pending = candidates.stream().filter(i -> !hits.containsKey(i.code()) || "invalid".equals(hits.get(i.code()).status())).toList();
        for(int offset=0;offset<pending.size();offset+=24) {
            List<IndicatorDefinition> batch=pending.subList(offset,Math.min(offset+24,pending.size()));
            for(int attempt=0;attempt<2;attempt++) {
                try {
                    StreamingIndicatorCollector collector = new StreamingIndicatorCollector(
                            batch, source, requestId, logPrefix, result, publishedCodes);
                    Map<String, Object> modelResponse = AiModelClient.askJsonStreaming(
                            "你是临床指标抽取器。只依据输入，不得猜测。仅返回一个合法 JSON 对象，结构必须是 "
                                    + "{\"items\":[{\"code\":\"候选编码\",\"value\":值,\"status\":\"predicted|unknown|not_applicable\",\"reason\":\"简短依据\"}]}。"
                                    + "不要输出 Markdown、解释或候选列表之外的 code。SINGLE_SELECT 的 value 必须与该指标 options 中某一项逐字相同；"
                                    + "MULTI_SELECT 的 value 必须是 options 中的字符串数组；NUMBER 必须是 JSON 数字且满足 valueDomain；TEXT 必须是字符串并满足 valueDomain。"
                                    + "信息未提及时可以不输出该指标，不要用默认值伪装成提取结果。",
                            "输入："+input+"\n候选指标（options 与 valueDomain 均来自数据库，只能按其取值）："
                                    +writeJson(batch.stream().map(this::indicatorPromptView).toList()),
                            collector::accept
                    );
                    collector.acceptStructuredResponse(modelResponse);
                    collector.finish();
                    List<IndicatorDefinition> invalid = batch.stream()
                            .filter(i -> result.containsKey(i.code()) && "invalid".equals(result.get(i.code()).status()))
                            .toList();
                    if (attempt == 0 && !invalid.isEmpty()) continue;
                    for (IndicatorDefinition indicator : invalid) {
                        result.put(indicator.code(), validator.resolveDefault(indicator, source,
                                "模型失败：重试后仍未通过数据库值域校验，已使用默认值"));
                    }
                    break;
                } catch(RuntimeException ex) {
                    String cause = ex instanceof IllegalArgumentException
                            ? "模型返回内容无法解析或指标值未通过校验"
                            : "模型服务调用未能完成";
                    String action = attempt == 0 ? "正在重试" : "重试后将使用数据库默认值";
                    LOG.info("指标抽取批次校验与重试：source={}, offset={}, attempt={}, 原因={}，处理方式={}",
                            source, offset, attempt + 1, cause, action);
                    if (attempt == 1) {
                        for (IndicatorDefinition indicator : batch) {
                            IndicatorValue current = result.get(indicator.code());
                            if (current == null || "invalid".equals(current.status())) {
                                result.put(indicator.code(), validator.resolveDefault(indicator, source,
                                        "模型失败：" + cause + "；重试后仍未通过，已使用数据库默认值"));
                            }
                        }
                    }
                }
            }
        }
        return result;
    }

    private final class StreamingIndicatorCollector {
        private final Map<String, IndicatorDefinition> candidates = new LinkedHashMap<>();
        private final String source;
        private final String requestId;
        private final String logPrefix;
        private final Map<String, IndicatorValue> result;
        private final Set<String> publishedCodes;
        private final StringBuilder pending = new StringBuilder();
        private final Set<String> acceptedCodes = new java.util.HashSet<>();
        private final Set<Integer> processedObjectStarts = new java.util.HashSet<>();
        private final List<Integer> objectStarts = new java.util.ArrayList<>();
        private final List<int[]> parsedRanges = new java.util.ArrayList<>();
        private int discoveryPosition;
        private char discoveryQuote;
        private boolean discoveryEscaped;
        private int acceptedRows;
        private boolean validStructuredResponse;

        private StreamingIndicatorCollector(List<IndicatorDefinition> batch, String source, String requestId,
                                            String logPrefix, Map<String, IndicatorValue> result,
                                            Set<String> publishedCodes) {
            batch.forEach(indicator -> candidates.put(indicator.code(), indicator));
            this.source = source;
            this.requestId = requestId;
            this.logPrefix = logPrefix;
            this.result = result;
            this.publishedCodes = publishedCodes;
        }

        private void accept(String delta) {
            if (delta == null || delta.isEmpty()) return;
            pending.append(delta);
            drainCompleteObjects();
        }

        private void finish() {
            drainCompleteObjects();
            if (!validStructuredResponse && acceptedRows == 0) {
                throw new IllegalArgumentException("模型结构化响应未包含可校验的候选指标");
            }
        }

        private void acceptStructuredResponse(Map<String, Object> response) {
            if (response == null || response.isEmpty()) return;
            if (response.get("raw") instanceof String raw) {
                if (acceptedRows == 0) accept(raw);
                return;
            }
            if (response.containsKey("items")) {
                if (!(response.get("items") instanceof List<?> items)) {
                    throw new IllegalArgumentException("模型结构化响应的 items 必须是数组");
                }
                validStructuredResponse = true;
                for (Object item : items) {
                    if (!(item instanceof Map<?, ?> row)) {
                        throw new IllegalArgumentException("模型结构化响应包含非对象指标项");
                    }
                    acceptRowIfMissing(row);
                }
            } else if (response.containsKey("code")) {
                validStructuredResponse = true;
                acceptRowIfMissing(response);
            }
        }

        private void drainCompleteObjects() {
            while (discoveryPosition < pending.length()) {
                int index = discoveryPosition++;
                char current = pending.charAt(index);
                if (discoveryQuote != 0) {
                    if (discoveryEscaped) discoveryEscaped = false;
                    else if (current == '\\') discoveryEscaped = true;
                    else if (current == discoveryQuote) discoveryQuote = 0;
                } else if (current == '"' || current == '\'') {
                    discoveryQuote = current;
                } else if (current == '{') {
                    objectStarts.add(index);
                }
            }
            for (int start : objectStarts) {
                if (processedObjectStarts.contains(start) || isInsideParsedObject(start)) continue;
                int end = findObjectEnd(pending, start);
                if (end >= 0) {
                    parseObject(pending.substring(start, end + 1));
                    processedObjectStarts.add(start);
                    parsedRanges.add(new int[]{start, end});
                }
            }
        }

        private boolean isInsideParsedObject(int start) {
            return parsedRanges.stream().anyMatch(range -> start > range[0] && start < range[1]);
        }

        private int findObjectEnd(CharSequence text, int start) {
            int depth = 0;
            char quote = 0;
            boolean escaped = false;
            for (int index = start; index < text.length(); index++) {
                char current = text.charAt(index);
                if (quote != 0) {
                    if (escaped) escaped = false;
                    else if (current == '\\') escaped = true;
                    else if (current == quote) quote = 0;
                } else if (current == '"' || current == '\'') {
                    quote = current;
                } else if (current == '{') {
                    depth++;
                } else if (current == '}' && --depth == 0) {
                    return index;
                }
            }
            return -1;
        }

        private void parseObject(String json) {
            try {
                Object parsed = tolerantJsonMapper.readValue(json, Object.class);
                if (parsed instanceof Map<?, ?> row) {
                    if (row.containsKey("code")) acceptRow(row);
                    else if (row.get("items") instanceof List<?> items) {
                        validStructuredResponse = true;
                        for (Object item : items) {
                            if (!(item instanceof Map<?, ?> itemMap)) {
                                throw new IllegalArgumentException("模型结构化响应包含非对象指标项");
                            }
                            acceptRowIfMissing(itemMap);
                        }
                    }
                }
            } catch (JsonProcessingException exception) {
                throw new IllegalArgumentException("模型结构化响应无法解析；将重试并回退到数据库默认值", exception);
            }
        }

        private void acceptRow(Map<?, ?> row) {
            if (!row.containsKey("code") || !row.containsKey("value") || !row.containsKey("status")) {
                throw new IllegalArgumentException("模型指标项缺少 code、value 或 status 字段");
            }
            String code = Objects.toString(row.get("code"), "");
            IndicatorDefinition indicator = candidates.get(code);
            if (indicator == null) {
                throw new IllegalArgumentException("模型返回了候选列表之外的指标编码");
            }
            if (!acceptedCodes.add(code)) {
                throw new IllegalArgumentException("模型对同一候选指标重复输出");
            }
            String status = Objects.toString(row.get("status"), "").toLowerCase(Locale.ROOT);
            if (!Set.of("predicted", "unknown", "not_applicable").contains(status)) {
                throw new IllegalArgumentException("模型返回了不支持的指标状态");
            }
            IndicatorValue value = validator.resolve(
                    indicator,
                    row.get("value"),
                    status,
                    source,
                    Objects.toString(row.get("reason"), "模型指标抽取")
            );
            result.put(indicator.code(), value);
            acceptedRows++;
            if (isObserved(value) && publishedCodes.add(indicator.code())) {
                String unit = indicator.unit() == null || indicator.unit().isBlank() ? "" : " " + indicator.unit();
                logBroker.publish(requestId, logPrefix + "：" + indicator.name() + "为" + value.value() + unit);
            }
        }

        private void acceptRowIfMissing(Map<?, ?> row) {
            String code = Objects.toString(row.get("code"), "");
            if (!acceptedCodes.contains(code)) acceptRow(row);
        }
    }

    private boolean shouldAskQwenForImage(
            Map<String, IndicatorValue> localHits,
            Map<String, Object> featureResult
    ) {
        if (localHits.isEmpty() || localHits.values().stream().anyMatch(v -> "invalid".equals(v.status()))) {
            return true;
        }
        for (Map.Entry<String, Object> entry : featureResult.entrySet()) {
            if (entry.getValue() instanceof Map<?, ?> metric) {
                Object status = metric.get("status");
                Object value = metric.get("value");
                if ("predicted".equalsIgnoreCase(Objects.toString(status, ""))
                        && value != null
                        && !Objects.toString(value, "").isBlank()
                        && !localHits.containsKey(entry.getKey())
                        && looksLikeClinicalFeature(entry.getKey())) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean looksLikeClinicalFeature(String key) {
        String lower = key == null ? "" : key.toLowerCase(Locale.ROOT);
        return lower.startsWith("burn_")
                || lower.startsWith("scar_")
                || lower.startsWith("vss_")
                || lower.startsWith("jss_")
                || lower.startsWith("patient_");
    }

    private Map<String, Object> indicatorPromptView(IndicatorDefinition indicator) {
        return Map.of(
                "code", indicator.code(),
                "name", indicator.name(),
                "valueType", indicator.valueType(),
                "sourceType", indicator.sourceType(),
                "options", indicator.options().stream().map(IndicatorOption::valueLabel).toList(),
                "valueDomain", Objects.toString(indicator.valueDomain(), ""),
                "basis", truncate(indicator.inferenceBasis(), 180)
        );
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            return String.valueOf(value);
        }
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private Object matchTextValue(IndicatorDefinition indicator, String text) {
        String name=indicator.name().toLowerCase(Locale.ROOT);
        int position=text.indexOf(name);
        if(position<0) return null;
        String tail=text.substring(position+name.length()).split("[；;。\\n]",2)[0].replaceFirst("^[：:\\s]+", "").trim();
        if(isNumeric(indicator)) {
            var match=java.util.regex.Pattern.compile("^(-?\\d+(?:\\.\\d+)?)").matcher(tail);
            return match.find()?new java.math.BigDecimal(match.group(1)):null;
        }
        if("MULTI_SELECT".equals(indicator.valueType())) {
            if(tail.startsWith("[") && tail.endsWith("]")) return java.util.Arrays.stream(tail.substring(1,tail.length()-1).split("[,，]")).map(String::trim).filter(v->!v.isEmpty()).toList();
            return null;
        }
        if ("TEXT".equalsIgnoreCase(indicator.valueType())) {
            return tail.isBlank() ? null : tail;
        }
        return indicator.options().stream().map(IndicatorOption::valueLabel)
                .filter(v->tail.equals(v.toLowerCase(Locale.ROOT))).findFirst().orElse(null);
    }

    private Object findImageRawValue(
            IndicatorDefinition indicator,
            Map<String, Object> flat,
            Map<String, Object> original
    ) {
        for (String key : java.util.Arrays.asList(indicator.code(), indicator.name(), lastPathPart(indicator.jsonPath()))) {
            if (key == null || key.isBlank()) {
                continue;
            }
            if (flat.containsKey(key)) {
                return flat.get(key);
            }
            String suffix = "." + key;
            for (Map.Entry<String, Object> entry : flat.entrySet()) {
                if (entry.getKey().endsWith(suffix)) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    private void flatten(String prefix, Object value, Map<String, Object> target) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = Objects.toString(entry.getKey(), "");
                String next = prefix.isBlank() ? key : prefix + "." + key;
                target.put(next, entry.getValue());
                flatten(next, entry.getValue(), target);
            }
        }
    }

    private String lastPathPart(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String clean = path.replace("$.", "");
        int dot = clean.lastIndexOf('.');
        return dot >= 0 ? clean.substring(dot + 1) : clean;
    }

    private boolean isNumeric(IndicatorDefinition indicator) {
        String valueType = indicator.valueType() == null ? "" : indicator.valueType().toUpperCase(Locale.ROOT);
        return valueType.contains("NUMBER") || valueType.contains("NUMERIC");
    }

    private Map<String, Object> toValueMap(Map<String, IndicatorValue> internal) {
        Map<String, Object> values = new LinkedHashMap<>();
        internal.forEach((code, value) -> { if (!"invalid".equals(value.status()) && value.value() != null) values.put(code, value.value()); });
        return values;
    }

    private void mergeObservedValues(
            ConfigSnapshot snapshot,
            Map<String, IndicatorValue> textValues,
            Map<String, IndicatorValue> imageValues,
            Map<String, IndicatorValue> merged,
            List<String> errors
    ) {
        for (IndicatorDefinition indicator : snapshot.rawIndicators()) {
            IndicatorValue textValue = textValues.get(indicator.code());
            IndicatorValue imageValue = imageValues.get(indicator.code());
            boolean textUsable = isObserved(textValue);
            boolean imageUsable = isObserved(imageValue);
            if (textUsable && imageUsable) {
                if (Objects.equals(textValue.value(), imageValue.value())) {
                    merged.put(indicator.code(), preferredBySource(indicator, textValue, imageValue));
                } else {
                    IndicatorValue selected = preferredBySource(indicator, textValue, imageValue);
                    merged.put(indicator.code(), selected);
                    errors.add(indicator.code() + ": 文本值与图片值冲突，已按 source_type="
                            + Objects.toString(indicator.sourceType(), "MIXED") + " 采用 " + selected.source() + " 的结果");
                }
            } else if (textUsable) {
                merged.put(indicator.code(), textValue);
            } else if (imageUsable) {
                merged.put(indicator.code(), imageValue);
            } else if (textValue != null && ("invalid".equals(textValue.status()) || textValue.reason().startsWith("模型失败："))) {
                merged.put(indicator.code(), textValue);
            } else if (imageValue != null && ("invalid".equals(imageValue.status()) || imageValue.reason().startsWith("模型失败："))) {
                merged.put(indicator.code(), imageValue);
            }
        }
    }

    private IndicatorValue preferredBySource(
            IndicatorDefinition indicator,
            IndicatorValue textValue,
            IndicatorValue imageValue
    ) {
        if (indicator.isImageLike()) {
            return imageValue;
        }
        return textValue;
    }

    private boolean isObserved(IndicatorValue value) {
        return value != null
                && value.value() != null
                && Set.of("predicted", "provided", "calculated").contains(value.status());
    }

    private void applyPublishedDefaults(ConfigSnapshot snapshot, Map<String, IndicatorValue> values) {
        for (IndicatorDefinition indicator : snapshot.rawIndicators()) {
            IndicatorValue current = values.get(indicator.code());
            boolean publishWithDefault = indicator.isImageLike()
                    || (indicator.isTextLike() && indicator.required());
            boolean modelFallback = current != null && current.reason().startsWith("模型失败：");
            if ((publishWithDefault || modelFallback) && (current == null || !isObserved(current))) {
                values.put(indicator.code(), validator.resolveDefault(
                        indicator,
                        "default",
                        modelFallback ? current.reason() : "未提取到有效结果，使用默认值"
                ));
            } else if (!publishWithDefault && !isObserved(current)) {
                values.remove(indicator.code());
            }
        }
    }

    private List<String> extractionErrors(Map<String, IndicatorValue> values) {
        return values.values().stream()
                .filter(value -> "invalid".equals(value.status()) || value.reason().startsWith("模型失败"))
                .map(value -> value.code() + ": " + value.reason())
                .toList();
    }
}
