package com.nytryx.burnai.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nytryx.burnai.repo.DecisionConfigRepository;
import com.nytryx.burnai.model.DecisionTreeExecutionRequest;
import com.nytryx.burnai.service.DecisionTreeRuntimeService;
import com.nytryx.burnai.service.DecisionTreeGraphService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** Administrative CRUD limited to AI decision configuration tables (llm_*). */
@RestController
@RequestMapping("/api/admin")
public class DecisionConfigAdminApiController {
    private final JdbcTemplate db;
    private final DecisionConfigRepository repository;
    private final ObjectMapper json;
    private final DecisionTreeRuntimeService runtime;
    private final DecisionTreeGraphService graph;

    @Autowired
    public DecisionConfigAdminApiController(JdbcTemplate db, DecisionConfigRepository repository, ObjectMapper json,
                                            DecisionTreeRuntimeService runtime, DecisionTreeGraphService graph) {
        this.db = db;
        this.repository = repository;
        this.json = json;
        this.runtime = runtime;
        this.graph = graph;
    }

    // Test constructor supports isolated route contract tests.
    DecisionConfigAdminApiController(DecisionConfigRepository repository, ObjectMapper json) {
        this(null, repository, json, null, null);
    }

    @GetMapping("/diseases") public Map<String,Object> diseases(@RequestParam(required=false) String keyword) {
        return ok(db.query("select * from llm_disease where (? is null or name like ?) order by sort_order,id",
                (rs,n)->row(rs), keyword, keyword == null ? null : "%"+keyword+"%"));
    }
    @GetMapping("/diseases/{id}") public Map<String,Object> disease(@PathVariable long id) { return ok(one("llm_disease",id)); }
    @GetMapping("/diseases/{id}/overview") public Map<String,Object> overview(@PathVariable long id) {
        return ok(Map.of("diseaseIndicatorCount",count("llm_indicator", "disease_id",id,"DISEASE"),
                "diagnosisIndicatorCount",count("llm_indicator","disease_id",id,"DIAGNOSIS"),
                "decisionIndicatorCount",count("llm_indicator","disease_id",id,"DECISION"),
                "groupCount",db.queryForObject("select count(*) from llm_indicator_group where disease_id=?",Integer.class,id),
                "decisionTreeCount",db.queryForObject("select count(*) from llm_decision_tree where disease_id=?",Integer.class,id)));
    }
    @PostMapping("/diseases") public Map<String,Object> createDisease(@RequestBody Map<String,Object> p) {
        long id=id(); exec("insert into llm_disease(id,code,name,description,status,sort_order,report_template_mode) values(?,?,?,?,?,?,?)",
                id,p.get("code"),p.get("name"),p.get("description"),def(p,"status","ENABLED"),num(p,"sortOrder"),def(p,"reportTemplateMode","MANUAL"));
        return disease(id);
    }
    @PutMapping("/diseases/{id}") public Map<String,Object> updateDisease(@PathVariable long id,@RequestBody Map<String,Object> p) {
        exec("update llm_disease set code=?,name=?,description=?,status=?,sort_order=?,report_template_mode=coalesce(?,report_template_mode) where id=?",
                p.get("code"),p.get("name"),p.get("description"),def(p,"status","ENABLED"),num(p,"sortOrder"),p.get("reportTemplateMode"),id); return disease(id);
    }
    @PutMapping("/diseases/{id}/report-template-mode") public Map<String,Object> templateMode(@PathVariable long id,@RequestBody Map<String,Object> p) {
        exec("update llm_disease set report_template_mode=? where id=?",p.get("mode"),id); return disease(id);
    }
    @DeleteMapping("/diseases/{id}") @Transactional public Map<String,Object> deleteDisease(@PathVariable long id) {
        if (count("llm_indicator","disease_id",id,null)>0 || db.queryForObject("select count(*) from llm_decision_tree where disease_id=?",Integer.class,id)>0)
            throw new IllegalArgumentException("病种仍有关联指标或决策树，不能删除");
        exec("delete from llm_indicator_group_item where group_id in (select id from llm_indicator_group where disease_id=?)",id);
        exec("delete from llm_indicator_group where disease_id=?",id); exec("delete from llm_disease where id=?",id); return ok(Map.of("deleted",true));
    }

