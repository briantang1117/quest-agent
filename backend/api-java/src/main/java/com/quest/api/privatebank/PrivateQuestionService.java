package com.quest.api.privatebank;

import com.quest.api.auth.*;
import com.quest.api.auth.UserModels.SessionIdentity;
import com.quest.api.admin.AdminControlService;
import com.quest.api.publicbank.PublicAiClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.*;
import tools.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

/** 私人原件、任务与题目按本人隔离；不提供Admin审批接口。 */
@Service
public class PrivateQuestionService {
    final ObjectProvider<JdbcTemplate> jdbc;
    final ObjectProvider<PlatformTransactionManager> managers;
    final UserService users;
    final AdminControlService controls;
    final ObjectMapper json;
    final PublicAiClient ai;
    @Value("${quest.private-questions.enabled:true}") boolean enabled=true;
    public PrivateQuestionService(ObjectProvider<JdbcTemplate> jdbc,ObjectProvider<PlatformTransactionManager> managers,UserService users,AdminControlService controls,ObjectMapper json,PublicAiClient ai){this.jdbc=jdbc;this.managers=managers;this.users=users;this.controls=controls;this.json=json;this.ai=ai;}
    JdbcTemplate db(){var value=jdbc.getIfAvailable();if(value==null)throw unavailable();return value;}
    <T>T transaction(Supplier<T> action){return new TransactionTemplate(managers.getObject()).execute(s->action.get());}
    <T>T nested(Supplier<T> action){var template=new TransactionTemplate(managers.getObject());template.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_NESTED);return template.execute(s->action.get());}
    <T>T tx(SessionIdentity who,Supplier<T> action){return transaction(()->{var current=users.session(who.user().id(),who.sessionId());if(!current.user().role().equals("student")||current.user().mustChangePassword())throw UserFailure.denied();if(!enabled)throw new UserFailure(503,50360,"私人题目导入未启用");return action.get();});}
    long user(SessionIdentity who){return Long.parseLong(who.user().id());}
    static UserFailure missing(){return new UserFailure(404,40460,"私有题目或导入任务不存在");}
    static UserFailure unavailable(){return new UserFailure(503,50360,"私人题目服务暂不可用，请确认数据库升级");}
    static String digest(byte[] raw){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));}catch(Exception e){throw new IllegalStateException();}}
    static UUID uuid(String value){try{return UUID.fromString(value);}catch(RuntimeException e){throw new IllegalArgumentException();}}
    void studentEnabled(long user){if(db().queryForObject("SELECT COUNT(*) FROM app_user WHERE id=? AND account_type='student' AND status='active'",Integer.class,user)!=1)throw UserFailure.invalid();controls.requireFeature(user,"coach");}
    Map<String,Object> metadata(Map<String,Object> row){Map<String,Object> result=new LinkedHashMap<>();for(String key:List.of("id","filename","kind","mime","sha256"))result.put(key,row.get(key));return result;}
    public Map<String,Object> upload(SessionIdentity who,UUID request,String name,String suppliedMime,byte[] bytes){
        if(request==null||bytes==null||bytes.length==0||bytes.length>8*1024*1024)throw new UserFailure(413,41360,"附件须为不超过8MiB的图片、PDF或UTF-8文本");
        String filename=name==null?"附件":name.replaceAll("[\\p{Cntrl}\\\\/]","_");if(filename.length()>200)filename=filename.substring(0,200);
        String kind,mime;
        if(bytes.length>=5&&new String(bytes,0,5,StandardCharsets.US_ASCII).equals("%PDF-")){kind="pdf";mime="application/pdf";}
        else if(bytes.length>=8&&bytes[0]==(byte)137&&bytes[1]==80&&bytes[2]==78&&bytes[3]==71){kind="image";mime="image/png";}
        else if(bytes.length>=3&&bytes[0]==(byte)255&&bytes[1]==(byte)216&&bytes[2]==(byte)255){kind="image";mime="image/jpeg";}
        else if(bytes.length>=12&&new String(bytes,0,4,StandardCharsets.US_ASCII).equals("RIFF")&&new String(bytes,8,4,StandardCharsets.US_ASCII).equals("WEBP")){kind="image";mime="image/webp";}
        else if(filename.toLowerCase(Locale.ROOT).endsWith(".txt")){
            try{StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes));}catch(Exception invalid){throw new IllegalArgumentException();}
            if(bytes.length>160000||new String(bytes,StandardCharsets.UTF_8).contains("\0"))throw new IllegalArgumentException();kind="text";mime="text/plain";
        }else throw new UserFailure(422,42260,"仅支持PNG/JPEG/WebP、PDF与UTF-8文本");
        final String safeName=filename;
        return tx(who,()->{long user=user(who);studentEnabled(user);db().queryForList("SELECT id FROM app_user WHERE id=? FOR UPDATE",user);
            var old=db().queryForList("SELECT id,filename,kind,mime,sha256 FROM private_asset WHERE user_id=? AND request_id=?",user,request);
            String hash=digest(bytes);if(!old.isEmpty()){if(!old.getFirst().get("sha256").equals(hash))throw new UserFailure(409,40960,"附件请求内容已变化");return metadata(old.getFirst());}
            long size=db().queryForObject("SELECT COALESCE(SUM(octet_length(file_bytes)),0) FROM private_asset WHERE user_id=?",Long.class,user);if(size+bytes.length>256L*1024*1024)throw new UserFailure(413,41360,"私人附件累计超过256MiB，请联系管理员调整存储预算");
            UUID id=UUID.randomUUID();db().update("INSERT INTO private_asset(id,user_id,request_id,filename,kind,mime,sha256,file_bytes) VALUES (?,?,?,?,?,?,?,?)",id,user,request,safeName,kind,mime,hash,bytes);return Map.of("id",id.toString(),"filename",safeName,"kind",kind,"mime",mime,"sha256",hash);
        });
    }
    public Map<String,Object> file(SessionIdentity who,UUID id){return tx(who,()->{var rows=db().queryForList("SELECT filename,mime,file_bytes FROM private_asset WHERE id=? AND user_id=?",id,user(who));if(rows.size()!=1)throw missing();return rows.getFirst();});}
    public ArrayNode assets(long user,List<UUID> ids){
        if(ids==null)return json.createArrayNode();if(ids.size()>4||new HashSet<>(ids).size()!=ids.size())throw new IllegalArgumentException();var out=json.createArrayNode();
        for(UUID id:ids){var rows=db().queryForList("SELECT id,filename,kind,mime,sha256 FROM private_asset WHERE id=? AND user_id=?",id,user);if(rows.size()!=1)throw missing();out.add(json.valueToTree(metadata(rows.getFirst())));}return out;
    }
    public JsonNode create(SessionIdentity who,UUID session,UUID request,String subject,String instruction,JsonNode attachments,String sourceText){
        return tx(who,()->{long user=user(who);studentEnabled(user);db().queryForList("SELECT id FROM app_user WHERE id=? FOR UPDATE",user);
            if(db().queryForObject("SELECT COUNT(*) FROM student_coach_session WHERE id=? AND user_id=? AND deleted_at IS NULL",Integer.class,session,user)!=1)throw missing();
            var old=db().queryForList("SELECT id FROM private_import WHERE user_id=? AND request_id=?",user,request);if(!old.isEmpty())return job(user,uuid(old.getFirst().get("id").toString()));
            if(db().queryForObject("SELECT COUNT(*) FROM private_import WHERE user_id=? AND status IN ('queued','processing')",Integer.class,user)>=3)throw new UserFailure(429,42960,"同时最多处理3个私人导入任务");
            if(!Set.of("vocational_aptitude","administrative_aptitude").contains(subject))throw new IllegalArgumentException();
            String text=sourceText==null?"":sourceText.strip();if(text.length()>160000)throw new IllegalArgumentException();
            if((attachments==null||attachments.isEmpty())&&text.isBlank())throw new UserFailure(422,42260,"请先发送题目文本或附件，再告诉我要导入哪些题目");
            UUID id=UUID.randomUUID();String kind=instruction.contains("错题")?"mistake":"question";
            db().update("INSERT INTO private_import(id,user_id,session_id,request_id,subject,kind) VALUES (?,?,?,?,?,?)",id,user,session,request,subject,kind);int ordinal=0;
            if(!text.isBlank())db().update("INSERT INTO private_import_source(id,import_id,user_id,ordinal,source_text) VALUES (?,?,?,?,?)",UUID.randomUUID(),id,user,++ordinal,text);
            if(attachments!=null){var ids=new ArrayList<UUID>();for(var asset:attachments)ids.add(uuid(asset.path("id").asText()));assets(user,ids);
                for(UUID asset:ids)db().update("INSERT INTO private_import_source(id,import_id,user_id,asset_id,ordinal) VALUES (?,?,?,?,?)",UUID.randomUUID(),id,user,asset,++ordinal);
            }
            return job(user,id);
        });
    }
    public JsonNode job(SessionIdentity who,UUID id){return tx(who,()->job(user(who),id));}
    static String failureMessage(int code){return switch(code){
        case 40240 -> "Token 余额不足，请联系管理员充值后重试。";
        case 40341 -> "此功能尚未开放，请联系管理员";
        case 41360 -> "附件、页数或本批题量超过处理预算，请拆分后重新导入";
        case 42260 -> "附件未通过解析检查，请检查文件有效性、活动内容或页数限制";
        case 50345 -> "Token 额度校验服务暂时不可用，请联系管理员检查后重试。";
        case 50360 -> "图片识别或私人题目服务暂不可用，请检查服务配置";
        case 50460,50401 -> "本步骤处理超时，请缩小处理范围后手动重试";
        case 50260 -> "模型返回的题目结构不完整或不符合要求，未自动重试";
        default -> "本步骤处理失败，请检查模型与识别服务状态；未自动重试（错误码："+code+"）";
    };}
    JsonNode job(long user,UUID id){
        var rows=db().queryForList("SELECT id,session_id,subject,kind,status,stage,error_code,warnings_json,created_at,updated_at FROM private_import WHERE id=? AND user_id=?",id,user);if(rows.size()!=1)throw missing();var row=rows.getFirst();
        ObjectNode result=json.createObjectNode();for(String key:List.of("id","session_id","subject","kind","status","stage"))result.put(key,String.valueOf(row.get(key)));result.set("warnings",json.readTree(String.valueOf(row.get("warnings_json"))));result.set("error_code",json.valueToTree(row.get("error_code")));
        var list=result.putArray("items");int ready=0,review=0,duplicate=0,failed=0;
        for(var item:db().queryForList("SELECT i.id,i.ordinal,i.status,i.result_json,i.question_id,i.error_code,s.asset_id FROM private_import_item i JOIN private_import_source s ON s.id=i.source_id WHERE i.import_id=? AND i.user_id=? ORDER BY i.ordinal",id,user)){
            var value=list.addObject();value.put("id",item.get("id").toString());value.put("ordinal",((Number)item.get("ordinal")).intValue());String status=item.get("status").toString();value.put("status",status);value.set("result",item.get("result_json")==null?json.nullNode():json.readTree(item.get("result_json").toString()));value.set("question_id",json.valueToTree(item.get("question_id")));value.set("error_code",json.valueToTree(item.get("error_code")));if(item.get("error_code") instanceof Number failureCode)value.put("error_message",failureMessage(failureCode.intValue()));value.set("asset_id",json.valueToTree(item.get("asset_id")));if(status.equals("ready"))ready++;if(status.equals("needs_attention"))review++;if(status.equals("duplicate"))duplicate++;if(status.equals("failed"))failed++;
        }
        result.put("saved",ready);result.put("needs_attention",review);result.put("duplicates",duplicate);result.put("failed",failed);
        result.put("source_failures",db().queryForObject("SELECT COUNT(*) FROM private_import_source WHERE import_id=? AND user_id=? AND status='failed'",Integer.class,id,user));
        var errors=result.putArray("source_errors");for(var failure:db().queryForList("SELECT s.id,s.ordinal,s.error_code,a.filename FROM private_import_source s LEFT JOIN private_asset a ON a.id=s.asset_id AND a.user_id=s.user_id WHERE s.import_id=? AND s.user_id=? AND s.status='failed' ORDER BY s.ordinal",id,user)){int code=failure.get("error_code") instanceof Number n?n.intValue():50360;errors.addObject().put("id",failure.get("id").toString()).put("filename",failure.get("filename")==null?"文本来源"+failure.get("ordinal"):failure.get("filename").toString()).put("code",code).put("message",failureMessage(code));}return result;
    }
    public ArrayNode sessionJobs(long user,JsonNode refs){var list=json.createArrayNode();if(refs==null||!refs.isArray())return list;for(var ref:refs){if(list.size()>=20)break;list.add(job(user,uuid(ref.asText())));}return list;}
    public JsonNode contextIssues(long user,UUID jobId){var value=job(user,jobId);var out=json.createArrayNode();for(var item:value.path("items")){if(!item.path("status").asText().equals("needs_attention"))continue;if(out.size()>=10)break;var node=out.addObject();node.put("id",item.path("id").asText());node.put("ordinal",item.path("ordinal").asInt());node.set("issues",item.path("result").path("issues"));node.set("original_keys",item.path("result").path("candidate").path("original_keys"));node.set("suggested_keys",item.path("result").path("answer_keys"));}return out;}
    public Map<String,Object> questions(SessionIdentity who,String subject,String status,String query,int page){
        if(page<1||page>10000||query.length()>100||!Set.of("","ready","needs_attention","excluded").contains(status)||!Set.of("","vocational_aptitude","administrative_aptitude").contains(subject))throw new IllegalArgumentException();
        return tx(who,()->{String where=" WHERE user_id=? AND deleted_at IS NULL";var args=new ArrayList<Object>();args.add(user(who));if(!subject.isBlank()){where+=" AND subject=?";args.add(subject);}if(!status.isBlank()){where+=" AND status=?";args.add(status);}if(!query.isBlank()){where+=" AND LOWER(result_json) LIKE ? ESCAPE '!'";args.add("%"+query.toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%");}
            long total=db().queryForObject("SELECT COUNT(*) FROM private_question"+where,Long.class,args.toArray());args.add(20);args.add((page-1)*20);var list=json.createArrayNode();
            for(var row:db().queryForList("SELECT id,subject,kind,status,result_json,revision FROM private_question"+where+" ORDER BY updated_at DESC,id LIMIT ? OFFSET ?",args.toArray())){var q=list.addObject();for(String key:List.of("id","subject","kind","status"))q.put(key,row.get(key).toString());q.set("revision",json.valueToTree(row.get("revision")));q.set("result",json.readTree(row.get("result_json").toString()));}return Map.of("list",list,"total",total,"page",page,"size",20);
        });
    }
    public JsonNode controlJob(SessionIdentity who,UUID id,String action){return tx(who,()->{long user=user(who);db().queryForList("SELECT id FROM private_import WHERE id=? AND user_id=? FOR UPDATE",id,user);var value=job(user,id);String status=value.path("status").asText();if(action.equals("cancel")){db().update("UPDATE private_import SET status='cancelled',updated_at=CURRENT_TIMESTAMP WHERE id=? AND user_id=? AND status IN ('queued','processing')",id,user);}else if(action.equals("retry")){
            if(!Set.of("failed","needs_attention").contains(status))throw new UserFailure(409,40960,"任务当前不能重试");studentEnabled(user);db().update("UPDATE private_import_source SET status='queued',error_code=NULL WHERE import_id=? AND user_id=? AND status IN ('failed','processing')",id,user);db().update("UPDATE private_import_item SET status='queued',error_code=NULL WHERE import_id=? AND user_id=? AND status IN ('failed','processing')",id,user);db().update("UPDATE private_import SET status='queued',error_code=NULL,run_id=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=? AND user_id=?",id,user);
        }else throw new IllegalArgumentException();return job(user,id);});}
    public JsonNode decide(SessionIdentity who,UUID session,UUID itemId,String action){
        return tx(who,()->{long user=user(who);studentEnabled(user);if(session==null||itemId==null||action==null)throw new IllegalArgumentException();if(db().queryForObject("SELECT COUNT(*) FROM student_coach_session WHERE id=? AND user_id=? AND deleted_at IS NULL",Integer.class,session,user)!=1)throw missing();db().queryForList("SELECT id FROM app_user WHERE id=? FOR UPDATE",user);
            var rows=db().queryForList("SELECT i.*,j.session_id,j.subject,j.kind FROM private_import_item i JOIN private_import j ON j.id=i.import_id WHERE i.id=? AND i.user_id=? FOR UPDATE",itemId,user);if(rows.size()!=1||!rows.getFirst().get("session_id").toString().equals(session.toString()))throw missing();var row=rows.getFirst();
            if(!row.get("status").equals("needs_attention"))throw new UserFailure(409,40960,"该疑点已处理，请刷新");
            ObjectNode result=(ObjectNode)json.readTree(row.get("result_json").toString());UUID question=row.get("question_id")==null?null:uuid(row.get("question_id").toString());
            if(action.equals("exclude")){
                result.put("status","excluded").put("resolved_by_user",true);
                db().update("UPDATE private_import_item SET status='excluded',result_json=? WHERE id=? AND user_id=?",json.writeValueAsString(result),itemId,user);
                if(question!=null&&row.get("question_revision")!=null){long revision=((Number)row.get("question_revision")).longValue();if(db().update("UPDATE private_question SET status='excluded',result_json=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND user_id=? AND revision=? AND status='needs_attention'",json.writeValueAsString(result),question,user,revision)==1)db().update("INSERT INTO private_question_version(question_id,user_id,revision,result_json) VALUES (?,?,?,?)",question,user,revision+1,json.writeValueAsString(result));}
            }
            else if(Set.of("use_suggestion","keep_original").contains(action)){
                if(result.path("candidate").path("requires_image").asBoolean())throw new UserFailure(422,42260,"本题仍依赖未核对图片，请在聊天中补充清晰题目后重新导入");
                if(action.equals("keep_original")){
                    var original=result.path("candidate").path("original_keys");if(!validAnswer(result.path("candidate"),original))throw new IllegalArgumentException();
                    if(!new HashSet<>(strings(original)).equals(new HashSet<>(strings(result.path("answer_keys"))))){result.set("ai_suggested_keys",result.path("answer_keys").deepCopy());result.put("ai_suggested_explanation",result.path("explanation_md").asText());result.set("answer_keys",original.deepCopy());result.put("explanation_md",result.path("candidate").path("original_explanation").asText());result.put("memory_tip","");result.put("status","excluded");result.put("answer_source","original_unverified");}
                }
                boolean held=result.path("status").asText().equals("excluded");
                if(!held&&(!validAnswer(result.path("candidate"),result.path("answer_keys"))||result.path("explanation_md").asText().isBlank()||result.path("memory_tip").asText().isBlank()))throw new IllegalArgumentException();
                if(!held){result.put("answer_source","user_confirmed");result.put("status","ready");}result.put("resolved_by_user",true);
                if(question==null)question=saveQuestion(user,row.get("subject").toString(),row.get("kind").toString(),result,null);
                else{
                    var current=db().queryForList("SELECT revision,status FROM private_question WHERE id=? AND user_id=? AND deleted_at IS NULL FOR UPDATE",question,user);if(current.size()!=1||((Number)current.getFirst().get("revision")).longValue()!=((Number)row.get("question_revision")).longValue())throw new UserFailure(409,40960,"题目版本已变化，请重新核对");
                    if(!current.getFirst().get("status").equals("needs_attention"))throw new UserFailure(409,40960,"已有题目的答案不同，不能静默覆盖；请保留已有题或重新补充说明");
                    long revision=((Number)current.getFirst().get("revision")).longValue()+1;db().update("UPDATE private_question SET status=?,result_json=?,revision=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND user_id=?",result.path("status").asText(),json.writeValueAsString(result),revision,question,user);db().update("INSERT INTO private_question_version(question_id,user_id,revision,result_json) VALUES (?,?,?,?)",question,user,revision,json.writeValueAsString(result));
                }
                db().update("UPDATE private_import_item SET status=?,question_id=?,result_json=? WHERE id=? AND user_id=?",result.path("status").asText(),question,json.writeValueAsString(result),itemId,user);
            }else throw new IllegalArgumentException();
            UUID importId=uuid(row.get("import_id").toString());
            db().update("UPDATE private_import SET status=CASE WHEN EXISTS(SELECT 1 FROM private_import_item WHERE import_id=? AND status IN ('needs_attention','failed')) OR EXISTS(SELECT 1 FROM private_import_source WHERE import_id=? AND status='failed') OR warnings_json<>'[]' THEN 'needs_attention' ELSE 'completed' END,updated_at=CURRENT_TIMESTAMP WHERE id=? AND user_id=? AND status='needs_attention'",importId,importId,importId,user);
            return job(user,importId);
        });
    }
    static List<String> strings(JsonNode values){var out=new ArrayList<String>();for(var value:values)out.add(value.asText());return out;}
    boolean validAnswer(JsonNode candidate,JsonNode keys){var options=new HashSet<String>();for(var option:candidate.path("options"))options.add(option.path("key").asText());var answer=strings(keys);return keys.isArray()&&answer.size()==new HashSet<>(answer).size()&&options.containsAll(answer)&&(candidate.path("type").asText().equals("single_choice")?answer.size()==1:answer.size()>=2);}
    String fingerprint(String subject,JsonNode candidate){var value=json.createObjectNode().put("subject",subject).put("type",candidate.path("type").asText()).put("stem",candidate.path("stem_md").asText().replaceAll("\\s+","").toLowerCase(Locale.ROOT));var options=value.putArray("options");for(var option:candidate.path("options"))options.add(option.path("text_md").asText().replaceAll("\\s+","").toLowerCase(Locale.ROOT));return digest(json.writeValueAsBytes(value));}
    UUID saveQuestion(long user,String subject,String kind,ObjectNode result,UUID requested){UUID id=requested==null?UUID.randomUUID():requested;db().update("INSERT INTO private_question(id,user_id,subject,fingerprint,kind,status,result_json) VALUES (?,?,?,?,?,?,?)",id,user,subject,fingerprint(subject,result.path("candidate")),kind,result.path("status").asText(),json.writeValueAsString(result));db().update("INSERT INTO private_question_version(question_id,user_id,revision,result_json) VALUES (?,?,1,?)",id,user,json.writeValueAsString(result));return id;}
}
