package com.quest.api.admin;
import com.quest.api.auth.*;
import com.quest.api.auth.UserModels.SessionIdentity;
import com.quest.api.publicbank.PublicAiClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
@Service
public class ModelMeterService {
    private final AdminControlService controls;
    private final UserService users;
    private final ObjectMapper json;
    private final String secret;
    public ModelMeterService(AdminControlService controls,UserService users,ObjectMapper json,@Value("${INTERNAL_HMAC_SECRET:}")String secret){this.controls=controls;this.users=users;this.json=json;this.secret=secret;}
    public record Reserve(String grant,UUID id,String model,String capability,long reservation){}
    public record Settle(String grant,UUID id,Long inputTokens,Long outputTokens,String outcome){}
    public String issue(PublicAiClient.Caller caller,String path){
        SessionIdentity identity=null;
        var attributes=org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if(attributes instanceof org.springframework.web.context.request.ServletRequestAttributes servlet&&servlet.getRequest().getAttribute(UserController.IDENTITY) instanceof SessionIdentity who)identity=who;
        String actor=caller!=null&&caller.actor()!=null?caller.actor().toString():identity==null?"":identity.user().id();
        String sid=identity!=null&&identity.user().id().equals(actor)?identity.sessionId():"";
        var value=Map.of("user",actor,"sid",sid,"path",path,"expires",Instant.now().getEpochSecond()+1200,"trace",caller==null||caller.traceId()==null?UUID.randomUUID().toString():caller.traceId());
        String encoded=Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(value));
        return encoded+"."+signature(encoded);
    }
    private String signature(String value){try{var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return HexFormat.of().formatHex(mac.doFinal(("meter\n"+value).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw UserFailure.unavailable();}}
    private JsonNode verify(String value){
        try{String[] parts=value.split("\\.",-1);if(parts.length!=2||value.length()>4000||!java.security.MessageDigest.isEqual(signature(parts[0]).getBytes(StandardCharsets.US_ASCII),parts[1].getBytes(StandardCharsets.US_ASCII)))throw UserFailure.denied();
            var result=json.readTree(Base64.getUrlDecoder().decode(parts[0]));if(result.path("expires").asLong()<Instant.now().getEpochSecond())throw UserFailure.denied();return result;
        }catch(UserFailure failure){throw failure;}catch(RuntimeException e){throw UserFailure.denied();}
    }
    public Map<String,Object> reserve(Reserve input){
        if(input==null||input.id()==null||input.model()==null||input.model().isBlank()||input.model().length()>300||input.capability()==null||input.capability().length()>100||input.reservation()<0||input.reservation()>32_000_000)throw new IllegalArgumentException();
        var grant=verify(input.grant());String actor=grant.path("user").asText(),sid=grant.path("sid").asText();
        return controls.tx(()->{
            Long user=actor.isBlank()?null:AdminControlService.id(actor);
            if(user!=null){
                var profiles=controls.db().queryForList("SELECT account_type,status FROM app_user WHERE id=? FOR UPDATE",user);
                if(profiles.size()!=1||!profiles.getFirst().get("status").equals("active"))throw UserFailure.invalid();
                if(!sid.isBlank())users.session(actor,sid);
                if(profiles.getFirst().get("account_type").equals("student")){
                    if(grant.path("path").asText().contains("coach-"))controls.requireFeature(user,"coach");
                    if(grant.path("path").asText().contains("syllabus-"))controls.requireFeature(user,"syllabus");
                    String feature=grant.path("path").asText().contains("material-generate")?"materials":input.capability().matches("(?i)search|fetch")?"search":null;
                    if(feature!=null)controls.requireFeature(user,feature);
                    if(input.reservation()>0){
                        var balance=controls.pooledBalance(user);
                        if(((Number)balance.get("credited")).longValue()-((Number)balance.get("consumed")).longValue()-((Number)balance.get("reserved")).longValue()<input.reservation())throw new UserFailure(402,40240,"Token余额不足，请联系管理员充值");
                        controls.db().update("UPDATE model_balance SET reserved=reserved+? WHERE user_id=? AND model=?",input.reservation(),user,AdminControlService.SHARED_BALANCE);
                    }
                }else user=null; // 管理员不扣普通用户余额，仍在记录中保留归属。
            }
            Long recorded=actor.isBlank()?null:AdminControlService.id(actor);
            if(controls.db().queryForObject("SELECT COUNT(*) FROM ai_usage WHERE id=?",Integer.class,input.id())>0)throw new UserFailure(409,40940,"模型调用编号已使用");
            controls.db().update("INSERT INTO ai_usage(id,user_id,model,capability,reservation,status,trace_id) VALUES (?,?,?,?,?,'reserved',?)",input.id(),recorded,input.model(),input.capability(),user==null?0:input.reservation(),grant.path("trace").asText());
            return Map.of("id",input.id().toString());
        });
    }
    public void settle(Settle input){
        var grant=verify(input.grant());
        if(input.id()==null||!Set.of("ok","failed").contains(input.outcome())||input.inputTokens()!=null&&input.inputTokens()<0||input.outputTokens()!=null&&input.outputTokens()<0||(input.inputTokens()==null)!=(input.outputTokens()==null)||input.inputTokens()!=null&&(input.inputTokens()>1_000_000_000L||input.outputTokens()>1_000_000_000L))throw new IllegalArgumentException();
        settleRecord(input.id(),grant.path("user").asText(),grant.path("trace").asText(),input.inputTokens(),input.outputTokens(),input.outcome(),false);
    }
    void settleRecord(UUID call,String actor,String trace,Long input,Long output,String outcome,boolean manual){controls.tx(()->{
        // 与充值和预占保持用户→调用→余额的锁顺序。
        if(!actor.isBlank())controls.db().queryForList("SELECT id FROM app_user WHERE id=? FOR UPDATE",AdminControlService.id(actor));
        var rows=controls.db().queryForList("SELECT * FROM ai_usage WHERE id=? FOR UPDATE",call);if(rows.size()!=1)throw new UserFailure(404,40440,"用量记录不存在");var row=rows.getFirst();
        String owner=row.get("user_id")==null?"":row.get("user_id").toString();if(!owner.equals(actor)||!manual&&!row.get("trace_id").equals(trace))throw UserFailure.denied();
        if(row.get("status").equals("settled")){if(!Objects.equals(row.get("input_tokens"),input)||!Objects.equals(row.get("output_tokens"),output))throw new UserFailure(409,40940,"用量结算冲突");return null;}
        if(input==null){controls.db().update("UPDATE ai_usage SET status='unknown',outcome=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",outcome,call);return null;}
        long reserved=((Number)row.get("reservation")).longValue();
        if(reserved>0&&!actor.isBlank()){controls.pooledBalance(AdminControlService.id(actor));controls.db().update("UPDATE model_balance SET reserved=reserved-?,consumed=consumed+? WHERE user_id=? AND model=?",reserved,Math.addExact(input,output),AdminControlService.id(actor),AdminControlService.SHARED_BALANCE);}
        controls.db().update("UPDATE ai_usage SET input_tokens=?,output_tokens=?,status='settled',outcome=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",input,output,outcome,call);return null;
    });}
}
