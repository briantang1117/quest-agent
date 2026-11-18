package com.quest.api.privatebank;

import com.quest.api.auth.UserFailure;
import com.quest.api.publicbank.PublicAiClient;
import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** 每次领取一个已授权步骤；已完成步骤落库，未知计费失败只允许用户显式重试。 */
@Component
public class PrivateImportProcessor {
    private final PrivateQuestionService service;
    @Value("${quest.private-questions.processing-enabled:true}") boolean processing=true;
    private final Semaphore slots=new Semaphore(2);
    private final ExecutorService workers=Executors.newVirtualThreadPerTaskExecutor();
    public PrivateImportProcessor(PrivateQuestionService service){this.service=service;}
    @PreDestroy void close(){workers.shutdownNow();}
    @Scheduled(fixedDelay=2000) public void tick(){
        if(!processing||!service.enabled||service.jdbc.getIfAvailable()==null)return;
        if(slots.tryAcquire())try{workers.submit(()->{try{processOne();}catch(RuntimeException e){org.slf4j.LoggerFactory.getLogger(getClass()).debug("私人导入领取未完成：type={}",e.getClass().getSimpleName());}finally{slots.release();}});}catch(RejectedExecutionException ignored){slots.release();}
    }
    record Work(UUID job,long user,UUID run,UUID source,UUID item,String subject,String kind,String content,JsonNode candidate,UUID asset){}
    public boolean processOne(){
        Work work;
        try{work=service.transaction(()->{
            service.db().update("UPDATE private_import SET status='failed',error_code=50460,updated_at=CURRENT_TIMESTAMP WHERE status='processing' AND lease_until<CURRENT_TIMESTAMP");
            var rows=service.db().queryForList("SELECT id,user_id,session_id,subject,kind FROM private_import WHERE status='queued' ORDER BY created_at,id LIMIT 1 FOR UPDATE SKIP LOCKED");if(rows.isEmpty())return null;var row=rows.getFirst();UUID job=UUID.fromString(row.get("id").toString());long user=((Number)row.get("user_id")).longValue();
            if(service.db().queryForObject("SELECT COUNT(*) FROM student_coach_session WHERE id=? AND user_id=? AND deleted_at IS NULL",Integer.class,row.get("session_id"),user)!=1){service.db().update("UPDATE private_import SET status='cancelled',updated_at=CURRENT_TIMESTAMP WHERE id=?",job);return null;}
            try{service.studentEnabled(user);}catch(UserFailure denied){service.db().update("UPDATE private_import SET status='failed',error_code=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",denied.code(),job);return null;}
            UUID run=UUID.randomUUID();String subject=row.get("subject").toString();
            var sources=service.db().queryForList("SELECT s.id,s.source_text,s.asset_id,a.kind,a.file_bytes FROM private_import_source s LEFT JOIN private_asset a ON a.id=s.asset_id AND a.user_id=s.user_id WHERE s.import_id=? AND s.user_id=? AND s.status='queued' ORDER BY s.ordinal LIMIT 1",job,user);
            Work selected;
            if(!sources.isEmpty()){
                var source=sources.getFirst();UUID sourceId=UUID.fromString(source.get("id").toString());UUID asset=source.get("asset_id")==null?null:UUID.fromString(source.get("asset_id").toString());String kind=asset==null?"text":source.get("kind").toString();String content=asset==null?source.get("source_text").toString():kind.equals("text")?new String((byte[])source.get("file_bytes"),java.nio.charset.StandardCharsets.UTF_8):Base64.getEncoder().encodeToString((byte[])source.get("file_bytes"));
                service.db().update("UPDATE private_import_source SET status='processing' WHERE id=?",sourceId);selected=new Work(job,user,run,sourceId,null,subject,kind,content,null,asset);
            }else{
                var items=service.db().queryForList("SELECT id,source_id,candidate_json FROM private_import_item WHERE import_id=? AND user_id=? AND status='queued' ORDER BY ordinal LIMIT 1",job,user);
                if(items.isEmpty()){finish(job,user);return null;}
                var item=items.getFirst();UUID itemId=UUID.fromString(item.get("id").toString());service.db().update("UPDATE private_import_item SET status='processing' WHERE id=?",itemId);selected=new Work(job,user,run,UUID.fromString(item.get("source_id").toString()),itemId,subject,row.get("kind").toString(),null,service.json.readTree(item.get("candidate_json").toString()),null);
            }
            service.db().update("UPDATE private_import SET status='processing',stage=?,run_id=?,lease_until=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",selected.item()==null?"recognizing":"checking",run,OffsetDateTime.now().plusMinutes(8),job);return selected;
        });}catch(DataAccessException absent){return false;}
        if(work==null)return false;
        JsonNode result=null;int failure=0;
        // 两个UUID去掉分隔符仍完整保留批次/执行信息，适配额度及审计的64字符字段。
        String trace=work.job().toString().replace("-","")+work.run().toString().replace("-","");
        try{result=service.ai.privateQuestions(work.item()==null?"source":"check",work.item()==null?Map.of("scope","student","kind",work.kind(),"content",work.content()):Map.of("scope","student","candidate",work.candidate(),"allow_search",Boolean.TRUE.equals(service.controls.features(work.user()).get("search"))),new PublicAiClient.Caller(work.user(),trace));}
        catch(RuntimeException e){failure=e instanceof UserFailure f?f.code():50360;}
        final JsonNode output=result;final int code=failure;
        service.transaction(()->{
            service.db().queryForList("SELECT id FROM app_user WHERE id=? FOR UPDATE",work.user());
            var rows=service.db().queryForList("SELECT status,run_id,subject,kind,session_id FROM private_import WHERE id=? AND user_id=? FOR UPDATE",work.job(),work.user());if(rows.size()!=1)return null;var job=rows.getFirst();
            if(!job.get("status").equals("processing")||!work.run().toString().equals(String.valueOf(job.get("run_id"))))return null;
            if(service.db().queryForObject("SELECT COUNT(*) FROM student_coach_session WHERE id=? AND user_id=? AND deleted_at IS NULL",Integer.class,job.get("session_id"),work.user())!=1){service.db().update("UPDATE private_import SET status='cancelled',updated_at=CURRENT_TIMESTAMP WHERE id=?",work.job());return null;}
            try{
                if(code!=0)throw new UserFailure(503,code,"本步骤处理失败");service.studentEnabled(work.user());
                service.nested(()->{if(work.item()==null)saveSource(work,output);else saveItem(work,job,output);return null;});
            }catch(RuntimeException invalid){int error=invalid instanceof UserFailure f?f.code():50260;
                if(work.item()==null)service.db().update("UPDATE private_import_source SET status='failed',error_code=? WHERE id=?",error,work.source());else service.db().update("UPDATE private_import_item SET status='failed',error_code=? WHERE id=?",error,work.item());
            }
            service.db().update("UPDATE private_import SET status='queued',run_id=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=?",work.job());finish(work.job(),work.user());return null;
        });return true;
    }
    private void saveSource(Work work,JsonNode output){
        JsonNode candidates=output.path("questions"),warnings=output.path("warnings");if(!candidates.isArray()||candidates.size()>100||!warnings.isArray()||warnings.size()>20)throw new IllegalArgumentException();
        int total=service.db().queryForObject("SELECT COUNT(*) FROM private_import_item WHERE import_id=?",Integer.class,work.job());if(total+candidates.size()>100)throw new UserFailure(413,41360,"每批最多100题，请拆分");
        for(var candidate:candidates)validateCandidate(candidate);
        for(var candidate:candidates){service.db().update("INSERT INTO private_import_item(id,import_id,user_id,source_id,ordinal,candidate_json) VALUES (?,?,?,?,?,?)",UUID.randomUUID(),work.job(),work.user(),work.source(),++total,service.json.writeValueAsString(candidate));}
        if(!warnings.isEmpty()){
            var old=service.json.readTree(service.db().queryForObject("SELECT warnings_json FROM private_import WHERE id=?",String.class,work.job()));var merged=service.json.createArrayNode();for(var v:old)if(merged.size()<100)merged.add(v.asText().substring(0,Math.min(500,v.asText().length())));for(var v:warnings)if(merged.size()<100)merged.add(v.asText().substring(0,Math.min(500,v.asText().length())));service.db().update("UPDATE private_import SET warnings_json=? WHERE id=?",service.json.writeValueAsString(merged),work.job());
        }
        service.db().update("UPDATE private_import_source SET status='done',error_code=NULL WHERE id=?",work.source());
    }
    private void validateCandidate(JsonNode q){
        if(!q.isObject()||!Set.of("single_choice","multiple_choice").contains(q.path("type").asText())||q.path("stem_md").asText().isBlank()||q.path("stem_md").asText().length()>5000||!q.path("options").isArray()||q.path("options").size()<2||q.path("options").size()>8)throw new IllegalArgumentException();
        int n=0;for(var o:q.path("options")){if(!o.path("key").asText().equals(String.valueOf((char)('A'+n++)))||o.path("text_md").asText().isBlank()||o.path("text_md").asText().length()>4000)throw new IllegalArgumentException();}
    }
    private void saveItem(Work work,Map<String,Object> job,JsonNode output){
        if(!output.isObject()||!Set.of("ready","needs_attention").contains(output.path("status").asText())||!output.path("candidate").equals(work.candidate())||!output.path("issues").isArray()||output.path("issues").size()>24||!output.path("answer_keys").isArray()||output.path("explanation_md").asText().length()>6000||output.path("memory_tip").asText().length()>2000)throw new IllegalArgumentException();
        validateCandidate(work.candidate());ObjectNode result=(ObjectNode)output.deepCopy();
        if(result.path("status").asText().equals("ready")&&(!service.validAnswer(work.candidate(),result.path("answer_keys"))||result.path("explanation_md").asText().isBlank()||result.path("memory_tip").asText().isBlank()||work.candidate().path("requires_image").asBoolean()||!result.path("issues").isEmpty()))throw new IllegalArgumentException();
        var asset=service.db().queryForList("SELECT s.asset_id,a.filename FROM private_import_source s LEFT JOIN private_asset a ON a.id=s.asset_id AND a.user_id=s.user_id WHERE s.id=? AND s.user_id=?",work.source(),work.user()).getFirst();var source=result.putObject("source");source.put("import_id",work.job().toString()).put("session_id",job.get("session_id").toString());source.set("asset_id",service.json.valueToTree(asset.get("asset_id")));source.set("filename",service.json.valueToTree(asset.get("filename")));
        var existing=service.db().queryForList("SELECT id,result_json,revision,deleted_at,status FROM private_question WHERE user_id=? AND fingerprint=? FOR UPDATE",work.user(),service.fingerprint(work.subject(),work.candidate()));
        UUID question;long revision=1;String status=result.path("status").asText();
        if(existing.isEmpty())question=service.saveQuestion(work.user(),work.subject(),job.get("kind").toString(),result,null);
        else{
            var old=existing.getFirst();question=UUID.fromString(old.get("id").toString());revision=((Number)old.get("revision")).longValue();var oldResult=service.json.readTree(old.get("result_json").toString());
            if(old.get("deleted_at")==null&&old.get("status").equals("ready")&&status.equals("ready")&&new HashSet<>(PrivateQuestionService.strings(oldResult.path("answer_keys"))).equals(new HashSet<>(PrivateQuestionService.strings(result.path("answer_keys")))))status="duplicate";
            else{status="needs_attention";result.put("status",status);result.withArray("issues").add("与已有题目或已删除版本不同，不自动覆盖已有内容");}
        }
        service.db().update("UPDATE private_import_item SET status=?,result_json=?,question_id=?,question_revision=?,error_code=NULL WHERE id=? AND user_id=?",status,service.json.writeValueAsString(result),question,revision,work.item(),work.user());
    }
    private void finish(UUID job,long user){
        long active=service.db().queryForObject("SELECT COUNT(*) FROM private_import_source WHERE import_id=? AND status IN ('queued','processing')",Long.class,job)+service.db().queryForObject("SELECT COUNT(*) FROM private_import_item WHERE import_id=? AND status IN ('queued','processing')",Long.class,job);
        if(active>0)return;
        long issues=service.db().queryForObject("SELECT COUNT(*) FROM private_import_item WHERE import_id=? AND status IN ('needs_attention','failed')",Long.class,job)+service.db().queryForObject("SELECT COUNT(*) FROM private_import_source WHERE import_id=? AND status='failed'",Long.class,job);
        var warnings=service.json.readTree(service.db().queryForObject("SELECT warnings_json FROM private_import WHERE id=?",String.class,job));
        service.db().update("UPDATE private_import SET status=?,stage='finished',run_id=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=? AND user_id=?",issues>0||!warnings.isEmpty()?"needs_attention":"completed",job,user);
    }
}
