package com.quest.api.coach;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * AI 调用审计查询：列表只返回元数据，原文按需单条读取。
 * 仅 Admin 可访问；不提供更新/删除，审计记录运行期不可篡改。
 */
@Service
public class AiCallAuditQuery {
    public record Row(String id,String action,String outcome,String actor,String targetId,String traceId,
                      long elapsedMs,int attempts,String model,String error,OffsetDateTime createdAt) {}
    public record Detail(Row row,String requestJson,String responseText) {}
    public record Page(List<Row> list,int total,int page,int size) {}
    private final ObjectProvider<JdbcTemplate> jdbc;
    private final ObjectMapper json;
    private final String mode;
    public AiCallAuditQuery(ObjectProvider<JdbcTemplate> jdbc,ObjectMapper json,@Value("${quest.persistence.mode:disabled}") String mode){this.jdbc=jdbc;this.json=json;this.mode=mode;}
    private JdbcTemplate db(){if(!mode.equals("postgresql")||jdbc.getIfAvailable()==null)throw com.quest.api.auth.UserFailure.denied();return jdbc.getObject();}

    public Page list(String action,String outcome,int page,int size){
        int current=Math.max(1,page),limit=Math.min(100,Math.max(1,size));
        // outcome 存在 JSONB 里；不在 SQL 用 ->>，避免依赖数据库方言（测试库 H2 不支持）。
        StringBuilder where=new StringBuilder(" WHERE a.target_type='ai_call'");
        List<Object> args=new ArrayList<>();
        if(action!=null&&!action.isBlank()){where.append(" AND a.action=?");args.add(action.strip());}
        String wanted=outcome==null?"":outcome.strip();
        List<Row> matched=new ArrayList<>();
        for(Row row:db().query("SELECT a.id,a.action,a.target_id,a.trace_id,a.after_data,a.created_at,u.username FROM audit_log a LEFT JOIN app_user u ON u.id=a.actor"
            +where+" ORDER BY a.created_at DESC,a.id DESC",(rs,n)->{
            JsonNode meta=parse(rs.getString("after_data"));
            return new Row(rs.getString("id"),rs.getString("action"),meta.path("outcome").asText(""),
                rs.getString("username"),rs.getString("target_id"),rs.getString("trace_id"),
                meta.path("elapsedMs").asLong(0),meta.path("attempts").asInt(0),
                meta.path("model").asText(""),meta.path("error").asText(""),
                rs.getObject("created_at",OffsetDateTime.class));
        },args.toArray()))if(wanted.isEmpty()||wanted.equals(row.outcome()))matched.add(row);
        int from=Math.min(matched.size(),(current-1)*limit),to=Math.min(matched.size(),from+limit);
        return new Page(new ArrayList<>(matched.subList(from,to)),matched.size(),current,limit);
    }
    /** 原文单独读取，列表页不携带，避免一次拉回大量对话内容。 */
    public Detail detail(String id){
        long auditId;
        try{auditId=Long.parseLong(id);}catch(RuntimeException failure){throw new com.quest.api.auth.UserFailure(404,40400,"记录不存在");}
        var rows=db().query("SELECT a.id,a.action,a.target_id,a.trace_id,a.after_data,a.created_at,u.username FROM audit_log a LEFT JOIN app_user u ON u.id=a.actor WHERE a.id=? AND a.target_type='ai_call'",
            (rs,n)->new Object[]{rs.getString("id"),rs.getString("action"),rs.getString("target_id"),rs.getString("trace_id"),
                rs.getString("after_data"),rs.getObject("created_at",OffsetDateTime.class),rs.getString("username")},auditId);
        if(rows.isEmpty())throw new com.quest.api.auth.UserFailure(404,40400,"记录不存在");
        Object[] r=rows.getFirst();
        JsonNode meta=parse((String)r[4]);
        Row row=new Row((String)r[0],(String)r[1],meta.path("outcome").asText(""),(String)r[6],(String)r[2],(String)r[3],
            meta.path("elapsedMs").asLong(0),meta.path("attempts").asInt(0),meta.path("model").asText(""),meta.path("error").asText(""),
            (OffsetDateTime)r[5]);
        var exchange=db().query("SELECT request_json,response_text FROM ai_call_exchange WHERE audit_id=? ORDER BY created_at DESC LIMIT 1",
            (rs,n)->new String[]{rs.getString("request_json"),rs.getString("response_text")},auditId);
        return new Detail(row,exchange.isEmpty()?"":exchange.getFirst()[0],exchange.isEmpty()?null:exchange.getFirst()[1]);
    }
    private JsonNode parse(String raw){return AiCallAudit.parseJson(json,raw);}
}
