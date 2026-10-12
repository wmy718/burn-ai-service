package com.nytryx.burnai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nytryx.burnai.model.DecisionNodeDefinition;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 严格执行 llm-admin 已注册的确定性医学计算公式，不调用生成式模型。 */
@Service
public class DeterministicFormulaExecutor {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal CM2_PER_M2 = BigDecimal.valueOf(10_000);

    private final ObjectMapper objectMapper;

    public DeterministicFormulaExecutor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public boolean supports(String code) { return code != null && Set.of("tbsa_percent_total","partial_thickness_tbsa_percent","full_thickness_tbsa_percent","baux_score","revised_baux_score","burn_surgery_burn_index","burn_surgery_index_component_score","burn_surgery_area_component_score","burn_surgery_repair_component_score","burn_surgery_anesthesia_component_score","burn_surgery_grade_total_score","vss_pigmentation_score","vss_vascularity_score","vss_height_score","vss_pliability_score","vss_total_score","posas_observer_total_score","posas_patient_total_score","posas_total_score","posas_completeness","posas_burden_grade","jss_population_score","jss_family_tendency_score","jss_lesion_number_score","jss_region_score","jss_age_onset_score","jss_cause_score","jss_size_score","jss_vertical_growth_score","jss_horizontal_growth_score","jss_shape_score","jss_perilesional_erythema_score","jss_subjective_symptom_score","jss_classification_total_score","jss_eval_induration_score","jss_eval_elevation_score","jss_eval_redness_score","jss_eval_perilesional_erythema_score","jss_eval_pain_score","jss_eval_itch_score","jss_evaluation_total_score").contains(code); }

    public FormulaResult compute(DecisionNodeDefinition node, Map<String, Object> values) {
        return compute(node, values, Map.of());
    }

