package com.quest.api.coach;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.UUID;

/**
 * AI 调用审计：元数据进 audit_log 供列表查询，原文进 ai_call_exchange 默认折叠。
 * 审计失败不得影响主流程，因此整体吞异常并只留本地日志。
 */
@Service
public class AiCallAudit {
    private final ObjectProvider<JdbcTemplate> jdbc;
    private final ObjectMapper json;
    private final String mode;
    public AiCallAudit(ObjectProvider<JdbcTemplate> jdbc,ObjectMapper json,@Value("${quest.persistence.mode:disabled}") String mode){this.jdbc=jdbc;this.json=json;this.mode=mode;}
    private boolean enabled(){return mode.equals("postgresql")&&jdbc.getIfAvailable()!=null;}
    /** JSONB 在 PG 读作文本；H2 兼容模式会把整个 JSON 再包成字符串，需要剥一层。 */
    static JsonNode parseJson(ObjectMapper json,String raw){
        if(raw==null||raw.isBlank())return json.createObjectNode();
        try{
            JsonNode node=json.readTree(raw);
            // H2 返回 "\"{...}\""：外层是字符串节点，内层才是真正的 JSON 对象。
            if(node!=null&&node.isString()){
                String inner=node.asText();
                if(inner.startsWith("{")){
                    try{return json.readTree(inner);}catch(RuntimeException ignored){return node;}
                }
            }
            return node==null?json.createObjectNode():node;
        }catch(RuntimeException failure){return json.createObjectNode();}
    }

    /** 记录一次模型调用：成功或失败都要留痕，失败时原文用于复现校验问题。 */
    public void record(Long actor,String action,String targetId,String traceId,Object request,JsonNode response,Throwable failure,long elapsedMs,int attempts,String model){
        if(!enabled())return;
        try{
            JdbcTemplate db=jdbc.getObject();
            String outcome=failure==null?"ok":"failed";
            String detail=failure==null?"":("类型="+failure.getClass().getSimpleName()+"; 耗时="+elapsedMs+"ms; 尝试="+attempts+"次");
            String after=json.writeValueAsString(java.util.Map.of("outcome",outcome,"elapsedMs",elapsedMs,"attempts",attempts,"model",model==null?"":model,"error",detail));
            // 不使用 RETURNING/to_jsonb，保持与测试库 H2 的 PostgreSQL 兼容模式一致。
            db.update("INSERT INTO audit_log(actor,action,target_type,target_id,after_data,trace_id) VALUES (?,?,?,?,CAST(? AS JSONB),?)",
                actor,action,"ai_call",targetId,after,traceId==null?"":traceId);
            Long auditId=db.queryForObject(
                "SELECT id FROM audit_log WHERE trace_id=? AND action=? AND target_type='ai_call' ORDER BY id DESC LIMIT 1",
                Long.class,traceId==null?"":traceId,action);
            if(auditId==null)return;
            String requestJson;
            try{requestJson=json.writeValueAsString(request);}catch(Exception ignored){requestJson="{}";}
            String responseText=response==null?null:(response.toString().length()>100000?response.toString().substring(0,100000):response.toString());
            db.update("INSERT INTO ai_call_exchange(id,audit_id,request_json,response_text,model) VALUES (?,?,CAST(? AS JSONB),?,?)",
                UUID.randomUUID(),auditId,requestJson,responseText,model);
        }catch(RuntimeException failureLogged){
            org.slf4j.LoggerFactory.getLogger(AiCallAudit.class)
                .warn("AI audit write failed: type={}",failureLogged.getClass().getName());
        }
    }
}
