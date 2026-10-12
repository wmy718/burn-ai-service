package com.nytryx.burnai.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import java.util.*;

/** Batched three-valued text evaluation, with isolated retries and scoped evidence. */
@Service
public class TextConditionEvaluationService {
    private final AiModelClient client;
    private final ObjectMapper mapper;
    public TextConditionEvaluationService(AiModelClient client,ObjectMapper mapper) { this.client=client; this.mapper=mapper; }
    public static boolean isText(String value) { return "TEXT".equals(value)||"TEXT_OPERATOR".equals(value); }
    public record Request(String key,String text,Map<String,Object> facts) {}
    public record Result(String result,String reason,List<String> evidenceCodes) {}
    public Map<String,Result> evaluate(List<Request> requests) {
        Map<String,Result> results=new LinkedHashMap<>();
        List<Request> pending=new ArrayList<>();
        for(Request r:requests) {
            String t=Objects.toString(r.text(), "").replace(" ", "");
            if(t.isBlank() || Set.of("无","根树").contains(t) || t.startsWith("无，根树") || t.startsWith("无,根树")) results.put(r.key(),new Result("MET","无额外条件",List.of()));
            else if(!client.available()) results.put(r.key(),unknown("条件模型不可用"));
            else pending.add(r);
        }
        for(int offset=0;offset<pending.size();offset+=12) {
            var batch=pending.subList(offset,Math.min(offset+12,pending.size()));
            try { results.putAll(call(batch)); } catch(RuntimeException ex) { /* isolate below */ }
            for(Request r:batch) if(!results.containsKey(r.key())) {
                try { results.putAll(call(List.of(r))); } catch(RuntimeException ex) { results.put(r.key(),unknown("条件模型批量及单条重试失败："+ex.getClass().getSimpleName())); }
                results.putIfAbsent(r.key(),unknown("模型未返回当前条件"));
            }
        }
        return results;
    }
    private Map<String,Result> call(List<Request> batch) {
        String input;
        try { input=mapper.writeValueAsString(batch); } catch(Exception ex) { throw new IllegalArgumentException("条件序列化失败"); }
        var response=client.askJson("依据每条条件的 facts 判断，禁止使用其他条件的事实或补造证据。unknown/invalid/not_applicable 状态不是已确认事实。证据不足返回 unknown。返回 JSON 对象 {items:[{key,result,reason,evidenceCodes}]}，result 只能 met/not_met/unknown。",input);
        if(!(response.get("items") instanceof List<?> items)) throw new IllegalArgumentException("条件结果格式错误");
        Map<String,Result> results=new LinkedHashMap<>();
        for(Object item:items) {
            if(!(item instanceof Map<?,?> row)) continue;
            Request r=batch.stream().filter(q->q.key().equals(row.get("key"))).findFirst().orElse(null);
            if(r==null) continue;
            String truth=Objects.toString(row.get("result"),"").toUpperCase(Locale.ROOT);
            if(!Set.of("MET","NOT_MET","UNKNOWN").contains(truth)) continue;
            List<String> codes=row.get("evidenceCodes") instanceof List<?> list?list.stream().map(String::valueOf).toList():List.of();
            if(!r.facts().keySet().containsAll(codes)) continue;
            results.put(r.key(),new Result(truth,Objects.toString(row.get("reason"),""),codes));
        }
        return results;
    }
    public String truth(String text,Map<String,Object> facts) { return evaluate(List.of(new Request("condition",text,facts))).get("condition").result(); }
    private Result unknown(String reason) { return new Result("UNKNOWN",reason,List.of()); }
}