    public FormulaResult compute(
            DecisionNodeDefinition node,
            Map<String, Object> values,
            Map<String, Object> evidenceContexts
    ) {
        String code = node.indicatorCode();
        try {
            Object value = switch (code) {
                case "tbsa_percent_total" -> tbsaTotal(values);
                case "partial_thickness_tbsa_percent" -> requirePercent(resolvePartialTbsa(values), code);
                case "full_thickness_tbsa_percent" -> requirePercent(resolveFullTbsa(values), code);
                case "baux_score" -> baux(values);
                case "revised_baux_score" -> revisedBaux(values, evidenceContexts);
                case "burn_surgery_burn_index" -> surgeryBurnIndex(values);
                case "burn_surgery_index_component_score" -> thresholdScore(surgeryBurnIndex(values));
                case "burn_surgery_area_component_score" -> surgeryAreaScore(values);
                case "burn_surgery_repair_component_score" -> surgeryRepairScore(values);
                case "burn_surgery_anesthesia_component_score" -> surgeryAnesthesiaScore(values);
                case "burn_surgery_grade_total_score" -> surgeryTotal(values);
                case "vss_pigmentation_score" -> mapRequired(values, "scar_pigmentation_category", Map.of(
                        "正常", 0, "色素减退", 1, "混合", 2, "色素沉着", 3
                ));
                case "vss_vascularity_score" -> mapRequired(values, "scar_vascularity_grade", Map.of(
                        "正常", 0, "粉红色", 1, "红色", 2, "紫色", 3
                ));
                case "vss_height_score" -> vssHeight(values);
                case "vss_pliability_score" -> mapRequired(values, "scar_palpation_pliability", Map.of(
                        "正常", 0,
                        "柔软，轻压可变形", 1,
                        "受压可屈服", 2,
                        "坚实，块状抗压", 3,
                        "绳索样条带", 4,
                        "挛缩并永久缩短", 5
                ));
                case "vss_total_score" -> sumRequired(values, List.of(
                        "vss_pigmentation_score",
                        "vss_vascularity_score",
                        "vss_height_score",
                        "vss_pliability_score"
                ));
                case "posas_observer_total_score" -> posasObserverTotal(values);
                case "posas_patient_total_score", "posas_total_score" -> posasPatientTotal(values);
                case "posas_completeness" -> posasCompleteness(values);
                case "posas_burden_grade" -> posasBurdenGrade(values);
                case "jss_population_score" -> mapRequired(values, "jss_population_category", Map.of(
                        "非洲裔", 2, "其他", 1, "高加索裔", 0
                ));
                case "jss_family_tendency_score" -> mapRequired(
                        values,
                        "family_history_pathological_scar",
                        Map.of("无", 0, "二级或其他亲属", 0, "一级亲属", 1, "多个亲属", 1)
                );
                case "jss_lesion_number_score" -> jssLesionNumber(values);
                case "jss_region_score" -> mapRequired(values, "jss_region_category", Map.of(
                        "前胸", 2, "肩胛肩部", 2, "耻骨上区", 2, "其他", 0
                ));
                case "jss_age_onset_score" -> jssAge(values);
                case "jss_cause_score" -> jssCause(values);
                case "jss_size_score" -> requireNumber(values, "scar_area_cm2")
                        .compareTo(BigDecimal.valueOf(20)) > 0 ? 1 : 0;
                case "jss_vertical_growth_score" -> mapRequired(values, "jss_elevation_grade", Map.of(
                        "无", 0, "弱", 2, "轻度", 2, "强", 2
                ));
                case "jss_horizontal_growth_score" -> mapRequired(values, "scar_boundary_class", Map.of(
                        "位于原始创缘内", 0, "超出原始创缘", 3
                ));
                case "jss_shape_score" -> mapRequired(values, "jss_characteristic_shape_presence", Map.of(
                        "不存在", 0, "存在", 3
                ));
                case "jss_perilesional_erythema_score" -> mapRequired(
                        values,
                        "scar_perilesional_erythema_grade",
                        Map.of("无", 0, "弱", 2, "轻度", 2, "强", 2)
                );
                case "jss_subjective_symptom_score" -> mapRequired(
                        values,
                        "scar_subjective_symptom_pattern",
                        Map.of("无", 0, "间歇", 1, "持续", 2)
                );
                case "jss_classification_total_score" -> sumRequired(values, List.of(
                        "jss_population_score", "jss_family_tendency_score", "jss_lesion_number_score",
                        "jss_region_score", "jss_age_onset_score", "jss_cause_score", "jss_size_score",
                        "jss_vertical_growth_score", "jss_horizontal_growth_score", "jss_shape_score",
                        "jss_perilesional_erythema_score", "jss_subjective_symptom_score"
                ));
                case "jss_eval_induration_score" -> gradeScore(values, "scar_induration_grade");
                case "jss_eval_elevation_score" -> gradeScore(values, "jss_elevation_grade");
                case "jss_eval_redness_score" -> mapRequired(values, "scar_vascularity_grade", Map.of(
                        "正常", 0, "粉红色", 1, "红色", 2, "紫色", 3
                ));
                case "jss_eval_perilesional_erythema_score" -> gradeScore(
                        values, "scar_perilesional_erythema_grade");
                case "jss_eval_pain_score" -> gradeScore(values, "jss_pain_grade_recorded");
                case "jss_eval_itch_score" -> gradeScore(values, "jss_itch_grade_recorded");
                case "jss_evaluation_total_score" -> sumRequired(values, List.of(
                        "jss_eval_induration_score", "jss_eval_elevation_score", "jss_eval_redness_score",
                        "jss_eval_perilesional_erythema_score", "jss_eval_pain_score", "jss_eval_itch_score"
                ));
                default -> throw new FormulaException("未注册确定性公式：" + code);
            };
            return new FormulaResult(true, normalizeNumber(value), null);
        } catch (FormulaException exception) {
            return new FormulaResult(false, null, exception.getMessage());
        } catch (RuntimeException exception) {
            return new FormulaResult(false, null, "公式 " + code + " 执行失败：" + safeMessage(exception));
        }
    }

    private BigDecimal tbsaTotal(Map<String, Object> values) {
        BigDecimal woundArea = optionalNumber(values.get("burn_wound_area_cm2"));
        if (woundArea != null) {
            if (woundArea.compareTo(ZERO) < 0) {
                throw new FormulaException("burn_wound_area_cm2 不能为负数");
            }
            Object site = values.get("clinician_burn_site_list");
            if (!(site instanceof Collection<?> sites) || sites.isEmpty()) {
                throw new FormulaException("局部面积估算 TBSA 需要医生记录烧伤部位");
            }
            BigDecimal estimatedBodySurfaceCm2 = estimateBodySurfaceCm2(values);
            BigDecimal estimated = woundArea
                    .multiply(ONE_HUNDRED)
                    .divide(estimatedBodySurfaceCm2, 4, RoundingMode.HALF_UP);
            return requirePercent(estimated, "tbsa_percent_total");
        }
        throw new FormulaException("公式缺少数值输入：burn_wound_area_cm2");
    }

