package com.nytryx.burnai.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nytryx.burnai.model.*;
import com.nytryx.burnai.repo.DecisionConfigRepository;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class RegressionTest {
    @Test void publicContractsDoNotExposeRemovedInternalContextFields() throws Exception {
        var mapper=new ObjectMapper();
        var extraction=new ExtractionResponse("extract-1","burn",Map.of(),Map.of(),List.of(),1,List.of());
        assertFalse(mapper.readTree(mapper.writeValueAsString(extraction)).has("internalIndicators"));
        var reportRequest=new ReportGenerationRequest("burn","report-1",Map.of("image_value","存在"));
        assertFalse(mapper.readTree(mapper.writeValueAsString(reportRequest)).has("extractionRequestId"));
    }

    @Test void indicatorExtractionStreamsCompleteJsonObjectsAndPublishesEachAcceptedValueImmediately() {
        var repository=mock(DecisionConfigRepository.class);var ai=mock(AiModelClient.class);var om=new ObjectMapper();var logs=new RunLogBroker();
        var options=List.of(new IndicatorOption(1,"存在",false,"",0),new IndicatorOption(2,"不存在",true,"",1));
        var first=new IndicatorDefinition(1,1,"burn","first_sign","第一体征","RAW","SINGLE_SELECT","CASE_REPORT","",null,null,"","","",false,0,options);
        var second=new IndicatorDefinition(2,1,"burn","second_sign","第二体征","RAW","SINGLE_SELECT","CASE_REPORT","",null,null,"","","",false,1,options);
        when(repository.load("burn")).thenReturn(new DecisionConfigRepository.ConfigSnapshot(List.of(first,second),List.of(),List.of(),List.of(),List.of(),List.of()));
        when(ai.available()).thenReturn(true);
        when(ai.askJsonStreaming(anyString(),anyString(),any())).thenAnswer(invocation -> {
            assertTrue(invocation.<String>getArgument(0).contains("SINGLE_SELECT 的 value 必须与该指标 options"));
            assertTrue(invocation.<String>getArgument(1).contains("\"options\":[\"存在\",\"不存在\"]"));
            @SuppressWarnings("unchecked") java.util.function.Consumer<String> delta=invocation.getArgument(2);
            delta.accept("{\"items\":[{\"code\":\"first_sign\",\"value\":\"存在\",\"status\":\"predicted\",\"reason\":\"文本明确\"},");
            assertTrue(logs.events("indicator-stream").stream().anyMatch(event -> event.get("message").toString().contains("第一体征为存在")),
                    "第一个完整指标应在模型流尚未结束时立即发布");
            delta.accept("{\"code\":\"second_sign\",\"value\":\"存");
            assertFalse(logs.events("indicator-stream").stream().anyMatch(event -> event.get("message").toString().contains("第二体征")),
                    "半截 JSON 不能提前发布");
            delta.accept("在\",\"status\":\"predicted\",\"reason\":\"文本明确\"}]}\n");
            return Map.of("items",List.of(
                    Map.of("code","first_sign","value","存在","status","predicted","reason","文本明确"),
                    Map.of("code","second_sign","value","存在","status","predicted","reason","文本明确")));
        });
        var service=new IndicatorExtractionService(repository,new ChineseIndicatorMapper(),ai,om,new DiseaseCodeResolver(),logs);
        var response=service.extract(new AggregateExtractionRequest("indicator-stream","burn","两项体征均已记录",Map.of()));
        assertEquals(Map.of("first_sign","存在","second_sign","存在"),response.indicatorCodes());
        assertEquals(1,logs.events("indicator-stream").stream().filter(event -> event.get("message").toString().contains("第一体征为存在")).count());
        assertEquals(1,logs.events("indicator-stream").stream().filter(event -> event.get("message").toString().contains("第二体征为存在")).count());
        verify(ai).askJsonStreaming(anyString(),anyString(),any());
        verify(ai,never()).askJson(anyString(),anyString());
    }

    @Test void indicatorExtractionRepairsCommonJsonFormattingDriftAcrossLines() {
        var repository=mock(DecisionConfigRepository.class);var ai=mock(AiModelClient.class);var om=new ObjectMapper();
        var options=List.of(new IndicatorOption(1,"存在",false,"",0),new IndicatorOption(2,"不存在",true,"",1));
        var indicator=new IndicatorDefinition(1,1,"burn","first_sign","第一体征","RAW","SINGLE_SELECT","CASE_REPORT","",null,null,"","","",false,0,options);
        when(repository.load("burn")).thenReturn(new DecisionConfigRepository.ConfigSnapshot(List.of(indicator),List.of(),List.of(),List.of(),List.of(),List.of()));
        when(ai.available()).thenReturn(true);
        when(ai.askJsonStreaming(anyString(),anyString(),any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked") java.util.function.Consumer<String> delta=invocation.getArgument(2);
            delta.accept("以下是结果：```json\n{\n  code: 'first_sign',\n  value: '存在',\n  status: 'predicted',\n  reason: '文本明确',\n}\n```\n");
            return Map.of();
        });
        var service=new IndicatorExtractionService(repository,new ChineseIndicatorMapper(),ai,om,new DiseaseCodeResolver(),new RunLogBroker());
        var response=service.extract(new AggregateExtractionRequest("json-repair","burn","第一体征明确存在",Map.of()));
        assertEquals("存在",response.indicatorCodes().get("first_sign"));
        verify(ai,times(1)).askJsonStreaming(anyString(),anyString(),any());
    }

    @Test void malformedModelStreamFallsBackWithoutLeakingOldInvalidJsonError() {
        var repository=mock(DecisionConfigRepository.class);var ai=mock(AiModelClient.class);var om=new ObjectMapper();
        var options=List.of(new IndicatorOption(1,"存在",false,"",0),new IndicatorOption(2,"不存在",true,"",1));
        var indicator=new IndicatorDefinition(1,1,"burn","infection_sign","感染征象","RAW","SINGLE_SELECT","CASE_REPORT","","不存在",null,"","","",true,0,options);
        when(repository.load("burn")).thenReturn(new DecisionConfigRepository.ConfigSnapshot(List.of(indicator),List.of(),List.of(),List.of(),List.of(),List.of()));
        when(ai.available()).thenReturn(true);
        when(ai.askJsonStreaming(anyString(),anyString(),any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked") java.util.function.Consumer<String> delta=invocation.getArgument(2);
            delta.accept("{ this is not complete JSON");
            return Map.of("raw","{ this is not complete JSON");
        });
        var service=new IndicatorExtractionService(repository,new ChineseIndicatorMapper(),ai,om,new DiseaseCodeResolver(),new RunLogBroker());
        var response=service.extract(new AggregateExtractionRequest("invalid-stream","burn","未明确感染",Map.of()));
        assertEquals("不存在",response.indicatorCodes().get("infection_sign"));
        assertTrue(response.errors().stream().noneMatch(error->error.contains("流式指标行不是合法 JSON")));
        assertTrue(response.errors().stream().noneMatch(error->error.contains("Exception") || error.contains("java.lang.")));
        assertFalse(response.errors().isEmpty());
        verify(ai,times(2)).askJsonStreaming(anyString(),anyString(),any());
    }

    @Test void outOfDomainModelValueUsesConfiguredDefaultAfterRetryEvenForOptionalIndicator() {
        var repository=mock(DecisionConfigRepository.class);var ai=mock(AiModelClient.class);var om=new ObjectMapper();
        var number=new IndicatorDefinition(1,1,"burn","severity_score","严重程度","RAW","NUMBER","CASE_REPORT","point","5",null,"","","integer;range=0..10",false,0,List.of());
        when(repository.load("burn")).thenReturn(new DecisionConfigRepository.ConfigSnapshot(List.of(number),List.of(),List.of(),List.of(),List.of(),List.of()));
        when(ai.available()).thenReturn(true);
        when(ai.askJsonStreaming(anyString(),anyString(),any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked") java.util.function.Consumer<String> delta=invocation.getArgument(2);
            delta.accept("{\"items\":[{\"code\":\"severity_score\",\"value\":99,\"status\":\"predicted\",\"reason\":\"text\"}]}");
            return Map.of("items",List.of(Map.of("code","severity_score","value",99,"status","predicted","reason","text")));
        });
        var service=new IndicatorExtractionService(repository,new ChineseIndicatorMapper(),ai,om,new DiseaseCodeResolver(),new RunLogBroker());
        var response=service.extract(new AggregateExtractionRequest("range-fallback","burn","未明确严重程度",Map.of()));
        assertEquals(0,new java.math.BigDecimal(response.indicatorCodes().get("severity_score").toString()).compareTo(new java.math.BigDecimal("5")));
        assertFalse(response.errors().isEmpty());
        verify(ai,times(2)).askJsonStreaming(anyString(),anyString(),any());
    }

    @Test void multiselectWhitespaceDifferenceIsCanonicalizedToDatabaseOption() {
        var repository=mock(DecisionConfigRepository.class);var ai=mock(AiModelClient.class);var om=new ObjectMapper();
        var options=List.of(new IndicatorOption(1,"温哥华瘢痕量表",false,"",0),
                new IndicatorOption(2,"患者与观察者瘢痕评估量表2.0版",false,"",1));
        var indicator=new IndicatorDefinition(1,1,"scar","scar_assessment_scale_documented_list","已记录量表","RAW","MULTI_SELECT","CASE_REPORT","",null,null,"","","array<string>；允许值：温哥华瘢痕量表 | 患者与观察者瘢痕评估量表2.0版",false,0,options);
        when(repository.load("scar")).thenReturn(new DecisionConfigRepository.ConfigSnapshot(List.of(indicator),List.of(),List.of(),List.of(),List.of(),List.of()));
        when(ai.available()).thenReturn(true);
        when(ai.askJsonStreaming(anyString(),anyString(),any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked") java.util.function.Consumer<String> delta=invocation.getArgument(2);
            var row=Map.of("code","scar_assessment_scale_documented_list","value",List.of("温哥华瘢痕量表","患者与观察者瘢痕评估量表 2.0 版"),"status","predicted","reason","病历明确记录");
            var wrapper=Map.of("items",List.of(row));
            delta.accept(om.writeValueAsString(wrapper));
            return wrapper;
        });
        var service=new IndicatorExtractionService(repository,new ChineseIndicatorMapper(),ai,om,new DiseaseCodeResolver(),new RunLogBroker());
        var response=service.extract(new AggregateExtractionRequest("canonical-options","scar","病历记录温哥华瘢痕量表及POSAS 2.0版",Map.of()));
        assertEquals(List.of("温哥华瘢痕量表","患者与观察者瘢痕评估量表2.0版"),response.indicatorCodes().get("scar_assessment_scale_documented_list"));
        assertTrue(response.errors().isEmpty());
        verify(ai,times(1)).askJsonStreaming(anyString(),anyString(),any());
    }

    @Test void clinicalReasoningUsesRealModelDeltasAndPublishesReadableSentences() {
        var repository=mock(DecisionConfigRepository.class);var q=mock(QwenClient.class);var om=new ObjectMapper();var logs=new RunLogBroker();
        var indicator=new IndicatorDefinition(1,1,"scar","scar_pain_nrs","瘢痕疼痛评分","RAW","NUMBER","CASE_REPORT","point",null,null,"","","integer;range=0..10",true,0,List.of());
        var snapshot=new DecisionConfigRepository.ConfigSnapshot(List.of(indicator),List.of(),List.of(),List.of(),List.of(),List.of());
        when(q.askTextStreaming(anyString(),anyString(),any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<String> consumer=invocation.getArgument(2);
            consumer.accept("疼痛评分为4分，提示存在需要关注的症状");
            consumer.accept("。仍需结合查体确认瘢痕活动情况。");
            return "疼痛评分为4分，提示存在需要关注的症状。仍需结合查体确认瘢痕活动情况。";
        });
        var service=new ReportGenerationService(repository,new ChineseIndicatorMapper(),om,logs,q,
                mock(DecisionTreeRuntimeService.class),mock(DecisionTreeGateService.class),new DiseaseCodeResolver(),180000,300000);
        service.streamClinicalReasoning(snapshot,"reasoning-stream",Map.of("scar_pain_nrs",4),Map.of());
        var messages=logs.events("reasoning-stream").stream().map(event->event.get("message").toString()).toList();
        assertEquals(List.of(
                "临床判断：疼痛评分为4分，提示存在需要关注的症状。",
                "临床判断：仍需结合查体确认瘢痕活动情况。"
        ),messages);
        verify(q).askTextStreaming(anyString(),contains("瘢痕疼痛评分"),any());
    }

    @Test void controllerExposesOnlyCurrentFourEndpoints() {
        var baseMapping = com.nytryx.burnai.api.AiDiagnosisApiController.class
                .getAnnotation(org.springframework.web.bind.annotation.RequestMapping.class);
        assertArrayEquals(new String[]{"/api"}, baseMapping.value());
        Set<String> routes = new LinkedHashSet<>();
        for (var method : com.nytryx.burnai.api.AiDiagnosisApiController.class.getDeclaredMethods()) {
            var post = method.getAnnotation(org.springframework.web.bind.annotation.PostMapping.class);
            if (post != null) for (String path : post.value()) routes.add("POST " + path);
            var get = method.getAnnotation(org.springframework.web.bind.annotation.GetMapping.class);
            if (get != null) for (String path : get.value()) routes.add("GET " + path);
        }
        assertEquals(Set.of(
                "POST /indicators/extract",
                "GET /indicators/logs",
                "POST /reports/generate",
                "GET /reports/logs"
        ), routes);
    }
    DecisionNodeDefinition formulaNode(String code) {
        return new DecisionNodeDefinition(1,1,"formula-"+code,code,"INDICATOR",code,"OUTPUT_INDICATOR",null,
                "MODEL_EDGE_SELECTION","[]",null,"",null,null,null,0);
    }
    IndicatorDefinition indicator(String type,String domain,List<String> options) {
        return new IndicatorDefinition(1,1,"burn","x","测试","RAW",type,"IMAGE_JSON","",null,null,"","",domain,false,0,
            options.stream().map(v->new IndicatorOption(1,v,false,"",0)).toList());
    }
    @Test void valueDomainsAndStates() {
        var v=new IndicatorValueValidator();
        var n=indicator("NUMBER","integer;range=0..100",List.of());
        for(Object bad:List.of(-1,101,1.5,"NaN","Infinity","12cm")) assertEquals("invalid",v.resolve(n,bad,"predicted","test","").status());
        assertEquals("predicted",v.resolve(n,100,"predicted","test","").status());
        var e=indicator("SINGLE_SELECT","",List.of("存在","不存在","未知"));
        assertEquals("invalid",v.resolve(e,"随意值","predicted","test","").status());
        assertEquals("unknown",v.resolve(e,"unknown","predicted","test","").status());
        assertEquals("未知",v.resolve(e,null,"unknown","test","").value());
        assertEquals("not_applicable",v.resolve(e,Map.of("value","存在","status","not_applicable"),"predicted","test","").status());
        assertEquals("invalid",v.resolve(indicator("MULTI_SELECT","",List.of("A")),List.of("B"),"predicted","test","").status());
        assertEquals("invalid",v.resolve(indicator("TEXT","object",List.of()),"oops","predicted","test","").status());
    }
    @Test void textBatchRetriesAndRejectsForeignEvidence() {
        var q=mock(QwenClient.class); when(q.available()).thenReturn(true);
        when(q.askJson(anyString(),anyString())).thenThrow(new IllegalStateException()).thenReturn(Map.of("items",List.of(Map.of("key","a","result","met","evidenceCodes",List.of("x")))));
        var svc=new TextConditionEvaluationService(q,new ObjectMapper());
        assertEquals("MET",svc.evaluate(List.of(new TextConditionEvaluationService.Request("a","有烧伤",Map.of("x","烧伤")))).get("a").result());
        reset(q);when(q.available()).thenReturn(true); when(q.askJson(anyString(),anyString())).thenReturn(Map.of("items",List.of(Map.of("key","a","result","met","evidenceCodes",List.of("foreign")))));
        assertEquals("UNKNOWN",svc.evaluate(List.of(new TextConditionEvaluationService.Request("a","有烧伤",Map.of("x","烧伤")))).get("a").result());
    }
    @Test void extractionFailureIsolatedAndInvalidNotPublished() {
        var repository=mock(DecisionConfigRepository.class);var q=mock(QwenClient.class);var om=new ObjectMapper();
        var i=new IndicatorDefinition(1,1,"burn","x","测试","RAW","NUMBER","CASE_REPORT","",null,null,"","","range=0..100",false,0,List.of());
        when(repository.load("burn")).thenReturn(new DecisionConfigRepository.ConfigSnapshot(List.of(i),List.of(),List.of(),List.of(),List.of(),List.of()));
        when(q.available()).thenReturn(true);when(q.askJsonStreaming(anyString(),anyString(),any())).thenThrow(new IllegalStateException("test"));
        var svc=new IndicatorExtractionService(repository,new ChineseIndicatorMapper(),q,om,new DiseaseCodeResolver(),new RunLogBroker());
        var result=svc.extract(new AggregateExtractionRequest("failure","burn","测试：101",Map.of()));
        assertTrue(result.indicators().isEmpty());
        assertFalse(result.errors().isEmpty());
        verify(q,times(2)).askJsonStreaming(anyString(),anyString(),any());
    }
    @Test void posas20ComputesBothSubscalesWithoutCombiningThem() {
        var formulas = new DeterministicFormulaExecutor(new ObjectMapper());
        Map<String,Object> values = new LinkedHashMap<>();
        values.put("scar_vascularity_grade", "红色");
        values.put("scar_pigmentation_category", "色素沉着");
        values.put("scar_height_mm", 3);
        values.put("scar_surface_relief", "隆起不规则");
        values.put("scar_palpation_pliability", "坚实，块状抗压");
        values.put("scar_boundary_class", "超出原始创缘");
        values.put("scar_pain_nrs", 4);
        values.put("scar_pruritus_nrs", 6);
        values.put("scar_stiffness_nrs", 5);
        values.put("posas_patient_color_difference_score", 7);
        values.put("posas_patient_thickness_difference_score", 8);
        values.put("posas_patient_irregularity_score", 6);
        assertEquals(40, formulas.compute(formulaNode("posas_observer_total_score"), values).value());
        assertEquals(39, formulas.compute(formulaNode("posas_patient_total_score"), values).value());
        assertEquals(39, formulas.compute(formulaNode("posas_total_score"), values).value());
        assertEquals("观察者与患者量表均完整", formulas.compute(formulaNode("posas_completeness"), values).value());
        assertEquals("中等差异", formulas.compute(formulaNode("posas_burden_grade"), values).value());
    }
    @Test void missingExtractionPublishesConfiguredDefaultOnlyAsIndicatorValue() {
        var repository=mock(DecisionConfigRepository.class);var q=mock(QwenClient.class);var om=new ObjectMapper();
        var i=new IndicatorDefinition(1,1,"burn","x","测试","RAW","SINGLE_SELECT","CASE_REPORT","",null,null,"","","",true,0,
            List.of(new IndicatorOption(1,"存在",false,"",0),new IndicatorOption(2,"不存在",true,"",1)));
        when(repository.load("burn")).thenReturn(new DecisionConfigRepository.ConfigSnapshot(List.of(i),List.of(),List.of(),List.of(),List.of(),List.of()));
        when(q.available()).thenReturn(false);
        var svc=new IndicatorExtractionService(repository,new ChineseIndicatorMapper(),q,om,new DiseaseCodeResolver(),new RunLogBroker());
        var result=svc.extract(new AggregateExtractionRequest("missing-default","burn","没有提到这个指标",Map.of()));
        assertEquals("不存在",result.indicators().get("测试"));
        assertTrue(result.errors().isEmpty());
    }
    @Test void freeTextIndicatorPreservesExplicitUserInput() {
        var repository=mock(DecisionConfigRepository.class);var q=mock(QwenClient.class);var om=new ObjectMapper();
        var i=new IndicatorDefinition(1,1,"burn","medication_text","用药","RAW","TEXT","CASE_REPORT","","未提供",null,"","","string；maxLength=2000",true,0,List.of());
        when(repository.load("burn")).thenReturn(new DecisionConfigRepository.ConfigSnapshot(List.of(i),List.of(),List.of(),List.of(),List.of(),List.of()));
        when(q.available()).thenReturn(false);
        var svc=new IndicatorExtractionService(repository,new ChineseIndicatorMapper(),q,om,new DiseaseCodeResolver(),new RunLogBroker());
        var result=svc.extract(new AggregateExtractionRequest("free-text","burn","用药：布洛芬缓释胶囊，每次0.3克，每日两次；",Map.of()));
        assertEquals("布洛芬缓释胶囊，每次0.3克，每日两次",result.indicators().get("用药"));
    }
    @Test void explicitNoAssociatedTraumaMustNotMatchPositiveTraumaRule() {
        var repository=mock(DecisionConfigRepository.class);var q=mock(QwenClient.class);var om=new ObjectMapper();
        var trauma=new IndicatorDefinition(1,1,"burn","associated_trauma_presence","合并创伤","RAW","SINGLE_SELECT","CASE_REPORT","","不存在",null,"","","",true,0,
                List.of(new IndicatorOption(1,"存在",false,"",0),new IndicatorOption(2,"不存在",true,"",1)));
        when(repository.load("burn")).thenReturn(new DecisionConfigRepository.ConfigSnapshot(List.of(trauma),List.of(),List.of(),List.of(),List.of(),List.of()));
        when(q.available()).thenReturn(false);
        var service=new IndicatorExtractionService(repository,new ChineseIndicatorMapper(),q,om,new DiseaseCodeResolver(),new RunLogBroker());
        var response=service.extract(new AggregateExtractionRequest("trauma-negation","burn","患者28岁女性，热液烫伤，无合并创伤。",Map.of()));
        assertEquals("不存在",response.indicatorCodes().get("associated_trauma_presence"));
    }
    @Test void aggregateExtractionReturnsCodeMapUsesSourcePriorityAndPublishesUnifiedLogs() {
        var repository=mock(DecisionConfigRepository.class);var q=mock(QwenClient.class);var om=new ObjectMapper();var logs=new RunLogBroker();
        var options=List.of(new IndicatorOption(1,"A",false,"",0),new IndicatorOption(2,"B",false,"",1));
        var textPreferred=new IndicatorDefinition(1,1,"burn","case_value","病例指标","RAW","SINGLE_SELECT","CASE_REPORT","",null,"$.case_value","","","",true,0,options);
        var imagePreferred=new IndicatorDefinition(2,1,"burn","image_value","图像指标","RAW","SINGLE_SELECT","IMAGE_JSON","",null,"$.image_value","","","",false,1,options);
        when(repository.load("burn")).thenReturn(new DecisionConfigRepository.ConfigSnapshot(List.of(textPreferred,imagePreferred),List.of(),List.of(),List.of(),List.of(),List.of()));
        when(q.available()).thenReturn(false);
        var svc=new IndicatorExtractionService(repository,new ChineseIndicatorMapper(),q,om,new DiseaseCodeResolver(),logs);
        var result=svc.extract(new AggregateExtractionRequest("aggregate-1","burn","病例指标：A；图像指标：A；",Map.of("case_value","B","image_value","B")));
        assertEquals(Map.of("病例指标","A","图像指标","B"),result.indicators());
        assertEquals(Map.of("case_value","A","image_value","B"),result.indicatorCodes());
        assertTrue(result.errors().isEmpty());
        assertTrue(logs.events("aggregate-1").stream().map(event->event.get("message").toString()).anyMatch(message->message.contains("文字中已识别：病例指标为A")));
        assertTrue(logs.events("aggregate-1").stream().map(event->event.get("message").toString()).anyMatch(message->message.contains("图片中已识别：图像指标为B")));
    }
    @Test void aggregatePublishesRequiredAndImagesButOmitsMissingOptionalText() {
        var repository=mock(DecisionConfigRepository.class);var q=mock(QwenClient.class);var om=new ObjectMapper();
        var yesNo=List.of(new IndicatorOption(1,"存在",false,"",0),new IndicatorOption(2,"不存在",true,"",1));
        var requiredText=new IndicatorDefinition(1,1,"burn","required_text","必填文本","RAW","SINGLE_SELECT","CASE_REPORT","","不存在","$.case.required_text","","","",true,0,yesNo);
        var optionalText=new IndicatorDefinition(2,1,"burn","optional_text","非必填文本","RAW","SINGLE_SELECT","CASE_REPORT","","不存在","$.case.optional_text","","","",false,1,yesNo);
        var image=new IndicatorDefinition(3,1,"burn","image_value","图像指标","RAW","SINGLE_SELECT","IMAGE_JSON","","不存在","$.image_value","","","",false,2,yesNo);
        when(repository.load("burn")).thenReturn(new DecisionConfigRepository.ConfigSnapshot(List.of(requiredText,optionalText,image),List.of(),List.of(),List.of(),List.of(),List.of()));
        when(q.available()).thenReturn(false);
        var svc=new IndicatorExtractionService(repository,new ChineseIndicatorMapper(),q,om,new DiseaseCodeResolver(),new RunLogBroker());
        var missing=svc.extract(new AggregateExtractionRequest("subset-1","burn","未提供相关指标",Map.of()));
        assertEquals(Map.of("required_text","不存在","image_value","不存在"),missing.indicatorCodes());
        assertFalse(missing.indicatorCodes().containsKey("optional_text"));
        var observed=svc.extract(new AggregateExtractionRequest("subset-2","burn","非必填文本：存在",Map.of()));
        assertEquals("存在",observed.indicatorCodes().get("optional_text"));
    }
    @Test void jsonPathDoesNotMakeCaseReportAnImageIndicator() {
        var caseReport=new IndicatorDefinition(1,1,"burn","case_value","文本指标","RAW","SINGLE_SELECT","CASE_REPORT","","不存在","$.case_value","","","",false,0,
                List.of(new IndicatorOption(1,"存在",false,"",0),new IndicatorOption(2,"不存在",true,"",1)));
        assertTrue(caseReport.isTextLike());
        assertFalse(caseReport.isImageLike());
    }
    @Test void reportKeepsResponseSubsetWhileDecisionFactsUseMissingDefaults() {
        var repository=mock(DecisionConfigRepository.class);var q=mock(QwenClient.class);var om=new ObjectMapper();
        var provided=new IndicatorDefinition(1,1,"burn","provided","已提交","RAW","NUMBER","CASE_REPORT","",null,null,"","","range=0..100",false,0,List.of());
        var missing=new IndicatorDefinition(2,1,"burn","missing","未提交","RAW","SINGLE_SELECT","CASE_REPORT","","不存在",null,"","","",false,1,
                List.of(new IndicatorOption(1,"存在",false,"",0),new IndicatorOption(2,"不存在",true,"",1)));
        var snapshot=new DecisionConfigRepository.ConfigSnapshot(List.of(provided,missing),List.of(),List.of(),List.of(),List.of(),List.of());
        when(repository.load("burn")).thenReturn(snapshot);when(q.available()).thenReturn(false);
        var service=new ReportGenerationService(repository,new ChineseIndicatorMapper(),om,new RunLogBroker(),q,
                mock(DecisionTreeRuntimeService.class),mock(DecisionTreeGateService.class),new DiseaseCodeResolver(),180000,300000);
        var response=service.generate(new ReportGenerationRequest("burn","report-subset",Map.of("provided",12)));
        assertEquals(Map.of("provided",12),response.normalizedIndicatorCodes());
        assertFalse(response.normalizedIndicatorCodes().containsKey("missing"));
        assertEquals(Map.of("provided",12,"missing","不存在"),service.completeFactsWithDefaults(snapshot,Map.of("provided",12)));
        assertFalse(response.report().get("病史回顾").toString().contains("未提交"));
    }
    @Test void explicitTbsaVssAndScarDiagnosisAreExtractedWithoutChangingProtocol() {
        var repository=mock(DecisionConfigRepository.class);var q=mock(QwenClient.class);var om=new ObjectMapper();
        var tbsa=new IndicatorDefinition(1,1,"burn","tbsa_percent_total","烧伤总面积","DECISION","NUMBER","DECISION_TREE","%TBSA","未生成",null,"","","range=0..100",false,0,List.of());
        var vss=new IndicatorDefinition(2,1,"burn","vss_total_score","VSS总分","DECISION","NUMBER","DECISION_TREE","point","未生成",null,"","","integer;range=0..15",false,1,List.of());
        var morphology=new IndicatorDefinition(3,1,"burn","scar_morphology_class","瘢痕形态分类","DECISION","SINGLE_SELECT","DECISION_TREE","","未生成",null,"","","",false,2,
                List.of(new IndicatorOption(1,"瘢痕疙瘩",false,"",0),new IndicatorOption(2,"广泛增生性瘢痕",false,"",1)));
        var hk=new IndicatorDefinition(4,1,"burn","hypertrophic_keloid_class","增生性瘢痕/瘢痕疙瘩判别","DECISION","SINGLE_SELECT","DECISION_TREE","","未生成",null,"","","",false,3,
                List.of(new IndicatorOption(1,"瘢痕疙瘩可能",false,"",0),new IndicatorOption(2,"增生性瘢痕可能",false,"",1)));
        var pruritus=new IndicatorDefinition(5,1,"burn","scar_pruritus_presence","瘢痕瘙痒","RAW","SINGLE_SELECT","CASE_REPORT","","不存在",null,"","","",false,4,
                List.of(new IndicatorOption(1,"存在",false,"",0),new IndicatorOption(2,"不存在",true,"",1)));
        var pain=new IndicatorDefinition(6,1,"burn","scar_pain_presence","瘢痕疼痛","RAW","SINGLE_SELECT","CASE_REPORT","","不存在",null,"","","",false,5,
                List.of(new IndicatorOption(1,"存在",false,"",0),new IndicatorOption(2,"不存在",true,"",1)));
        when(repository.load("burn")).thenReturn(new DecisionConfigRepository.ConfigSnapshot(List.of(tbsa,vss,morphology,hk,pruritus,pain),List.of(),List.of(),List.of(),List.of(),List.of()));
        when(q.available()).thenReturn(false);
        var svc=new IndicatorExtractionService(repository,new ChineseIndicatorMapper(),q,om,new DiseaseCodeResolver(),new RunLogBroker());
        var result=svc.extract(new AggregateExtractionRequest("explicit","burn","患者24岁，烧伤总面积95%TBSA，明确诊断瘢痕疙瘩，VSS总分11分，瘙痒明显并伴触痛。",Map.of()));
        assertFalse(result.indicatorCodes().containsKey("tbsa_percent_total"));
        assertFalse(result.indicatorCodes().containsKey("vss_total_score"));
        assertFalse(result.indicatorCodes().containsKey("scar_morphology_class"));
        assertEquals("存在",result.indicatorCodes().get("scar_pruritus_presence"));
        assertEquals("存在",result.indicatorCodes().get("scar_pain_presence"));
    }
    @Test void duplicateNodeIdsReturnErrorsInsteadOfThrowing() {
        var om=new ObjectMapper();var svc=new DecisionTreeGraphService(new DataDrivenNodeLogicService(om),om);
        var n=new DecisionNodeDefinition(1,1,"x","x","INDICATOR","x","RAW",null,"MODEL_EDGE_SELECTION","[]",null,null,null,null,null,0);
        var tree=new DecisionTreeDefinition(1,1,"burn","B","B","",null,null,null,0,List.of(n,n),List.of());
        assertTrue(svc.validateTree(tree).stream().anyMatch(e->e.contains("ID 重复")));
    }
    @Test void textTriggerWithoutBoundIndicatorIsEvaluated() {
        var q=mock(QwenClient.class);when(q.available()).thenReturn(true);
        when(q.askJson(anyString(),anyString())).thenReturn(Map.of("items",List.of(Map.of("key","item:1","result","not_met"))));
        var gate=new DecisionTreeGateService(new TextConditionEvaluationService(q,new ObjectMapper()));
        var tree=new DecisionTreeDefinition(1,1,"burn","B","B","",2L,null,null,0,List.of(),List.of());
        var row=new IndicatorGroupItemDefinition(1,2,null,true,0,"TEXT","有感染依据",null,0);
        var snap=new DecisionConfigRepository.ConfigSnapshot(List.of(),List.of(tree),List.of(),List.of(row),List.of(),List.of());
        assertFalse(gate.evaluateTrigger(tree,snap,Map.of("x","无感染")).runnable());
        verify(q).askJson(anyString(),anyString());
    }
    @Test void textFailureDoesNotBecomeMet() {
        var q=mock(QwenClient.class);when(q.available()).thenReturn(true);when(q.askJson(anyString(),anyString())).thenThrow(new IllegalStateException());
        var svc=new TextConditionEvaluationService(q,new ObjectMapper());
        assertEquals("UNKNOWN",svc.truth("存在感染",Map.of()));
        verify(q,times(2)).askJson(anyString(),anyString());
    }
    @Test void invalidGraphDetectsCycleModeAndMalformedInputs() {
        var om=new ObjectMapper();var graph=new DecisionTreeGraphService(new DataDrivenNodeLogicService(om),om);
        var n=new DecisionNodeDefinition(1,1,"x","x","INDICATOR","x","RAW",null,"UNSUPPORTED","[1]",null,null,null,null,null,0);
        var e=new DecisionEdgeDefinition(1,1,1,1,"loop","",null,null,"FIXED",0,false);
        var tree=new DecisionTreeDefinition(1,1,"burn","B","B","",null,null,null,0,List.of(n),List.of(e));
        var errors=graph.validateTree(tree);
        assertTrue(errors.stream().anyMatch(x->x.contains("环路")));
        assertTrue(errors.stream().anyMatch(x->x.contains("输入编码")));
        assertTrue(errors.stream().anyMatch(x->x.contains("根指标")));
    }
    @Test void textDependencyAndTreeJudgmentExecute() {
        var q=mock(QwenClient.class);when(q.available()).thenReturn(true);
        when(q.askJson(anyString(),anyString())).thenReturn(Map.of("items",List.of(Map.of("key","condition","result","met","evidenceCodes",List.of("x")), Map.of("key","dependency:1","result","met","evidenceCodes",List.of("x")))));
        var om=new ObjectMapper();var evaluator=new TextConditionEvaluationService(q,om);
        var gate=new DecisionTreeGateService(evaluator);
        var root=new DecisionNodeDefinition(1,1,"root","root","INDICATOR","y","OUTPUT_INDICATOR",null,"MODEL_EDGE_SELECTION","[\"x\"]",null,null,null,null,null,0);
        var judgment=new DecisionNodeDefinition(2,1,"judgment","judgment","JUDGMENT","y","JUDGMENT","yes","FIXED_OUTPUT","[\"x\"]","有感染",null,
            "{\"kind\":\"CONDITION_GROUPS\",\"groups\":[{\"conditions\":[{\"mode\":\"INDICATOR\",\"indicatorCode\":\"x\",\"operator\":\"TEXT_OPERATOR\",\"text\":\"有感染\"}]}]}",null,null,1);
        var value=new DecisionNodeDefinition(3,1,"value","value","INDICATOR_VALUE","y","DECISION","yes","FIXED_OUTPUT","[]",null,null,null,null,"{\"indicatorCode\":\"y\",\"value\":\"yes\"}",2);
        var edges=List.of(new DecisionEdgeDefinition(1,1,1,2,"a","",null,"[]","MODEL_EDGE_SELECTION",0,false),new DecisionEdgeDefinition(2,1,2,3,"b","",null,"[]","FIXED",0,false));
        var tree=new DecisionTreeDefinition(1,1,"burn","B","B","",null,null,null,0,List.of(root,judgment,value),edges);
        var dep=new DecisionTreeDependencyDefinition(1,1,2,0,"COMPLETION",null,null,null,null);
        var cond=new DecisionTreeDependencyConditionDefinition(1,1,0,"TEXT_OPERATOR",null,null,null,"有感染",0);
        var snap=new DecisionConfigRepository.ConfigSnapshot(List.of(),List.of(tree),List.of(),List.of(),List.of(dep),List.of(cond));
        assertTrue(gate.evaluatePrerequisite(tree,snap,Map.of("x","感染"),Map.of(2L,"COMPLETED")).runnable());
        var edgeModel=mock(EdgeSelectionModel.class);
        var runtime=new DecisionTreeRuntimeService(new DeterministicFormulaExecutor(om),edgeModel,new DataDrivenNodeLogicService(om),om,evaluator);
        var result=runtime.execute(tree,new DecisionTreeExecutionRequest(Map.of("x","感染"),Map.of(),Map.of(),Map.of()));
        assertEquals("COMPLETED",result.status(),result.validationErrors().toString());
        assertEquals("yes",result.decisionOutputs().get("y"));verifyNoInteractions(edgeModel);
    }
}

