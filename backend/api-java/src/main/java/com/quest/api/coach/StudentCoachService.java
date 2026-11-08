package com.quest.api.coach;

import com.quest.api.auth.UserFailure;
import com.quest.api.auth.UserModels.SessionIdentity;
import com.quest.api.auth.UserService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.ArrayNode;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Supplier;

/** 学员身份、题目快照和判分的唯一事实源；模型只能建议意图和讲解。 */
@Service
public class StudentCoachService {
    public record Preference(String subject) {}
    public record Create(UUID requestId,String mode) {}
    public record Turn(UUID requestId,String action,String text,String questionId,List<String> keys) {}
    record Session(UUID id,long userId,String subject,String title,ObjectNode state,UUID processing) {}
    record Pending(Session session,Turn request,JsonNode replay) {}
    private final ObjectProvider<JdbcTemplate> jdbc;
    private final ObjectProvider<PlatformTransactionManager> managers;
    private final UserService users;
    private final ObjectMapper json;
    private final CoachAiClient ai;
    private final String mode;
    public StudentCoachService(ObjectProvider<JdbcTemplate> jdbc,ObjectProvider<PlatformTransactionManager> managers,UserService users,ObjectMapper json,CoachAiClient ai,@Value("${quest.persistence.mode:disabled}") String mode){this.jdbc=jdbc;this.managers=managers;this.users=users;this.json=json;this.ai=ai;this.mode=mode;}
    private JdbcTemplate db(){if(!mode.equals("postgresql")||jdbc.getIfAvailable()==null)throw unavailable();return jdbc.getObject();}
    private <T> T tx(SessionIdentity who,Supplier<T> work){
        db();var manager=managers.getIfAvailable();if(manager==null)throw unavailable();
        try{return new TransactionTemplate(manager).execute(status->{
            var current=users.session(who.user().id(),who.sessionId());
            if(!current.user().role().equals("student")||current.user().mustChangePassword())throw UserFailure.denied();
            return work.get();
        });}catch(DataAccessException failure){throw unavailable();}
    }
    public Preference preference(SessionIdentity who){return tx(who,()->new Preference(subject(Long.parseLong(who.user().id()))));}
    public Preference preference(SessionIdentity who,String subject){checkSubject(subject);return tx(who,()->{
        long user=Long.parseLong(who.user().id());
        // 与同用户偏好更新串行，避免首次设置并发插入。
        db().queryForObject("SELECT id FROM app_user WHERE id=? FOR UPDATE",Long.class,user);
        if(db().update("UPDATE student_study_preference SET subject=?,updated_at=CURRENT_TIMESTAMP WHERE user_id=?",subject,user)==0)db().update("INSERT INTO student_study_preference(user_id,subject) VALUES (?,?)",user,subject);
        return new Preference(subject);
    });}
    private String subject(long user){var values=db().query("SELECT subject FROM student_study_preference WHERE user_id=?",(rs,n)->rs.getString(1),user);return values.isEmpty()?null:values.getFirst();}
    public JsonNode create(SessionIdentity who,Create input){
        if(input==null||input.requestId()==null||!Set.of("practice","chat").contains(input.mode()))throw new IllegalArgumentException();
        return tx(who,()->{
            long user=Long.parseLong(who.user().id());db().queryForObject("SELECT id FROM app_user WHERE id=? FOR UPDATE",Long.class,user);
            var existing=find(input.requestId(),user,false);if(existing!=null)return view(existing);
            // ID全局唯一，但不可用它穿透他人的会话。
            if(db().queryForObject("SELECT COUNT(*) FROM student_coach_session WHERE id=?",Integer.class,input.requestId())!=0)throw missing();
            String subject=subject(user);if(subject==null)throw new UserFailure(409,40930,"请先在个人中心选择学习科目");
            ObjectNode state=json.createObjectNode();state.putArray("messages");state.putArray("seen");
            Session session=new Session(input.requestId(),user,subject,input.mode().equals("practice")?"互动刷题":"个人对话",state,null);
            if(input.mode().equals("practice")){message(state,"user","按我当前选择的科目开始刷题，一次一道，回答后再讲解。");nextQuestion(session);}
            else message(state,"assistant","你好，我是你的个人学习Agent。可以问我知识点，也可以说‘开始刷题’，我会从当前科目的已发布题目中选一道。 ");
            db().update("INSERT INTO student_coach_session(id,user_id,subject,title,state_json) VALUES (?,?,?,?,?)",session.id(),user,subject,session.title(),json.writeValueAsString(state));
            return view(session);
        });
    }
    public Map<String,Object> list(SessionIdentity who){return tx(who,()->Map.of("list",db().query("SELECT id,subject,title,updated_at FROM student_coach_session WHERE user_id=? ORDER BY updated_at DESC,id LIMIT 50",(rs,n)->Map.of("id",rs.getString("id"),"subject",rs.getString("subject"),"title",rs.getString("title"),"updatedAt",rs.getObject("updated_at",OffsetDateTime.class).toString()),Long.parseLong(who.user().id()))));}
    public JsonNode get(SessionIdentity who,String id){UUID key=uuid(id);return tx(who,()->view(require(key,Long.parseLong(who.user().id()),false)));}
    private Session find(UUID id,long user,boolean lock){var rows=db().query("SELECT * FROM student_coach_session WHERE id=? AND user_id=?"+(lock?" FOR UPDATE":""),(rs,n)->new Session(id,user,rs.getString("subject"),rs.getString("title"),(ObjectNode)json.readTree(rs.getString("state_json")),rs.getObject("processing_request",UUID.class)),id,user);return rows.isEmpty()?null:rows.getFirst();}
    private Session require(UUID id,long user,boolean lock){var s=find(id,user,lock);if(s==null)throw missing();return s;}
    public JsonNode turn(SessionIdentity who,String id,Turn input){return turn(who,id,input,null);}
    public JsonNode turn(SessionIdentity who,String id,Turn input,String traceId){
        UUID key=uuid(id);validateTurn(input);
        Pending pending;
        try{
            pending=tx(who,()->{
            Session s=require(key,Long.parseLong(who.user().id()),true);
            var prior=db().queryForList("SELECT request_hash,status,response_json FROM student_coach_turn WHERE session_id=? AND request_id=?",key,input.requestId());
            if(!prior.isEmpty()){
                var row=prior.getFirst();if(!Objects.equals(row.get("request_hash"),requestFingerprint(input)))throw conflict();
                if("done".equals(row.get("status")))return new Pending(s,input,json.readTree((String)row.get("response_json")));
                if("processing".equals(row.get("status")))throw conflict();
                throw new UserFailure(503,50331,"本次消息处理失败，未自动重试");
            }
            if(s.processing()!=null)throw conflict();
            db().update("INSERT INTO student_coach_turn(id,session_id,request_id,request_hash,status) VALUES (?,?,?,?,'processing')",UUID.randomUUID(),key,input.requestId(),requestFingerprint(input));
            db().update("UPDATE student_coach_session SET processing_request=? WHERE id=? AND user_id=?",input.requestId(),key,s.userId());
            return new Pending(s,input,null);
            });
        }catch(RuntimeException failure){
            if(failure instanceof UserFailure userFailure)throw userFailure;
            org.slf4j.LoggerFactory.getLogger(StudentCoachService.class)
                .warn("Coach turn claim failed: type={}", failure.getClass().getName(), failure);
            throw new UserFailure(503,50330,"个人Agent暂不可用，请确认数据库与模型服务");
        }
        if(pending.replay()!=null)return pending.replay();
        try {
            Session s=pending.session();ObjectNode state=s.state();
            String text=input.text()==null?"":input.text().strip();
            String action=input.action();List<String> keys=input.keys()==null?List.of():input.keys();
            message(state,"user",action.equals("answer")?"我的答案："+String.join("、",keys):action.equals("next")?"下一题":text);
            if(action.equals("chat")){
                JsonNode intent=ai.turn(context(s,text),who.user().id(),traceId);
                action=intent.path("action").asText();
                if(!Set.of("next","answer","chat","clarify").contains(action))throw unavailable();
                if(action.equals("answer")){
                    keys=new ArrayList<>();for(JsonNode k:intent.path("keys"))keys.add(k.asText());
                    // 自然语言识别先展示待确认选择，不直接替用户提交。
                    state.set("suggestedKeys",json.valueToTree(keys));
                    message(state,"assistant","你是要提交 "+String.join("、",keys)+" 吗？请在题目卡片确认选项后提交。");
                } else if(action.equals("next"))nextQuestion(s);
                // 纯答疑场景也可能想接着练：模型给文案，能否"下一题"由 Java 状态决定。
                else suggestions(message(state,"assistant",safeReply(intent)),intent.path("suggestions"),false,hasRemaining(s));
            }else if(action.equals("next"))nextQuestion(s);
            else if(action.equals("answer")){
                JsonNode current=state.path("current");
                if(current.isMissingNode()||!current.path("id").asText().equals(input.questionId())||state.hasNonNull("result"))throw conflict();
                JsonNode snapshot=current.path("snapshot");validateSelection(snapshot,keys);
                var actual=new HashSet<String>();for(JsonNode k:snapshot.path("answer").path("keys"))actual.add(k.asText());
                boolean correct=actual.equals(new HashSet<>(keys));
                ObjectNode result=json.createObjectNode();result.put("correct",correct);result.set("selectedKeys",json.valueToTree(keys));result.set("answerKeys",snapshot.path("answer").path("keys"));result.put("explanationMd",snapshot.path("explanation_md").asText(""));state.set("result",result);state.remove("suggestedKeys");
                // 判分结果跟随出题那条消息，追问后历史题目仍能看到自己的答案与正解。
                for(JsonNode item:state.withArray("messages"))if(item.path("question").path("id").asText().equals(input.questionId()))((ObjectNode)item).set("result",result);
                String feedback=correct?"回答正确。":"这次回答不正确。";
                JsonNode modelSuggestions=null;
                // 判分成功即便讲解上游故障也保留，避免用户重复作答/计费。
                try{
                    JsonNode explanation=ai.turn(context(s,"请基于权威判分讲解这道题，说明思路与容易混淆之处。"),who.user().id(),traceId);
                    feedback += "\n\n"+safeReply(explanation);
                    modelSuggestions=explanation.path("suggestions");
                }
                catch(RuntimeException failure){
                    // 只记录类型，不打题干/解析/模型输出；降级不影响已判分结果。
                    org.slf4j.LoggerFactory.getLogger(StudentCoachService.class).warn("Coach explanation degraded: type={}",failure.getClass().getName());
                    feedback += "\n\nAI讲解暂时不可用，你的作答已保存，可以先查看原解析。";}
                // 判分后可以继续下一题：是否真的还有未做题由 Java 判定，模型只提供文案。
                suggestions(message(state,"assistant",feedback),modelSuggestions,true,hasRemaining(s));
            }
            JsonNode response=view(s);
            return tx(who,()->{
                Session live=require(key,s.userId(),true);if(!input.requestId().equals(live.processing()))throw conflict();
                db().update("UPDATE student_coach_session SET state_json=?,processing_request=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=? AND user_id=?",json.writeValueAsString(state),key,s.userId());
                db().update("UPDATE student_coach_turn SET status='done',response_json=? WHERE session_id=? AND request_id=?",json.writeValueAsString(response),key,input.requestId());
                return response;
            });
        }catch(RuntimeException failure){
            tx(who,()->{db().update("UPDATE student_coach_turn SET status='failed' WHERE session_id=? AND request_id=? AND status='processing'",key,input.requestId());db().update("UPDATE student_coach_session SET processing_request=NULL WHERE id=? AND user_id=? AND processing_request=?",key,Long.parseLong(who.user().id()),input.requestId());return null;});
            if(failure instanceof UserFailure userFailure)throw userFailure;
            // 只记录异常类型与traceId，不打用户消息、题干或模型输出，避免原文入日志。
            org.slf4j.LoggerFactory.getLogger(StudentCoachService.class)
                .warn("Coach turn failed: type={}", failure.getClass().getName(), failure);
            throw new UserFailure(503,50331,"陪练处理失败，未自动重试");
        }
    }
    /** 是否还有本会话未练过的已发布题：只返回布尔，不暴露题库规模。 */
    private boolean hasRemaining(Session s){
        var seen=new HashSet<String>();for(JsonNode item:s.state().withArray("seen"))seen.add(item.asText());
        var ids=db().query("SELECT q.id FROM public_question q JOIN public_bank b ON b.id=q.bank_id WHERE b.subject=? AND q.status='published' AND q.deleted_at IS NULL ORDER BY q.id LIMIT 200",
            (rs,n)->rs.getString(1),s.subject());
        return ids.stream().anyMatch(id->!seen.contains(id));
    }
    private void nextQuestion(Session s){
        ObjectNode state=s.state();if(state.hasNonNull("current")&&!state.hasNonNull("result")){message(state,"assistant","先回答当前这道题吧。若不确定，可以告诉我你的思路，我们一起分析。");return;}
        var rows=db().query("SELECT q.id,q.draft_json FROM public_question q JOIN public_bank b ON b.id=q.bank_id WHERE b.subject=? AND q.status='published' AND q.deleted_at IS NULL ORDER BY q.id",(rs,n)->Map.entry(rs.getString("id"),rs.getString("draft_json")),s.subject());
        var seen=new HashSet<String>();for(JsonNode item:state.withArray("seen"))seen.add(item.asText());
        var available=rows.stream().filter(row->!seen.contains(row.getKey())).toList();
        if(available.isEmpty()){message(state,"assistant",rows.isEmpty()?"当前科目暂无已发布的题目，发布后就可以开始练习。":"这个会话已练完当前可用题目。可以继续讨论知识点，或新建对话重新练习。");return;}
        var selected=available.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(available.size()));
        JsonNode snapshot=json.readTree(selected.getValue());
        // 首段不向用户发缺图题；不能把题图缺失当普通文字题。
        ObjectNode current=json.createObjectNode();current.put("id",selected.getKey());current.set("snapshot",snapshot);state.set("current",current);state.remove("result");state.remove("suggestedKeys");state.withArray("seen").add(selected.getKey());
        ObjectNode msg=message(state,"assistant","来一道题，先选出你的答案，也可以说说思路。");msg.set("question",visibleQuestion(current));
    }
    private ObjectNode visibleQuestion(JsonNode current){
        ObjectNode card=json.createObjectNode();card.put("id",current.path("id").asText());JsonNode snapshot=current.path("snapshot");card.put("type",snapshot.path("type").asText());card.put("stemMd",snapshot.path("stem_md").asText());
        ArrayNode options=card.putArray("options");for(JsonNode option:snapshot.path("options")){ObjectNode item=options.addObject();item.put("key",option.path("key").asText());item.put("textMd",option.path("text_md").asText());}
        return card;
    }
    private JsonNode view(Session s){
        ObjectNode response=json.createObjectNode();response.put("id",s.id().toString());response.put("subject",s.subject());response.put("title",s.title());response.set("messages",s.state().path("messages").deepCopy());
        response.set("currentQuestion",s.state().hasNonNull("current")?visibleQuestion(s.state().get("current")):json.nullNode());response.set("result",s.state().path("result").isMissingNode()?json.nullNode():s.state().get("result").deepCopy());
        response.set("suggestedKeys",s.state().path("suggestedKeys").isMissingNode()?json.createArrayNode():s.state().get("suggestedKeys"));response.put("processing",s.processing()!=null);return response;
    }
    private ObjectNode message(ObjectNode state,String role,String text){ArrayNode messages=state.withArray("messages");ObjectNode item=messages.addObject();item.put("id",UUID.randomUUID().toString());item.put("role",role);item.put("text",text);if(messages.size()>100)messages.remove(0);return item;}
    /**
     * 快捷建议：动作由权威状态决定（判分归 Java），文案可由模型给。
     * 只接受白名单动作，模型给的未知动作一律丢弃，避免前端被诱导"已提交/已抽题"。
     */
    private void suggestions(ObjectNode message,JsonNode fromModel,boolean afterGrading,boolean hasNextQuestion){
        ArrayNode list=json.createArrayNode();
        if(fromModel!=null&&fromModel.isArray()){
            for(JsonNode item:fromModel){
                String action=item.path("action").asText("");
                String label=item.path("label").asText("").strip();
                if(label.isEmpty()||label.length()>20)continue;
                // next 的可用条件是"当前题已判分或没有在做的题"且确实还有没练过的题；
                // 纯答疑里同样可以给 next，不能因为不在判分分支就一律禁掉。
                boolean allowed=switch(action){
                    case "next"->hasNextQuestion;
                    case "chat"->true;
                    default->false;
                };
                if(!allowed)continue;
                ObjectNode entry=list.addObject();entry.put("action",action);entry.put("label",label);
                if(action.equals("chat"))entry.put("text",item.path("text").asText("").strip());
                if(list.size()>=4)break;
            }
        }
        // 模型没给可用建议时，用确定性兜底，保证用户始终能继续。
        // 仅在判分后的场景兜底，纯答疑不硬塞"下一题"，尊重 agent 不以刷题为主的用法。
        if(list.isEmpty()&&afterGrading&&hasNextQuestion){ObjectNode entry=list.addObject();entry.put("action","next");entry.put("label","继续下一题");}
        if(!list.isEmpty())message.set("suggestions",list);
    }
    private Map<String,Object> context(Session s,String text){
        Map<String,Object> payload=new LinkedHashMap<>();payload.put("scope","student");payload.put("subject",s.subject());payload.put("message",text);
        List<Map<String,String>> history=new ArrayList<>();JsonNode messages=s.state().path("messages");for(int i=Math.max(0,messages.size()-10);i<messages.size();i++){var msg=messages.get(i);history.add(Map.of("role",msg.path("role").asText(),"text",msg.path("text").asText().substring(0,Math.min(6000,msg.path("text").asText().length()))));}payload.put("history",history);
        if(s.state().hasNonNull("current")){
            JsonNode snapshot=s.state().path("current").path("snapshot");List<Map<String,String>> options=new ArrayList<>();for(JsonNode option:snapshot.path("options"))options.add(Map.of("key",option.path("key").asText(),"text_md",option.path("text_md").asText()));payload.put("question",Map.of("type",snapshot.path("type").asText(),"stem_md",snapshot.path("stem_md").asText(),"options",options));
        }
        if(s.state().hasNonNull("result")){
            JsonNode result=s.state().get("result");payload.put("result",Map.of("correct",result.path("correct").asBoolean(),"selected_keys",result.path("selectedKeys"),"answer_keys",result.path("answerKeys"),"explanation_md",result.path("explanationMd").asText()));
        }
        return payload;
    }
    private String safeReply(JsonNode result){String reply=result.path("reply").asText("");if(reply.isBlank()||reply.length()>6000)throw unavailable();return reply;}
    private void validateSelection(JsonNode snapshot,List<String> keys){
        var options=new HashSet<String>();for(JsonNode option:snapshot.path("options"))options.add(option.path("key").asText());
        if(keys.isEmpty()||keys.size()>8||new HashSet<>(keys).size()!=keys.size()||!options.containsAll(keys)||snapshot.path("type").asText().equals("single_choice")&&keys.size()!=1)throw new IllegalArgumentException();
    }
    private void validateTurn(Turn input){if(input==null||input.requestId()==null||input.action()==null||!Set.of("next","answer","chat").contains(input.action())||input.text()!=null&&input.text().length()>2000||input.keys()!=null&&input.keys().size()>8||input.action().equals("chat")&&(input.text()==null||input.text().isBlank()))throw new IllegalArgumentException();}
    private String requestFingerprint(Turn input){
        try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(input)));}
        catch(java.security.NoSuchAlgorithmException failure){throw new IllegalStateException();}
    }
    private void checkSubject(String subject){if(!Set.of("vocational_aptitude","administrative_aptitude").contains(subject))throw new IllegalArgumentException();}
    private UUID uuid(String id){try{return UUID.fromString(id);}catch(RuntimeException e){throw new IllegalArgumentException();}}
    private UserFailure missing(){return new UserFailure(404,40430,"对话不存在");}
    private UserFailure conflict(){return new UserFailure(409,40931,"对话正在处理或题目状态已变化，请刷新后重试");}
    private UserFailure unavailable(){return new UserFailure(503,50330,"个人Agent暂不可用，请确认数据库与模型服务");}
}
