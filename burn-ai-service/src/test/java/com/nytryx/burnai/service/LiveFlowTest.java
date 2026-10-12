package com.nytryx.burnai.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nytryx.burnai.model.*;
import com.nytryx.burnai.repo.DecisionConfigRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE)
@EnabledIfSystemProperty(named="live",matches="true")
class LiveFlowTest {
 @Autowired IndicatorExtractionService extraction;
 @Autowired ReportGenerationService reports;
 @Autowired DecisionConfigRepository repository;
 @Autowired DecisionTreeGraphService graph;
 @Autowired DecisionTreeRuntimeService runtime;
 @Autowired RunLogBroker logs;
 @Autowired ObjectMapper om;
 @Test void allConfiguredGraphsAreValid() {
  Map<String,Object> graphErrors=new LinkedHashMap<>();
  for(String disease:List.of("burn","scar")) {
   for(var tree:repository.load(disease).trees()) {
    var errors=graph.validateTree(tree);
    if(!errors.isEmpty()) graphErrors.put(disease+":"+tree.code(),errors);
   }
  }
  assertTrue(graphErrors.isEmpty(),graphErrors.toString());
 }
 @Test void posasPatientResultIsProducedWhenObserverImageDataIsUnavailable() {
  var tree=repository.load("scar").trees().stream().filter(item->"S22".equals(item.code())).findFirst().orElseThrow();
  Map<String,Object> values=new LinkedHashMap<>();
  values.put("scar_pain_nrs",3);
  values.put("scar_pruritus_nrs",5);
  values.put("scar_stiffness_nrs",4);
  values.put("posas_patient_color_difference_score",7);
  values.put("posas_patient_thickness_difference_score",8);
  values.put("posas_patient_irregularity_score",6);
  var result=runtime.execute(tree,new DecisionTreeExecutionRequest(values,Map.of(),Map.of(),Map.of()));
  assertEquals("COMPLETED",result.status(),result.validationErrors().toString());
  assertEquals(36,result.decisionOutputs().get("posas_patient_total_score"));
  assertEquals(36,result.decisionOutputs().get("posas_total_score"));
  assertEquals("仅患者量表完整",result.decisionOutputs().get("posas_completeness"));
  assertEquals("中等差异",result.decisionOutputs().get("posas_burden_grade"));
 }
 @Test void burnAndScar() throws Exception {
  Path dir=Path.of("test-results");Files.createDirectories(dir);
  List<Map<String,Object>> summary=new ArrayList<>();
  for(String disease:List.of("burn","scar")) {
   var snapshot=repository.load(disease);
   Map<String,Object> graphErrors=new LinkedHashMap<>();
   for(var tree:snapshot.trees()) { var errors=graph.validateTree(tree); if(!errors.isEmpty()) graphErrors.put(tree.code(),errors); }
   om.writerWithDefaultPrettyPrinter().writeValue(dir.resolve(disease+"-graph-errors.json").toFile(),graphErrors);
   var sample=om.readTree(Path.of("..","burn-ai-service-"+disease+"-flow-v2.json").toFile());
   Map<String,Object> textValues=om.convertValue(sample.path("textIndicators"),Map.class);
   Map<String,Object> imageValues=om.convertValue(sample.path("imageIndicators"),Map.class);
   StringBuilder text=new StringBuilder();textValues.forEach((k,v)->text.append(k).append("：").append(v).append("；"));
   var extractionRequest=new AggregateExtractionRequest("extract-"+disease+"-"+UUID.randomUUID(),disease,text.toString(),imageValues);
   System.out.println("LIVE START "+disease+" trees="+snapshot.trees().size()+" graphErrors="+graphErrors.size());
   var extracted=extraction.extract(extractionRequest);System.out.println("LIVE EXTRACT "+disease+" "+extracted.elapsedMs());
   var reportRequest=new ReportGenerationRequest(disease,"benchmark-"+disease+"-"+UUID.randomUUID(),extracted.indicatorCodes());
   var r=reports.generate(reportRequest);System.out.println("LIVE REPORT "+disease+" "+r.elapsedMs());
   om.writerWithDefaultPrettyPrinter().writeValue(dir.resolve(disease+"-flow.json").toFile(),Map.of("extractionRequest",extractionRequest,"reportRequest",reportRequest,"extraction",extracted,"report",r));
   summary.add(Map.of("disease",disease,"extractionMs",extracted.elapsedMs(),"reportMs",r.elapsedMs(),"extractionTimings",extracted.timings(),"reportTimings",r.timings(),"graphErrors",graphErrors.size()));
   om.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("summary.json").toFile(),summary);
   assertTrue(graphErrors.isEmpty(),graphErrors.toString());
   assertEquals(List.of("病史回顾","临床表现分析","诊断意见","治疗建议","预后评估","后续建议","注意事项","总结"),new ArrayList<>(r.report().keySet()));
    assertTrue(r.report().get("病史回顾") instanceof Map);
    assertTrue(r.report().get("临床表现分析") instanceof Map);
    assertTrue(r.report().get("诊断意见") instanceof Map);
    assertTrue(r.report().get("治疗建议") instanceof Map);
   assertTrue(r.report().get("后续建议") instanceof Map);
    assertTrue(logs.events(reportRequest.requestId()).stream()
            .map(event -> Objects.toString(event.get("message"), ""))
            .anyMatch(message -> message.startsWith("正在整理病史回顾")), "missing streamed report progress");
    assertEquals(extracted.indicators().size(),extracted.indicatorCodes().size());
    assertEquals(r.normalizedIndicators().size(),r.normalizedIndicatorCodes().size());
    assertEquals(r.decisionOutputs().size(),r.decisionOutputCodes().size());
    assertEquals(extracted.indicatorCodes(),r.normalizedIndicatorCodes());
    for(var indicator:snapshot.rawIndicators()) {
     boolean returned=extracted.indicatorCodes().containsKey(indicator.code());
     if(indicator.isTextLike() && indicator.required()) {
      assertTrue(returned,"required text indicator missing: "+indicator.code());
     }
     if(indicator.isImageLike()) assertTrue(returned,"image indicator must be returned for editing: "+indicator.code());
     if(returned) {
      assertTrue(indicator.isTextLike(),"only text indicators may be published: "+indicator.code());
     }
    }
    for(String error:extracted.errors()) {
     String code=error.substring(0,error.indexOf(':'));
     var indicator=snapshot.indicatorByCode().get(code);
     if(indicator!=null && indicator.isTextLike() && !indicator.required()) {
      assertFalse(extracted.indicatorCodes().containsKey(code),"invalid optional text indicator was published: "+code);
     }
    }
    assertTrue(extracted.elapsedMs()<300000 && r.elapsedMs()<300000);
  }
 }
}
