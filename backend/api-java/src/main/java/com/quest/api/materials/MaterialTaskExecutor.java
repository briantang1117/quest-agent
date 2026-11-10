package com.quest.api.materials;

import com.quest.api.auth.UserFailure;
import com.quest.api.publicbank.PublicAiClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.Semaphore;

/** 只由 MQ 消费驱动领取；数据库行锁和 generation 是重复消息的幂等边界。 */
@Service
public class MaterialTaskExecutor {
    public record Event(UUID taskId,UUID generation,UUID eventId){}
    private final LearningMaterialService materials;private final ObjectMapper json;private final PublicAiClient ai;
    private final Semaphore capacity=new Semaphore(2);
    public MaterialTaskExecutor(LearningMaterialService materials,ObjectMapper json,PublicAiClient ai){this.materials=materials;this.json=json;this.ai=ai;}
    public Map<String,String> execute(Event event,String trace){
        materials.ensureEnabled();
        if(event==null||event.taskId()==null||event.generation()==null||event.eventId()==null)throw new IllegalArgumentException();
        if(!capacity.tryAcquire())throw new UserFailure(503,50342,"资料生成容量已满，请稍后投递");
        UUID lease=UUID.randomUUID();
        try{
            var claim=materials.transaction(()->{
                var rows=materials.db().queryForList("SELECT * FROM learning_material WHERE id=? FOR UPDATE",event.taskId());
                if(rows.isEmpty())return Map.<String,Object>of("skip","ignored");var row=rows.getFirst();
                if(!event.generation().equals(row.get("generation"))||!event.eventId().equals(row.get("event_id")))return Map.<String,Object>of("skip","ignored");
                String status=(String)row.get("status");if(!status.equals("queued"))return Map.<String,Object>of("skip",status);
                materials.db().update("UPDATE learning_material SET status='processing',lease_token=?,lease_until=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",lease,OffsetDateTime.now().plusMinutes(15),event.taskId());return row;
            });
            if(claim.containsKey("skip"))return Map.of("status",(String)claim.get("skip"));
            try{
                var payload=new LinkedHashMap<String,Object>();payload.put("scope","student");payload.put("subject",claim.get("subject"));payload.put("source_title",claim.get("source_title"));payload.put("instructions",claim.get("instructions"));payload.put("messages",json.readTree((String)claim.get("snapshot_json")));
                var result=ai.generateMaterial(payload,new PublicAiClient.Caller(((Number)claim.get("user_id")).longValue(),trace));
                String title=result.path("title").asText(),markdown=result.path("markdown").asText();if(title.isBlank()||title.length()>300||markdown.isBlank()||markdown.length()>100000)throw new UserFailure(502,50240,"资料模型返回内容不完整或超限");
                int changed=materials.transaction(()->materials.db().update("UPDATE learning_material SET status='ready',title=?,markdown=?,lease_token=NULL,lease_until=NULL,error=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=? AND generation=? AND lease_token=? AND status='processing' AND lease_until>CURRENT_TIMESTAMP",title,markdown,event.taskId(),event.generation(),lease));
                return Map.of("status",changed==1?"ready":"ignored");
            }catch(RuntimeException failure){
                String reason=failure instanceof UserFailure uf&&uf.code()==50401?"模型整理超时，请手动重试":"资料整理未完成，请检查模型服务后手动重试";
                materials.transaction(()->materials.db().update("UPDATE learning_material SET status='failed',error=?,lease_token=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=? AND generation=? AND lease_token=? AND status='processing'",reason,event.taskId(),event.generation(),lease));
                return Map.of("status","failed");
            }
        }finally{capacity.release();}
    }
    public Map<String,Object> claimOutbox(){materials.ensureEnabled();return materials.transaction(()->{
        // 只维护失效任务，不从 PG 领取或自动重做生成；死信由人工检查。
        materials.db().update("UPDATE learning_material SET status='failed',error='任务长时间未被消费，请检查MQ后手动重试',updated_at=CURRENT_TIMESTAMP WHERE status='queued' AND updated_at<?",OffsetDateTime.now().minusMinutes(30));
        materials.db().update("UPDATE learning_material SET status='failed',error='生成已超时或中断，请手动重试',lease_token=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP WHERE status='processing' AND lease_until<CURRENT_TIMESTAMP");
        var rows=materials.db().queryForList("SELECT * FROM mq_outbox WHERE topic='quest-learning-materials' AND status=0 AND (next_retry_at IS NULL OR next_retry_at<=CURRENT_TIMESTAMP) AND (lease_until IS NULL OR lease_until<CURRENT_TIMESTAMP) ORDER BY id LIMIT 8 FOR UPDATE SKIP LOCKED");
        var list=new ArrayList<Map<String,Object>>();for(var row:rows){UUID token=UUID.randomUUID();materials.db().update("UPDATE mq_outbox SET lease_token=?,lease_until=? WHERE id=?",token,OffsetDateTime.now().plusMinutes(2),row.get("id"));
            Object raw=row.get("payload");String payload=raw instanceof byte[] bytes?new String(bytes,java.nio.charset.StandardCharsets.UTF_8):raw.toString();var parsed=json.readTree(payload);if(parsed.isString())parsed=json.readTree(parsed.asText());
            list.add(Map.of("eventId",row.get("event_id").toString(),"leaseToken",token.toString(),"taskId",parsed.path("taskId").asText(),"generation",parsed.path("generation").asText(),"topic",row.get("topic"),"tag",row.get("tag"),"msgKey",row.get("msg_key"),"payload",json.writeValueAsString(parsed)));
        }return Map.of("list",list);
    });}
    public record Ack(UUID eventId,UUID leaseToken,boolean success){}
    public void ack(Ack ack){if(ack.eventId()==null||ack.leaseToken()==null)throw new IllegalArgumentException();materials.transaction(()->{
        materials.db().update("UPDATE mq_outbox SET status=?,retry_count=retry_count+?,next_retry_at=?,lease_token=NULL,lease_until=NULL WHERE event_id=? AND topic='quest-learning-materials' AND lease_token=?",ack.success()?1:0,ack.success()?0:1,ack.success()?null:OffsetDateTime.now().plusSeconds(30),ack.eventId(),ack.leaseToken());return null;
    });}
}
