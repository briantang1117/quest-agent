package com.quest.api.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.config.import=","spring.datasource.url=jdbc:h2:mem:syllabusIntegration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password=","spring.flyway.enabled=false","spring.autoconfigure.exclude=com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration","quest.persistence.mode=postgresql","quest.security.jwt.secret=student-coach-test-secret-at-least-32-bytes","quest.security.login.account-limit=100","quest.security.login.source-limit=1000"})
@ActiveProfiles("test") @AutoConfigureMockMvc
class SyllabusIntegrationTest {
    @Autowired org.springframework.context.ApplicationContext applicationContext;
    @Autowired MockMvc mvc; @Autowired JdbcTemplate db; @Autowired ObjectMapper json;
    @org.springframework.test.context.bean.override.mockito.MockitoBean com.quest.api.coach.CoachAiClient ai;
    static final String PASSWORD="Initial-password-123";
    static String h2(String sql){return sql.replace("TIMESTAMPTZ","TIMESTAMP WITH TIME ZONE").replace("embedding vector","embedding TEXT").replace("AND vector_dims(embedding)=embedding_dimensions","");}
    @BeforeEach void setup() throws Exception{
        db.execute("DROP ALL OBJECTS");db.execute("CREATE ROLE quest_app");db.execute("CREATE ROLE quest_ai");db.execute("CREATE SEQUENCE app_user_id_seq");db.execute("CREATE SEQUENCE audit_log_id_seq");
        db.execute("CREATE TABLE app_user(id BIGSERIAL PRIMARY KEY,username VARCHAR(64) UNIQUE NOT NULL,password_hash VARCHAR(128) NOT NULL,display_name VARCHAR(128) NOT NULL,status VARCHAR(16) DEFAULT 'active',created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP)");
        db.execute("CREATE TABLE audit_log(id BIGSERIAL PRIMARY KEY,actor BIGINT,action VARCHAR(64),target_type VARCHAR(64),target_id VARCHAR(64),before_data JSONB,after_data JSONB,ip VARCHAR(45),user_agent TEXT,trace_id VARCHAR(64),ai_job_id BIGINT,created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP)");TestUserSchema.applyV4(db);
        for(String file:List.of("V5__public_banks_and_imports.sql","V6__public_pdf_batches.sql","V7__public_question_soft_delete.sql","V8__student_coach.sql","V9__ai_call_audit.sql","V10__syllabus_versions.sql","V11__syllabus_pipeline.sql","V12__student_coach_session_soft_delete.sql")){String sql=new String(new ClassPathResource("db/migration/"+file).getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);sql=h2(sql);new ResourceDatabasePopulator(new org.springframework.core.io.ByteArrayResource(sql.getBytes(java.nio.charset.StandardCharsets.UTF_8))).execute(db.getDataSource());}
        var bcrypt=new BCryptPasswordEncoder();for(String name:List.of("admin","alice","bob"))db.update("INSERT INTO app_user(username,password_hash,display_name,account_type,must_change_password) VALUES (?,?,?,?,FALSE)",name,bcrypt.encode(PASSWORD),name,name.equals("admin")?"admin":"student");
        var chatReply=json.readTree("{\"action\":\"chat\",\"keys\":[],\"reply\":\"我们一起理解这个知识点。\"}");
        // Service 走带归因的三参重载，两参重载仅为兼容保留，两者都要打桩。
        org.mockito.Mockito.when(ai.turn(org.mockito.ArgumentMatchers.any())).thenReturn(chatReply);
        org.mockito.Mockito.when(ai.turn(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenReturn(chatReply);
    }
    /** 断言三参重载的调用次数，与 Service 实际调用口径一致。 */
    void turnCount(int times){
        org.mockito.Mockito.verify(ai,org.mockito.Mockito.times(times)).turn(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
    }
    /** 两种重载都要打桩：Service 走三参版，遗漏会导致实际调用落到默认返回值。 */
    void stubFailure(RuntimeException failure){
        org.mockito.Mockito.when(ai.turn(org.mockito.ArgumentMatchers.any())).thenThrow(failure);
        org.mockito.Mockito.when(ai.turn(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenThrow(failure);
    }
    JsonNode postData(String path,String token,Object body,int expected)throws Exception{
        var req=post(path).contentType("application/json").content(json.writeValueAsString(body));if(token!=null)req.header("Authorization","Bearer "+token);
        return json.readTree(mvc.perform(req).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString()).get("data");
    }
    String token(String name)throws Exception{return postData("/api/v1/auth/login",null,Map.of("username",name,"password",PASSWORD),200).get("accessToken").asText();}
    void preference(String token,String subject)throws Exception{mvc.perform(put("/api/v1/me/study-preference").header("Authorization","Bearer "+token).contentType("application/json").content(json.writeValueAsString(Map.of("subject",subject)))).andExpect(status().isOk());}
    UUID seed(String subject,String status,boolean deleted){
        long actor=db.queryForObject("SELECT id FROM app_user WHERE username='admin'",Long.class);UUID bank=UUID.randomUUID(),task=UUID.randomUUID(),q=UUID.randomUUID();
        db.update("INSERT INTO public_bank(id,name,exam_type,subject,created_by) VALUES (?,?,?,?,?)",bank,"试卷",subject.equals("vocational_aptitude")?"career_a":"shanghai_civil",subject,actor);
        db.update("INSERT INTO public_import_task(id,bank_id,created_by,source_text,status) VALUES (?,?,?,?,'reviewing')",task,bank,actor,"PRIVATE_SOURCE");
        String draft="{\"type\":\"single_choice\",\"stem_md\":\"合成题干\",\"options\":[{\"key\":\"A\",\"text_md\":\"甲\"},{\"key\":\"B\",\"text_md\":\"乙\"}],\"answer\":{\"keys\":[\"B\"],\"source\":\"imported\"},\"explanation_md\":\"PRIVATE_EXPLANATION\",\"knowledge_points\":[\"GK\"],\"difficulty\":3}";
        db.update("INSERT INTO public_question(id,bank_id,task_id,imported_by,draft_json,evidence_json) VALUES (?,?,?,?,?,?)",q,bank,task,actor,draft,"{}");
        if(status.equals("published"))db.update("UPDATE public_question SET status='published',confirmed_by=?,confirmed_at=CURRENT_TIMESTAMP,confirmed_version=1,published_at=CURRENT_TIMESTAMP WHERE id=?",actor,q);
        if(deleted)db.update("UPDATE public_question SET deleted_at=CURRENT_TIMESTAMP,deleted_by=? WHERE id=?",actor,q);
        return q;
    }
    JsonNode create(String token,UUID id,String mode)throws Exception{return postData("/api/v1/coach/sessions",token,Map.of("requestId",id,"mode",mode),200);}
    @org.springframework.test.context.bean.override.mockito.MockitoBean com.quest.api.publicbank.PublicAiClient parser;
    @Autowired com.quest.api.syllabus.SyllabusPipeline pipeline;
    JsonNode upload(String token,String exam,int year)throws Exception{
        var report=json.readTree("{\"sha256\":\""+"a".repeat(64)+"\",\"pageCount\":2,\"pages\":[{\"page\":1,\"printedPage\":\"I\",\"text\":\"考试说明\"},{\"page\":2,\"printedPage\":\"1\",\"text\":\"判断推理\"}],\"sections\":[{\"ordinal\":1,\"title\":\"判断推理\",\"path\":\"行测/判断推理\",\"kind\":\"requirement\",\"scope\":\"administrative_aptitude\",\"enabled\":false,\"pageStart\":2,\"pageEnd\":2,\"printedPages\":\"1\",\"text\":\"测查逻辑分析能力\"}],\"warnings\":[]}");
        byte[] raw="%PDF-1.7\nlocal-test".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        ((tools.jackson.databind.node.ObjectNode)report).put("sha256",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(raw)));
        org.mockito.Mockito.when(parser.inspectSyllabus(org.mockito.ArgumentMatchers.any())).thenReturn(report);
        var file=new org.springframework.mock.web.MockMultipartFile("file","考纲.pdf","application/pdf",raw);
        return json.readTree(mvc.perform(multipart("/api/v1/admin/syllabi").file(file).param("title","考试大纲").param("examType",exam).param("examYear",String.valueOf(year)).param("versionLabel",year+"版").param("issuer","考试机构").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }
    JsonNode save(String token,JsonNode detail,boolean enabled)throws Exception{
        var sections=detail.path("sections").deepCopy();((tools.jackson.databind.node.ObjectNode)sections.get(0)).put("enabled",enabled);
        var body=Map.of("revision",detail.path("revision").asInt(),"title",detail.path("title").asText(),"versionLabel",detail.path("versionLabel").asText(),"issuer","考试机构","sourceUrl","","sections",sections);
        return json.readTree(mvc.perform(put("/api/v1/admin/syllabi/"+detail.path("id").asText()).header("Authorization","Bearer "+token).contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }
    JsonNode published(String admin,int year)throws Exception{
        var d=save(admin,upload(admin,"shanghai_civil",year),true);
        return postData("/api/v1/admin/syllabi/"+d.path("id").asText()+"/publish",admin,Map.of("revision",d.path("revision").asInt()),200);
    }
    @Test void technicalParserNoiseIsHiddenFromReviewIssues()throws Exception{
        var all=json.readTree("[\"page:1:contains_images\",\"pending_review\",\"address_space_limit_unavailable\",\"rss_watchdog_not_hard_memory_limit\"]");
        var svc=applicationContext.getBean(com.quest.api.syllabus.SyllabusService.class);
        var m=com.quest.api.syllabus.SyllabusService.class.getDeclaredMethod("classifyWarnings",tools.jackson.databind.JsonNode.class);
        m.setAccessible(true);
        assertThat(((tools.jackson.databind.JsonNode)m.invoke(svc,all)).size()).isZero();
        var real=json.readTree("[\"序号9标题疑似截断\",\"page:2:contains_images\"]");
        var filtered=(tools.jackson.databind.JsonNode)m.invoke(svc,real);
        assertThat(filtered.toString()).contains("疑似截断").doesNotContain("contains_images");
    }
    @Test void uploadReviewPublishIndexAndSemanticRetrieveUsesRealPipeline()throws Exception{
        String admin=token("admin"),alice=token("alice");
        org.mockito.Mockito.when(parser.reviewSyllabus(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any())).thenAnswer(call->{var out=json.createObjectNode();out.set("sections",(JsonNode)call.getArgument(1));out.putArray("issues");return out;});
        org.mockito.Mockito.when(parser.embedSyllabus(org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.anyString())).thenAnswer(call->{java.util.List<String> texts=call.getArgument(0);var result=json.createObjectNode();result.put("model","test-embedding");result.put("dimensions",3);var vectors=result.putArray("vectors");for(String t:texts)vectors.addArray().add(1.0).add(0.0).add(0.0);return result;});
        var draft=upload(admin,"shanghai_civil",2027);String id=draft.path("id").asText();
        assertThat(draft.path("reviewStatus").asText()).isEqualTo("queued");
        pipeline.processOne();
        var reviewed=json.readTree(mvc.perform(get("/api/v1/admin/syllabi/"+id).header("Authorization","Bearer "+admin)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        assertThat(reviewed.path("reviewStatus").asText()).isEqualTo("ready");
        var enabled=save(admin,reviewed,true);var pub=postData("/api/v1/admin/syllabi/"+id+"/publish",admin,Map.of("revision",enabled.path("revision").asInt()),200);
        assertThat(pub.path("indexStatus").asText()).isEqualTo("queued");pipeline.processOne();
        var state=pipeline.status(UUID.fromString(id));assertThat(state.get("indexStatus")).isEqualTo("ready");
        var result=json.readTree(mvc.perform(get("/api/v1/study/syllabus").param("subject","administrative_aptitude").param("examYear","2027").param("keyword","如何考察分析关系的能力").header("Authorization","Bearer "+alice)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        assertThat(result.path("retrievalMode").asText()).isEqualTo("vector");assertThat(result.path("sections").size()).isEqualTo(1);assertThat(result.path("sections").get(0).path("versionId").asText()).isEqualTo(id);
        assertThat(result.path("sections").get(0).path("text").asText()).contains("逻辑分析");
        var context=pipeline.retrieve(UUID.fromString(id),"administrative_aptitude","自然语言问题",true);
        var keys=new java.util.HashSet<String>();context.get(0).propertyNames().forEach(keys::add);
        assertThat(keys).containsExactlyInAnyOrder("version_id","title","exam_year","section_title","page_start","page_end","text");
    }
}
