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

@SpringBootTest(properties={"spring.config.import=","spring.datasource.url=jdbc:h2:mem:privatebank;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password=","spring.flyway.enabled=false","spring.autoconfigure.exclude=com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration","quest.persistence.mode=postgresql","INTERNAL_HMAC_SECRET=private-meter-test-secret-at-least-32-bytes","quest.coach.harness.enabled=false","quest.private-questions.processing-enabled=false","quest.security.jwt.secret=student-coach-test-secret-at-least-32-bytes","quest.security.login.account-limit=100","quest.security.login.source-limit=1000"})
@ActiveProfiles("test") @AutoConfigureMockMvc
class PrivateQuestionTest {
    @Autowired MockMvc mvc; @Autowired JdbcTemplate db; @Autowired ObjectMapper json;
    @Autowired com.quest.api.coach.StudentCoachService coach; @Autowired UserService users;
    @org.springframework.test.context.bean.override.mockito.MockitoBean com.quest.api.coach.CoachAiClient ai;
    static final String PASSWORD="Initial-password-123";
    static String h2(String sql){return sql.replace("TIMESTAMPTZ","TIMESTAMP WITH TIME ZONE").replace("embedding vector","embedding TEXT").replace("AND vector_dims(embedding)=embedding_dimensions","");}
    @BeforeEach void setup() throws Exception {
        db.execute("DROP ALL OBJECTS");db.execute("CREATE ROLE quest_app");db.execute("CREATE ROLE quest_ai");db.execute("CREATE SEQUENCE app_user_id_seq");db.execute("CREATE SEQUENCE audit_log_id_seq");
        db.execute("CREATE TABLE app_user(id BIGSERIAL PRIMARY KEY,username VARCHAR(64) UNIQUE NOT NULL,password_hash VARCHAR(128) NOT NULL,display_name VARCHAR(128) NOT NULL,status VARCHAR(16) DEFAULT 'active',created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP)");
        db.execute("CREATE TABLE audit_log(id BIGSERIAL PRIMARY KEY,actor BIGINT,action VARCHAR(64),target_type VARCHAR(64),target_id VARCHAR(64),before_data JSONB,after_data JSONB,ip VARCHAR(45),user_agent TEXT,trace_id VARCHAR(64),ai_job_id BIGINT,created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP)");TestUserSchema.applyV4(db);
        for(String file:List.of("V5__public_banks_and_imports.sql","V6__public_pdf_batches.sql","V7__public_question_soft_delete.sql","V8__student_coach.sql","V9__ai_call_audit.sql","V10__syllabus_versions.sql","V12__student_coach_session_soft_delete.sql","V15__learning_topics.sql","V16__private_question_pool.sql")){String sql=h2(new String(new ClassPathResource("db/migration/"+file).getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));new ResourceDatabasePopulator(new org.springframework.core.io.ByteArrayResource(sql.getBytes(java.nio.charset.StandardCharsets.UTF_8))).execute(db.getDataSource());}
        var bcrypt=new BCryptPasswordEncoder();for(String name:List.of("admin","alice","bob"))db.update("INSERT INTO app_user(username,password_hash,display_name,account_type,must_change_password) VALUES (?,?,?,?,FALSE)",name,bcrypt.encode(PASSWORD),name,name.equals("admin")?"admin":"student");
        var chatReply=json.readTree("{\"action\":\"chat\",\"keys\":[],\"reply\":\"我们一起理解这个知识点。\"}");
        // Service 走带归因的三参重载，两参重载仅为兼容保留，两者都要打桩。
        org.mockito.Mockito.when(ai.turn(org.mockito.ArgumentMatchers.any())).thenReturn(chatReply);
        org.mockito.Mockito.when(ai.turn(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenReturn(chatReply);
    }
    @Autowired com.quest.api.privatebank.PrivateImportProcessor processor;
    @Autowired com.quest.api.admin.ModelMeterService meter;
    @Autowired com.quest.api.coach.AiCallAudit audit;
    @org.springframework.test.context.bean.override.mockito.MockitoBean com.quest.api.publicbank.PublicAiClient privateAi;
    JsonNode postData(String path,String token,Object body,int expected)throws Exception{
        var req=post(path).contentType("application/json").content(json.writeValueAsString(body));if(token!=null)req.header("Authorization","Bearer "+token);
        return json.readTree(mvc.perform(req).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString()).get("data");
    }
    String token(String name)throws Exception{return postData("/api/v1/auth/login",null,Map.of("username",name,"password",PASSWORD),200).get("accessToken").asText();}
    UUID session(String token)throws Exception{mvc.perform(put("/api/v1/me/study-preference").header("Authorization","Bearer "+token).contentType("application/json").content("{\"subject\":\"vocational_aptitude\"}")).andExpect(status().isOk());UUID id=UUID.randomUUID();postData("/api/v1/coach/sessions",token,Map.of("requestId",id,"mode","chat","preview",false),200);return id;}
    JsonNode candidate(){return json.readTree("{\"type\":\"single_choice\",\"stem_md\":\"一加一是多少\",\"options\":[{\"key\":\"A\",\"text_md\":\"二\"},{\"key\":\"B\",\"text_md\":\"三\"}],\"original_keys\":[],\"original_answer_quote\":\"\",\"original_explanation\":\"\",\"personal_keys\":[],\"requires_image\":false,\"issues\":[],\"page_numbers\":[1]}");}
    tools.jackson.databind.node.ObjectNode result(String status){var node=json.createObjectNode();node.set("candidate",candidate());node.putArray("answer_keys").add("A");node.put("explanation_md","一加一等于二").put("memory_tip","两个一合成二").put("status",status).put("answer_source","ai_suggested").put("web_verified",false);node.putArray("issues");node.putArray("web_sources");return node;}
    JsonNode start(String token,UUID session)throws Exception{org.mockito.Mockito.when(ai.turn(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenReturn(json.readTree("{\"action\":\"private_import\"}"));var jobs=postData("/api/v1/coach/sessions/"+session+"/turns",token,Map.of("requestId",UUID.randomUUID(),"action","chat","text","帮我导入题目\n一加一是多少 A.二 B.三"),200).path("privateImports");return jobs.get(jobs.size()-1);}
    void extraction() {org.mockito.Mockito.when(privateAi.privateQuestions(org.mockito.ArgumentMatchers.eq("source"),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenReturn(json.createObjectNode().set("questions",json.createArrayNode().add(candidate())).set("warnings",json.createArrayNode()));}
    @Test void importPersistsThenDeduplicatesAndIsOwnerOnly()throws Exception{
        String alice=token("alice"),bob=token("bob"),admin=token("admin");UUID s=session(alice);var job=start(alice,s);extraction();org.mockito.Mockito.when(privateAi.privateQuestions(org.mockito.ArgumentMatchers.eq("check"),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenReturn(result("ready"));
        assertThat(processor.processOne()).isTrue();assertThat(processor.processOne()).isTrue();
        String path="/api/v1/study/private-imports/"+job.path("id").asText();mvc.perform(get(path).header("Authorization","Bearer "+alice)).andExpect(status().isOk()).andExpect(jsonPath("$.data.saved").value(1)).andExpect(jsonPath("$.data.status").value("completed"));
        mvc.perform(get(path).header("Authorization","Bearer "+bob)).andExpect(status().isNotFound());mvc.perform(get(path).header("Authorization","Bearer "+admin)).andExpect(status().isForbidden());
        var second=start(alice,s);processor.processOne();processor.processOne();mvc.perform(get("/api/v1/study/private-imports/"+second.path("id").asText()).header("Authorization","Bearer "+alice)).andExpect(jsonPath("$.data.duplicates").value(1));assertThat(db.queryForObject("SELECT COUNT(*) FROM private_question",Integer.class)).isEqualTo(1);
    }
    @Test void uploadOnlyAcknowledgesWithoutPaidCallsAndRejectsForeignAttachment()throws Exception{
        String alice=token("alice"),bob=token("bob");UUID s=session(alice);var file=new org.springframework.mock.web.MockMultipartFile("file","题目.txt","text/plain","一加一 A.二 B.三".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var raw=mvc.perform(multipart("/api/v1/coach/attachments").file(file).param("requestId",UUID.randomUUID().toString()).header("Authorization","Bearer "+alice)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();String asset=json.readTree(raw).path("data").path("id").asText();
        var response=postData("/api/v1/coach/sessions/"+s+"/turns",alice,Map.of("requestId",UUID.randomUUID(),"action","chat","text","已发送附件","attachments",List.of(asset)),200);assertThat(response.path("messages").toString()).contains("题目.txt","没有开始识题");org.mockito.Mockito.verifyNoInteractions(ai,privateAi);
        mvc.perform(get("/api/v1/coach/attachments/"+asset+"/file").header("Authorization","Bearer "+bob)).andExpect(status().isNotFound());UUID bs=session(bob);postData("/api/v1/coach/sessions/"+bs+"/turns",bob,Map.of("requestId",UUID.randomUUID(),"action","chat","text","已发送附件","attachments",List.of(asset)),404);
    }
    @Test void failedStepNeedsExplicitRetryAndDoesNotDuplicateCompletedSource()throws Exception{
        String alice=token("alice");UUID s=session(alice);var job=start(alice,s);extraction();org.mockito.Mockito.when(privateAi.privateQuestions(org.mockito.ArgumentMatchers.eq("check"),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenThrow(new UserFailure(502,50260,"失败"));processor.processOne();processor.processOne();assertThat(processor.processOne()).isFalse();
        postData("/api/v1/study/private-imports/"+job.path("id").asText()+"/actions",alice,Map.of("action","retry"),200);org.mockito.Mockito.when(privateAi.privateQuestions(org.mockito.ArgumentMatchers.eq("check"),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenReturn(result("ready"));processor.processOne();assertThat(db.queryForObject("SELECT COUNT(*) FROM private_question",Integer.class)).isEqualTo(1);org.mockito.Mockito.verify(privateAi,org.mockito.Mockito.times(1)).privateQuestions(org.mockito.ArgumentMatchers.eq("source"),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
    }

    @Test void cancelDuringPaidStepDiscardsLateResultAndCannotAutoRetry()throws Exception{
        String alice=token("alice");UUID session=session(alice);var job=start(alice,session);String id=job.path("id").asText();
        org.mockito.Mockito.when(privateAi.privateQuestions(org.mockito.ArgumentMatchers.eq("source"),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenAnswer(call->{postData("/api/v1/study/private-imports/"+id+"/actions",alice,Map.of("action","cancel"),200);return json.createObjectNode().set("questions",json.createArrayNode().add(candidate())).set("warnings",json.createArrayNode());});
        assertThat(processor.processOne()).isTrue();assertThat(processor.processOne()).isFalse();assertThat(db.queryForObject("SELECT COUNT(*) FROM private_import_item",Integer.class)).isZero();postData("/api/v1/study/private-imports/"+id+"/actions",alice,Map.of("action","retry"),409);
    }
    @Test void malformedSecondCandidateDoesNotPartiallyPersistFirstCandidate()throws Exception{
        String alice=token("alice");UUID session=session(alice);start(alice,session);var invalid=(tools.jackson.databind.node.ObjectNode)candidate();invalid.put("stem_md","");
        org.mockito.Mockito.when(privateAi.privateQuestions(org.mockito.ArgumentMatchers.eq("source"),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenReturn(json.createObjectNode().set("questions",json.createArrayNode().add(candidate()).add(invalid)).set("warnings",json.createArrayNode()));processor.processOne();assertThat(db.queryForObject("SELECT COUNT(*) FROM private_import_item",Integer.class)).isZero();assertThat(db.queryForObject("SELECT status FROM private_import_source",String.class)).isEqualTo("failed");
    }
    @Test void keepConflictingOriginalPreservesOriginalExplanationAndExcludesFromPractice()throws Exception{
        String alice=token("alice");UUID session=session(alice);var job=start(alice,session);var question=(tools.jackson.databind.node.ObjectNode)candidate();question.putArray("original_keys").add("B");question.put("original_explanation","材料中B的解释");
        org.mockito.Mockito.when(privateAi.privateQuestions(org.mockito.ArgumentMatchers.eq("source"),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenReturn(json.createObjectNode().set("questions",json.createArrayNode().add(question)).set("warnings",json.createArrayNode()));var checked=result("needs_attention");checked.set("candidate",question);checked.withArray("issues").add("原答案冲突");org.mockito.Mockito.when(privateAi.privateQuestions(org.mockito.ArgumentMatchers.eq("check"),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenReturn(checked);processor.processOne();processor.processOne();
        UUID item=db.queryForObject("SELECT id FROM private_import_item",UUID.class);var outcome=postData("/api/v1/study/private-items/"+item+"/decision",alice,Map.of("sessionId",session,"action","keep_original"),200);assertThat(outcome.path("status").asText()).isEqualTo("completed");var saved=json.readTree(db.queryForObject("SELECT result_json FROM private_question",String.class));assertThat(saved.path("answer_keys").get(0).asText()).isEqualTo("B");assertThat(saved.path("explanation_md").asText()).isEqualTo("材料中B的解释");assertThat(saved.path("ai_suggested_explanation").asText()).isEqualTo("一加一等于二");assertThat(saved.path("status").asText()).isEqualTo("excluded");assertThat(db.queryForObject("SELECT COUNT(*) FROM private_question_version",Integer.class)).isEqualTo(2);
    }

    @Test void failedSourceExposesQuotaReasonWithoutAutomaticallyRetrying()throws Exception{
        String alice=token("alice");UUID session=session(alice);var job=start(alice,session);
        org.mockito.Mockito.when(privateAi.privateQuestions(org.mockito.ArgumentMatchers.eq("source"),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenThrow(new UserFailure(402,40240,"余额不足"));processor.processOne();assertThat(processor.processOne()).isFalse();
        mvc.perform(get("/api/v1/study/private-imports/"+job.path("id").asText()).header("Authorization","Bearer "+alice)).andExpect(status().isOk()).andExpect(jsonPath("$.data.source_errors[0].code").value(40240)).andExpect(jsonPath("$.data.source_errors[0].message").value("Token 余额不足，请联系管理员充值后重试。")).andExpect(jsonPath("$.data.source_failures").value(1));org.mockito.Mockito.verify(privateAi,org.mockito.Mockito.times(1)).privateQuestions(org.mockito.ArgumentMatchers.eq("source"),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
    }

    @Test void questionQuotaFailureAlsoTellsUserToContactAdministrator()throws Exception{
        String alice=token("alice");UUID session=session(alice);var job=start(alice,session);extraction();
        org.mockito.Mockito.when(privateAi.privateQuestions(org.mockito.ArgumentMatchers.eq("check"),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenThrow(new UserFailure(402,40240,"额度不足"));processor.processOne();processor.processOne();
        mvc.perform(get("/api/v1/study/private-imports/"+job.path("id").asText()).header("Authorization","Bearer "+alice)).andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].error_message").value("Token 余额不足，请联系管理员充值后重试。"));
    }

    @Test void backgroundImportTraceFitsRealMeterAndAuditColumns()throws Exception{
        String alice=token("alice");UUID session=session(alice);var job=start(alice,session);var trace=new java.util.concurrent.atomic.AtomicReference<String>();
        org.mockito.Mockito.when(privateAi.privateQuestions(org.mockito.ArgumentMatchers.eq("source"),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenAnswer(call->{
            com.quest.api.publicbank.PublicAiClient.Caller caller=call.getArgument(2);trace.set(caller.traceId());var grant=meter.issue(caller,"/internal/private-source");UUID id=UUID.randomUUID();meter.reserve(new com.quest.api.admin.ModelMeterService.Reserve(grant,id,"test-ocr","OCR",0));meter.settle(new com.quest.api.admin.ModelMeterService.Settle(grant,id,0L,0L,"ok"));audit.record(caller.actor(),"ai.private_source",null,caller.traceId(),Map.of("bytes",1),json.createObjectNode(),null,1,1,null);return json.createObjectNode().set("questions",json.createArrayNode().add(candidate())).set("warnings",json.createArrayNode());
        });
        assertThat(processor.processOne()).isTrue();assertThat(trace.get()).hasSize(64).startsWith(job.path("id").asText().replace("-",""));assertThat(db.queryForObject("SELECT COUNT(*) FROM ai_usage WHERE trace_id=? AND status='settled'",Integer.class,trace.get())).isEqualTo(1);assertThat(db.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='ai.private_source' AND trace_id=?",Integer.class,trace.get())).isEqualTo(1);assertThat(db.queryForObject("SELECT status FROM private_import_source",String.class)).isEqualTo("done");
    }
}
