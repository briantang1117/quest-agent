package com.quest.api.syllabus;

import com.quest.api.auth.UserFailure;
import com.quest.api.publicbank.PublicAiClient;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** 持久化考纲任务；失败只允许人工重试，lease 过期不会重放不确定的付费调用。 */
@Component
public class SyllabusPipeline {
    private static final String FAILURE="考纲处理失败，请检查服务后人工重试";
    // 单批过大时模型一次要输出20段JSON，实测30秒内做不完；按段数和字数双限。
    private static final int REVIEW_BATCH_SECTIONS=8;
    private static final int REVIEW_BATCH_CHARS=12000;
    private static final String EXPIRED="处理已超时或中断，请人工确认后重试";
    private final ObjectProvider<JdbcTemplate> jdbc;
    private final ObjectProvider<PlatformTransactionManager> managers;
    private final PublicAiClient ai;
    private final ObjectMapper json;
    private final String mode;
    private final AtomicBoolean running=new AtomicBoolean();
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->daemon(r,"syllabus-worker"));
    private final ExecutorService calls=Executors.newSingleThreadExecutor(r->daemon(r,"syllabus-model"));
    private volatile boolean closed;
    private record Version(UUID id,String title,String exam,int year,String status,int revision,JsonNode sections,JsonNode warnings){}
    private record Job(UUID id,UUID version,String type,int revision,String status,UUID generation,UUID token,OffsetDateTime until,int indexed,int count,String model,Integer dimensions){}
    private record Embedded(String model,int dimensions,List<String> vectors){}
    public SyllabusPipeline(ObjectProvider<JdbcTemplate> jdbc,ObjectProvider<PlatformTransactionManager> managers,PublicAiClient ai,ObjectMapper json,@Value("${quest.persistence.mode:disabled}") String mode){this.jdbc=jdbc;this.managers=managers;this.ai=ai;this.json=json;this.mode=mode;}
    private static Thread daemon(Runnable r,String name){Thread t=new Thread(r,name);t.setDaemon(true);return t;}
    private JdbcTemplate db(){if(!"postgresql".equals(mode)||jdbc.getIfAvailable()==null)throw unavailable();return jdbc.getObject();}
    private <T>T tx(Supplier<T> action){return new TransactionTemplate(managers.getObject()).execute(s->action.get());}
    private boolean tables(){
        if(!"postgresql".equals(mode)||jdbc.getIfAvailable()==null)return false;
        try{return Boolean.TRUE.equals(db().execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>)connection->{
            for(String name:List.of("syllabus_job","syllabus_chunk","syllabus_pipeline_lock")){boolean found=false;try(var rows=connection.getMetaData().getTables(null,null,null,new String[]{"TABLE"})){while(rows.next())if(name.equalsIgnoreCase(rows.getString("TABLE_NAME"))){found=true;break;}}if(!found)return false;}return true;
        }));}catch(org.springframework.dao.DataAccessException e){return false;}
    }
    private boolean h2(){return Boolean.TRUE.equals(db().execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>)c->"H2".equals(c.getMetaData().getDatabaseProductName())));}
    /** 与上传/发布同事务；不在此处启动模型调用。type 为 review/index。 */
    public void enqueue(UUID versionId,String type,int revision){
        checkType(type);if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("考纲任务必须在调用者事务中排队");
        if(!tables())throw unavailable();Version v=version(versionId,true);if(v.revision()!=revision)throw conflict("考纲已变化，请刷新后重试");
        if(!allowedStatus(type,v.status()))throw conflict("当前考纲状态不能执行该任务");
        db().queryForObject("SELECT id FROM syllabus_pipeline_lock WHERE id=1 FOR UPDATE",Integer.class);
        if(active(versionId))throw conflict("考纲存在活动任务，请稍后重试");
        if(db().queryForObject("SELECT COUNT(*) FROM syllabus_job WHERE status IN ('queued','processing')",Integer.class)>=1000)throw conflict("考纲任务队列已满，请稍后重试");
        var old=jobs(versionId,type);UUID id=old.isEmpty()?UUID.randomUUID():old.getFirst().id();UUID generation=UUID.randomUUID();
        if(old.isEmpty())db().update("INSERT INTO syllabus_job(id,version_id,type,revision,status,generation) VALUES (?,?,?,?,'queued',?)",id,versionId,type,revision,generation);
        else db().update("UPDATE syllabus_job SET revision=?,status='queued',attempt=attempt+1,generation=?,lease_token=NULL,lease_until=NULL,in_flight=FALSE,indexed_count=0,chunk_count=0,embedding_model=NULL,embedding_dimensions=NULL,error=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=?",revision,generation,id);
    }
    public void retry(UUID id,String type,int revision){tx(()->{enqueue(id,type,revision);return null;});}
    public boolean active(UUID id){if(!tables())return false;return db().queryForObject("SELECT COUNT(*) FROM syllabus_job WHERE version_id=? AND status IN ('queued','processing')",Integer.class,id)>0;}
    public Map<String,Object> status(UUID id){
        var out=new LinkedHashMap<String,Object>();out.put("reviewStatus","not_started");out.put("indexStatus","not_started");out.put("reviewError",null);out.put("indexError",null);out.put("indexedCount",0);out.put("chunkCount",0);out.put("embeddingModel",null);out.put("embeddingDimensions",null);
        if(!tables())return out;
        db().query("SELECT * FROM syllabus_job WHERE version_id=?",rs->{String type=rs.getString("type");out.put(type+"Status",rs.getString("status"));out.put(type+"Error",rs.getString("error"));if(type.equals("index")){out.put("indexedCount",rs.getInt("indexed_count"));out.put("chunkCount",rs.getInt("chunk_count"));out.put("embeddingModel",rs.getString("embedding_model"));out.put("embeddingDimensions",rs.getObject("embedding_dimensions",Integer.class));}},id);return out;
    }
    /** 定时入口只扫描已迁移的真实 PG；H2 测试必须显式 processOne。 */
    @Scheduled(fixedDelay=3000)
    public void schedule(){if(closed||running.get()||!tables()||h2())return;try{worker.submit(this::processOne);}catch(RejectedExecutionException ignored){}}
    @PreDestroy public void close(){closed=true;worker.shutdownNow();calls.shutdownNow();}
    public void processOne(){
        if(closed||!running.compareAndSet(false,true))return;
        try{if(!tables())return;Job job=claim();if(job==null)return;
            try{if(job.type().equals("review"))review(job);else index(job);}
            catch(RuntimeException failure){fail(job,reason(failure));}
        }finally{running.set(false);}
    }
    private Job claim(){return tx(()->{
        db().queryForObject("SELECT id FROM syllabus_pipeline_lock WHERE id=1 FOR UPDATE",Integer.class);
        // 最保守恢复：无法证明崩溃前请求是否已经计费，过期 processing 固定失败，等待人工 retry。
        db().update("UPDATE syllabus_job SET status='failed',error=?,lease_token=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP WHERE status='processing' AND lease_until<CURRENT_TIMESTAMP",EXPIRED);
        if(db().queryForObject("SELECT COUNT(*) FROM syllabus_job WHERE status='processing'",Integer.class)>0)return null;
        var ids=db().query("SELECT id FROM syllabus_job WHERE status='queued' ORDER BY created_at,id LIMIT 1",(rs,n)->rs.getObject(1,UUID.class));if(ids.isEmpty())return null;
        UUID id=ids.getFirst(),token=UUID.randomUUID();db().update("UPDATE syllabus_job SET status='processing',lease_token=?,lease_until=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='queued'",token,OffsetDateTime.now(java.time.ZoneOffset.UTC).plusMinutes(10),id);
        return db().queryForObject("SELECT * FROM syllabus_job WHERE id=?",this::job,id);
    });}
    private void review(Job job){
        Version v=version(job.version(),false);guardVersion(v,job);JsonNode source=v.sections();if(!source.isArray()||source.size()>1000||json.writeValueAsString(source).length()>4_000_000)throw new IllegalArgumentException();
        var reviewed=json.createArrayNode();var issues=json.createArrayNode();int start=0,totalText=0;var ordinals=new HashSet<Integer>();
        for(JsonNode section:source){int length=section.path("text").asText().length();totalText+=length;if(length<1||length>12000||totalText>2_000_000||!section.path("ordinal").isIntegralNumber()||!ordinals.add(section.path("ordinal").asInt()))throw new IllegalArgumentException();}
        while(start<source.size()){
            var batch=json.createArrayNode();int length=0;while(start<source.size()&&batch.size()<REVIEW_BATCH_SECTIONS){JsonNode next=source.get(start);int size=next.path("text").asText().length();if(!batch.isEmpty()&&length+size>REVIEW_BATCH_CHARS)break;batch.add(next.deepCopy());length+=size;start++;}
            var request=(ArrayNode)batch.deepCopy();for(JsonNode section:request)((ObjectNode)section).put("enabled",false);
            JsonNode response=call(job,()->ai.reviewSyllabus(v.exam(),request));
            if(response==null||!response.path("sections").isArray()||response.path("sections").size()!=batch.size()||!response.path("issues").isArray()||response.path("issues").size()>100)throw new IllegalArgumentException();
            var suggestions=new HashMap<Integer,JsonNode>();for(JsonNode suggestion:response.path("sections")){int ordinal=suggestion.path("ordinal").asInt(-1);if(suggestions.put(ordinal,suggestion)!=null)throw new IllegalArgumentException();}
            for(JsonNode original:batch){JsonNode suggestion=suggestions.remove(original.path("ordinal").asInt());validateSuggestion(v.exam(),original,suggestion);var merged=(ObjectNode)original.deepCopy();for(String field:List.of("title","path","kind","scope"))merged.set(field,suggestion.get(field));reviewed.add(merged);}
            if(!suggestions.isEmpty())throw new IllegalArgumentException();for(JsonNode issue:response.path("issues")){if(!issue.isString()||issue.asText().length()>1000||issues.size()>=1000)throw new IllegalArgumentException();issues.add(issue);}
        }
        tx(()->{Version current=version(job.version(),true);guardVersion(current,job);guardLease(job);
            var warnings=json.createArrayNode();if(!current.warnings().isArray()||current.warnings().size()>1000)throw new IllegalArgumentException();for(JsonNode warning:current.warnings())warnings.add(warning);for(JsonNode issue:issues)if(warnings.size()<2000)warnings.add(issue);else throw new IllegalArgumentException();
            db().update("UPDATE syllabus_version SET sections_json=?,warnings_json=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND revision=? AND status='draft'",json.writeValueAsString(reviewed),json.writeValueAsString(warnings),job.version(),job.revision());ready(job);return null;
        });
    }
    private void validateSuggestion(String exam,JsonNode original,JsonNode value){
        if(value==null||!value.isObject())throw new IllegalArgumentException();
        for(String key:List.of("ordinal","text","pageStart","pageEnd","printedPages"))if(!original.path(key).equals(value.path(key)))throw new IllegalArgumentException();
        if(!value.path("enabled").isBoolean()||value.path("enabled").asBoolean())throw new IllegalArgumentException();
        for(String field:List.of("title","path","kind","scope"))if(!value.path(field).isString())throw new IllegalArgumentException();
        if(value.path("title").asText().isBlank()||value.path("title").asText().length()>300||value.path("path").asText().length()>1000||!Set.of("overview","requirement","example","other").contains(value.path("kind").asText())||!Set.of("excluded",SyllabusChunks.subject(exam)).contains(value.path("scope").asText()))throw new IllegalArgumentException();
    }
    private void index(Job job){
        Version v=version(job.version(),false);guardVersion(v,job);var chunks=SyllabusChunks.split(v.sections(),v.exam());
        tx(()->{guardVersion(version(job.version(),true),job);guardLease(job);
            // 上次完整向量仍保留用于同版本缓存，本 generation 从确定性切片重新开始。
            db().update("DELETE FROM syllabus_chunk WHERE version_id=? AND generation=?",job.version(),job.generation());
            // 失败后最多保留一代已成功缓存，避免反复人工重试积累无界行数。
            var cacheGenerations=db().query("SELECT generation FROM syllabus_chunk WHERE version_id=? AND embedding IS NOT NULL GROUP BY generation ORDER BY COUNT(*) DESC,MAX(created_at) DESC LIMIT 1",(rs,n)->rs.getObject(1,UUID.class),job.version());
            if(cacheGenerations.isEmpty())db().update("DELETE FROM syllabus_chunk WHERE version_id=?",job.version());
            else db().update("DELETE FROM syllabus_chunk WHERE version_id=? AND generation<>?",job.version(),cacheGenerations.getFirst());
            for(var chunk:chunks)db().update("INSERT INTO syllabus_chunk(id,version_id,generation,revision,chunk_no,section_ordinal,subject,enabled,section_title,section_path,page_start,page_end,printed_pages,text,content_hash) VALUES (?,?,?,?,?,?,?,TRUE,?,?,?,?,?,?,?)",UUID.randomUUID(),job.version(),job.generation(),job.revision(),chunk.number(),chunk.ordinal(),chunk.subject(),chunk.title(),chunk.path(),chunk.pageStart(),chunk.pageEnd(),chunk.printedPages(),chunk.text(),chunk.hash());
            db().update("UPDATE syllabus_job SET chunk_count=? WHERE id=? AND lease_token=?",chunks.size(),job.id(),job.token());return null;
        });
        String model=null;int dimensions=0;int cursor=0;
        boolean hasCache=db().queryForObject("SELECT COUNT(*) FROM syllabus_chunk WHERE version_id=? AND embedding IS NOT NULL AND generation<>?",Integer.class,job.version(),job.generation())>0;
        while(cursor<chunks.size()){
            var batch=new ArrayList<SyllabusChunks.Chunk>();int budget=0;
            // 不知道供应商当前配置时必须真实探测，不能把旧 model 当成新配置。
            int batchLimit=model==null&&hasCache?1:8;
            while(cursor<chunks.size()&&batch.size()<batchLimit){
                var chunk=chunks.get(cursor);
                if(model!=null){var cached=db().query("SELECT embedding FROM syllabus_chunk WHERE version_id=? AND content_hash=? AND embedding_model=? AND embedding_dimensions=? AND embedding IS NOT NULL AND generation<>? ORDER BY created_at DESC LIMIT 1",(rs,n)->rs.getString(1),job.version(),chunk.hash(),model,dimensions,job.generation());
                    if(!cached.isEmpty()){String vector=cached.getFirst();SyllabusChunks.vector(json.readTree(vector),dimensions);saveVectors(job,List.of(chunk),new Embedded(model,dimensions,List.of(vector)));cursor++;continue;}}
                if(budget+chunk.text().length()>12000)break;batch.add(chunk);budget+=chunk.text().length();cursor++;
            }
            if(batch.isEmpty())continue;var requested=List.copyOf(batch);Embedded embedded=embed(call(job,()->ai.embedSyllabus(requested.stream().map(SyllabusChunks.Chunk::text).toList(),"admin")),batch.size());
            if(model!=null&&(!model.equals(embedded.model())||dimensions!=embedded.dimensions()))throw new IllegalArgumentException();model=embedded.model();dimensions=embedded.dimensions();saveVectors(job,batch,embedded);
        }
        tx(()->{guardVersion(version(job.version(),true),job);guardLease(job);int count=db().queryForObject("SELECT COUNT(*) FROM syllabus_chunk WHERE version_id=? AND generation=? AND embedding_model=? AND embedding_dimensions=? AND embedding IS NOT NULL",Integer.class,job.version(),job.generation(),latest(job.id()).model(),latest(job.id()).dimensions());if(count!=chunks.size())throw new IllegalArgumentException();ready(job);
            // 仅在新 generation 完整就绪的同一事务回收旧索引。
            db().update("DELETE FROM syllabus_chunk WHERE version_id=? AND generation<>?",job.version(),job.generation());return null;
        });
    }
    private void saveVectors(Job job,List<SyllabusChunks.Chunk> chunks,Embedded embedded){tx(()->{
        guardVersion(version(job.version(),true),job);guardLease(job);String placeholder=h2()?"?":"CAST(? AS vector)";
        for(int i=0;i<chunks.size();i++)db().update("UPDATE syllabus_chunk SET embedding="+placeholder+",embedding_model=?,embedding_dimensions=? WHERE version_id=? AND generation=? AND chunk_no=?",embedded.vectors().get(i),embedded.model(),embedded.dimensions(),job.version(),job.generation(),chunks.get(i).number());
        int count=db().queryForObject("SELECT COUNT(*) FROM syllabus_chunk WHERE version_id=? AND generation=? AND embedding IS NOT NULL",Integer.class,job.version(),job.generation());
        db().update("UPDATE syllabus_job SET indexed_count=?,embedding_model=?,embedding_dimensions=?,in_flight=FALSE,updated_at=CURRENT_TIMESTAMP WHERE id=? AND lease_token=?",count,embedded.model(),embedded.dimensions(),job.id(),job.token());return null;
    });}
    private Embedded embed(JsonNode response,int count){
        if(response==null||!response.path("model").isString()||response.path("model").asText().isBlank()||response.path("model").asText().length()>300||!response.path("dimensions").isIntegralNumber()||!response.path("vectors").isArray()||response.path("vectors").size()!=count)throw new IllegalArgumentException();int dimensions=response.path("dimensions").asInt();var vectors=new ArrayList<String>();for(JsonNode vector:response.path("vectors")){SyllabusChunks.vector(vector,dimensions);vectors.add(json.writeValueAsString(vector));}return new Embedded(response.path("model").asText(),dimensions,vectors);
    }
    private JsonNode call(Job job,Supplier<JsonNode> action){
        tx(()->{guardVersion(version(job.version(),true),job);guardLease(job);db().update("UPDATE syllabus_job SET in_flight=TRUE WHERE id=? AND lease_token=?",job.id(),job.token());return null;});
        long remaining=java.time.Duration.between(OffsetDateTime.now(java.time.ZoneOffset.UTC),job.until()).toMillis();if(remaining<=0)throw new IllegalStateException();Long actor=db().queryForObject("SELECT created_by FROM syllabus_version WHERE id=?",Long.class,job.version());Future<JsonNode> result=calls.submit(()->com.quest.api.publicbank.PublicAiClient.asActor(new com.quest.api.publicbank.PublicAiClient.Caller(actor,job.id().toString()),action));
        try{JsonNode value=result.get(Math.min(110_000,remaining),TimeUnit.MILLISECONDS);tx(()->{guardLease(job);db().update("UPDATE syllabus_job SET in_flight=FALSE WHERE id=? AND lease_token=?",job.id(),job.token());return null;});return value;}
        catch(InterruptedException e){result.cancel(true);Thread.currentThread().interrupt();throw new IllegalStateException();}
        // 保留 cause，否则上游超时/配置错误的真实原因无法写进失败状态。
        catch(ExecutionException e){result.cancel(true);throw new IllegalStateException("上游调用失败",e.getCause());}
        catch(TimeoutException e){result.cancel(true);throw new IllegalStateException("上游调用超时",e);}
    }
    /** null 仅代表没有 ready 索引；供应商故障由调用者显式标记后降级，不能静默伪称成功。 */
    public JsonNode retrieve(UUID id,String subject,String query,boolean snake){
        if(!Set.of("vocational_aptitude","administrative_aptitude").contains(subject)||query==null||query.isBlank()||query.length()>3000)throw new IllegalArgumentException();if(!tables())return null;
        Version version=version(id,false);if(version.status().equals("draft")||!SyllabusChunks.subject(version.exam()).equals(subject))return json.createArrayNode();
        var found=jobs(id,"index");if(found.isEmpty()||!found.getFirst().status().equals("ready"))return null;Job job=found.getFirst();
        // 归档只改变生命周期，不改章节，因此历史绑定允许读取原已发布 generation。
        if(job.revision()!=version.revision()&&!version.status().equals("archived"))return null;
        Embedded queryVector;try{queryVector=embed(ai.embedSyllabus(List.of(query),"student"),1);}catch(UserFailure e){throw e;}catch(RuntimeException e){throw new UserFailure(503,50342,"考纲向量查询失败，可使用关键词检索");}
        if(!queryVector.model().equals(job.model())||!Objects.equals(queryVector.dimensions(),job.dimensions()))throw new UserFailure(409,40942,"考纲向量模型或维度已变化，请管理员重建索引");
        double[] target=SyllabusChunks.vector(json.readTree(queryVector.vectors().getFirst()),queryVector.dimensions());
        record Hit(double score,int number,int ordinal,String title,int start,int end,String printed,String text){}
        // 严格先限定版本/科目/model/dim/generation，再在 Java 精确计算余弦，首版最多 5000 候选。
        var candidates=db().query("SELECT * FROM syllabus_chunk WHERE version_id=? AND generation=? AND subject=? AND enabled=TRUE AND embedding_model=? AND embedding_dimensions=? AND embedding IS NOT NULL ORDER BY chunk_no LIMIT 5001",(rs,n)->new Hit(SyllabusChunks.cosine(target,SyllabusChunks.vector(json.readTree(rs.getString("embedding")),job.dimensions())),rs.getInt("chunk_no"),rs.getInt("section_ordinal"),rs.getString("section_title"),rs.getInt("page_start"),rs.getInt("page_end"),rs.getString("printed_pages"),rs.getString("text")),id,job.generation(),subject,job.model(),job.dimensions());
        if(candidates.size()>5000)throw unavailable();candidates.sort(Comparator.comparingDouble(Hit::score).reversed().thenComparingInt(Hit::number));
        // 查询期间若 generation 改变，拒绝把旧向量结果冒充新索引。
        Job current=latest(job.id());if(!current.status().equals("ready")||!current.generation().equals(job.generation()))return null;
        var out=json.createArrayNode();int budget=12000;for(Hit hit:candidates){if(out.size()>=8||budget<hit.text().length())break;var item=out.addObject();item.put(snake?"version_id":"versionId",id.toString());item.put("title",version.title());item.put(snake?"exam_year":"examYear",version.year());item.put(snake?"section_title":"sectionTitle",hit.title());item.put(snake?"page_start":"pageStart",hit.start());item.put(snake?"page_end":"pageEnd",hit.end());if(!snake){item.put("printedPages",hit.printed());item.put("sectionOrdinal",hit.ordinal());}item.put("text",hit.text());budget-=hit.text().length();}return out;
    }
    private Version version(UUID id,boolean lock){var rows=db().query("SELECT * FROM syllabus_version WHERE id=?"+(lock?" FOR UPDATE":""),(rs,n)->new Version(rs.getObject("id",UUID.class),rs.getString("title"),rs.getString("exam_type"),rs.getInt("exam_year"),rs.getString("status"),rs.getInt("revision"),json.readTree(rs.getString("sections_json")),json.readTree(rs.getString("warnings_json"))),id);if(rows.isEmpty())throw new UserFailure(404,40440,"考纲不存在");return rows.getFirst();}
    private List<Job> jobs(UUID id,String type){return db().query("SELECT * FROM syllabus_job WHERE version_id=? AND type=?",this::job,id,type);}
    private Job latest(UUID id){return db().queryForObject("SELECT * FROM syllabus_job WHERE id=?",this::job,id);}
    private Job job(ResultSet rs,int n)throws SQLException{return new Job(rs.getObject("id",UUID.class),rs.getObject("version_id",UUID.class),rs.getString("type"),rs.getInt("revision"),rs.getString("status"),rs.getObject("generation",UUID.class),rs.getObject("lease_token",UUID.class),rs.getObject("lease_until",OffsetDateTime.class),rs.getInt("indexed_count"),rs.getInt("chunk_count"),rs.getString("embedding_model"),rs.getObject("embedding_dimensions",Integer.class));}
    private static boolean allowedStatus(String type,String status){return type.equals("review")?status.equals("draft"):Set.of("published","archived").contains(status);}
    private void guardVersion(Version version,Job job){if(version.revision()!=job.revision()||!allowedStatus(job.type(),version.status()))throw conflict("考纲已变化");}
    private void guardLease(Job job){var current=db().queryForObject("SELECT * FROM syllabus_job WHERE id=? FOR UPDATE",this::job,job.id());if(current==null||!current.status().equals("processing")||!Objects.equals(current.token(),job.token())||!current.generation().equals(job.generation())||current.until()==null||!current.until().isAfter(OffsetDateTime.now(java.time.ZoneOffset.UTC)))throw new IllegalStateException();}
    private void ready(Job job){if(db().update("UPDATE syllabus_job SET status='ready',error=NULL,in_flight=FALSE,lease_token=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='processing' AND lease_token=?",job.id(),job.token())!=1)throw new IllegalStateException();}
    /** 失败原因要能指导操作：区分超时、配置缺失、上游异常，不泄露原文或密钥。 */
    private static String reason(Throwable failure){
        // call() 会把上游失败包成 IllegalStateException；沿 cause 链找原始原因。
        if(failure instanceof IllegalStateException && failure.getCause()!=null)failure=failure.getCause();
        for(Throwable cause=failure;cause!=null;cause=cause.getCause()){
            if(cause instanceof UserFailure userFailure){
                int code=userFailure.code();
                if(code==50401)return "模型响应超时（后台批处理允许较长耗时，可重试或减少单次章节数）";
                if(code==50321)return "内部AI服务未配置（检查服务令牌与HMAC密钥）";
                if(code==50302)return "文本模型未配置，无法执行AI整理";
                if(code==50203||code==50201)return "模型返回内容不符合要求，已保留原文章节，可重试";
                if(code==50200)return "AI服务返回错误，请检查Python服务日志";
            }
        }
        String type=failure.getClass().getSimpleName();
        return "考纲处理失败（"+type+"），请检查服务后人工重试";
    }
    private void fail(Job job,String message){db().update("UPDATE syllabus_job SET status='failed',error=?,lease_token=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='processing' AND lease_token=?",message,job.id(),job.token());}
    private static void checkType(String type){if(!Set.of("review","index").contains(type))throw new IllegalArgumentException();}
    private static UserFailure unavailable(){return new UserFailure(503,50340,"考纲任务服务未就绪，请核对迁移状态");}
    private static UserFailure conflict(String text){return new UserFailure(409,40940,text);}
}
