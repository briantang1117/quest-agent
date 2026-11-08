package com.quest.api.publicbank;

import com.quest.api.auth.UserFailure;
import com.quest.api.auth.UserModels.SessionIdentity;
import com.quest.api.auth.UserService;
import com.quest.common.question.Question;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Supplier;

@Service
public class PublicBankService {
    public record Bank(String id, String name, String examType, String subject, OffsetDateTime createdAt) {}
    public record Task(String id, String bankId, String status, String errorMessage, String questionId, OffsetDateTime createdAt, String questionStatus) {}
    public record Review(String id, String bankId, String taskId, String importedBy, String status, int version,
                         String sourceText, JsonNode draft, JsonNode evidence, String confirmedBy, OffsetDateTime confirmedAt) {}
    public record Page<T>(List<T> list, long total, int page, int size) {}
    private final ObjectProvider<JdbcTemplate> jdbc;
    private final ObjectProvider<PlatformTransactionManager> managers;
    private final UserService users;
    private final ObjectMapper json;
    private final String mode;
    public PublicBankService(ObjectProvider<JdbcTemplate> jdbc, ObjectProvider<PlatformTransactionManager> managers,
                             UserService users, ObjectMapper json, @Value("${quest.persistence.mode:disabled}") String mode) {
        this.jdbc=jdbc; this.managers=managers; this.users=users; this.json=json; this.mode=mode;
    }
    private JdbcTemplate db() {
        if (!mode.equals("postgresql") || jdbc.getIfAvailable()==null) throw unavailable();
        return jdbc.getObject();
    }
    private <T> T tx(SessionIdentity identity, Supplier<T> work) {
        db();
        var manager=managers.getIfAvailable();
        if(manager==null)throw unavailable();
        try {
            return new TransactionTemplate(manager).execute(status -> {
                var current=users.session(identity.user().id(),identity.sessionId());
                if(!current.user().role().equals("admin") || current.user().mustChangePassword())throw UserFailure.denied();
                return work.get();
            });
        } catch(DataAccessException failure) { throw unavailable(); }
    }
    public Page<Bank> list(SessionIdentity who,int page,int size,String keyword,String examType) {
        pagination(page,size);
        if(keyword==null || keyword.length()>128 || examType==null || !Set.of("","career_a","shanghai_civil").contains(examType))throw new IllegalArgumentException();
        return tx(who,()->{
            String clause=" FROM public_bank WHERE 1=1";
            List<Object> args=new ArrayList<>();
            if(!keyword.isBlank()){
                clause+=" AND LOWER(name) LIKE ? ESCAPE '!'";
                args.add("%"+keyword.strip().toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%");
            }
            if(!examType.isEmpty()){clause+=" AND exam_type=?";args.add(examType);}
            long total=db().queryForObject("SELECT COUNT(*)"+clause,Long.class,args.toArray());
            args.add(size);args.add((long)(page-1)*size);
            var rows=db().query("SELECT *"+clause+" ORDER BY created_at DESC,id LIMIT ? OFFSET ?",(rs,n)->new Bank(rs.getString("id"),rs.getString("name"),rs.getString("exam_type"),rs.getString("subject"),rs.getObject("created_at",OffsetDateTime.class)),args.toArray());
            return new Page<>(rows,total,page,size);
        });
    }
    public Bank create(SessionIdentity who,String name,String exam,String subject) {
        if(name==null || name.isBlank() || name.strip().length()>128)throw new IllegalArgumentException();
        if(!(("career_a".equals(exam)&&"vocational_aptitude".equals(subject)) || ("shanghai_civil".equals(exam)&&"administrative_aptitude".equals(subject))))throw new IllegalArgumentException();
        return tx(who,()->{
            UUID id=UUID.randomUUID();
            db().update("INSERT INTO public_bank(id,name,exam_type,subject,created_by) VALUES (?,?,?,?,?)",id,name.strip(),exam,subject,Long.parseLong(who.user().id()));
            return bank(id);
        });
    }
    public Bank detail(SessionIdentity who,String id) { return tx(who,()->bank(uuid(id))); }
    private Bank bank(UUID id) {
        var rows=db().query("SELECT * FROM public_bank WHERE id=?",(rs,n)->new Bank(rs.getString("id"),rs.getString("name"),rs.getString("exam_type"),rs.getString("subject"),rs.getObject("created_at",OffsetDateTime.class)),id);
        if(rows.isEmpty())throw missing();return rows.getFirst();
    }
    public Task submit(SessionIdentity who,String bankId,String text) {
        if(text==null || text.isBlank() || text.length()>12000)throw new IllegalArgumentException();
        UUID bank=uuid(bankId);
        return tx(who,()->{
            bank(bank); UUID id=UUID.randomUUID();
            db().update("INSERT INTO public_import_task(id,bank_id,created_by,source_text,status) VALUES (?,?,?,?,'queued')",id,bank,Long.parseLong(who.user().id()),text.strip());
            return task(id);
        });
    }
    public record Document(String id,String bankId,String sourceName,String status,int candidateCount,JsonNode report,OffsetDateTime createdAt,int totalCount,int queuedCount,int processingCount,int reviewingCount,int failedCount,int confirmedCount,int publishedCount,int deletedCount) {}
    public record SourceFile(byte[] bytes) {}
    public record QuestionRow(String id,String status,int version,Integer questionNo,JsonNode draft,OffsetDateTime updatedAt) {}
    static JsonNode numberedDraft(JsonNode draft,JsonNode evidence) { return numberedDraft(draft,evidence,true); }
    private static JsonNode numberedDraft(JsonNode draft,JsonNode evidence,boolean initialVersion) {
        var copy=(tools.jackson.databind.node.ObjectNode)draft.deepCopy();
        JsonNode source=copy.path("source");
        if(source.isMissingNode()||source.isNull())source=copy.putObject("source");
        if(source.isObject()&&(initialVersion||source.path("question_no").isMissingNode())){
            JsonNode original=evidence.path("provenance").path("question_no");
            if(original.isMissingNode())original=evidence.path("source_candidate").path("question_no");
            if(original.isIntegralNumber()&&original.canConvertToInt()&&original.asInt()>0)((tools.jackson.databind.node.ObjectNode)source).set("question_no",original);
            else ((tools.jackson.databind.node.ObjectNode)source).putNull("question_no");
        }
        return copy;
    }
    private QuestionRow questionRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        JsonNode draft=numberedDraft(json.readTree(rs.getString("draft_json")),json.readTree(rs.getString("evidence_json")),rs.getInt("version")==1);
        JsonNode no=draft.path("source").path("question_no");
        return new QuestionRow(rs.getString("id"),rs.getString("status"),rs.getInt("version"),no.isIntegralNumber()?no.asInt():null,draft,rs.getObject("updated_at",OffsetDateTime.class));
    }
    public Page<QuestionRow> questions(SessionIdentity who,String bankId,int page,int size,String keyword,String status) {
        pagination(page,size);UUID key=uuid(bankId);
        if(keyword==null||keyword.length()>128||status==null||!Set.of("","draft","confirmed","published").contains(status))throw new IllegalArgumentException();
        return tx(who,()->{
            bank(key);
            String clause=" FROM public_question WHERE bank_id=? AND deleted_at IS NULL";
            List<Object> args=new ArrayList<>();args.add(key);
            if(!status.isEmpty()){clause+=" AND status=?";args.add(status);}
            String match=keyword.strip().toLowerCase(Locale.ROOT);
            // 单库是一份试卷；先统一旧来源题号，再按真实题干匹配/数字排序，最后分页。
            // 不在JSON全文LIKE，避免选项/答案/键名命中；百分号和下划线按普通字符处理。
            var rows=db().query("SELECT id,status,version,draft_json,evidence_json,updated_at"+clause,(rs,n)->questionRow(rs),args.toArray()).stream()
                .filter(row->match.isEmpty()||row.draft().path("stem_md").asText().toLowerCase(Locale.ROOT).contains(match))
                .sorted(Comparator.comparing(QuestionRow::questionNo,Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(QuestionRow::id))
                .toList();
            long offset=(long)(page-1)*size;
            int start=(int)Math.min(offset,rows.size());int end=(int)Math.min(offset+size,rows.size());
            return new Page<>(rows.subList(start,end),rows.size(),page,size);
        });
    }
    public record QuestionSummary(long totalCount,long draftCount,long confirmedCount,long publishedCount) {}
    public QuestionSummary questionSummary(SessionIdentity who,String bankId){
        UUID key=uuid(bankId);
        return tx(who,()->{
            bank(key);Map<String,Long> counts=new HashMap<>();
            db().query("SELECT status,COUNT(*) AS count FROM public_question WHERE bank_id=? AND deleted_at IS NULL GROUP BY status",rs->{counts.put(rs.getString("status"),rs.getLong("count"));},key);
            return new QuestionSummary(counts.values().stream().mapToLong(Long::longValue).sum(),counts.getOrDefault("draft",0L),counts.getOrDefault("confirmed",0L),counts.getOrDefault("published",0L));
        });
    }
    public Document importPdf(SessionIdentity who,String bankId,String filename,byte[] bytes,PublicAiClient ai) {
        UUID key=uuid(bankId);
        if(bytes==null||bytes.length<5||bytes.length>20*1024*1024||!new String(bytes,0,5,java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-"))throw new IllegalArgumentException("Invalid PDF");
        tx(who,()->bank(key));
        // 解析是离线子进程预检，不持有数据库锁等待网络，不调用付费模型。
        JsonNode report=ai.inspectPdf(bytes);
        JsonNode candidates=report.path("candidates");
        if(!candidates.isArray()||candidates.size()>100)throw new UserFailure(502,50220,"PDF预检结果无效");
        for(JsonNode candidate:candidates)if(!candidate.path("source_text").isString()||candidate.path("source_text").asText().isBlank()||candidate.path("source_text").asText().length()>12000)throw new UserFailure(422,42220,"PDF题目超限或内容不完整，请拆分文件");
        String name=filename==null?"document.pdf":filename.replace('\\','/');name=name.substring(name.lastIndexOf('/')+1).replaceAll("[\\p{Cntrl}]","");if(name.isBlank()||name.length()>256)name="document.pdf";
        final String safeName=name;
        final String hash;
        try{hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException();}
        return tx(who,()->{
            bank(key);UUID document=UUID.randomUUID();
            String state=candidates.isEmpty()?"needs_attention":"staged";
            db().update("INSERT INTO public_import_document(id,bank_id,created_by,source_name,sha256,file_bytes,report_json,candidate_count,status) VALUES (?,?,?,?,?,?,?,?,?)",document,key,Long.parseLong(who.user().id()),safeName,hash,bytes,json.writeValueAsString(report),candidates.size(),state);
            int seq=0;
            for(JsonNode candidate:candidates)db().update("INSERT INTO public_import_task(id,bank_id,created_by,source_text,status,document_id,candidate_json,seq) VALUES (?,?,?,?,'queued',?,?,?)",UUID.randomUUID(),key,Long.parseLong(who.user().id()),candidate.path("source_text").asText(),document,json.writeValueAsString(candidate),++seq);
            return document(document,false);
        });
    }
    private Document document(UUID id,boolean lock){
        Map<String,Integer> counts=new HashMap<>();
        db().query("SELECT CASE WHEN q.deleted_at IS NOT NULL THEN 'deleted' WHEN t.status='reviewing' AND q.status='confirmed' THEN 'confirmed' WHEN t.status='reviewing' AND q.status='published' THEN 'published' ELSE t.status END AS display_status,COUNT(*) AS count FROM public_import_task t LEFT JOIN public_question q ON q.task_id=t.id WHERE t.document_id=? GROUP BY display_status",rs->{counts.put(rs.getString("display_status"),rs.getInt("count"));},id);
        var rows=db().query("SELECT id,bank_id,source_name,status,candidate_count,report_json,created_at FROM public_import_document WHERE id=?"+(lock?" FOR UPDATE":""),(rs,n)->new Document(rs.getString("id"),rs.getString("bank_id"),rs.getString("source_name"),rs.getString("status"),rs.getInt("candidate_count"),json.readTree(rs.getString("report_json")),rs.getObject("created_at",OffsetDateTime.class),counts.values().stream().mapToInt(Integer::intValue).sum(),counts.getOrDefault("queued",0),counts.getOrDefault("processing",0),counts.getOrDefault("reviewing",0),counts.getOrDefault("failed",0),counts.getOrDefault("confirmed",0),counts.getOrDefault("published",0),counts.getOrDefault("deleted",0)),id);if(rows.isEmpty())throw missing();return rows.getFirst();
    }
    public Page<Document> documents(SessionIdentity who,String bankId,int page,int size){pagination(page,size);UUID key=uuid(bankId);return tx(who,()->{bank(key);return new Page<>(db().query("SELECT id FROM public_import_document WHERE bank_id=? ORDER BY created_at DESC,id LIMIT ? OFFSET ?",(rs,n)->rs.getObject("id",UUID.class),key,size,(long)(page-1)*size).stream().map(id->document(id,false)).toList(),db().queryForObject("SELECT COUNT(*) FROM public_import_document WHERE bank_id=?",Long.class,key),page,size);});}
    public Document startDocument(SessionIdentity who,String id){return tx(who,()->{UUID key=uuid(id);Document item=document(key,true);Long importer=db().queryForObject("SELECT created_by FROM public_import_document WHERE id=?",Long.class,key);if(importer!=Long.parseLong(who.user().id()))throw UserFailure.denied();if(!item.status().equals("staged"))throw conflict();db().update("UPDATE public_import_document SET status='queued' WHERE id=?",key);return document(key,false);});}
    public SourceFile sourceFile(SessionIdentity who,String id){return tx(who,()->{UUID key=uuid(id);document(key,false);return new SourceFile(db().queryForObject("SELECT file_bytes FROM public_import_document WHERE id=?",byte[].class,key));});}
    private static final String TASK_SQL="SELECT t.*,q.id AS question_id,q.status AS question_status FROM public_import_task t LEFT JOIN public_question q ON q.task_id=t.id AND q.deleted_at IS NULL";
    private static Task task(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Task(rs.getString("id"),rs.getString("bank_id"),rs.getString("status"),rs.getString("error_message"),rs.getString("question_id"),rs.getObject("created_at",OffsetDateTime.class),rs.getString("question_status"));
    }
    private Task task(UUID id) { var rows=db().query(TASK_SQL+" WHERE t.id=?",(rs,n)->task(rs),id);if(rows.isEmpty())throw missing();return rows.getFirst(); }
    public Task task(SessionIdentity who,String id) { return tx(who,()->task(uuid(id))); }
    public Page<Task> tasks(SessionIdentity who,String id,int page,int size,String source) {
        pagination(page,size);if(!Set.of("all","text").contains(source))throw new IllegalArgumentException();
        UUID bank=uuid(id);String filter=source.equals("text")?" AND t.document_id IS NULL":"";
        return tx(who,()->{bank(bank);return new Page<>(db().query(TASK_SQL+" WHERE t.bank_id=?"+filter+" ORDER BY t.created_at DESC,t.id LIMIT ? OFFSET ?",(rs,n)->task(rs),bank,size,(long)(page-1)*size),db().queryForObject("SELECT COUNT(*) FROM public_import_task t WHERE t.bank_id=?"+filter,Long.class,bank),page,size);});
    }
    private static void pagination(int page,int size){if(page<1||size<1||size>100)throw new IllegalArgumentException();}
    private Review review(UUID id,boolean lock) {
        var rows=db().query("SELECT q.*,t.source_text FROM public_question q JOIN public_import_task t ON t.id=q.task_id WHERE q.id=? AND q.deleted_at IS NULL"+(lock?" FOR UPDATE":""),(rs,n)->new Review(rs.getString("id"),rs.getString("bank_id"),rs.getString("task_id"),rs.getString("imported_by"),rs.getString("status"),rs.getInt("version"),rs.getString("source_text"),numberedDraft(json.readTree(rs.getString("draft_json")),json.readTree(rs.getString("evidence_json")),rs.getInt("version")==1),json.readTree(rs.getString("evidence_json")),rs.getString("confirmed_by"),rs.getObject("confirmed_at",OffsetDateTime.class)),id);
        if(rows.isEmpty())throw missing();return rows.getFirst();
    }
    public Review review(SessionIdentity who,String id) {return tx(who,()->review(uuid(id),false));}
    public Review edit(SessionIdentity who,String id,int version,JsonNode draft) {
        validateDraft(draft);
        return tx(who,()->{
            UUID key=uuid(id);Review row=review(key,true);version(row,version);
            if(row.status().equals("published"))throw conflict();
            db().update("UPDATE public_question SET draft_json=?,version=version+1,status='draft',confirmed_by=NULL,confirmed_at=NULL,confirmed_version=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=?",json.writeValueAsString(draft),key);
            return review(key,false);
        });
    }
    public Review confirm(SessionIdentity who,String id,int version) {
        return tx(who,()->{
            UUID key=uuid(id);Review row=review(key,true);version(row,version);
            if(!row.importedBy().equals(who.user().id()))throw UserFailure.denied();
            if(!row.status().equals("draft"))throw conflict();
            validateDraft(row.draft());
            db().update("UPDATE public_question SET status='confirmed',confirmed_by=?,confirmed_at=CURRENT_TIMESTAMP,confirmed_version=version,updated_at=CURRENT_TIMESTAMP WHERE id=?",Long.parseLong(who.user().id()),key);
            return review(key,false);
        });
    }
    public Review publish(SessionIdentity who,String id,int version) {
        return tx(who,()->{
            UUID key=uuid(id);Review row=review(key,true);version(row,version);
            if(!row.status().equals("confirmed"))throw conflict();
            validateDraft(row.draft());
            db().update("UPDATE public_question SET status='published',published_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=?",key);
            return review(key,false);
        });
    }
    public Review withdraw(SessionIdentity who,String id,int version) {
        return tx(who,()->{
            UUID key=uuid(id);Review row=review(key,true);version(row,version);
            if(!row.status().equals("published"))throw conflict();
            JsonNode draft=numberedDraft(row.draft(),row.evidence(),false);
            ((tools.jackson.databind.node.ObjectNode)draft).put("status","draft");
            ((tools.jackson.databind.node.ObjectNode)draft).putObject("quality").put("review_status","pending");
            db().update("UPDATE public_question SET status='draft',version=version+1,draft_json=?,confirmed_by=NULL,confirmed_at=NULL,confirmed_version=NULL,published_at=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=?",json.writeValueAsString(draft),key);
            return review(key,false);
        });
    }
    public void deleteQuestion(SessionIdentity who,String id,int version) {
        tx(who,()->{
            UUID key=uuid(id);Review row=review(key,true);version(row,version);
            if(row.status().equals("published"))throw conflict();
            db().update("UPDATE public_question SET deleted_at=CURRENT_TIMESTAMP,deleted_by=?,version=version+1,status='draft',confirmed_by=NULL,confirmed_at=NULL,confirmed_version=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=?",Long.parseLong(who.user().id()),key);
            return null;
        });
    }
    public record PublishItem(String id,int version) {}
    public record PublishResult(int publishedCount) {}
    public PublishResult publishBatch(SessionIdentity who,String bankId,List<PublishItem> items) {
        if(items==null||items.isEmpty()||items.size()>100||items.stream().anyMatch(Objects::isNull))throw new IllegalArgumentException();
        UUID bank=uuid(bankId);
        Map<UUID,Integer> versions=new TreeMap<>();
        for(var item:items){if(item.version()<1||versions.put(uuid(item.id()),item.version())!=null)throw new IllegalArgumentException();}
        return tx(who,()->{
            bank(bank);
            // 固定加锁顺序；全部校验通过后才发布，任一冲突整批回滚。
            for(var entry:versions.entrySet()){
                Review row=review(entry.getKey(),true);
                if(!row.bankId().equals(bank.toString()))throw missing();
                version(row,entry.getValue());
                if(!row.status().equals("confirmed"))throw conflict();
                validateDraft(row.draft());
            }
            for(UUID key:versions.keySet())db().update("UPDATE public_question SET status='published',published_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=?",key);
            return new PublishResult(versions.size());
        });
    }
    public static void validateDraft(JsonNode draft) {
        if(draft==null || !draft.isObject())throw new IllegalArgumentException();
        JsonNode source=draft.path("source");
        if(!source.isMissingNode()&&!source.isNull()&&!source.isObject())throw new IllegalArgumentException();
        JsonNode questionNo=source.path("question_no");
        if(!questionNo.isMissingNode()&&!questionNo.isNull()&&(!questionNo.isIntegralNumber()||!questionNo.canConvertToInt()||questionNo.asInt()<1))throw new IllegalArgumentException();
        String type=draft.path("type").asText();
        Question.Type kind=switch(type){case "single_choice"->Question.Type.SINGLE_CHOICE;case "multiple_choice"->Question.Type.MULTIPLE_CHOICE;default->throw new IllegalArgumentException();};
        List<Question.Option> options=new ArrayList<>();
        if(!draft.path("options").isArray())throw new IllegalArgumentException();
        for(JsonNode option:draft.path("options")){
            String text=option.path("text_md").asText("");if(text.length()>5000)throw new IllegalArgumentException();
            options.add(new Question.Option(option.path("key").asText(""),text));
        }
        var keys=strings(draft.path("answer").path("keys"));var knowledge=strings(draft.path("knowledge_points"));
        if(!draft.path("difficulty").isIntegralNumber() || !Set.of("imported","ai_generated","manual").contains(draft.path("answer").path("source").asText("")))throw new IllegalArgumentException();
        if(!draft.path("stem_images").isMissingNode() && (!draft.path("stem_images").isArray() || !draft.path("stem_images").isEmpty()))throw new IllegalArgumentException();
        for(JsonNode option:draft.path("options"))if(!option.path("image").isMissingNode()&&!option.path("image").isNull())throw new IllegalArgumentException();
        if(!draft.path("explanation_md").isString() || draft.path("explanation_md").asText().length()>20000)throw new IllegalArgumentException();
        if(!draft.path("stem_md").isString())throw new IllegalArgumentException();
        for(JsonNode option:draft.path("options"))if(!option.path("text_md").isString())throw new IllegalArgumentException();
        new Question(kind,draft.path("stem_md").asText(""),options,keys,knowledge,draft.path("difficulty").asInt());
    }
    private static List<String> strings(JsonNode array){if(!array.isArray())throw new IllegalArgumentException();var result=new ArrayList<String>();for(JsonNode value:array){if(!value.isString())throw new IllegalArgumentException();result.add(value.asText());}return result;}
    private static void version(Review row,int version){if(version<1||row.version()!=version)throw conflict();}
    static UUID uuid(String value){try{return UUID.fromString(value);}catch(RuntimeException failure){throw new IllegalArgumentException("Invalid identifier");}}
    static UserFailure unavailable(){return new UserFailure(503,50320,"公共题库暂不可用，请确认数据库升级状态");}
    private static UserFailure missing(){return new UserFailure(404,40420,"公共题库资源不存在");}
    private static UserFailure conflict(){return new UserFailure(409,40920,"内容已变更或当前状态不允许此操作，请刷新后重试");}
}
