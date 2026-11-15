package com.quest.api.admin;

import com.quest.api.auth.*;
import com.quest.api.auth.UserModels.SessionIdentity;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.function.Supplier;

@Service
public class AdminControlService {
    private final ObjectProvider<JdbcTemplate> jdbc;
    private final ObjectProvider<PlatformTransactionManager> managers;
    private final UserService users;
    public AdminControlService(ObjectProvider<JdbcTemplate> jdbc,ObjectProvider<PlatformTransactionManager> managers,UserService users){this.jdbc=jdbc;this.managers=managers;this.users=users;}
    JdbcTemplate db(){var value=jdbc.getIfAvailable();if(value==null)throw UserFailure.unavailable();return value;}
    <T>T tx(Supplier<T> task){return new TransactionTemplate(managers.getObject()).execute(s->task.get());}
    static long id(String value){if(value==null||!value.matches("[1-9][0-9]{0,18}"))throw new IllegalArgumentException();try{return Long.parseLong(value);}catch(NumberFormatException e){throw new IllegalArgumentException();}}
    void student(String value){if(db().queryForObject("SELECT COUNT(*) FROM app_user WHERE id=? AND account_type='student'",Integer.class,id(value))!=1)throw new UserFailure(404,40400,"用户不存在");}
    public void loginLog(Long actor,String username,String outcome,String source,String agent,String trace){
        if(actor==null&&username!=null&&username.length()<=64){var matches=db().queryForList("SELECT id FROM app_user WHERE username=?",Long.class,username);if(matches.size()==1)actor=matches.getFirst();}
        log(actor,username,"auth.login",outcome,null,source,agent,"",trace);
    }
    public void log(Long actor,String username,String action,String outcome,String target,String source,String agent,String detail,String trace){
        db().update("INSERT INTO account_event(actor,username,action,outcome,target_id,source,user_agent,detail,trace_id) VALUES (?,?,?,?,?,?,?,?,?)",actor,cut(username,64),cut(action,80),outcome,target,cut(source,200),cut(agent,500),cut(detail,1000),cut(trace,64));
    }
    static String cut(String value,int max){return value==null?"":value.substring(0,Math.min(value.length(),max)).replaceAll("[\\p{Cntrl}]"," ");}
    public void metric(Long actor,String method,String route,int status,long elapsed){db().update("INSERT INTO api_call_metric(actor,method,route,status,elapsed_ms) VALUES (?,?,?,?,?)",actor,cut(method,10),cut(route,200),status,elapsed);}
    public Map<String,Object> events(SessionIdentity who,String user,String kind,int page,int size){
        users.requireAdmin(who);page(page,size);
        String where=" WHERE 1=1";var args=new ArrayList<Object>();
        if(user!=null&&!user.isBlank()){where+=" AND (actor=? OR target_id=?)";args.add(id(user));args.add(user);}
        if(kind.equals("login"))where+=" AND action LIKE 'auth.%'";
        else if(kind.equals("operation"))where+=" AND action NOT LIKE 'auth.%'";
        else if(!kind.isBlank())throw new IllegalArgumentException();
        long total=db().queryForObject("SELECT COUNT(*) FROM account_event"+where,Long.class,args.toArray());
        args.add(size);args.add((long)(page-1)*size);
        var rows=db().queryForList("SELECT id,actor,username,action,outcome,target_id,source,user_agent,detail,trace_id,created_at FROM account_event"+where+" ORDER BY id DESC LIMIT ? OFFSET ?",args.toArray());
        return Map.of("list",wire(rows),"total",total,"page",page,"size",size);
    }
    static void page(int page,int size){if(page<1||size<1||size>100)throw new IllegalArgumentException();}
    static java.util.List<Map<String,Object>> wire(java.util.List<Map<String,Object>> source){
        return source.stream().map(row->{Map<String,Object> value=new LinkedHashMap<>();row.forEach((key,item)->{String name=key.toLowerCase(Locale.ROOT);value.put(name,item!=null&&Set.of("id","actor","user_id").contains(name)?item.toString():item);});return value;}).toList();
    }
    public Map<String,Object> controls(SessionIdentity who,String user){
        users.requireAdmin(who);student(user);
        return Map.of("usage",wire(db().queryForList("SELECT id,model,capability,reservation,input_tokens,output_tokens,status,outcome,created_at FROM ai_usage WHERE user_id=? ORDER BY created_at DESC LIMIT 100",id(user))),"features",features(id(user)),"models",wire(db().queryForList("SELECT model,credited,consumed,reserved,credited-consumed-reserved AS available FROM model_balance WHERE user_id=? ORDER BY model",id(user))));
    }
    public Map<String,Boolean> features(long user){
        var result=new LinkedHashMap<String,Boolean>();result.put("materials",false);result.put("coach",true);result.put("syllabus",true);result.put("search",true);
        for(var row:db().queryForList("SELECT feature,enabled FROM user_feature WHERE user_id=?",user))result.put((String)row.get("feature"),(Boolean)row.get("enabled"));
        return result;
    }
    public void requireFeature(long user,String feature){
        if(!Boolean.TRUE.equals(features(user).get(feature)))throw new UserFailure(403,40341,"此功能尚未开放，请联系管理员");
    }
    public void feature(SessionIdentity who,String user,String feature,boolean enabled,String trace){
        if(!Set.of("materials","coach","syllabus","search").contains(feature))throw new IllegalArgumentException();
        tx(()->{users.requireAdmin(who);student(user);db().queryForList("SELECT id FROM app_user WHERE id=? FOR UPDATE",id(user));
            if(db().update("UPDATE user_feature SET enabled=?,updated_at=CURRENT_TIMESTAMP WHERE user_id=? AND feature=?",enabled,id(user),feature)==0)db().update("INSERT INTO user_feature(user_id,feature,enabled) VALUES (?,?,?)",id(user),feature,enabled);
            log(id(who.user().id()),who.user().username(),"user.feature","ok",user,"","",feature+"="+enabled,trace);return null;});
    }
    static final String SHARED_BALANCE = "*";
    // 调用方必须持有用户行锁；旧模型余额并入统一池，保留历史充值与调用的模型信息。
    Map<String,Object> pooledBalance(long user){
        var rows=db().queryForList("SELECT model,credited,consumed,reserved FROM model_balance WHERE user_id=? FOR UPDATE",user);
        long credited=0,consumed=0,reserved=0;boolean exists=false;
        for(var row:rows){
            credited=Math.addExact(credited,((Number)row.get("credited")).longValue());
            consumed=Math.addExact(consumed,((Number)row.get("consumed")).longValue());
            reserved=Math.addExact(reserved,((Number)row.get("reserved")).longValue());
            if(SHARED_BALANCE.equals(row.get("model")))exists=true;
        }
        if(!exists)db().update("INSERT INTO model_balance(user_id,model) VALUES (?,?)",user,SHARED_BALANCE);
        db().update("UPDATE model_balance SET credited=0,consumed=0,reserved=0 WHERE user_id=? AND model<>?",user,SHARED_BALANCE);
        db().update("UPDATE model_balance SET credited=?,consumed=?,reserved=? WHERE user_id=? AND model=?",credited,consumed,reserved,user,SHARED_BALANCE);
        return Map.of("credited",credited,"consumed",consumed,"reserved",reserved);
    }
    public void credit(SessionIdentity who,String user,long amount,UUID request,String reason,String trace){
        if(amount<=0||amount>1_000_000_000_000L||request==null||reason!=null&&reason.length()>500)throw new IllegalArgumentException();
        String note=reason==null?"":reason.trim();
        tx(()->{users.requireAdmin(who);student(user);db().queryForList("SELECT id FROM app_user WHERE id=? FOR UPDATE",id(user));
            var old=db().queryForList("SELECT * FROM model_credit WHERE request_id=?",request);
            if(!old.isEmpty()){var row=old.getFirst();if(!row.get("user_id").toString().equals(user)||!row.get("model").equals(SHARED_BALANCE)||((Number)row.get("amount")).longValue()!=amount||!row.get("reason").equals(note))throw new UserFailure(409,40900,"充值请求冲突");return null;}
            pooledBalance(id(user));
            if(db().update("UPDATE model_balance SET credited=credited+? WHERE user_id=? AND model=? AND credited<=9000000000000-?",amount,id(user),SHARED_BALANCE,amount)!=1)throw new UserFailure(409,40941,"累计额度超出支持范围");
            db().update("INSERT INTO model_credit(request_id,user_id,model,amount,actor,reason) VALUES (?,?,?,?,?,?)",request,id(user),SHARED_BALANCE,amount,id(who.user().id()),note);
            log(id(who.user().id()),who.user().username(),"user.credit","ok",user,"","","统一额度 +"+amount+" token"+(note.isEmpty()?"":"；"+note),trace);return null;});
    }
    public Map<String,Object> statistics(SessionIdentity who,String user,String from,String to){
        users.requireAdmin(who);
        var start=java.time.LocalDate.parse(from).atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toOffsetDateTime();
        var end=java.time.LocalDate.parse(to).plusDays(1).atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toOffsetDateTime();
        if(!end.isAfter(start)||java.time.Duration.between(start,end).toDays()>366)throw new IllegalArgumentException("日期范围最多366天");
        String where=" WHERE created_at>=? AND created_at<?";var args=new ArrayList<Object>(List.of(start,end));
        String actor="";if(user!=null&&!user.isBlank()){actor=" AND actor=?";args.add(id(user));}
        var apis=db().queryForList("SELECT method,route,COUNT(*) AS calls,SUM(CASE WHEN status>=400 THEN 1 ELSE 0 END) AS failed,SUM(elapsed_ms) AS elapsed_ms FROM api_call_metric"+where+actor+" GROUP BY method,route ORDER BY calls DESC",args.toArray());
        var usage=db().queryForList("SELECT model,capability,COUNT(*) AS calls,SUM(CASE WHEN outcome='failed' THEN 1 ELSE 0 END) AS failed,SUM(COALESCE(input_tokens,0)) AS input_tokens,SUM(COALESCE(output_tokens,0)) AS output_tokens,SUM(CASE WHEN status<>'settled' THEN reservation ELSE 0 END) AS pending_tokens FROM ai_usage"+where+actor.replace("actor","user_id")+" GROUP BY model,capability ORDER BY model,capability",args.toArray());
        return Map.of("api",wire(apis),"ai",wire(usage),"from",from,"to",to);
    }
}