    private BigDecimal estimateBodySurfaceCm2(Map<String, Object> values) {
        BigDecimal age = requireNumber(values, "patient_age_years");
        if (age.compareTo(ZERO) < 0 || age.compareTo(BigDecimal.valueOf(130)) > 0) {
            throw new FormulaException("patient_age_years 必须在 0..130 范围内");
        }
        BigDecimal squareMeters;
        if (age.compareTo(BigDecimal.ONE) < 0) {
            squareMeters = BigDecimal.valueOf(0.25);
        } else if (age.compareTo(BigDecimal.valueOf(5)) < 0) {
            squareMeters = BigDecimal.valueOf(0.55);
        } else if (age.compareTo(BigDecimal.valueOf(12)) < 0) {
            squareMeters = BigDecimal.valueOf(0.95);
        } else if (age.compareTo(BigDecimal.valueOf(18)) < 0) {
            squareMeters = BigDecimal.valueOf(1.45);
        } else {
            squareMeters = BigDecimal.valueOf(1.73);
        }
        String habitus = values.get("patient_body_habitus") == null
                ? null
                : String.valueOf(values.get("patient_body_habitus")).trim();
        if ("低体重".equals(habitus) || "恶病质".equals(habitus)) {
            squareMeters = squareMeters.multiply(BigDecimal.valueOf(0.90));
        } else if ("超重".equals(habitus)) {
            squareMeters = squareMeters.multiply(BigDecimal.valueOf(1.10));
        } else if ("肥胖".equals(habitus) || "全身水肿".equals(habitus)) {
            squareMeters = squareMeters.multiply(BigDecimal.valueOf(1.20));
        }
        return squareMeters.multiply(CM2_PER_M2);
    }

    private BigDecimal resolvePartialTbsa(Map<String, Object> values) {
        BigDecimal recorded = optionalNumber(values.get("partial_thickness_tbsa_recorded_percent"));
        return recorded != null
                ? recorded
                : sumRegion(values.get("tbsa_region_measurements"), "partial_thickness_percent");
    }

    private BigDecimal resolveFullTbsa(Map<String, Object> values) {
        BigDecimal recorded = optionalNumber(values.get("full_thickness_tbsa_recorded_percent"));
        return recorded != null
                ? recorded
                : sumRegion(values.get("tbsa_region_measurements"), "full_thickness_percent");
    }

    private BigDecimal sumRegion(Object raw, String field) {
        if (raw == null) {
            return null;
        }
        Map<String, Object> object = asMap(raw, "tbsa_region_measurements");
        Object regionsRaw = object.get("regions");
        if (!(regionsRaw instanceof Collection<?> regions) || regions.isEmpty()) {
            return null;
        }
        BigDecimal total = ZERO;
        boolean found = false;
        for (Object regionRaw : regions) {
            Map<String, Object> region = asMap(regionRaw, "tbsa_region_measurements.regions[]");
            BigDecimal value = optionalNumber(region.get(field));
            if (value != null) {
                requirePercent(value, field);
                total = total.add(value);
                found = true;
            }
        }
        return found ? total : null;
    }

    private BigDecimal baux(Map<String, Object> values) {
        return requireNumber(values, "patient_age_years")
                .add(requireNumber(values, "tbsa_percent_total"));
    }

    private BigDecimal revisedBaux(
            Map<String, Object> values,
            Map<String, Object> evidenceContexts
    ) {
        BigDecimal base = optionalNumber(values.get("baux_score"));
        if (base == null) {
            base = baux(values);
        }
        if (values.get("inhalation_injury_class") == null
                && "not_applicable".equals(evidenceStatus(
                        evidenceContexts, "inhalation_injury_class"))) {
            return base;
        }
        String inhalation = requireText(values, "inhalation_injury_class");
        Set<String> positive = Set.of("上气道损伤", "下气道损伤", "上下气道联合损伤", "混合");
        Set<String> negative = Set.of("无", "仅全身中毒");
        if (positive.contains(inhalation)) {
            return base.add(BigDecimal.valueOf(17));
        }
        if (negative.contains(inhalation)) {
            return base;
        }
        throw new FormulaException("revised_baux_score 缺少明确的吸入性气道损伤分类");
    }