    @GetMapping("/indicators") public Map<String,Object> indicators(@RequestParam(required=false) Long diseaseId,@RequestParam(required=false) String type) {
        String sql="select i.*,i.required as required from llm_indicator i where (? is null or disease_id=?) and (? is null or type=?) order by sort_order,id";
        return ok(db.query(sql,(rs,n)->row(rs),diseaseId,diseaseId,type,type));
    }
    @PostMapping("/indicators") public Map<String,Object> createIndicator(@RequestBody Map<String,Object> p) {
        long id=id(); writeIndicator(id,p,false); return ok(one("llm_indicator",id));
    }
    @PutMapping("/indicators/{id}") public Map<String,Object> updateIndicator(@PathVariable long id,@RequestBody Map<String,Object> p) {
        writeIndicator(id,p,true); return ok(one("llm_indicator",id));
    }
    @PutMapping("/indicators/{id}/with-values") @Transactional public Map<String,Object> updateIndicatorWithValues(@PathVariable long id,@RequestBody Map<String,Object> p) {
        Map<String,Object> indicator=map(p.get("indicator")); writeIndicator(id,indicator,true); replaceValues(id,list(p.get("values"))); return ok(one("llm_indicator",id));
    }
    @GetMapping("/indicators/{id}/values") public Map<String,Object> values(@PathVariable long id) {
        return ok(db.query("select * from llm_indicator_value_option where indicator_id=? order by sort_order,id",(rs,n)->row(rs),id));
    }
    @PutMapping("/indicators/{id}/values") @Transactional public Map<String,Object> replaceIndicatorValues(@PathVariable long id,@RequestBody List<Map<String,Object>> values) {
        replaceValues(id,values); return ok(db.query("select * from llm_indicator_value_option where indicator_id=? order by sort_order,id",(rs,n)->row(rs),id));
    }
    @PostMapping("/indicators/{id}/values/impact") public Map<String,Object> valueImpact(@PathVariable long id,@RequestBody(required=false) Object ignored) {
        Map<String,Object> indicator=one("llm_indicator",id); if(indicator==null) throw new IllegalArgumentException("指标不存在");
        String code=Objects.toString(indicator.get("code"),"");
        List<Map<String,Object>> refs=db.query("select t.name as tree_name,n.node_key,n.value_code from llm_decision_node n join llm_decision_tree t on t.id=n.tree_id where n.indicator_code=? and n.node_type='INDICATOR_VALUE' order by t.sort_order,t.id,n.sort_order,n.id",(rs,n)->row(rs),code);
        return ok(Map.of("blocked",!refs.isEmpty(),"references",refs.stream().map(r->{Map<String,Object> item=new LinkedHashMap<>(); item.put("location",r.get("treeName")+" / "+r.get("nodeKey")); item.put("automaticallyUpdated",false); item.put("valueCode",r.get("valueCode")); return item;}).toList(),"renames",Map.of(),"summary",refs.isEmpty()?"未发现决策树节点引用":"该指标的已有取值仍被决策树节点引用，请先调整节点配置"));
    }
    @DeleteMapping("/indicators/{id}") @Transactional public Map<String,Object> deleteIndicator(@PathVariable long id) {
        Integer refs=db.queryForObject("select (select count(*) from llm_decision_tree_indicator where indicator_id=?)+(select count(*) from llm_indicator_group_item where indicator_id=? )",Integer.class,id,id);
        if (refs!=null && refs>0) throw new IllegalArgumentException("指标仍被决策树或病种触发条件引用，不能删除");
        exec("delete from llm_indicator_value_option where indicator_id=?",id); exec("delete from llm_indicator where id=?",id); return ok(Map.of("deleted",true));
    }

