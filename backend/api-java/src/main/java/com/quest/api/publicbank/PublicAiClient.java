package com.quest.api.publicbank;

import com.quest.api.auth.UserFailure;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Component
public class PublicAiClient {
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.quest.api.admin.ModelMeterService meter;
    private final ObjectMapper json;
    private final String base,token,secret;
    private final com.quest.api.coach.AiCallAudit audit;
    @Value("${quest.materials.ai-timeout:PT650S}")
    private String materialTimeout="PT650S";
    // 内部 Uvicorn 仅提供 HTTP/1.1，禁止默认 h2c Upgrade 探测。
    private final HttpClient http=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    public PublicAiClient(ObjectMapper json,@Value("${AI_SERVICE_URL:http://127.0.0.1:8000}") String base,
                          @Value("${INTERNAL_SERVICE_TOKEN:}") String token,@Value("${INTERNAL_HMAC_SECRET:}") String secret,
                          com.quest.api.coach.AiCallAudit audit){this.json=json;this.base=base;this.token=token;this.secret=secret;this.audit=audit;}
    /** actor 与 traceId 只用于审计归因，不参与签名。 */
    public record Caller(Long actor,String traceId){}
    private static final ThreadLocal<Caller> BACKGROUND=new ThreadLocal<>();
    public static <T>T asActor(Caller caller,java.util.function.Supplier<T> work){var prior=BACKGROUND.get();BACKGROUND.set(caller);try{return work.get();}finally{if(prior==null)BACKGROUND.remove();else BACKGROUND.set(prior);}}
    private Caller effective(Caller caller){
        if(caller!=null)return caller;if(BACKGROUND.get()!=null)return BACKGROUND.get();
        var attributes=org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if(attributes instanceof org.springframework.web.context.request.ServletRequestAttributes servlet&&servlet.getRequest().getAttribute(com.quest.api.auth.UserController.IDENTITY) instanceof com.quest.api.auth.UserModels.SessionIdentity who)return new Caller(Long.valueOf(who.user().id()),com.quest.api.common.TraceFilter.traceId(servlet.getRequest()));
        return null;
    }
    public JsonNode coachTurn(Object payload,Caller caller) { return post("/internal/coach-turn",payload,128*1024,caller); }
    public JsonNode coachRoute(Object payload,Caller caller) { return post("/internal/coach-route",payload,128*1024,caller); }
    public JsonNode coachTurn(Object payload) { return post("/internal/coach-turn",payload,128*1024,null); }
    public JsonNode extract(String text,Caller caller){return post("/internal/public-import",Map.of("scope","admin","text",text),256*1024,caller);}
    public JsonNode extractCandidate(String text,JsonNode candidate,Caller caller){return post("/internal/public-import",Map.of("scope","admin","text",text,"candidate",candidate),256*1024,caller);}
    public JsonNode extract(String text) { return post("/internal/public-import",Map.of("scope","admin","text",text),256*1024,null); }
    public JsonNode extractCandidate(String text,JsonNode candidate) { return post("/internal/public-import",Map.of("scope","admin","text",text,"candidate",candidate),256*1024,null); }
    public JsonNode inspectPdf(byte[] pdf) { return post("/internal/pdf-inspect",Map.of("scope","admin","pdf_base64",Base64.getEncoder().encodeToString(pdf)),2*1024*1024,null); }
    public JsonNode reviewSyllabus(String examType,JsonNode sections) { return post("/internal/syllabus-review",Map.of("scope","admin","examType",examType,"sections",sections),512*1024,null); }
    public JsonNode embedSyllabus(List<String> texts,String scope) { return post("/internal/syllabus-embed",Map.of("scope",scope,"texts",texts),4*1024*1024,null); }
    public JsonNode inspectSyllabus(byte[] pdf) { return post("/internal/syllabus-inspect",Map.of("scope","admin","pdf_base64",Base64.getEncoder().encodeToString(pdf)),6*1024*1024,null); }
    /** 本地排版，不调用模型；私人正文与PDF不进入审计原文。 */
    public byte[] materialPdf(String title,String markdown,Caller caller){
        long started=System.nanoTime();Throwable failure=null;byte[] pdf=null;
        try{
            var result=doPost("/internal/material-pdf",Map.of("title",title,"markdown",markdown),12*1024*1024,caller,"material.pdf_export",started,Duration.ofSeconds(60));
            try{pdf=Base64.getDecoder().decode(result.path("pdf_base64").asText());}
            catch(IllegalArgumentException invalid){throw new UserFailure(503,50242,"PDF排版返回无效文件");}
            if(pdf.length<5||pdf.length>8*1024*1024||!new String(pdf,0,5,StandardCharsets.US_ASCII).equals("%PDF-"))throw new UserFailure(503,50242,"PDF排版返回无效文件");
            return pdf;
        }catch(RuntimeException error){failure=error;throw error;}
        finally{
            var input=json.createObjectNode().put("bytes",markdown.getBytes(StandardCharsets.UTF_8).length).put("sha256",com.quest.api.materials.MaterialDigest.of(markdown.getBytes(StandardCharsets.UTF_8)));
            var output=json.createObjectNode();if(pdf!=null)output.put("bytes",pdf.length);
            audit.record(caller==null?null:caller.actor(),"material.pdf_export",null,caller==null?null:caller.traceId(),input,output,failure,elapsed(started),0,null);
        }
    }
    /** 私人资料采用独立长预算，审计仅记录摘要，不暴露整份私人上下文。 */
    public JsonNode generateMaterial(Object payload,Caller caller){
        long started=System.nanoTime();Throwable failure=null;JsonNode result=null;
        Duration timeout=org.springframework.boot.convert.DurationStyle.detectAndParse(materialTimeout);
        var control=new com.quest.api.coach.CoachStreamControl();
        // 资料总截止时间覆盖响应头和完整响应体；只关闭本次连接，不中断复用线程。
        var deadline=DEADLINES.schedule(control::close,timeout.toNanos(),java.util.concurrent.TimeUnit.NANOSECONDS);
        try{
            result=doPost("/internal/material-generate",payload,1024*1024,caller,"ai.material_generate",started,timeout,control);
            control.check();return result;
        }
        catch(RuntimeException error){
            var reported=control.cancelled()||System.nanoTime()-started>=timeout.toNanos()
                ?new UserFailure(504,50401,"模型整理超时，未自动重试"):error;
            failure=reported;throw reported;
        }
        finally{
            deadline.cancel(false);
            control.close();
            var input=json.createObjectNode().put("bytes",json.writeValueAsBytes(payload).length).put("sha256",com.quest.api.materials.MaterialDigest.of(json.writeValueAsBytes(payload)));
            var output=json.createObjectNode();if(result!=null){byte[] raw=json.writeValueAsBytes(result);output.put("bytes",raw.length).put("sha256",com.quest.api.materials.MaterialDigest.of(raw));}
            audit.record(caller==null?null:caller.actor(),"ai.material_generate",null,caller==null?null:caller.traceId(),input,output,failure,elapsed(started),1,null);
        }
    }
    private JsonNode post(String path,Object payload,int maxResponse){return post(path,payload,maxResponse,null);}
    private static final java.util.concurrent.ScheduledExecutorService DEADLINES=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"coach-stream-deadline");t.setDaemon(true);return t;});
    /** 有界读取内部 SSE，截止时间覆盖响应头及响应体；任何出口都记录一次审计。 */
    public JsonNode coachExplain(Object payload,Caller caller,java.util.function.Consumer<String> onDelta,java.util.function.Consumer<JsonNode> onStatus,com.quest.api.coach.CoachStreamControl control){
        control.onToolStatus(onStatus);return coachExplain(payload,caller,onDelta,control);
    }
    public JsonNode coachExplain(Object payload,Caller caller,java.util.function.Consumer<String> onDelta,com.quest.api.coach.CoachStreamControl control){
        return coachStream("/internal/coach-explain",payload,caller,onDelta,control,false);
    }
    public JsonNode coachWorkflow(Object payload,Caller caller,java.util.function.Consumer<String> onDelta,com.quest.api.coach.CoachStreamControl control){
        return coachStream("/internal/coach-workflow",payload,caller,onDelta,control,true);
    }
    private JsonNode coachStream(String path,Object payload,Caller caller,java.util.function.Consumer<String> onDelta,com.quest.api.coach.CoachStreamControl control,boolean workflow){
        long started=System.nanoTime();
        int timeout=workflow?135:95;String kind="";boolean workflowResult=false;
        StringBuilder text=new StringBuilder();JsonNode suggestions=json.createObjectNode().set("items",json.createArrayNode());
        JsonNode sources=json.createArrayNode();
        Throwable auditFailure=null;
        var deadline=DEADLINES.schedule(control::close,timeout,java.util.concurrent.TimeUnit.SECONDS);
        try{
            control.check();
            if(token.isBlank()||secret.length()<32)throw new UserFailure(503,50321,"内部AI服务未配置");
            URI root=URI.create(base);
            if(!Set.of("http","https").contains(root.getScheme())||root.getHost()==null||root.getUserInfo()!=null||root.getQuery()!=null||root.getFragment()!=null||(root.getPath()!=null&&!Set.of("","/").contains(root.getPath())))throw new IllegalArgumentException();
            byte[] body=json.writeValueAsBytes(payload);
            String stamp=Long.toString(Instant.now().getEpochSecond()),nonce=UUID.randomUUID().toString();
            String message=String.join("\n",stamp,nonce,"POST",path,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)));
            Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            var request=HttpRequest.newBuilder(root.resolve(path)).timeout(Duration.ofSeconds(timeout)).header("Content-Type","application/json")
                .header("X-Service-Token",token).header("X-Usage-Grant",meter==null?"":meter.issue(caller,path)).header("X-Timestamp",stamp).header("X-Nonce",nonce).header("X-Signature",HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8))))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            var response=http.send(request,HttpResponse.BodyHandlers.ofInputStream());
            try(var stream=response.body()){
                control.attach(stream);
                if(response.statusCode()!=200)throw new UserFailure(503,50200,"AI服务暂不可用");
                if(!response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT).startsWith("text/event-stream"))throw new UserFailure(503,50204,"AI服务流格式错误");
                var decoder=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT);
                var reader=new java.io.InputStreamReader(stream,decoder);
                String event="";StringBuilder line=new StringBuilder(),data=new StringBuilder();int total=0,toolAudits=0;boolean done=false;
                for(int ch;(ch=reader.read())!=-1;){
                    control.check();if(++total>128*1024)throw new UserFailure(503,50204,"AI服务流超出上限");
                    if(ch!='\n'){if(ch!='\r')line.append((char)ch);if(line.length()>32768)throw new UserFailure(503,50204,"AI服务事件超出上限");continue;}
                    String value=line.toString();line.setLength(0);
                    if(value.isEmpty()){
                        if(event.equals("error")){int code=json.readTree(data.toString()).path("code").asInt();if(code==40240)throw new UserFailure(402,40240,"该模型token余额不足，请联系管理员充值");if(code==40341)throw new UserFailure(403,40341,"此功能尚未开放，请联系管理员");throw new UserFailure(503,50203,"AI回答未完整生成");}
                        if(event.equals("done")){done=true;break;}
                        if(event.equals("delta")){
                            JsonNode parsed=json.readTree(data.toString());if(!parsed.path("text").isString())throw new UserFailure(503,50204,"AI服务流格式错误");
                            String chunk=parsed.path("text").asText();if(text.length()+chunk.length()>MAX_REPLY_CHARS)throw new UserFailure(503,50204,"AI回答超出上限");
                            text.append(chunk);onDelta.accept(chunk);
                        }else if(event.equals("suggestions")){
                            JsonNode parsed=json.readTree(data.toString());if(!parsed.path("items").isArray()||parsed.path("items").size()>4)throw new UserFailure(503,50204,"AI建议格式错误");suggestions=parsed;
                        }else if(event.equals("tool_status")){
                            JsonNode parsed=json.readTree(data.toString());
                            if(!Set.of("search_web","fetch_page","get_current_time").contains(parsed.path("tool").asText())||!Set.of("running","completed","failed").contains(parsed.path("status").asText()))throw new UserFailure(503,50204,"AI工具状态格式错误");
                            control.toolStatus(json.createObjectNode().put("tool",parsed.path("tool").asText()).put("status",parsed.path("status").asText()));
                        }else if(event.equals("tool_audit")){
                            if(++toolAudits>32)throw new UserFailure(503,50204,"AI工具审计超过上限");
                            JsonNode parsed=json.readTree(data.toString());
                            if(!Set.of("search_web","fetch_page","get_current_time").contains(parsed.path("tool").asText())||!parsed.path("parameter_hash").asText().matches("[0-9a-f]{64}")
                                ||!parsed.path("result_count").isInt()||parsed.path("result_count").asInt()<0||parsed.path("result_count").asInt()>3
                                ||!parsed.path("duration_ms").isIntegralNumber()||parsed.path("duration_ms").asLong()<0||parsed.path("duration_ms").asLong()>95000
                                ||!Set.of("ok","timeout","unavailable","cancelled").contains(parsed.path("failure").asText()))throw new UserFailure(503,50204,"AI工具审计格式错误");
                            var summary=json.createObjectNode().put("parameter_hash",parsed.path("parameter_hash").asText()).put("result_count",parsed.path("result_count").asInt()).put("failure",parsed.path("failure").asText());
                            audit.record(caller==null?null:caller.actor(),parsed.path("tool").asText().equals("get_current_time")?"ai.tool_time":parsed.path("tool").asText().equals("search_web")?"ai.tool_search":"ai.tool_fetch",null,caller==null?null:caller.traceId(),summary,summary,
                                parsed.path("failure").asText().equals("ok")?null:new UserFailure(503,50203,"工具调用失败"),parsed.path("duration_ms").asLong(),1,null);
                        }else if(workflow&&event.equals("workflow_state")){
                            if(!json.readTree(data.toString()).isObject())throw new UserFailure(503,50204,"编排状态格式错误");
                            control.workflowState();
                        }else if(workflow&&event.equals("workflow_result")){
                            String nextKind=json.readTree(data.toString()).path("kind").asText();
                            if(workflowResult||!Set.of("state_only","explain").contains(nextKind))throw new UserFailure(503,50204,"编排终态格式错误");
                            workflowResult=true;kind=nextKind;
                        }else if(event.equals("sources")){
                            sources=com.quest.api.coach.CoachSources.validate(json.readTree(data.toString()).path("items"),json);
                        }else if(!event.isEmpty())throw new UserFailure(503,50204,"未知AI事件");
                        event="";data.setLength(0);continue;
                    }
                    if(value.startsWith("event:"))event=value.substring(6).strip();
                    else if(value.startsWith("data:")){if(!data.isEmpty())data.append('\n');data.append(value.substring(5).stripLeading());if(data.length()>32768)throw new UserFailure(503,50204,"AI事件超出上限");}
                    else if(!value.startsWith(":"))throw new UserFailure(503,50204,"AI服务流格式错误");
                }
                if(!done||workflow&&(!workflowResult||kind.equals("state_only")&&!text.isEmpty())||text.toString().isBlank()&&(!workflow||!kind.equals("state_only")))throw new UserFailure(503,50204,"AI回答未完整返回");
                control.check();((tools.jackson.databind.node.ObjectNode)suggestions).set("sources",sources);if(workflow)((tools.jackson.databind.node.ObjectNode)suggestions).put("kind",kind);return suggestions;
            }finally{control.detach();}
        }catch(InterruptedException failure){auditFailure=failure;Thread.currentThread().interrupt();throw new UserFailure(503,50332,"本次生成已中断，未自动重试");}
        catch(RuntimeException failure){auditFailure=failure;throw failure;}
        catch(Exception failure){auditFailure=failure;throw new UserFailure(503,50322,"AI处理失败，未自动重试");}
        finally{
            deadline.cancel(false);
            // 中断标记不应阻止审计使用 JDBC；写完恢复，供上层取消流程识别。
            boolean interrupted=Thread.interrupted();
            try{
                JsonNode auditInput=json.valueToTree(payload);if(workflow&&auditInput.isObject())((tools.jackson.databind.node.ObjectNode)auditInput).remove("grant");
                audit.record(caller==null?null:caller.actor(),workflow?"ai.coach_workflow":"ai.coach_explain",null,caller==null?null:caller.traceId(),auditInput,json.createObjectNode().put("reply",text.toString()).set("suggestions",suggestions.path("items")),auditFailure,elapsed(started),1,null);}
            finally{if(interrupted)Thread.currentThread().interrupt();}
        }
    }
    private JsonNode post(String path,Object payload,int maxResponse,Caller caller) {
        long started=System.nanoTime();
        String action="ai."+path.replace("/internal/","").replace('/','_');
        try{return doPost(path,payload,maxResponse,caller,action,started);}
        catch(RuntimeException failure){audit.record(caller==null?null:caller.actor(),action,null,caller==null?null:caller.traceId(),payload,null,failure,elapsed(started),1,null);throw failure;}
    }
    private long elapsed(long started){return (System.nanoTime()-started)/1_000_000;}
    /** 后台批处理（按20段整理整份考纲）比对话慢得多，不能套用对话的短超时。 */
    private static final Duration BACKGROUND_TIMEOUT=Duration.ofSeconds(300);
    /** 多主体对比/长解析的正常篇幅会超过4000字，上限过紧会被误判为格式错误。 */
    private static final int MAX_REPLY_CHARS=12000;
    private JsonNode doPost(String path,Object payload,int maxResponse,Caller caller,String action,long started) {
        return doPost(path,payload,maxResponse,caller,action,started,path.startsWith("/internal/syllabus-review")?BACKGROUND_TIMEOUT:Duration.ofSeconds(100));
    }
    private JsonNode doPost(String path,Object payload,int maxResponse,Caller caller,String action,long started,Duration timeout) {
        return doPost(path,payload,maxResponse,caller,action,started,timeout,null);
    }
    private JsonNode doPost(String path,Object payload,int maxResponse,Caller caller,String action,long started,Duration timeout,com.quest.api.coach.CoachStreamControl control) {
        caller=effective(caller);
        if(token.isBlank() || secret.length()<32)throw new UserFailure(503,50321,"内部AI服务未配置");
        try {
            URI root=URI.create(base);
            if(!Set.of("http","https").contains(root.getScheme()) || root.getHost()==null || root.getUserInfo()!=null || root.getQuery()!=null || root.getFragment()!=null || (root.getPath()!=null&&!Set.of("","/").contains(root.getPath())))throw new IllegalArgumentException();
            byte[] body=json.writeValueAsBytes(payload);
            String stamp=Long.toString(Instant.now().getEpochSecond()),nonce=UUID.randomUUID().toString();
            String message=String.join("\n",stamp,nonce,"POST",path,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)));
            Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            var request=HttpRequest.newBuilder(root.resolve(path)).timeout(timeout).header("Content-Type","application/json")
                .header("X-Service-Token",token).header("X-Usage-Grant",meter==null?"":meter.issue(caller,path)).header("X-Timestamp",stamp).header("X-Nonce",nonce).header("X-Signature",HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8))))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            // 无应用级自动重试；故障不回显上游响应/URL/签名。
            var response=http.send(request,HttpResponse.BodyHandlers.ofInputStream());
            byte[] raw;
            try(var stream=response.body()){
                if(control!=null)control.attach(stream);
                raw=stream.readNBytes(maxResponse+1);
                if(control!=null)control.check();
            }finally{if(control!=null)control.detach();}
            if(raw.length>maxResponse)throw new IllegalStateException();
            // 非 200 时把上游业务码带出来，不能统一吞成一句话，否则无法定位。
            JsonNode envelope=json.readTree(raw);
            if(response.statusCode()!=200){
                String upstream=String.valueOf(envelope.path("code").asInt(0));
                if(upstream.equals("40240"))throw new UserFailure(402,40240,"该模型token余额不足，请联系管理员充值");
                if(upstream.equals("40341"))throw new UserFailure(403,40341,"此功能尚未开放，请联系管理员");
                throw new UserFailure(503,50200,"AI服务返回错误（上游码 "+upstream+"）");
            }
            if(!envelope.path("code").isIntegralNumber() || envelope.path("code").asInt()!=0 || !envelope.path("data").isObject())throw new IllegalStateException();
            JsonNode data=envelope.get("data");
            if(!Set.of("ai.material_generate","material.pdf_export").contains(action))audit.record(caller==null?null:caller.actor(),action,null,caller==null?null:caller.traceId(),payload,data,null,elapsed(started),1,null);
            return data;
        } catch(InterruptedException failure){Thread.currentThread().interrupt();throw new UserFailure(503,50322,"AI处理已中断，未自动重试");}
        // UserFailure 已带明确原因（例如上游业务码），不能被下面兜底覆盖成通用文案。
        catch(UserFailure failure){throw failure;}
        catch(Exception failure){throw new UserFailure(503,50322,"AI处理失败，请核对服务状态后人工处理");}
    }
}