    private String evidenceStatus(Map<String, Object> evidenceContexts, String indicatorCode) {
        if (evidenceContexts == null || indicatorCode == null) {
            return null;
        }
        Object context = evidenceContexts.get(indicatorCode);
        if (context instanceof Map<?, ?> map) {
            Object status = map.get("status");
            return status == null ? null : String.valueOf(status);
        }
        return null;
    }

    private BigDecimal surgeryBurnIndex(Map<String, Object> values) {
        BigDecimal full = requireNumber(values, "full_thickness_tbsa_percent");
        BigDecimal partial = requireNumber(values, "partial_thickness_tbsa_percent");
        BigDecimal index = full.add(partial.multiply(BigDecimal.valueOf(0.5)));
        BigDecimal age = requireNumber(values, "patient_age_years");
        if (age.compareTo(BigDecimal.valueOf(12)) <= 0
                || age.compareTo(BigDecimal.valueOf(70)) >= 0) {
            index = index.multiply(BigDecimal.valueOf(2));
        }
        return index;
    }

    private int surgeryAreaScore(Map<String, Object> values) {
        Object specialRaw = values.get("planned_surgery_special_category_list");
        if (specialRaw instanceof Collection<?> special && !special.isEmpty()) {
            return 4;
        }
        BigDecimal area = requireNumber(values, "planned_surgical_area_percent_tbsa");
        BigDecimal age = requireNumber(values, "patient_age_years");
        if (age.compareTo(BigDecimal.valueOf(12)) <= 0
                || age.compareTo(BigDecimal.valueOf(70)) >= 0) {
            area = area.multiply(BigDecimal.valueOf(2));
        }
        return thresholdScore(area);
    }

    private int surgeryRepairScore(Map<String, Object> values) {
        return mapRequired(values, "planned_burn_repair_method", Map.of(
                "游离皮片移植", 1,
                "非轴型皮瓣", 2,
                "轴向型皮瓣", 3,
                "瘢痕松解皮瓣修复", 8,
                "自由皮瓣", 8
        ));
    }

    private int surgeryAnesthesiaScore(Map<String, Object> values) {
        return mapRequired(values, "anesthesia_systemic_risk_category", Map.of(
                "局部病变无全身性疾病", 1,
                "轻度或中度全身性疾病", 2,
                "严重的全身性疾病威胁生命", 3,
                "危重状态，生命难以维持", 4
        ));
    }

    private int surgeryTotal(Map<String, Object> values) {
        return sumRequired(values, List.of(
                "burn_surgery_index_component_score",
                "burn_surgery_area_component_score",
                "burn_surgery_repair_component_score",
                "burn_surgery_anesthesia_component_score"
        ));
    }

    private int thresholdScore(BigDecimal value) {
        if (value.compareTo(BigDecimal.valueOf(5)) < 0) {
            return 1;
        }
        if (value.compareTo(BigDecimal.valueOf(10)) < 0) {
            return 2;
        }
        if (value.compareTo(BigDecimal.valueOf(20)) < 0) {
            return 3;
        }
        return 4;
    }

    private int vssHeight(Map<String, Object> values) {
        BigDecimal height = requireNumber(values, "scar_height_mm");
        if (height.compareTo(ZERO) < 0) {
            throw new FormulaException("scar_height_mm 不能为负数");
        }
        if (height.compareTo(ZERO) == 0) {
            return 0;
        }
        if (height.compareTo(BigDecimal.ONE) < 0) {
            return 1;
        }
        if (height.compareTo(BigDecimal.valueOf(3)) <= 0) {
            return 2;
        }
        if (height.compareTo(BigDecimal.valueOf(4)) <= 0) {
            return 3;
        }
        return 4;
    }

    private int posasTotal(Map<String, Object> values, String code, List<String> fields) {
        Map<String, Object> object = asMap(values.get(code), code);
        int total = 0;
        for (String field : fields) {
            BigDecimal value = optionalNumber(object.get(field));
            if (value == null
                    || value.stripTrailingZeros().scale() > 0
                    || value.compareTo(BigDecimal.ONE) < 0
                    || value.compareTo(BigDecimal.TEN) > 0) {
                throw new FormulaException(code + "." + field + " 必须是 1..10 的整数");
            }
            total += value.intValueExact();
        }
        return total;
    }

