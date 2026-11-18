package com.quest.api.materials;

import com.quest.api.auth.UserFailure;
import com.quest.api.auth.UserModels.SessionIdentity;
import com.quest.api.auth.UserService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Supplier;

/** 私人资料事实源；创建任务不调用模型，执行由 RocketMQ 消费触发。 */
@Service
public class LearningMaterialService {
    public record Create(UUID requestId,String instructions){}
    private final ObjectProvider<JdbcTemplate> jdbc;
    private final ObjectProvider<PlatformTransactionManager> managers;
    private final UserService users;
    private final ObjectMapper json;
    @org.springframework.beans.factory.annotation.Autowired private com.quest.api.admin.AdminControlService controls;
    private final boolean enabled;
    private final int maxChars,maxBytes;
    public LearningMaterialService(ObjectProvider<JdbcTemplate> jdbc,ObjectProvider<PlatformTransactionManager> managers,UserService users,ObjectMapper json,
        @Value("${quest.materials.enabled:false}") boolean enabled,@Value("${quest.materials.max-input-chars:1000000}")int maxChars,@Value("${quest.materials.max-snapshot-bytes:8388608}")int maxBytes){this.jdbc=jdbc;this.managers=managers;this.users=users;this.json=json;this.enabled=enabled;this.maxChars=maxChars;this.maxBytes=maxBytes;}
    public boolean generationEnabled(){return enabled;}
    void ensureEnabled(){if(!enabled)throw new UserFailure(503,50341,"学习资料生成尚未启用");}
    JdbcTemplate db(){var value=jdbc.getIfAvailable();if(value==null)throw new UserFailure(503,50340,"学习资料存储暂不可用");return value;}
    <T>T transaction(Supplier<T> work){var manager=managers.getIfAvailable();if(manager==null)throw new UserFailure(503,50340,"学习资料存储暂不可用");return new TransactionTemplate(manager).execute(s->work.get());}
    private long owner(SessionIdentity identity){var current=users.session(identity.user().id(),identity.sessionId());if(!current.user().role().equals("student")||current.user().mustChangePassword())throw UserFailure.denied();return Long.parseLong(current.user().id());}
    public Map<String,Object> create(SessionIdentity identity,String session,Create input){return create(identity,session,input,null);}
    public Map<String,Object> create(SessionIdentity identity,String session,Create input,String excludedMessage){
        if(input==null||input.requestId()==null||input.instructions()!=null&&input.instructions().length()>2000)throw new IllegalArgumentException();
        if(!enabled)throw new UserFailure(503,50341,"学习资料生成尚未启用");
        UUID source=UUID.fromString(session);String instructions=input.instructions()==null?"":input.instructions().strip();
        return transaction(()->{
            long user=owner(identity);controls.requireFeature(user,"materials");
            String hash=hash(source+"\n"+instructions);
            var replay=replay(user,input.requestId(),hash);if(replay!=null)return replay;
            // 与重试保持来源→资料的锁顺序；等待来源锁后必须再次检查幂等键。
            var rows=db().queryForList("SELECT * FROM student_coach_session WHERE id=? AND user_id=? FOR UPDATE",source,user);if(rows.isEmpty())throw missing();
            replay=replay(user,input.requestId(),hash);if(replay!=null)return replay;
            if(rows.getFirst().get("deleted_at")!=null)throw missing();
            if(excludedMessage==null&&rows.getFirst().get("processing_request")!=null)throw new UserFailure(409,40932,"上一条消息仍在处理中，请稍后创建资料");
            var active=db().queryForList("SELECT id FROM learning_material WHERE session_id=? AND user_id=? AND status IN ('queued','processing') ORDER BY created_at",source,user);
            UUID id;
            if(!active.isEmpty())id=(UUID)active.getFirst().get("id");
            else{
                var sourceRow=rows.getFirst();JsonNode state=json.readTree((String)sourceRow.get("state_json"));
                var messages=MaterialSnapshot.collect(db(),json,source,state,excludedMessage);
                String snapshot=json.writeValueAsString(messages);
                int chars=0;for(var message:messages)chars+=json.writeValueAsString(message).length();
                if(chars>maxChars||snapshot.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>maxBytes)throw new UserFailure(413,41340,"对话超出整理预算，请缩小整理范围；本次未截断或调用模型");
                id=UUID.randomUUID();UUID generation=UUID.randomUUID(),event=UUID.randomUUID();String title=(String)sourceRow.get("title");
                db().update("INSERT INTO learning_material(id,user_id,session_id,source_title,subject,title,status,instructions,snapshot_json,generation,event_id) VALUES (?,?,?,?,?,?,'queued',?,?,?,?)",id,user,source,title,sourceRow.get("subject"),title+" · 学习资料",instructions,snapshot,generation,event);
                enqueue(id,generation,event);
            }
            if(excludedMessage==null){
                var state=(tools.jackson.databind.node.ObjectNode)json.readTree((String)rows.getFirst().get("state_json"));
                var messages=state.withArray("messages");boolean linked=false;
                for(var message:messages)if(message.path("materialId").asText().equals(id.toString())){linked=true;break;}
                if(!linked){
                    messages.addObject().put("id",UUID.randomUUID().toString()).put("role","assistant")
                        .put("text","已创建学习资料整理任务，完成后可在文件卡片或学习资料中下载。")
                        .put("materialNotice",true).put("materialId",id.toString());
                    db().update("UPDATE student_coach_session SET state_json=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND user_id=?",json.writeValueAsString(state),source,user);
                }
            }
            db().update("INSERT INTO learning_material_request(user_id,request_id,request_hash,material_id) VALUES (?,?,?,?)",user,input.requestId(),hash,id);
            return getOwned(user,id,false);
        });
    }
    private Map<String,Object> replay(long user,UUID request,String hash){
        var prior=db().queryForList("SELECT request_hash,material_id FROM learning_material_request WHERE user_id=? AND request_id=?",user,request);
        if(prior.isEmpty())return null;
        if(!hash.equals(prior.getFirst().get("request_hash")))throw conflict();
        return getOwned(user,(UUID)prior.getFirst().get("material_id"),false);
    }
    void enqueue(UUID id,UUID generation,UUID event){
        String payload=json.writeValueAsString(Map.of("taskId",id,"generation",generation,"eventId",event));
        db().update("INSERT INTO mq_outbox(topic,tag,msg_key,payload,event_id) VALUES ('quest-learning-materials','generate',?,CAST(? AS JSONB),?)",id.toString(),payload,event);
    }
    public Map<String,Object> list(SessionIdentity identity){return transaction(()->Map.of("list",listOwned(owner(identity),null)));}
    public List<Map<String,Object>> sessionMaterials(long user,UUID session){
        if(!enabled)return List.of();
        return listOwned(user,session);
    }
    private List<Map<String,Object>> listOwned(long user,UUID session){
        String sql="SELECT m.*,s.deleted_at AS source_deleted FROM learning_material m JOIN student_coach_session s ON s.id=m.session_id AND s.user_id=m.user_id WHERE m.user_id=?"+(session==null?"":" AND m.session_id=?")+" ORDER BY m.created_at DESC,m.id LIMIT 200";
        return session==null?db().query(sql,(rs,n)->view(rs,false),user):db().query(sql,(rs,n)->view(rs,false),user,session);
    }
    public Map<String,Object> get(SessionIdentity identity,String id){return transaction(()->getOwned(owner(identity),UUID.fromString(id),true));}
    Map<String,Object> getOwned(long user,UUID id,boolean body){var result=db().query("SELECT m.*,s.deleted_at AS source_deleted FROM learning_material m JOIN student_coach_session s ON s.id=m.session_id AND s.user_id=m.user_id WHERE m.id=? AND m.user_id=?",(rs,n)->view(rs,body),id,user);if(result.isEmpty())throw missing();return result.getFirst();}
    private Map<String,Object> view(ResultSet rs,boolean body)throws java.sql.SQLException{
        var out=new LinkedHashMap<String,Object>();out.put("id",rs.getString("id"));out.put("sessionId",rs.getString("session_id"));out.put("sourceTitle",rs.getString("source_title"));out.put("sourceDeleted",rs.getObject("source_deleted")!=null);out.put("subject",rs.getString("subject"));out.put("title",rs.getString("title"));out.put("status",rs.getString("status"));out.put("format",rs.getString("format"));out.put("createdAt",rs.getObject("created_at",OffsetDateTime.class).toString());out.put("updatedAt",rs.getObject("updated_at",OffsetDateTime.class).toString());out.put("error",rs.getString("error"));var snapshot=json.readTree(rs.getString("snapshot_json"));if(snapshot.isArray()&&!snapshot.isEmpty()){String anchor=snapshot.get(snapshot.size()-1).path("id").asText();if(!anchor.isBlank())out.put("sourceMessageId",anchor);}if(body&&"ready".equals(rs.getString("status")))out.put("markdown",rs.getString("markdown"));return out;
    }
    public Map<String,Object> retry(SessionIdentity identity,String material,UUID request){
        if(request==null)throw new IllegalArgumentException();if(!enabled)throw new UserFailure(503,50341,"学习资料生成尚未启用");UUID id=UUID.fromString(material);
        return transaction(()->{long user=owner(identity);controls.requireFeature(user,"materials");String hash=hash("retry\n"+id);
            var replay=replay(user,request,hash);if(replay!=null)return replay;
            var rows=db().queryForList("SELECT session_id FROM learning_material WHERE id=? AND user_id=?",id,user);if(rows.isEmpty())throw missing();UUID source=(UUID)rows.getFirst().get("session_id");
            db().queryForList("SELECT id FROM student_coach_session WHERE id=? AND user_id=? FOR UPDATE",source,user);
            db().queryForList("SELECT id FROM learning_material WHERE id=? AND user_id=? FOR UPDATE",id,user);
            replay=replay(user,request,hash);if(replay!=null)return replay;
            var value=getOwned(user,id,false);if(!"failed".equals(value.get("status")))throw conflict();
            if(db().queryForObject("SELECT COUNT(*) FROM learning_material WHERE session_id=? AND status IN ('queued','processing')",Integer.class,source)>0)throw conflict();
            UUID generation=UUID.randomUUID(),event=UUID.randomUUID();db().update("UPDATE learning_material SET status='queued',generation=?,event_id=?,error=NULL,lease_token=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=? AND user_id=?",generation,event,id,user);enqueue(id,generation,event);
            db().update("INSERT INTO learning_material_request(user_id,request_id,request_hash,material_id) VALUES (?,?,?,?)",user,request,hash,id);return getOwned(user,id,false);
        });
    }
    static String hash(String input){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException();}}
    static UserFailure missing(){return new UserFailure(404,40440,"学习资料或来源对话不存在");}
    static UserFailure conflict(){return new UserFailure(409,40940,"资料任务状态已变化，请刷新后操作");}
}