    @GetMapping("/indicator-groups") public Map<String,Object> groups(@RequestParam(required=false) Long diseaseId) {
        List<Map<String,Object>> sourceRows=db.query("select * from llm_indicator_group where (? is null or disease_id=?) order by sort_order,id",(rs,n)->row(rs),diseaseId,diseaseId);
        List<Map<String,Object>> result=new ArrayList<>();
        for(var group:sourceRows) {
            List<Map<String,Object>> relations=db.query("select * from llm_indicator_group_item where group_id=? order by card_group,sort_order,id",(rs,n)->row(rs),group.get("id"));
            List<Map<String,Object>> items=new ArrayList<>();
            for(var item:relations) {
                Map<String,Object> relation=new LinkedHashMap<>(item);
                Map<String,Object> viewItem=new LinkedHashMap<>(); viewItem.put("relation",relation);
                viewItem.put("indicator",item.get("indicatorId")==null?null:one("llm_indicator",((Number)item.get("indicatorId")).longValue()));
                items.add(viewItem);
            }
            Map<String,Object> view=new LinkedHashMap<>(); view.put("group",group); view.put("items",items); result.add(view);
        }
        return ok(result);
    }
    @PostMapping("/indicator-groups") @Transactional public Map<String,Object> createGroup(@RequestBody Map<String,Object> p) { long id=id(); saveGroup(id,p,false); return ok(one("llm_indicator_group",id)); }
    @PutMapping("/indicator-groups/{id}") @Transactional public Map<String,Object> updateGroup(@PathVariable long id,@RequestBody Map<String,Object> p) { saveGroup(id,p,true); return ok(one("llm_indicator_group",id)); }
    @DeleteMapping("/indicator-groups/{id}") @Transactional public Map<String,Object> deleteGroup(@PathVariable long id) { exec("update llm_decision_tree set indicator_group_id=null where indicator_group_id=?",id); exec("delete from llm_indicator_group_item where group_id=?",id); exec("delete from llm_indicator_group where id=?",id); return ok(Map.of("deleted",true)); }