    private int posasObserverTotal(Map<String, Object> values) {
        int vascularity = mapRequired(values, "scar_vascularity_grade", Map.of(
                "正常", 1, "粉红色", 3, "红色", 6, "紫色", 9
        ));
        int pigmentation = mapRequired(values, "scar_pigmentation_category", Map.of(
                "正常", 1, "色素减退", 6, "色素沉着", 6, "混合", 8
        ));
        BigDecimal height = requireNumber(values, "scar_height_mm");
        int thickness = height.compareTo(ZERO) <= 0 ? 1
                : height.compareTo(BigDecimal.ONE) <= 0 ? 3
                : height.compareTo(BigDecimal.valueOf(2)) <= 0 ? 5
                : height.compareTo(BigDecimal.valueOf(4)) <= 0 ? 7 : 9;
        int relief = mapRequired(values, "scar_surface_relief", Map.of(
                "平坦光滑", 1, "隆起光滑", 3, "隆起不规则", 6, "结节状", 8,
                "条索状", 8, "凹坑状", 8, "溃疡型", 10, "混合", 9
        ));
        int pliability = mapRequired(values, "scar_palpation_pliability", Map.of(
                "正常", 1, "柔软，轻压可变形", 3, "受压可屈服", 5,
                "坚实，块状抗压", 7, "绳索样条带", 9, "挛缩并永久缩短", 10
        ));
        int surfaceArea = mapRequired(values, "scar_boundary_class", Map.of(
                "位于原始创缘内", 1, "超出原始创缘", 8
        ));
        return vascularity + pigmentation + thickness + relief + pliability + surfaceArea;
    }

    private int posasPatientTotal(Map<String, Object> values) {
        return nrsToPosas(values, "scar_pain_nrs")
                + nrsToPosas(values, "scar_pruritus_nrs")
                + posasItem(values, "posas_patient_color_difference_score")
                + nrsToPosas(values, "scar_stiffness_nrs")
                + posasItem(values, "posas_patient_thickness_difference_score")
                + posasItem(values, "posas_patient_irregularity_score");
    }

    private int nrsToPosas(Map<String, Object> values, String code) {
        BigDecimal nrs = requireNumber(values, code);
        if (nrs.stripTrailingZeros().scale() > 0 || nrs.compareTo(ZERO) < 0 || nrs.compareTo(BigDecimal.TEN) > 0) {
            throw new FormulaException(code + " 必须是 0..10 的整数");
        }
        return Math.min(10, nrs.intValueExact() + 1);
    }

    private int posasItem(Map<String, Object> values, String code) {
        BigDecimal score = requireNumber(values, code);
        if (score.stripTrailingZeros().scale() > 0 || score.compareTo(BigDecimal.ONE) < 0 || score.compareTo(BigDecimal.TEN) > 0) {
            throw new FormulaException(code + " 必须是 1..10 的整数");
        }
        return score.intValueExact();
    }

    private String posasCompleteness(Map<String, Object> values) {
        boolean observer = canComputePosasObserver(values);
        boolean patient = canComputePosasPatient(values);
        if (observer && patient) return "观察者与患者量表均完整";
        if (observer) return "仅观察者量表完整";
        if (patient) return "仅患者量表完整";
        return "资料不足，无法形成正式总分";
    }

    private String posasBurdenGrade(Map<String, Object> values) {
        Integer observer = tryPosasObserver(values);
        Integer patient = tryPosasPatient(values);
        if (observer == null && patient == null) throw new FormulaException("POSAS观察者和患者量表均不完整");
        int score = Math.max(observer == null ? 0 : observer, patient == null ? 0 : patient);
        if (score <= 17) return "接近正常皮肤";
        if (score <= 29) return "轻度差异";
        if (score <= 41) return "中等差异";
        if (score <= 50) return "重度差异";
        return "极重度差异";
    }

    private boolean canComputePosasObserver(Map<String, Object> values) { return tryPosasObserver(values) != null; }
    private boolean canComputePosasPatient(Map<String, Object> values) { return tryPosasPatient(values) != null; }
    private Integer tryPosasObserver(Map<String, Object> values) {
        try { return posasObserverTotal(values); } catch (FormulaException ignored) { return null; }
    }
    private Integer tryPosasPatient(Map<String, Object> values) {
        try { return posasPatientTotal(values); } catch (FormulaException ignored) { return null; }
    }

