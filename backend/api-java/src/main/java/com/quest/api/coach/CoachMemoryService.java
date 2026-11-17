package com.quest.api.coach;

import com.quest.api.auth.UserFailure;
import com.quest.api.auth.UserModels.SessionIdentity;
import com.quest.api.auth.UserService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

/** 记忆只承载历史资料；不能替代题目快照、判分、功能授权或工具参数。 */
@Service
public class CoachMemoryService {
    private final ObjectProvider<JdbcTemplate> jdbc;
    private final ObjectProvider<PlatformTransactionManager> managers;
    private final UserService users;
    public CoachMemoryService(ObjectProvider<JdbcTemplate> jdbc,ObjectProvider<PlatformTransactionManager> managers,UserService users){this.jdbc=jdbc;this.managers=managers;this.users=users;}
    private JdbcTemplate db(){var db=jdbc.getIfAvailable();if(db==null)throw UserFailure.unavailable();return db;}
    private <T>T tx(SessionIdentity who,Supplier<T> action){return new TransactionTemplate(managers.getObject()).execute(s->{var live=users.session(who.user().id(),who.sessionId());if(!live.user().role().equals("student")||live.user().mustChangePassword())throw UserFailure.denied();return action.get();});}
    public record TopicInput(UUID requestId,String subject,String title,String goal,String progress,String questions,String plan,Long revision){}
    private String text(String value,int max,boolean required){String result=value==null?"":value.strip();if(result.length()>max||required&&result.isBlank())throw new IllegalArgumentException();return result;}
    private Map<String,Object> wire(Map<String,Object> row){Map<String,Object> result=new LinkedHashMap<>();row.forEach((key,value)->result.put(key.toLowerCase(Locale.ROOT),value instanceof java.sql.Timestamp stamp?stamp.toInstant().toString():value instanceof java.time.OffsetDateTime time?time.toInstant().toString():value instanceof UUID?value.toString():value));return result;}
    public Map<String,Object> list(SessionIdentity who){return tx(who,()->Map.of("list",db().queryForList("SELECT id,subject,title,goal,progress,questions,plan,revision,source_session_id,updated_at FROM student_learning_topic WHERE user_id=? AND deleted_at IS NULL ORDER BY updated_at DESC,id LIMIT 100",Long.parseLong(who.user().id())).stream().map(this::wire).toList()));}
    private Map<String,Object> topic(long user,UUID id){var rows=db().queryForList("SELECT id,subject,title,goal,progress,questions,plan,revision,source_session_id,updated_at FROM student_learning_topic WHERE id=? AND user_id=? AND deleted_at IS NULL",id,user);if(rows.size()!=1)throw new UserFailure(404,40435,"学习主题不存在");return wire(rows.getFirst());}
    public Map<String,Object> save(SessionIdentity who,UUID id,TopicInput body,boolean create){
        if(body==null||!Set.of("vocational_aptitude","administrative_aptitude").contains(body.subject()==null?"":body.subject()))throw new IllegalArgumentException();
        String title=text(body.title(),100,true),goal=text(body.goal(),1500,false),progress=text(body.progress(),1500,false),questions=text(body.questions(),1500,false),plan=text(body.plan(),1500,false);
        return tx(who,()->{long user=Long.parseLong(who.user().id());
            db().queryForList("SELECT id FROM app_user WHERE id=? FOR UPDATE",user);
            if(create){
                var existing=db().queryForList("SELECT user_id,title,subject,goal FROM student_learning_topic WHERE id=?",id);
                if(!existing.isEmpty()){var old=existing.getFirst();if(!old.get("user_id").toString().equals(who.user().id()))throw new UserFailure(404,40435,"学习主题不存在");if(!old.get("title").equals(title)||!old.get("subject").equals(body.subject())||!old.get("goal").equals(goal))throw new UserFailure(409,40935,"学习主题已变化，请刷新");return topic(user,id);}
                db().update("INSERT INTO student_learning_topic(id,user_id,subject,title,goal,progress,questions,plan) VALUES (?,?,?,?,?,?,?,?)",id,user,body.subject(),title,goal,progress,questions,plan);
            }else{
                if(body.revision()==null||body.revision()<0)throw new IllegalArgumentException();
                var old=topic(user,id);if(!old.get("subject").equals(body.subject()))throw new IllegalArgumentException();
                if(db().update("UPDATE student_learning_topic SET title=?,goal=?,progress=?,questions=?,plan=?,revision=revision+1,source_session_id=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=? AND user_id=? AND revision=? AND deleted_at IS NULL",title,goal,progress,questions,plan,id,user,body.revision())!=1)throw new UserFailure(409,40935,"学习主题已变化，请刷新");
            }
            return topic(user,id);
        });
    }
    public void delete(SessionIdentity who,UUID id){tx(who,()->{if(db().update("UPDATE student_learning_topic SET deleted_at=CURRENT_TIMESTAMP,revision=revision+1 WHERE id=? AND user_id=? AND deleted_at IS NULL",id,Long.parseLong(who.user().id()))!=1)throw new UserFailure(404,40435,"学习主题不存在");return null;});}
    String subject(long user,UUID topicId,String fallback){return topicId==null?fallback:topic(user,topicId).get("subject").toString();}
    Map<String,Object> linked(long user,ObjectNode state){
        if(!state.hasNonNull("topicId"))return null;
        try{return topic(user,UUID.fromString(state.path("topicId").asText()));}catch(UserFailure missing){if(missing.code()==40435)return null;throw missing;}
    }
    void bind(long user,String subject,ObjectNode state,UUID id){
        if(id==null){state.remove("topicId");return;}
        var value=topic(user,id);if(!value.get("subject").equals(subject))throw new IllegalArgumentException();state.put("topicId",id.toString());
    }
    Map<String,Object> context(long user,ObjectNode state){
        var value=linked(user,state);if(value==null)return null;
        Map<String,Object> result=new LinkedHashMap<>();for(String key:List.of("title","goal","progress","questions","plan","revision"))result.put(key,value.get(key));return result;
    }
    void stage(ObjectNode state,JsonNode update){
        if(update==null||!update.isObject()||!update.path("summary").isString()||update.path("summary").asText().isBlank()||update.path("summary").asText().length()>4000)return;
        for(String key:List.of("progress","questions","plan"))if(!update.path(key).isString()||update.path(key).asText().length()>1500)return;
        state.set("_pendingMemory",update.deepCopy());
    }
    // 在最终会话事务中调用：断流、取消、失败和幂等回放均不得覆盖记忆。
    void commit(long user,UUID session,ObjectNode state){
        JsonNode update=state.remove("_pendingMemory");if(update==null)return;
        ObjectNode memory=state.objectNode();memory.put("summary",update.path("summary").asText());memory.put("updatedAt",Instant.now().toString());
        var messages=state.path("messages");if(!messages.isEmpty())memory.put("throughMessageId",messages.get(messages.size()-1).path("id").asText());state.set("memory",memory);
        var topic=linked(user,state);if(topic!=null&&update.path("topic_revision").isIntegralNumber()){
            // 版本冲突保留本会话摘要，不覆盖其他会话或用户手动修改的主题档案。
            db().update("UPDATE student_learning_topic SET progress=?,questions=?,plan=?,source_session_id=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND user_id=? AND revision=? AND deleted_at IS NULL",update.path("progress").asText(),update.path("questions").asText(),update.path("plan").asText(),session,topic.get("id"),user,update.path("topic_revision").asLong());
        }
    }
}