    @GetMapping("/decision-trees") public Map<String,Object> trees(@RequestParam(required=false) Long diseaseId) { return ok(treeRows(diseaseId)); }
    @GetMapping("/decision-trees/dependencies") public Map<String,Object> dependencies(@RequestParam(required=false) Long diseaseId) {
        List<Map<String,Object>> rows=db.query("select d.* from llm_decision_tree_dependency d join llm_decision_tree t on t.id=d.tree_id where (? is null or t.disease_id=?) order by d.tree_id,d.sort_order,d.id",(rs,n)->row(rs),diseaseId,diseaseId);
        for(var dependency:rows) dependency.put("conditions",db.query("select * from llm_decision_tree_dependency_condition where dependency_id=? order by card_group,sort_order,id",(rs,n)->row(rs),dependency.get("id")));
        return ok(rows);
    }
    @GetMapping("/decision-trees/{id}/detail") public Map<String,Object> treeDetail(@PathVariable long id) {
        Map<String,Object> tree=one("llm_decision_tree",id); if(tree==null) throw new IllegalArgumentException("决策树不存在");
        List<Map<String,Object>> treeIndicators=db.query("select * from llm_decision_tree_indicator where tree_id=? order by sort_order,id",(rs,n)->row(rs),id);
        for(int i=0;i<treeIndicators.size();i++) { Map<String,Object> relation=treeIndicators.get(i); Map<String,Object> view=new LinkedHashMap<>(); view.put("relation",relation); view.put("indicator",one("llm_indicator",((Number)relation.get("indicatorId")).longValue())); treeIndicators.set(i,view); }
        tree.put("indicators",treeIndicators);
        List<Map<String,Object>> nodes=db.query("select * from llm_decision_node where tree_id=? order by sort_order,id",(rs,n)->row(rs),id);
        List<Map<String,Object>> edges=db.query("select * from llm_decision_node_edge where tree_id=? order by priority_order,id",(rs,n)->row(rs),id);
        Map<String,Object> result=new LinkedHashMap<>(); result.put("tree",tree); result.put("nodes",nodes); result.put("edges",edges); result.put("indicators",treeIndicators); return ok(result);
    }
    @GetMapping("/decision-trees/{id}/condition-indicators") public Map<String,Object> conditionIndicators(@PathVariable long id) {
        Long disease=db.queryForObject("select disease_id from llm_decision_tree where id=?",Long.class,id);
        return ok(db.query("select i.* from llm_indicator i join llm_decision_tree_indicator r on r.indicator_id=i.id where r.tree_id=? and i.disease_id=? order by r.sort_order,r.id",(rs,n)->row(rs),id,disease));
    }
    @PostMapping("/decision-trees") @Transactional public Map<String,Object> createTree(@RequestBody Map<String,Object> p) { long id=id(); saveTree(id,p,false); return ok(one("llm_decision_tree",id)); }
    @PutMapping("/decision-trees/{id}") @Transactional public Map<String,Object> updateTree(@PathVariable long id,@RequestBody Map<String,Object> p) { saveTree(id,p,true); return ok(one("llm_decision_tree",id)); }
    @PutMapping("/decision-trees/{id}/prerequisites") @Transactional public Map<String,Object> prerequisites(@PathVariable long id,@RequestBody Map<String,Object> p) { savePrerequisites(id,list(p.get("prerequisites")),p.get("prerequisiteTreeIds")); return ok(Map.of("updated",true)); }
    @PutMapping("/decision-trees/layouts") @Transactional public Map<String,Object> layouts(@RequestBody List<Map<String,Object>> rows) { for(var r:rows) exec("update llm_decision_tree set graph_position_x=?,graph_position_y=? where id=?",r.getOrDefault("positionX",r.get("x")),r.getOrDefault("positionY",r.get("y")),r.getOrDefault("treeId",r.get("id"))); return ok(Map.of("updated",rows.size())); }
    @DeleteMapping("/decision-trees/{id}") @Transactional public Map<String,Object> deleteTree(@PathVariable long id) {
        exec("delete from llm_decision_tree_dependency_condition where dependency_id in (select id from llm_decision_tree_dependency where tree_id=? or prerequisite_tree_id=?)",id,id);
        exec("delete from llm_decision_tree_dependency where tree_id=? or prerequisite_tree_id=?",id,id); exec("delete from llm_decision_node_edge where tree_id=?",id);
        exec("delete from llm_decision_node where tree_id=?",id); exec("delete from llm_decision_tree_indicator where tree_id=?",id); exec("delete from llm_decision_tree where id=?",id); repository.invalidateCache(); return ok(Map.of("deleted",true));
    }
    @PostMapping("/decision-trees/{id}/indicators") @Transactional public Map<String,Object> addTreeIndicator(@PathVariable long id,@RequestBody Map<String,Object> p) { long rid=id(); exec("insert into llm_decision_tree_indicator(id,tree_id,indicator_id,required,condition_expression,sort_order) values(?,?,?,?,?,?)",rid,id,p.get("indicatorId"),bool(p,"required",true),p.get("conditionExpression"),num(p,"sortOrder")); repository.invalidateCache(); return ok(Map.of("id",rid,"treeId",id,"indicatorId",p.get("indicatorId"))); }
    @PutMapping("/decision-trees/indicators/{id}") public Map<String,Object> updateTreeIndicator(@PathVariable long id,@RequestBody Map<String,Object> p) { exec("update llm_decision_tree_indicator set required=?,condition_expression=?,sort_order=? where id=?",bool(p,"required",true),p.get("conditionExpression"),num(p,"sortOrder"),id); repository.invalidateCache(); return ok(one("llm_decision_tree_indicator",id)); }
    @DeleteMapping("/decision-trees/indicators/{id}") public Map<String,Object> deleteTreeIndicator(@PathVariable long id) { exec("delete from llm_decision_tree_indicator where id=?",id); repository.invalidateCache(); return ok(Map.of("deleted",true)); }
    @PostMapping("/decision-trees/{id}/nodes") @Transactional public Map<String,Object> createNode(@PathVariable long id,@RequestBody Map<String,Object> p) { long node=id(); writeNode(node,id,p,false); repository.invalidateCache(); return treeDetail(id); }
    @PutMapping("/decision-trees/nodes/{id}") @Transactional public Map<String,Object> updateNode(@PathVariable long id,@RequestBody Map<String,Object> p) { long tree=numLong(p,"treeId"); writeNode(id,tree,p,true); repository.invalidateCache(); return ok(one("llm_decision_node",id)); }
    @DeleteMapping("/decision-trees/nodes/{id}") @Transactional public Map<String,Object> deleteNode(@PathVariable long id) { Long tree=db.queryForObject("select tree_id from llm_decision_node where id=?",Long.class,id); exec("delete from llm_decision_node_edge where source_node_id=? or target_node_id=?",id,id); exec("delete from llm_decision_node where id=?",id); repository.invalidateCache(); return tree==null?ok(Map.of("deleted",true)):treeDetail(tree); }
    @PostMapping("/decision-trees/{id}/edges") @Transactional public Map<String,Object> createEdge(@PathVariable long id,@RequestBody Map<String,Object> p) { long edge=id(); exec("insert into llm_decision_node_edge(id,tree_id,source_node_id,target_node_id,edge_key,branch_label,condition_expression,input_indicator_codes,selector_mode,priority_order,is_default) values(?,?,?,?,?,?,?,?,?,?,?)",edge,id,p.get("sourceNodeId"),p.get("targetNodeId"),def(p,"edgeKey","edge_"+edge),def(p,"branchLabel","分支"),p.get("conditionExpression"),p.get("inputIndicatorCodes"),def(p,"selectorMode","MODEL_EDGE_SELECTION"),num(p,"priorityOrder"),bool(p,"isDefault",false)); repository.invalidateCache(); return treeDetail(id); }
    @GetMapping("/decision-trees/{id}/validate") public Map<String,Object> validateTree(@PathVariable long id) {
        var tree=repository.load("all").trees().stream().filter(item->item.id()==id).findFirst().orElseThrow(()->new IllegalArgumentException("决策树不存在"));
        return ok(graph.validateTree(tree));
    }
    @PostMapping("/decision-trees/{id}/execute") public Map<String,Object> executeTree(@PathVariable long id,@RequestBody(required=false) Map<String,Object> p) {
        var tree=repository.load("all").trees().stream().filter(item->item.id()==id).findFirst().orElseThrow(()->new IllegalArgumentException("决策树不存在"));
        Map<String,Object> body=p==null?Map.of():p;
        DecisionTreeExecutionRequest request=new DecisionTreeExecutionRequest(map(body.get("indicatorValues")),map(body.get("indicatorEvidence")),stringMap(body.get("edgeSelections")),stringListMap(body.get("edgeSetSelections")));
        return ok(runtime.execute(tree,request));
    }