    private int jssLesionNumber(Map<String, Object> values) {
        BigDecimal image = optionalNumber(values.get("scar_lesion_count_image"));
        BigDecimal clinical = optionalNumber(values.get("scar_lesion_count_clinical"));
        if ((image != null && image.compareTo(BigDecimal.ONE) > 0)
                || (clinical != null && clinical.compareTo(BigDecimal.ONE) > 0)) {
            return 2;
        }
        if (image != null
                && clinical != null
                && image.compareTo(BigDecimal.ONE) == 0
                && clinical.compareTo(BigDecimal.ONE) == 0) {
            return 0;
        }
        throw new FormulaException("jss_lesion_number_score 需要完整的图像与临床病灶计数，或任一来源明确多发");
    }

    private int jssAge(Map<String, Object> values) {
        BigDecimal age = requireNumber(values, "scar_age_at_onset_years");
        if (age.compareTo(ZERO) < 0) {
            throw new FormulaException("scar_age_at_onset_years 不能为负数");
        }
        if (age.compareTo(BigDecimal.valueOf(30)) <= 0) {
            return 2;
        }
        if (age.compareTo(BigDecimal.valueOf(60)) <= 0) {
            return 1;
        }
        return 0;
    }

    private int jssCause(Map<String, Object> values) {
        String cause = requireText(values, "scar_source_class");
        if ("自发或轻微损伤后发生".equals(cause)) {
            return 3;
        }
        if (Set.of("烧伤", "手术", "创伤", "粉刺", "感染", "穿孔或穿刺", "疫苗接种", "咬伤")
                .contains(cause)) {
            return 0;
        }
        throw new FormulaException("jss_cause_score 需要明确的瘢痕病因");
    }

    private int gradeScore(Map<String, Object> values, String code) {
        return mapRequired(values, code, Map.of("无", 0, "弱", 1, "轻度", 2, "强", 3));
    }

    private int sumRequired(Map<String, Object> values, List<String> codes) {
        int total = 0;
        for (String code : codes) {
            BigDecimal number = requireNumber(values, code);
            try {
                total += number.intValueExact();
            } catch (ArithmeticException exception) {
                throw new FormulaException(code + " 必须是整数");
            }
        }
        return total;
    }

    private int mapRequired(Map<String, Object> values, String code, Map<String, Integer> mapping) {
        String raw = requireText(values, code);
        Integer mapped = mapping.get(raw);
        if (mapped == null) {
            throw new FormulaException(code + " 的值不能用于当前公式：" + raw);
        }
        return mapped;
    }

    private BigDecimal requireNumber(Map<String, Object> values, String code) {
        BigDecimal value = optionalNumber(values.get(code));
        if (value == null) {
            throw new FormulaException("公式缺少数值输入：" + code);
        }
        return value;
    }

    private BigDecimal requirePercent(BigDecimal value, String code) {
        if (value == null) {
            throw new FormulaException("公式缺少百分比输入：" + code);
        }
        if (value.compareTo(ZERO) < 0 || value.compareTo(ONE_HUNDRED) > 0) {
            throw new FormulaException(code + " 必须在 0..100 范围内");
        }
        return value;
    }

    private BigDecimal optionalNumber(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            BigDecimal value = raw instanceof BigDecimal decimal
                    ? decimal
                    : new BigDecimal(String.valueOf(raw).trim());
            if (!Double.isFinite(value.doubleValue())) {
                return null;
            }
            return value;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String requireText(Map<String, Object> values, String code) {
        Object raw = values.get(code);
        if (raw == null || String.valueOf(raw).isBlank()) {
            throw new FormulaException("公式缺少枚举输入：" + code);
        }
        return String.valueOf(raw).trim();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object raw, String code) {
        if (raw instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        if (raw instanceof JsonNode jsonNode && jsonNode.isObject()) {
            return objectMapper.convertValue(jsonNode, Map.class);
        }
        throw new FormulaException(code + " 需要结构化 object");
    }

    private Object normalizeNumber(Object value) {
        if (!(value instanceof BigDecimal number)) {
            return value;
        }
        BigDecimal normalized = number.stripTrailingZeros();
        if (normalized.scale() <= 0) {
            try {
                return normalized.intValueExact();
            } catch (ArithmeticException ignored) {
                return normalized.longValue();
            }
        }
        return normalized.setScale(Math.min(normalized.scale(), 4), RoundingMode.HALF_UP)
                .stripTrailingZeros();
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    /** 确定性公式执行结果；失败时不产生不完整值。 */
    public record FormulaResult(boolean success, Object value, String error) {
    }

    private static class FormulaException extends RuntimeException {
        FormulaException(String message) {
            super(message);
        }
    }
}
