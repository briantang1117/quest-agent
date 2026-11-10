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

@SpringBootTest(properties={"spring.config.import=","spring.datasource.url=jdbc:h2:mem:syllabus;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password=","spring.flyway.enabled=false","spring.autoconfigure.exclude=com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration","quest.persistence.mode=postgresql","quest.security.jwt.secret=student-coach-test-secret-at-least-32-bytes","quest.security.login.account-limit=100","quest.security.login.source-limit=1000"})
@ActiveProfiles("test") @AutoConfigureMockMvc
class SyllabusTest {
    @Autowired MockMvc mvc; @Autowired JdbcTemplate db; @Autowired ObjectMapper json;
    @org.springframework.test.context.bean.override.mockito.MockitoBean com.quest.api.coach.CoachAiClient ai;
    static final String PASSWORD="Initial-password-123";
    static String h2(String sql){return sql.replace("TIMESTAMPTZ","TIMESTAMP WITH TIME ZONE").replace("embedding vector","embedding TEXT").replace("AND vector_dims(embedding)=embedding_dimensions","");}
    @BeforeEach void setup() throws Exception {
        db.execute("DROP ALL OBJECTS");db.execute("CREATE ROLE quest_app");db.execute("CREATE ROLE quest_ai");db.execute("CREATE SEQUENCE app_user_id_seq");db.execute("CREATE SEQUENCE audit_log_id_seq");
        db.execute("CREATE TABLE app_user(id BIGSERIAL PRIMARY KEY,username VARCHAR(64) UNIQUE NOT NULL,password_hash VARCHAR(128) NOT NULL,display_name VARCHAR(128) NOT NULL,status VARCHAR(16) DEFAULT 'active',created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP)");
        db.execute("CREATE TABLE audit_log(id BIGSERIAL PRIMARY KEY,actor BIGINT,action VARCHAR(64),target_type VARCHAR(64),target_id VARCHAR(64),before_data JSONB,after_data JSONB,ip VARCHAR(45),user_agent TEXT,trace_id VARCHAR(64),ai_job_id BIGINT,created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP)");TestUserSchema.applyV4(db);
        for(String file:List.of("V5__public_banks_and_imports.sql","V6__public_pdf_batches.sql","V7__public_question_soft_delete.sql","V8__student_coach.sql","V9__ai_call_audit.sql","V10__syllabus_versions.sql")){String sql=h2(new String(new ClassPathResource("db/migration/"+file).getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));new ResourceDatabasePopulator(new org.springframework.core.io.ByteArrayResource(sql.getBytes(java.nio.charset.StandardCharsets.UTF_8))).execute(db.getDataSource());}
        var bcrypt=new BCryptPasswordEncoder();for(String name:List.of("admin","alice","bob"))db.update("INSERT INTO app_user(username,password_hash,display_name,account_type,must_change_password) VALUES (?,?,?,?,FALSE)",name,bcrypt.encode(PASSWORD),name,name.equals("admin")?"admin":"student");
        org.mockito.Mockito.when(pipeline.status(org.mockito.ArgumentMatchers.any())).thenReturn(Map.of("reviewStatus","ready","indexStatus","not_started"));
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
    @org.springframework.test.context.bean.override.mockito.MockitoBean com.quest.api.syllabus.SyllabusPipeline pipeline;
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
    @Test void syllabusDraftReviewPublishLookupAndArchive()throws Exception{
        String admin=token("admin"),alice=token("alice");var d=upload(admin,"shanghai_civil",2027);String id=d.path("id").asText();
        assertThat(d.path("sections").get(0).path("enabled").asBoolean()).isFalse();
        mvc.perform(get("/api/v1/admin/syllabi/"+id).header("Authorization","Bearer "+alice)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/study/syllabus").param("subject","administrative_aptitude").param("examYear","2027").header("Authorization","Bearer "+alice)).andExpect(status().isOk()).andExpect(jsonPath("$.data.versionId").isEmpty());
        postData("/api/v1/admin/syllabi/"+id+"/publish",admin,Map.of("revision",1),409);
        var edited=save(admin,d,true);postData("/api/v1/admin/syllabi/"+id+"/publish",admin,Map.of("revision",1),409);
        var pub=postData("/api/v1/admin/syllabi/"+id+"/publish",admin,Map.of("revision",edited.path("revision").asInt()),200);
        mvc.perform(get("/api/v1/study/syllabus").param("subject","administrative_aptitude").param("examYear","2027").header("Authorization","Bearer "+alice)).andExpect(status().isOk()).andExpect(jsonPath("$.data.versionId").value(id)).andExpect(jsonPath("$.data.sections[0].pageStart").value(2));
        mvc.perform(get("/api/v1/study/syllabus").param("subject","administrative_aptitude").param("examYear","2026").header("Authorization","Bearer "+alice)).andExpect(status().isOk()).andExpect(jsonPath("$.data.versionId").isEmpty());
        postData("/api/v1/admin/syllabi/"+id+"/archive",admin,Map.of("revision",pub.path("revision").asInt()),200);
        mvc.perform(get("/api/v1/study/syllabus").param("subject","administrative_aptitude").param("examYear","2027").header("Authorization","Bearer "+alice)).andExpect(jsonPath("$.data.versionId").isEmpty());
    }
    @Test void rejectsOutOfScopeExamplesBadPagesAndDuplicatePublishedYear()throws Exception{
        String admin=token("admin");var first=published(admin,2027);var draft=upload(admin,"shanghai_civil",2027);
        var sections=draft.path("sections").deepCopy();var section=(tools.jackson.databind.node.ObjectNode)sections.get(0);section.put("enabled",true);section.put("scope","excluded");
        var body=new HashMap<String,Object>();body.put("revision",1);body.put("title","测试");body.put("versionLabel","2027版");body.put("issuer","机构");body.put("sections",sections);
        String path="/api/v1/admin/syllabi/"+draft.path("id").asText();
        mvc.perform(put(path).header("Authorization","Bearer "+admin).contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isBadRequest());
        section.put("scope","administrative_aptitude");section.put("kind","example");
        mvc.perform(put(path).header("Authorization","Bearer "+admin).contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isBadRequest());
        section.put("kind","requirement");section.put("pageEnd",3);
        mvc.perform(put(path).header("Authorization","Bearer "+admin).contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isBadRequest());
        section.put("pageEnd",2);body.put("sourceUrl","https://user:password@example.org/source");
        mvc.perform(put(path).header("Authorization","Bearer "+admin).contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isBadRequest());
        var updated=save(admin,draft,true);postData(path+"/publish",admin,Map.of("revision",updated.path("revision").asInt()),409);
        mvc.perform(get("/api/v1/admin/syllabi/"+first.path("id").asText()+"/file").header("Authorization","Bearer "+admin)).andExpect(status().isOk()).andExpect(content().contentType("application/pdf"));
        body.remove("sourceUrl");body.put("revision",first.path("revision").asInt());
        mvc.perform(put("/api/v1/admin/syllabi/"+first.path("id").asText()).header("Authorization","Bearer "+admin).contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isConflict());
    }
    @Test void publishedLookupClipsLargeTextAndPreservesCitationVersion()throws Exception{
        String admin=token("admin"),alice=token("alice");var draft=upload(admin,"shanghai_civil",2027);var sections=draft.path("sections").deepCopy();var section=(tools.jackson.databind.node.ObjectNode)sections.get(0);section.put("enabled",true);section.put("text","判断推理"+"内容".repeat(5000));
        var body=Map.of("revision",1,"title","测试","versionLabel","2027版","issuer","机构","sections",sections);
        String id=draft.path("id").asText();var updated=json.readTree(mvc.perform(put("/api/v1/admin/syllabi/"+id).header("Authorization","Bearer "+admin).contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        postData("/api/v1/admin/syllabi/"+id+"/publish",admin,Map.of("revision",updated.path("revision").asInt()),200);
        var result=json.readTree(mvc.perform(get("/api/v1/study/syllabus").param("subject","administrative_aptitude").param("examYear","2027").param("keyword","判断推理考什么").header("Authorization","Bearer "+alice)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        assertThat(result.path("sections").size()).isEqualTo(1);assertThat(result.path("sections").get(0).path("text").asText().length()).isLessThanOrEqualTo(3000);assertThat(result.path("sections").get(0).path("versionId").asText()).isEqualTo(id);
    }
    @Test void parallelPublishCannotActivateTwoVersionsOfSameExamYear()throws Exception{
        String admin=token("admin");var a=save(admin,upload(admin,"shanghai_civil",2027),true);var b=save(admin,upload(admin,"shanghai_civil",2027),true);
        var start=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()){
            var jobs=new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
            for(JsonNode d:List.of(a,b))jobs.add(pool.submit(()->{start.await();return mvc.perform(post("/api/v1/admin/syllabi/"+d.path("id").asText()+"/publish").header("Authorization","Bearer "+admin).contentType("application/json").content(json.writeValueAsString(Map.of("revision",d.path("revision").asInt())))).andReturn().getResponse().getStatus();}));
            start.countDown();var statuses=new java.util.ArrayList<Integer>();for(var job:jobs)statuses.add(job.get(10,java.util.concurrent.TimeUnit.SECONDS));assertThat(statuses).containsExactlyInAnyOrder(200,409);
        }
        assertThat(db.queryForObject("SELECT COUNT(*) FROM syllabus_current",Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM syllabus_version WHERE status='published'",Integer.class)).isEqualTo(1);
    }
    @Test void malformedSectionTypesAreRejectedRatherThanCoerced()throws Exception{
        String admin=token("admin");var draft=upload(admin,"shanghai_civil",2027);var sections=draft.path("sections").deepCopy();var section=(tools.jackson.databind.node.ObjectNode)sections.get(0);
        section.put("pageStart","2");
        var body=Map.of("revision",1,"title","测试","versionLabel","2027版","issuer","机构","sections",sections);
        mvc.perform(put("/api/v1/admin/syllabi/"+draft.path("id").asText()).header("Authorization","Bearer "+admin).contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isBadRequest());
    }
    @Test void examYearValidationAndSubjectFilteringNeverFallsBack()throws Exception{
        String alice=token("alice"),admin=token("admin");published(admin,2027);
        mvc.perform(put("/api/v1/me/study-preference").header("Authorization","Bearer "+alice).contentType("application/json").content("{\"subject\":\"administrative_aptitude\",\"examYear\":1999}")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/study/syllabus").param("subject","vocational_aptitude").param("examYear","2027").header("Authorization","Bearer "+alice)).andExpect(status().isOk()).andExpect(jsonPath("$.data.versionId").isEmpty());
        mvc.perform(get("/api/v1/study/syllabus").param("subject","administrative_aptitude").param("examYear","2027").param("keyword","不存在的考点").header("Authorization","Bearer "+alice)).andExpect(status().isOk()).andExpect(jsonPath("$.data.sections.length()").value(0));
    }
    @Test void failedAiReviewCannotBePublishedByBypassingFrontend()throws Exception{
        String admin=token("admin");var d=save(admin,upload(admin,"shanghai_civil",2027),true);
        org.mockito.Mockito.when(pipeline.status(org.mockito.ArgumentMatchers.any())).thenReturn(Map.of("reviewStatus","failed","indexStatus","not_started"));
        postData("/api/v1/admin/syllabi/"+d.path("id").asText()+"/publish",admin,Map.of("revision",d.path("revision").asInt()),409);
    }
    @Test void vectorOutageFallsBackWithExplicitModeInsteadOfBreakingStudy()throws Exception{
        String admin=token("admin"),alice=token("alice");published(admin,2027);
        org.mockito.Mockito.when(pipeline.retrieve(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyBoolean())).thenThrow(new UserFailure(503,50343,"向量查询暂不可用"));
        var body=json.readTree(mvc.perform(get("/api/v1/study/syllabus").param("subject","administrative_aptitude").param("examYear","2027").param("keyword","判断推理").header("Authorization","Bearer "+alice)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        assertThat(body.path("retrievalMode").asText()).isEqualTo("keyword_fallback");assertThat(body.path("retrievalWarning").asText()).isNotBlank();assertThat(body.path("sections").size()).isEqualTo(1);
    }
    @Test void naturalLanguageSyllabusOverviewUsesBoundVersionWithoutExactMatch()throws Exception{
        String admin=token("admin"),alice=token("alice");var pub=published(admin,2027);
        mvc.perform(put("/api/v1/me/study-preference").header("Authorization","Bearer "+alice).contentType("application/json").content("{\"subject\":\"administrative_aptitude\",\"examYear\":2027}")).andExpect(status().isOk());
        var session=create(alice,UUID.randomUUID(),"chat");postData("/api/v1/coach/sessions/"+session.path("id").asText()+"/turns",alice,Map.of("requestId",UUID.randomUUID(),"action","chat","text","请介绍这份考纲的考试范围"),200);
        var capture=org.mockito.ArgumentCaptor.forClass(Object.class);org.mockito.Mockito.verify(ai).turn(capture.capture(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        var context=json.valueToTree(capture.getValue());assertThat(context.path("syllabus").size()).isGreaterThan(0);assertThat(context.path("syllabus").get(0).path("version_id").asText()).isEqualTo(pub.path("id").asText());
    }
    @Test void sessionKeepsBoundVersionAfterPreferenceChangeAndArchive()throws Exception{
        String admin=token("admin"),alice=token("alice");var pub=published(admin,2027);
        mvc.perform(put("/api/v1/me/study-preference").header("Authorization","Bearer "+alice).contentType("application/json").content("{\"subject\":\"administrative_aptitude\",\"examYear\":2027}")).andExpect(status().isOk());
        var session=create(alice,UUID.randomUUID(),"chat");
        postData("/api/v1/admin/syllabi/"+pub.path("id").asText()+"/archive",admin,Map.of("revision",pub.path("revision").asInt()),200);
        postData("/api/v1/coach/sessions/"+session.path("id").asText()+"/turns",alice,Map.of("requestId",UUID.randomUUID(),"action","chat","text","判断推理考什么"),200);
        var capture=org.mockito.ArgumentCaptor.forClass(Object.class);org.mockito.Mockito.verify(ai).turn(capture.capture(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        var ctx=json.valueToTree(capture.getValue());assertThat(ctx.path("syllabus").get(0).path("exam_year").asInt()).isEqualTo(2027);
        assertThat(ctx.path("syllabus").get(0).path("version_id").asText()).isEqualTo(pub.path("id").asText());
        var next=create(alice,UUID.randomUUID(),"chat");assertThat(next.path("syllabusVersionId").isNull()).isTrue();
    }
}