    private List<Map<String,Object>> treeRows(Long diseaseId) {
        List<Map<String,Object>> rows=db.query("select * from llm_decision_tree where (? is null or disease_id=?) order by sort_order,id",(rs,n)->row(rs),diseaseId,diseaseId);
        for(var row:rows) row.put("prerequisiteTreeIds",db.queryForList("select prerequisite_tree_id from llm_decision_tree_dependency where tree_id=? order by sort_order,id",Long.class,row.get("id")));
        return rows;
    }
    private void saveTree(long id,Map<String,Object> p,boolean update) {
        if(update) exec("update llm_decision_tree set disease_id=?,indicator_group_id=?,code=?,name=?,description=?,status=?,sort_order=? where id=?",p.get("diseaseId"),p.get("indicatorGroupId"),def(p,"code","TREE_"+id),p.get("name"),p.get("description"),def(p,"status","ENABLED"),num(p,"sortOrder"),id);
        else exec("insert into llm_decision_tree(id,disease_id,indicator_group_id,code,name,description,status,sort_order) values(?,?,?,?,?,?,?,?)",id,p.get("diseaseId"),p.get("indicatorGroupId"),def(p,"code","TREE_"+id),p.get("name"),p.get("description"),def(p,"status","ENABLED"),num(p,"sortOrder"));
        if(p.containsKey("prerequisites")||p.containsKey("prerequisiteTreeIds")) savePrerequisites(id,list(p.get("prerequisites")),p.get("prerequisiteTreeIds")); repository.invalidateCache();
    }
    private void savePrerequisites(long treeId,List<Map<String,Object>> rules,Object idsValue) {
        exec("delete from llm_decision_tree_dependency_condition where dependency_id in (select id from llm_decision_tree_dependency where tree_id=?)",treeId); exec("delete from llm_decision_tree_dependency where tree_id=?",treeId);
        List<Map<String,Object>> rulesCopy=rules;
        if(rulesCopy.isEmpty() && idsValue instanceof List<?> ids) for(Object prerequisite:ids) rulesCopy.add(Map.of("prerequisiteTreeId",prerequisite,"cards",List.of()));
        int order=0; for(var rule:rulesCopy) { long dep=id(); long pre=numLong(rule,"prerequisiteTreeId"); exec("insert into llm_decision_tree_dependency(id,tree_id,prerequisite_tree_id,sort_order,condition_mode) values(?,?,?,?,?)",dep,treeId,pre,order++,"COMPLETION");
            int group=0; for(var card:list(rule.get("cards"))) { for(var condition:list(card.get("conditions"))) { String mode=Objects.toString(condition.get("mode"),"INDICATOR"); exec("insert into llm_decision_tree_dependency_condition(id,dependency_id,card_group,condition_mode,indicator_code,comparison_operator,expected_value,condition_text,sort_order) values(?,?,?,?,?,?,?,?,?)",id(),dep,group,mode,condition.get("indicatorCode"),condition.get("operator"),condition.get("expectedValue"),condition.get("text"),0); } group++; }
        } repository.invalidateCache();
    }
    private void saveGroup(long id,Map<String,Object> p,boolean update) {
        if(update) exec("update llm_indicator_group set disease_id=?,name=?,sort_order=? where id=?",p.get("diseaseId"),p.get("name"),num(p,"sortOrder"),id);
        else exec("insert into llm_indicator_group(id,disease_id,name,sort_order) values(?,?,?,?)",id,p.get("diseaseId"),p.get("name"),num(p,"sortOrder"));
        exec("delete from llm_indicator_group_item where group_id=?",id); int g=0; for(var card:list(p.get("cards"))) { for(var condition:list(card.get("conditions"))) exec("insert into llm_indicator_group_item(id,group_id,indicator_id,required,card_group,comparison_operator,expected_value,sort_order) values(?,?,?,?,?,?,?,?)",id(),id,condition.get("indicatorId"),true,g,condition.get("operator"),condition.get("expectedValue"),0); g++; }
        repository.invalidateCache();
    }
    private void writeIndicator(long id,Map<String,Object> p,boolean update) {
        String fields="disease_id=?,code=?,name=?,type=?,value_type=?,source_type=?,unit=?,default_value=?,sample_value=?,json_path=?,medical_type=?,options_json=?,inference_basis=?,description=?,trigger_usage=?,value_domain=?,missing_handling=?,source_evidence=?,required=?,status=?,sort_order=?";
        Object[] args={p.get("diseaseId"),p.get("code"),p.get("name"),def(p,"type","DISEASE"),def(p,"valueType","TEXT"),def(p,"sourceType","MIXED"),p.get("unit"),p.get("defaultValue"),p.get("sampleValue"),p.get("jsonPath"),p.get("medicalType"),p.get("optionsJson"),p.get("inferenceBasis"),p.get("description"),p.get("triggerUsage"),p.get("valueDomain"),p.get("missingHandling"),p.get("sourceEvidence"),bool(p,"required",true),def(p,"status","ENABLED"),num(p,"sortOrder")};
        if(update) { Object[] all=Arrays.copyOf(args,args.length+1); all[args.length]=id; exec("update llm_indicator set "+fields+" where id=?",all); }
        else exec("insert into llm_indicator(id,disease_id,code,name,type,value_type,source_type,unit,default_value,sample_value,json_path,medical_type,options_json,inference_basis,description,trigger_usage,value_domain,missing_handling,source_evidence,required,status,sort_order) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",prepend(id,args)); repository.invalidateCache();
    }
    private void replaceValues(long indicatorId,List<Map<String,Object>> values) { exec("delete from llm_indicator_value_option where indicator_id=?",indicatorId); int order=0; for(var v:values) exec("insert into llm_indicator_value_option(id,indicator_id,value_label,default_value,is_default,value_kind,inference_basis,sort_order) values(?,?,?,?,?,?,?,?)",id(),indicatorId,v.get("valueLabel"),bool(v,"isDefault",false)?v.get("valueLabel"):null,bool(v,"isDefault",false),def(v,"valueKind","DOMAIN_VALUE"),v.get("inferenceBasis"),num(v,"sortOrder",order++)); repository.invalidateCache(); }
    private void writeNode(long id,long tree,Map<String,Object> p,boolean update) {
        String cols="tree_id,node_key,title,node_type,indicator_code,indicator_stage,value_code,execution_mode,input_indicator_codes,condition_expression,logic_description,logic_definition_json,action_payload_json,output_payload_json,sort_order,position_x,position_y";
        Object[] values={tree,p.get("nodeKey"),p.get("title"),p.get("nodeType"),p.get("indicatorCode"),p.get("indicatorStage"),p.get("valueCode"),def(p,"executionMode","MODEL_EDGE_SELECTION"),p.get("inputIndicatorCodes"),p.get("conditionExpression"),p.get("logicDescription"),p.get("logicDefinitionJson"),p.get("actionPayloadJson"),p.get("outputPayloadJson"),num(p,"sortOrder"),num(p,"positionX",80),num(p,"positionY",80)};
        if(update) exec("update llm_decision_node set tree_id=?,node_key=?,title=?,node_type=?,indicator_code=?,indicator_stage=?,value_code=?,execution_mode=?,input_indicator_codes=?,condition_expression=?,logic_description=?,logic_definition_json=?,action_payload_json=?,output_payload_json=?,sort_order=?,position_x=?,position_y=? where id=?",append(values,id));
        else exec("insert into llm_decision_node(id,"+cols+") values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",prepend(id,values));
    }
    private int count(String table,String column,long id,String type) { if(type==null)return db.queryForObject("select count(*) from "+table+" where "+column+"=?",Integer.class,id); return db.queryForObject("select count(*) from "+table+" where "+column+"=? and type=?",Integer.class,id,type); }
    private Map<String,Object> one(String table,long id) { List<Map<String,Object>> r=db.query("select * from "+table+" where id=?",(rs,n)->row(rs),id); return r.isEmpty()?null:r.get(0); }
    private static Map<String,Object> row(java.sql.ResultSet rs) throws java.sql.SQLException { Map<String,Object> row=new LinkedHashMap<>(); var md=rs.getMetaData(); for(int i=1;i<=md.getColumnCount();i++) { Object v=rs.getObject(i); String key=md.getColumnLabel(i); row.put(key,v); if(key.contains("_")) row.put(camel(key),v); } return row; }
    private static String camel(String key) { StringBuilder b=new StringBuilder(); boolean up=false; for(char c:key.toCharArray()) if(c=='_') up=true; else { b.append(up?Character.toUpperCase(c):c); up=false; } return b.toString(); }
    private void exec(String sql,Object... args) { db.update(sql,args); }
    private static Map<String,Object> ok(Object data) { return Map.of("code",0,"message","ok","data",data==null?Map.of():data); }
    private static Object def(Map<String,Object> p,String key,Object value) { return p.getOrDefault(key,value); }
    private static int num(Map<String,Object> p,String key) { return num(p,key,0); }
    private static int num(Map<String,Object> p,String key,int d) { Object v=p.get(key); return v==null?d:((Number)v).intValue(); }
    private static long numLong(Map<String,Object> p,String key) { Object v=p.get(key); return v==null?0L:((Number)v).longValue(); }
    private static boolean bool(Map<String,Object> p,String key,boolean d) { Object v=p.get(key); return v==null?d:Boolean.parseBoolean(v.toString()); }
    private static long id() { return Math.abs(UUID.randomUUID().getMostSignificantBits()); }
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object p) { return p instanceof Map<?,?> m?(Map<String,Object>)m:Map.of(); }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> list(Object p) { return p instanceof List<?> l?(List<Map<String,Object>>)(List<?>)l:new ArrayList<>(); }
    private static Object[] prepend(Object first,Object[] rest) { Object[] all=new Object[rest.length+1]; all[0]=first; System.arraycopy(rest,0,all,1,rest.length); return all; }
    private static Object[] append(Object[] rest,Object last) { Object[] all=Arrays.copyOf(rest,rest.length+1); all[rest.length]=last; return all; }
    private static Map<String,String> stringMap(Object v) { Map<String,String> r=new LinkedHashMap<>(); map(v).forEach((k,value)->r.put(k,Objects.toString(value,""))); return r; }
    private static Map<String,List<String>> stringListMap(Object v) { Map<String,List<String>> r=new LinkedHashMap<>(); map(v).forEach((k,value)->r.put(k,value instanceof List<?> l?l.stream().map(String::valueOf).toList():List.of())); return r; }
}
