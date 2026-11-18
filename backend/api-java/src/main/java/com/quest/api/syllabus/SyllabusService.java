package com.quest.api.syllabus;

import com.quest.api.auth.UserFailure;
import com.quest.api.auth.UserModels.SessionIdentity;
import com.quest.api.auth.UserService;
import com.quest.api.publicbank.PublicAiClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Supplier;

/** 平台考纲版本管理；学生只读明确年份及科目已发布章节。 */
@Service
public class SyllabusService {
    public record Page(List<JsonNode> list,long total,int page,int size) {}
    public record Metadata(String title,String examType,int examYear,String versionLabel,String issuer,LocalDate publishedDate,String sourceUrl) {}
    public record Edit(int revision,String title,String versionLabel,String issuer,LocalDate publishedDate,String sourceUrl,JsonNode sections) {}
    private final ObjectProvider<JdbcTemplate> jdbc;
    private final ObjectProvider<PlatformTransactionManager> managers;
    private final UserService users;
    private final PublicAiClient parser;
    private final ObjectMapper json;
    private final String mode;
    private final SyllabusPipeline pipeline;
    public SyllabusService(ObjectProvider<JdbcTemplate> jdbc,ObjectProvider<PlatformTransactionManager> managers,UserService users,PublicAiClient parser,ObjectMapper json,@Value("${quest.persistence.mode:disabled}") String mode,SyllabusPipeline pipeline){this.jdbc=jdbc;this.managers=managers;this.users=users;this.parser=parser;this.json=json;this.mode=mode;this.pipeline=pipeline;}
    private JdbcTemplate db(){if(!mode.equals("postgresql")||jdbc.getIfAvailable()==null)throw unavailable();return jdbc.getObject();}
    private <T>T tx(SessionIdentity who,String role,Supplier<T> work){
        db();try{return new TransactionTemplate(managers.getObject()).execute(status->{var live=users.session(who.user().id(),who.sessionId());if(!live.user().role().equals(role)||live.user().mustChangePassword())throw UserFailure.denied();return work.get();});}
        catch(DataAccessException failure){throw unavailable();}
    }
    public Page list(SessionIdentity who,int page,int size,String keyword,String exam,Integer year,String status){
        if(page<1||page>100000||size<1||size>100||keyword.length()>200||!Set.of("","career_a","shanghai_civil").contains(exam)||!Set.of("","draft","published","archived").contains(status))throw new IllegalArgumentException();if(year!=null)year(year);
        return tx(who,"admin",()->{
            String where=" FROM syllabus_version WHERE 1=1";var args=new ArrayList<Object>();
            if(!keyword.isBlank()){where+=" AND LOWER(title) LIKE ? ESCAPE '!'";args.add("%"+keyword.strip().toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%");}
            if(!exam.isEmpty()){where+=" AND exam_type=?";args.add(exam);}if(year!=null){where+=" AND exam_year=?";args.add(year);}if(!status.isEmpty()){where+=" AND status=?";args.add(status);}
            long count=db().queryForObject("SELECT COUNT(*)"+where,Long.class,args.toArray());args.add(size);args.add((long)(page-1)*size);
            return new Page(db().query("SELECT *"+where+" ORDER BY created_at DESC,id LIMIT ? OFFSET ?",(rs,n)->row(rs,false),args.toArray()),count,page,size);
        });
    }
    public JsonNode detail(SessionIdentity who,String id){UUID key=uuid(id);return tx(who,"admin",()->find(key,false));}
    public JsonNode upload(SessionIdentity who,Metadata meta,String filename,byte[] bytes){
        validate(meta);if(bytes==null||bytes.length<5||bytes.length>20*1024*1024||!new String(bytes,0,5,java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-"))throw new IllegalArgumentException();
        tx(who,"admin",()->true);
        JsonNode report=parser.inspectSyllabus(bytes);validateReport(report,bytes,meta.examType());
        String name=filename==null?"考纲.pdf":filename.replace('\\','/');name=name.substring(name.lastIndexOf('/')+1);if(name.isBlank()||name.length()>255)throw new IllegalArgumentException();final String sourceName=name;
        return tx(who,"admin",()->{UUID id=UUID.randomUUID();
            db().update("INSERT INTO syllabus_version(id,title,exam_type,exam_year,version_label,issuer,published_date,source_url,source_name,sha256,page_count,pages_json,sections_json,warnings_json,created_by) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",id,meta.title().strip(),meta.examType(),meta.examYear(),meta.versionLabel().strip(),meta.issuer().strip(),meta.publishedDate(),url(meta.sourceUrl()),sourceName,report.path("sha256").asText(),report.path("pageCount").asInt(),json.writeValueAsString(report.path("pages")),json.writeValueAsString(report.path("sections")),json.writeValueAsString(report.path("warnings")),Long.valueOf(who.user().id()));
            db().update("INSERT INTO syllabus_file(version_id,file_bytes) VALUES (?,?)",id,bytes);pipeline.enqueue(id,"review",1);return find(id,false);
        });
    }
    public JsonNode edit(SessionIdentity who,String id,Edit input){UUID key=uuid(id);if(input==null)throw new IllegalArgumentException();return tx(who,"admin",()->{
        JsonNode current=find(key,true);revision(current,input.revision());if(pipeline.active(key))throw conflict("考纲正在处理，请完成后再编辑");if(!current.path("status").asText().equals("draft"))throw conflict("已发布或归档版本不可编辑，请新增版本");
        var meta=new Metadata(input.title(),current.path("examType").asText(),current.path("examYear").asInt(),input.versionLabel(),input.issuer(),input.publishedDate(),input.sourceUrl());validate(meta);
        sections(input.sections(),current.path("pageCount").asInt(),meta.examType(),false);
        db().update("UPDATE syllabus_version SET title=?,version_label=?,issuer=?,published_date=?,source_url=?,sections_json=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",meta.title().strip(),meta.versionLabel().strip(),meta.issuer().strip(),meta.publishedDate(),url(meta.sourceUrl()),json.writeValueAsString(input.sections()),key);return find(key,false);
    });}
    public JsonNode publish(SessionIdentity who,String id,int revision){UUID key=uuid(id);return tx(who,"admin",()->{
        var current=find(key,true);revision(current,revision);if(pipeline.active(key))throw conflict("考纲正在处理，请完成后再发布");if(!current.path("status").asText().equals("draft"))throw conflict("只有草稿可发布");
        if(!"ready".equals(pipeline.status(key).get("reviewStatus")))throw conflict("请先完成AI整理并核对疑点后发布");
        sections(current.path("sections"),current.path("pageCount").asInt(),current.path("examType").asText(),true);
        // 锁定固定主键，避免同考试同年份发布两个活动版本；唯一约束为最终并发保护。
        try{db().update("INSERT INTO syllabus_current(exam_type,exam_year,version_id) VALUES (?,?,?)",current.path("examType").asText(),current.path("examYear").asInt(),key);}catch(org.springframework.dao.DuplicateKeyException failure){throw conflict("该考试年度已有已发布考纲，请先归档旧版");}
        db().update("UPDATE syllabus_version SET status='published',revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",key);pipeline.enqueue(key,"index",revision+1);return find(key,false);
    });}
    public JsonNode archive(SessionIdentity who,String id,int revision){UUID key=uuid(id);return tx(who,"admin",()->{var current=find(key,true);revision(current,revision);if(current.path("status").asText().equals("archived"))throw conflict("版本已归档");db().update("DELETE FROM syllabus_current WHERE version_id=?",key);db().update("UPDATE syllabus_version SET status='archived',revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",key);return find(key,false);});}
    public JsonNode retry(SessionIdentity who,String id,int revision,String type){UUID key=uuid(id);return tx(who,"admin",()->{var d=find(key,true);revision(d,revision);String status=d.path("status").asText();if(type.equals("review")&&!status.equals("draft"))throw conflict("只有草稿可重新整理");if(type.equals("index")&&status.equals("draft"))throw conflict("请先发布考纲再建立索引");pipeline.retry(key,type,revision);return find(key,false);});}
    public byte[] file(SessionIdentity who,String id){UUID key=uuid(id);return tx(who,"admin",()->{find(key,false);return db().queryForObject("SELECT file_bytes FROM syllabus_file WHERE version_id=?",byte[].class,key);});}
    public JsonNode study(SessionIdentity who,String subject,int examYear,String keyword){String exam=exam(subject);year(examYear);if(keyword.length()>200)throw new IllegalArgumentException();return tx(who,"student",()->{
        UUID id=boundVersion(subject,examYear);var out=json.createObjectNode();out.put("examYear",examYear);out.putArray("sections");if(id==null){out.putNull("versionId");return out;}
        JsonNode d=find(id,false);out.put("versionId",id.toString());out.put("title",d.path("title").asText());out.put("versionLabel",d.path("versionLabel").asText());out.put("indexStatus",String.valueOf(pipeline.status(id).get("indexStatus")));JsonNode hits=null;String retrievalMode="keyword";if(!keyword.isBlank())try{hits=pipeline.retrieve(id,subject,keyword,false);if(hits!=null)retrievalMode="vector";}catch(UserFailure failure){retrievalMode="keyword_fallback";out.put("retrievalWarning","向量检索暂不可用，当前使用同版本关键词结果；请检查索引状态");}out.put("retrievalMode",retrievalMode);out.set("sections",hits==null?citations(d,subject,keyword,false):hits);return out;
    });}
    /** 仅供已鉴权的服务在创建会话时绑定，调用方必须在其业务事务内。 */
    public UUID boundVersion(String subject,Integer year){if(year==null)return null;var ids=db().query("SELECT c.version_id FROM syllabus_current c JOIN syllabus_version v ON v.id=c.version_id WHERE c.exam_type=? AND c.exam_year=? AND v.status='published'",(rs,n)->rs.getObject(1,UUID.class),exam(subject),year);return ids.isEmpty()?null:ids.getFirst();}
    /** 历史绑定可读取归档版本；草稿从未可绑定，且必须再次核对科目与年份。 */
    public JsonNode context(String id,String subject,int year,String keyword){
        var d=find(uuid(id),false);if(d.path("status").asText().equals("draft")||!d.path("examType").asText().equals(exam(subject))||d.path("examYear").asInt()!=year)return json.createArrayNode();
        JsonNode vectorHits=null;
        if(keyword!=null&&!keyword.isBlank())try{vectorHits=pipeline.retrieve(uuid(id),subject,keyword,true);}catch(RuntimeException failure){
            // RAG 是可选增强。向量供应商、索引或查询异常均跳过检索，不能阻断聊天/已判分讲解。
            // 仅记录安全元数据；空片段让模型基于题目、权威判分和历史继续，不伪造引用。
            org.slf4j.LoggerFactory.getLogger(SyllabusService.class).warn("Syllabus RAG skipped: version={} code={} type={}",id,failure instanceof UserFailure uf?uf.code():0,failure.getClass().getSimpleName());
            return json.createArrayNode();
        }
        if(vectorHits!=null)return vectorHits;
        JsonNode matched=citations(d,subject,keyword,true);
        // 对话不是精确搜索框：考纲概览问题缺少章节关键词时，提供同一绑定版本的受限片段。
        // 只放宽本版章节选择，绝不放宽年份、科目、启用状态或例题边界。
        if(matched.isEmpty()&&keyword!=null&&(keyword.contains("考纲")||keyword.contains("大纲")||keyword.contains("考试范围")))return citations(d,subject,"",true);
        return matched;
    }
    private JsonNode citations(JsonNode d,String subject,String keyword,boolean snake){
        var out=json.createArrayNode();int budget=12000;String term=keyword==null?"":keyword.strip().toLowerCase(Locale.ROOT);
        var candidates=new ArrayList<JsonNode>();for(JsonNode section:d.path("sections"))if(section.path("enabled").asBoolean()&&section.path("scope").asText().equals(subject)&&Set.of("overview","requirement").contains(section.path("kind").asText()))candidates.add(section);
        // 简单关键词优先；没有匹配时不凭空补其他内容，空问题可返回范围概览。
        for(JsonNode section:candidates){String text=section.path("text").asText();if(!term.isEmpty()&&!(section.path("title").asText()+text).toLowerCase(Locale.ROOT).contains(term)&&!(section.path("title").asText().length()>=2&&term.contains(section.path("title").asText().toLowerCase(Locale.ROOT))))continue;if(out.size()>=8||budget<=0)break;String clipped=text.substring(0,Math.min(text.length(),Math.min(3000,budget)));budget-=clipped.length();var hit=out.addObject();
            hit.put(snake?"version_id":"versionId",d.path("id").asText());hit.put("title",d.path("title").asText());hit.put(snake?"exam_year":"examYear",d.path("examYear").asInt());hit.put(snake?"section_title":"sectionTitle",section.path("title").asText());hit.put(snake?"page_start":"pageStart",section.path("pageStart").asInt());hit.put(snake?"page_end":"pageEnd",section.path("pageEnd").asInt());hit.put("text",clipped);
        }return out;
    }
    private JsonNode find(UUID id,boolean lock){var rows=db().query("SELECT * FROM syllabus_version WHERE id=?"+(lock?" FOR UPDATE":""),(rs,n)->row(rs,true),id);if(rows.isEmpty())throw new UserFailure(404,40440,"考纲不存在");return rows.getFirst();}
    private JsonNode row(java.sql.ResultSet rs,boolean detail)throws java.sql.SQLException{
        var out=json.createObjectNode();out.put("id",rs.getString("id"));out.put("title",rs.getString("title"));out.put("examType",rs.getString("exam_type"));out.put("examYear",rs.getInt("exam_year"));out.put("versionLabel",rs.getString("version_label"));out.put("status",rs.getString("status"));out.put("revision",rs.getInt("revision"));out.put("pageCount",rs.getInt("page_count"));out.put("createdAt",rs.getObject("created_at",OffsetDateTime.class).toString());var sections=json.readTree(rs.getString("sections_json"));out.put("sectionCount",sections.size());int enabled=0;for(JsonNode section:sections)if(section.path("enabled").asBoolean())enabled++;out.put("enabledCount",enabled);
        if(detail){out.put("issuer",rs.getString("issuer"));var date=rs.getObject("published_date",LocalDate.class);if(date==null)out.putNull("publishedDate");else out.put("publishedDate",date.toString());out.put("sourceUrl",rs.getString("source_url"));out.put("sourceName",rs.getString("source_name"));out.put("sha256",rs.getString("sha256"));out.set("pages",json.readTree(rs.getString("pages_json")));out.set("sections",sections);JsonNode allWarnings=json.readTree(rs.getString("warnings_json"));out.set("warnings",classifyWarnings(allWarnings));out.put("technicalWarningCount",countTechnical(allWarnings));}out.setAll((tools.jackson.databind.node.ObjectNode)json.valueToTree(pipeline.status(rs.getObject("id",UUID.class))));return out;
    }
    /**
     * 只把需要管理员处理的疑点返回给界面。
     * 逐页 contains_images、解析器能力自述、流程状态属于系统噪音，不当作文档问题展示。
     */
    JsonNode classifyWarnings(JsonNode all){
        var out=json.createArrayNode();if(all==null||!all.isArray())return out;
        for(JsonNode item:all){String text=item.asText("");if(text.isBlank()||isTechnical(text))continue;out.add(text.strip());}
        return out;
    }
    private static int countTechnical(JsonNode all){
        if(all==null||!all.isArray())return 0;int count=0;for(JsonNode item:all)if(isTechnical(item.asText("")))count++;return count;
    }
    private static boolean isTechnical(String text){
        return (text.startsWith("page:")&&text.contains(":contains_images"))
            ||text.equals("pending_review")
            ||text.equals("address_space_limit_unavailable")
            ||text.equals("rss_watchdog_not_hard_memory_limit");
    }
    private void validateReport(JsonNode report,byte[] bytes,String exam){
        try{if(!report.path("sha256").asText().equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))))throw new IllegalArgumentException();}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
        if(!report.path("pageCount").isIntegralNumber()||!report.path("pageCount").canConvertToInt())throw new IllegalArgumentException();
        int count=report.path("pageCount").asInt();if(count<1||count>200||!report.path("pages").isArray()||report.path("pages").size()!=count||!report.path("warnings").isArray())throw new IllegalArgumentException();int n=0,total=0;for(JsonNode page:report.path("pages")){if(!page.path("page").isIntegralNumber()||page.path("page").asInt()!=++n||!page.path("text").isString()||!(page.path("printedPage").isString()||page.path("printedPage").isNull()))throw new IllegalArgumentException();total+=page.path("text").asText().length();}if(total>2000000)throw new IllegalArgumentException();sections(report.path("sections"),count,exam,false);for(JsonNode section:report.path("sections"))if(section.path("enabled").asBoolean())throw new IllegalArgumentException();
    }
    private void sections(JsonNode sections,int count,String exam,boolean publishing){
        if(sections==null||!sections.isArray()||sections.size()>1000)throw new IllegalArgumentException();var ordinals=new HashSet<Integer>();boolean usable=false;int total=0;
        for(JsonNode section:sections){
            if(!section.isObject()||!section.path("ordinal").isIntegralNumber()||!section.path("ordinal").canConvertToInt()||!section.path("pageStart").isIntegralNumber()||!section.path("pageEnd").isIntegralNumber()||!section.path("pageStart").canConvertToInt()||!section.path("pageEnd").canConvertToInt())throw new IllegalArgumentException();
            for(String field:List.of("title","path","kind","scope","printedPages","text"))if(!section.path(field).isString())throw new IllegalArgumentException();
            if(section.path("printedPages").asText().length()>1000)throw new IllegalArgumentException();
            int ordinal=section.path("ordinal").asInt(),start=section.path("pageStart").asInt(),end=section.path("pageEnd").asInt();String kind=section.path("kind").asText(),scope=section.path("scope").asText(),text=section.path("text").asText();if(ordinal<1||!ordinals.add(ordinal)||start<1||end<start||end>count||section.path("title").asText().isBlank()||section.path("title").asText().length()>300||section.path("path").asText().length()>1000||text.isBlank()||text.length()>12000||!Set.of("overview","requirement","example","other").contains(kind)||!Set.of("excluded","vocational_aptitude","administrative_aptitude").contains(scope)||!section.path("enabled").isBoolean())throw new IllegalArgumentException();total+=text.length();boolean enabled=section.path("enabled").asBoolean();if(enabled&&(!scope.equals(exam.equals("career_a")?"vocational_aptitude":"administrative_aptitude")||!Set.of("overview","requirement").contains(kind)))throw new IllegalArgumentException();usable|=enabled;}
        if(total>2000000)throw new IllegalArgumentException();if(publishing&&!usable)throw conflict("请先核对并启用至少一个适用范围章节");
    }
    private void validate(Metadata m){if(m==null)throw new IllegalArgumentException();text(m.title(),200);text(m.versionLabel(),100);text(m.issuer(),200);if(!Set.of("career_a","shanghai_civil").contains(m.examType()))throw new IllegalArgumentException();year(m.examYear());url(m.sourceUrl());}
    private void text(String value,int max){if(value==null||value.isBlank()||value.length()>max)throw new IllegalArgumentException();}
    private String url(String value){if(value==null||value.isBlank())return "";try{URI u=URI.create(value);if(value.length()>2000||!Set.of("http","https").contains(u.getScheme())||u.getHost()==null||u.getUserInfo()!=null)throw new IllegalArgumentException();return value;}catch(RuntimeException failure){throw new IllegalArgumentException();}}
    private String exam(String subject){return switch(subject){case "vocational_aptitude"->"career_a";case "administrative_aptitude"->"shanghai_civil";default->throw new IllegalArgumentException();};}
    private void year(int year){if(year<2000||year>2100)throw new IllegalArgumentException();}
    private UUID uuid(String id){try{return UUID.fromString(id);}catch(RuntimeException failure){throw new IllegalArgumentException();}}
    private void revision(JsonNode current,int revision){if(current.path("revision").asInt()!=revision)throw conflict("考纲已变化，请刷新后重试");}
    private UserFailure conflict(String message){return new UserFailure(409,40940,message);}
    private UserFailure unavailable(){return new UserFailure(503,50340,"考纲服务暂不可用");}
}
