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

@SpringBootTest(properties={"spring.config.import=","spring.datasource.url=jdbc:h2:mem:materials;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password=","spring.flyway.enabled=false","spring.autoconfigure.exclude=com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration","quest.persistence.mode=postgresql","quest.materials.enabled=true","INTERNAL_SERVICE_TOKEN=material-test-token","INTERNAL_HMAC_SECRET=material-test-hmac-secret-at-least-32-bytes","quest.security.jwt.secret=materials-test-secret-at-least-32-bytes","quest.security.login.account-limit=100","quest.security.login.source-limit=1000"})
@ActiveProfiles("test") @AutoConfigureMockMvc
class LearningMaterialsTest {
    @Autowired MockMvc mvc; @Autowired JdbcTemplate db; @Autowired ObjectMapper json;
    @org.springframework.test.context.bean.override.mockito.MockitoBean com.quest.api.publicbank.PublicAiClient ai;
    static final String PASSWORD="Initial-password-123";
    @BeforeEach void setup()throws Exception{
        db.execute("DROP ALL OBJECTS");db.execute("CREATE ROLE quest_app");db.execute("CREATE ROLE quest_ai");db.execute("CREATE SEQUENCE app_user_id_seq");db.execute("CREATE SEQUENCE audit_log_id_seq");
        db.execute("CREATE TABLE app_user(id BIGSERIAL PRIMARY KEY,username VARCHAR(64) UNIQUE NOT NULL,password_hash VARCHAR(128) NOT NULL,display_name VARCHAR(128) NOT NULL,status VARCHAR(16) DEFAULT 'active',created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP)");
        db.execute("CREATE TABLE audit_log(id BIGSERIAL PRIMARY KEY,actor BIGINT,action VARCHAR(64),target_type VARCHAR(64),target_id VARCHAR(64),before_data JSONB,after_data JSONB,ip VARCHAR(45),user_agent TEXT,trace_id VARCHAR(64),ai_job_id BIGINT,created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP)");TestUserSchema.applyV4(db);
        db.execute("CREATE TABLE mq_outbox(id BIGSERIAL PRIMARY KEY,topic VARCHAR(64) NOT NULL,tag VARCHAR(64) NOT NULL,msg_key VARCHAR(64) NOT NULL,payload JSONB NOT NULL,status SMALLINT DEFAULT 0,retry_count INTEGER DEFAULT 0,next_retry_at TIMESTAMP WITH TIME ZONE,created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,event_id UUID NOT NULL UNIQUE)");
        for(String f:List.of("V8__student_coach.sql","V12__student_coach_session_soft_delete.sql","V13__learning_materials.sql")){
            String sql=new String(new ClassPathResource("db/migration/"+f).getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).replace("TIMESTAMPTZ","TIMESTAMP WITH TIME ZONE");
            new ResourceDatabasePopulator(new org.springframework.core.io.ByteArrayResource(sql.getBytes(java.nio.charset.StandardCharsets.UTF_8))).execute(db.getDataSource());
        }
        for(String name:List.of("admin","alice","bob"))db.update("INSERT INTO app_user(username,password_hash,display_name,account_type,must_change_password) VALUES (?,?,?,?,FALSE)",name,new BCryptPasswordEncoder().encode(PASSWORD),name,name.equals("admin")?"admin":"student");
    }
    String token(String name)throws Exception{return json.readTree(mvc.perform(post("/api/v1/auth/login").contentType("application/json").content(json.writeValueAsString(Map.of("username",name,"password",PASSWORD)))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("accessToken").asText();}
    UUID source(String owner){UUID id=UUID.randomUUID();long user=db.queryForObject("SELECT id FROM app_user WHERE username=?",Long.class,owner);db.update("INSERT INTO student_coach_session(id,user_id,subject,title,state_json) VALUES (?,?,?,?,?)",id,user,"vocational_aptitude","知识对话","{\"messages\":[{\"id\":\"m1\",\"role\":\"user\",\"text\":\"财政政策和货币政策区别\"},{\"id\":\"m2\",\"role\":\"assistant\",\"text\":\"财政侧重收支，货币侧重货币与信贷。\"}]}");return id;}
    JsonNode create(UUID source,String token,UUID request)throws Exception{return json.readTree(mvc.perform(post("/api/v1/coach/sessions/"+source+"/materials").header("Authorization","Bearer "+token).contentType("application/json").content(json.writeValueAsString(Map.of("requestId",request)))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");}
    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder signed(String path,Object payload)throws Exception{
        return signedAt(path,payload,java.time.Instant.now().getEpochSecond());
    }
    /** 指定时间戳但签名仍然有效，用来验证时间窗而不是签名验证。 */
    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder signedAt(String path,Object payload,long stampSeconds)throws Exception{
        byte[] body=json.writeValueAsBytes(payload);String stamp=Long.toString(stampSeconds),nonce=UUID.randomUUID().toString();
        String digest=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(body));
        var mac=javax.crypto.Mac.getInstance("HmacSHA256");mac.init(new javax.crypto.spec.SecretKeySpec("material-test-hmac-secret-at-least-32-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8),"HmacSHA256"));
        String sig=HexFormat.of().formatHex(mac.doFinal(String.join("\n",stamp,nonce,"POST",path,digest).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return post(path).contentType("application/json").content(body).header("X-Service-Token","material-test-token").header("X-Timestamp",stamp).header("X-Nonce",nonce).header("X-Signature",sig);
    }
    JsonNode internal(String suffix,Object body)throws Exception{return json.readTree(mvc.perform(signed("/internal/materials/"+suffix,body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");}
    @Test void mqExecutionIsIdempotentAndSourceDeletionPreservesReadyMarkdown()throws Exception{
        String alice=token("alice");UUID source=source("alice");JsonNode material=create(source,alice,UUID.randomUUID());String id=material.path("id").asText();
        var event=db.queryForMap("SELECT event_id,generation FROM learning_material WHERE id=?",UUID.fromString(id));
        var payload=Map.of("taskId",id,"generation",event.get("generation").toString(),"eventId",event.get("event_id").toString());
        org.mockito.Mockito.when(ai.generateMaterial(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenReturn(json.readTree("{\"title\":\"政策对比\",\"markdown\":\"# 政策对比\\n财政与货币政策。\"}"));
        db.update("UPDATE student_coach_session SET deleted_at=CURRENT_TIMESTAMP WHERE id=?",source);
        assertThat(internal("execute",payload).path("status").asText()).isEqualTo("ready");
        assertThat(internal("execute",payload).path("status").asText()).isEqualTo("ready");
        org.mockito.Mockito.verify(ai,org.mockito.Mockito.times(1)).generateMaterial(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        mvc.perform(get("/api/v1/study/materials/"+id).header("Authorization","Bearer "+alice)).andExpect(status().isOk()).andExpect(jsonPath("$.data.sourceDeleted").value(true));
        mvc.perform(get("/api/v1/study/materials/"+id+"/download").header("Authorization","Bearer "+alice)).andExpect(status().isOk()).andExpect(content().string("# 政策对比\n财政与货币政策。"));
        JsonNode outbox=internal("outbox/claim",Map.of());assertThat(outbox.path("list").size()).isEqualTo(1);
        var item=outbox.path("list").get(0);assertThat(item.path("payload").asText()).doesNotContain("财政");
        internal("outbox/ack",Map.of("eventId",item.path("eventId").asText(),"leaseToken",item.path("leaseToken").asText(),"success",true));
        assertThat(internal("outbox/claim",Map.of()).path("list").isEmpty()).isTrue();
    }
    @Test void internalExecutionRequiresHmacAndRejectsReplay()throws Exception{
        String path="/internal/materials/outbox/claim";
        mvc.perform(post(path).contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
        var signed=signed(path,Map.of());mvc.perform(signed).andExpect(status().isOk());mvc.perform(signed).andExpect(status().isUnauthorized());
    }
    @Test void hmacWindowRejectsFarPastAndFarFutureEvenWithValidSignature()throws Exception{
        String path="/internal/materials/outbox/claim";long now=java.time.Instant.now().getEpochSecond();
        mvc.perform(signedAt(path,Map.of(),now-301)).andExpect(status().isUnauthorized());
        mvc.perform(signedAt(path,Map.of(),now+301)).andExpect(status().isUnauthorized());
        mvc.perform(signedAt(path,Map.of(),now-290)).andExpect(status().isOk());
    }
    @Test void hmacRejectsTamperedBodyAndWrongMethodWithoutMutatingOutbox()throws Exception{
        String path="/internal/materials/outbox/claim";
        mvc.perform(signed(path,Map.of()).content("{\"changed\":true}")).andExpect(status().isUnauthorized());
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/materials/not-allowed").contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM mq_outbox",Integer.class)).isZero();
    }
    @Test void failedOutboxSendIsRetriedAndStaleAckCannotConfirmNewLease()throws Exception{
        String alice=token("alice");create(source("alice"),alice,UUID.randomUUID());
        var first=internal("outbox/claim",Map.of()).path("list").get(0);
        assertThat(internal("outbox/claim",Map.of()).path("list").size()).isZero();
        internal("outbox/ack",Map.of("eventId",first.path("eventId").asText(),"leaseToken",first.path("leaseToken").asText(),"success",false));
        assertThat(internal("outbox/claim",Map.of()).path("list").size()).isZero();
        db.update("UPDATE mq_outbox SET next_retry_at=?",java.time.OffsetDateTime.now().minusMinutes(1));
        var next=internal("outbox/claim",Map.of()).path("list").get(0);
        assertThat(next.path("eventId")).isEqualTo(first.path("eventId"));assertThat(next.path("leaseToken")).isNotEqualTo(first.path("leaseToken"));
        internal("outbox/ack",Map.of("eventId",first.path("eventId").asText(),"leaseToken",first.path("leaseToken").asText(),"success",true));
        assertThat(db.queryForObject("SELECT status FROM mq_outbox",Integer.class)).isZero();
        internal("outbox/ack",Map.of("eventId",next.path("eventId").asText(),"leaseToken",next.path("leaseToken").asText(),"success",true));
        assertThat(db.queryForObject("SELECT status FROM mq_outbox",Integer.class)).isEqualTo(1);
    }
    @Test void expiredProcessingFailsWithoutCallingModelAgain()throws Exception{
        String alice=token("alice");UUID id=UUID.fromString(create(source("alice"),alice,UUID.randomUUID()).path("id").asText());
        db.update("UPDATE learning_material SET status='processing',lease_token=?,lease_until=? WHERE id=?",UUID.randomUUID(),java.time.OffsetDateTime.now().minusMinutes(1),id);
        internal("outbox/claim",Map.of());
        assertThat(db.queryForObject("SELECT status FROM learning_material WHERE id=?",String.class,id)).isEqualTo("failed");org.mockito.Mockito.verifyNoInteractions(ai);
    }
    @Test void duplicateWhileExecutingDoesNotCallModelAgain()throws Exception{
        String alice=token("alice");UUID source=source("alice");UUID id=UUID.fromString(create(source,alice,UUID.randomUUID()).path("id").asText());
        var row=db.queryForMap("SELECT generation,event_id FROM learning_material WHERE id=?",id);var event=Map.of("taskId",id,"generation",row.get("generation"),"eventId",row.get("event_id"));
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.when(ai.generateMaterial(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenAnswer(call->{entered.countDown();if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException();return json.readTree("{\"title\":\"并发资料\",\"markdown\":\"完整内容\"}");});
        var pool=java.util.concurrent.Executors.newSingleThreadExecutor();var first=pool.submit(()->internal("execute",event));
        try{assertThat(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();assertThat(internal("execute",event).path("status").asText()).isEqualTo("processing");}
        finally{release.countDown();pool.shutdown();}
        assertThat(first.get(10,java.util.concurrent.TimeUnit.SECONDS).path("status").asText()).isEqualTo("ready");org.mockito.Mockito.verify(ai,org.mockito.Mockito.times(1)).generateMaterial(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
    }
    @Test void streamMaterialIntentReturnsTaskCardWithoutCallingExplain()throws Exception{
        String alice=token("alice");UUID source=source("alice");
        org.mockito.Mockito.when(ai.coachRoute(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenReturn(json.readTree("{\"action\":\"material\",\"keys\":[],\"reply\":\"整理\",\"suggestions\":[]}"));
        var result=mvc.perform(post("/api/v1/coach/sessions/"+source+"/turns/stream").header("Authorization","Bearer "+alice).contentType("application/json").content(json.writeValueAsString(Map.of("requestId",UUID.randomUUID(),"action","chat","text","整理本次对话成学习资料")))).andExpect(request().asyncStarted()).andReturn();
        String body=mvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body).contains("event:done","materials","queued");org.mockito.Mockito.verify(ai,org.mockito.Mockito.never()).coachExplain(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
    }
    @Test void staleGenerationCannotOverwriteRetryAndSnapshotRemainsPrivate()throws Exception{
        String alice=token("alice");UUID source=source("alice");JsonNode material=create(source,alice,UUID.randomUUID());UUID id=UUID.fromString(material.path("id").asText());
        var row=db.queryForMap("SELECT generation,event_id FROM learning_material WHERE id=?",id);var old=Map.of("taskId",id,"generation",row.get("generation"),"eventId",row.get("event_id"));
        org.mockito.Mockito.when(ai.generateMaterial(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenThrow(new UserFailure(504,50401,"合成超时"));
        assertThat(internal("execute",old).path("status").asText()).isEqualTo("failed");
        UUID request=UUID.randomUUID();String retry="/api/v1/study/materials/"+id+"/retry";
        for(int i=0;i<2;i++)mvc.perform(post(retry).header("Authorization","Bearer "+alice).contentType("application/json").content(json.writeValueAsString(Map.of("requestId",request)))).andExpect(status().isOk());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM mq_outbox",Integer.class)).isEqualTo(2);
        assertThat(internal("execute",old).path("status").asText()).isEqualTo("ignored");
        var fresh=db.queryForMap("SELECT generation,event_id FROM learning_material WHERE id=?",id);
        org.mockito.Mockito.doReturn(json.readTree("{\"title\":\"新资料\",\"markdown\":\"完整新资料\"}")).when(ai).generateMaterial(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        assertThat(internal("execute",Map.of("taskId",id,"generation",fresh.get("generation"),"eventId",fresh.get("event_id"))).path("status").asText()).isEqualTo("ready");
        var response=mvc.perform(get("/api/v1/study/materials").header("Authorization","Bearer "+alice)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("snapshot_json","instructions","财政政策和货币政策区别");
    }
    @Test void snapshotRebuildsMoreThanOneHundredVisibleMessages()throws Exception{
        String alice=token("alice");UUID source=source("alice");var early=json.createObjectNode();var messages=early.putArray("messages");
        for(int i=0;i<110;i++)messages.addObject().put("id","history-"+i).put("role",i%2==0?"user":"assistant").put("text","知识点-"+i);
        db.update("INSERT INTO student_coach_turn(id,session_id,request_id,request_hash,status,response_json) VALUES (?,?,?,?,'done',?)",UUID.randomUUID(),source,UUID.randomUUID(),"hash",json.writeValueAsString(early));
        JsonNode material=create(source,alice,UUID.randomUUID());JsonNode snapshot=json.readTree(db.queryForObject("SELECT snapshot_json FROM learning_material WHERE id=?",String.class,UUID.fromString(material.path("id").asText())));
        assertThat(snapshot.size()).isEqualTo(112);assertThat(snapshot.get(0).path("text").asText()).isEqualTo("知识点-0");
        assertThat(json.writeValueAsString(snapshot)).doesNotContain("snapshot\":","suggestions");
    }
    @Test void snapshotExcludesUnansweredSecretsAndMergesLatestPublicGrade()throws Exception{
        String alice=token("alice");UUID source=source("alice");
        var question=json.readTree("{\"id\":\"q1\",\"type\":\"single_choice\",\"stemMd\":\"公开题干\",\"options\":[{\"key\":\"A\",\"textMd\":\"甲\"},{\"key\":\"B\",\"textMd\":\"乙\"}]}");
        var state=json.createObjectNode();var message=state.putArray("messages").addObject().put("id","question-message").put("role","assistant").put("text","来一道题");message.set("question",question);
        state.putObject("current").putObject("snapshot").put("answer","PRIVATE_HIDDEN_ANSWER").put("explanation_md","PRIVATE_HIDDEN_EXPLANATION");
        db.update("UPDATE student_coach_session SET state_json=? WHERE id=?",json.writeValueAsString(state),source);
        UUID id=UUID.fromString(create(source,alice,UUID.randomUUID()).path("id").asText());String raw=db.queryForObject("SELECT snapshot_json FROM learning_material WHERE id=?",String.class,id);assertThat(raw).doesNotContain("PRIVATE_HIDDEN","answerKeys");
        db.update("UPDATE learning_material SET status='failed' WHERE id=?",id);
        db.update("INSERT INTO student_coach_turn(id,session_id,request_id,request_hash,status,response_json) VALUES (?,?,?,?,'done',?)",UUID.randomUUID(),source,UUID.randomUUID(),"hash",json.writeValueAsString(state));
        message.set("result",json.readTree("{\"correct\":true,\"selectedKeys\":[\"B\"],\"answerKeys\":[\"B\"],\"explanationMd\":\"已揭示解析\"}"));
        db.update("UPDATE student_coach_session SET state_json=? WHERE id=?",json.writeValueAsString(state),source);
        UUID next=UUID.fromString(create(source,alice,UUID.randomUUID()).path("id").asText());JsonNode snapshot=json.readTree(db.queryForObject("SELECT snapshot_json FROM learning_material WHERE id=?",String.class,next));
        assertThat(snapshot.size()).isEqualTo(1);assertThat(snapshot.get(0).path("result").path("answerKeys").get(0).asText()).isEqualTo("B");
    }
    @Test void naturalLanguageMaterialIntentCreatesTaskWithoutExplanation()throws Exception{
        String alice=token("alice");UUID source=source("alice");
        org.mockito.Mockito.when(ai.coachTurn(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenReturn(json.readTree("{\"action\":\"material\",\"keys\":[],\"reply\":\"整理资料\",\"suggestions\":[]}"));
        var response=mvc.perform(post("/api/v1/coach/sessions/"+source+"/turns").header("Authorization","Bearer "+alice).contentType("application/json").content(json.writeValueAsString(Map.of("requestId",UUID.randomUUID(),"action","chat","text","把本次对话整理成复习材料")))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode value=json.readTree(response).path("data");assertThat(value.path("materials").size()).isEqualTo(1);assertThat(value.path("processing").asBoolean()).isFalse();
        assertThat(db.queryForObject("SELECT snapshot_json FROM learning_material",String.class)).doesNotContain("把本次对话整理成复习材料");
        org.mockito.Mockito.verify(ai,org.mockito.Mockito.never()).generateMaterial(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
    }
    @Test void queuedTimeoutUsesUpdatedAtAndManualRetryRejectsOldEvent()throws Exception{
        String alice=token("alice");UUID source=source("alice");UUID id=UUID.fromString(create(source,alice,UUID.randomUUID()).path("id").asText());
        var row=db.queryForMap("SELECT generation,event_id FROM learning_material WHERE id=?",id);
        var old=Map.of("taskId",id,"generation",row.get("generation"),"eventId",row.get("event_id"));
        db.update("UPDATE learning_material SET created_at=?,updated_at=? WHERE id=?",java.time.OffsetDateTime.now().minusHours(2),java.time.OffsetDateTime.now(),id);
        internal("outbox/claim",Map.of());assertThat(db.queryForObject("SELECT status FROM learning_material WHERE id=?",String.class,id)).isEqualTo("queued");
        db.update("UPDATE learning_material SET updated_at=? WHERE id=?",java.time.OffsetDateTime.now().minusMinutes(31),id);
        internal("outbox/claim",Map.of());
        assertThat(db.queryForObject("SELECT status FROM learning_material WHERE id=?",String.class,id)).isEqualTo("failed");
        assertThat(db.queryForObject("SELECT error FROM learning_material WHERE id=?",String.class,id)).isEqualTo("任务长时间未被消费，请检查MQ后手动重试");
        assertThat(internal("execute",old).path("status").asText()).isEqualTo("failed");
        mvc.perform(post("/api/v1/study/materials/"+id+"/retry").header("Authorization","Bearer "+alice).contentType("application/json").content(json.writeValueAsString(Map.of("requestId",UUID.randomUUID())))).andExpect(status().isOk());
        assertThat(internal("execute",old).path("status").asText()).isEqualTo("ignored");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM mq_outbox",Integer.class)).isEqualTo(2);org.mockito.Mockito.verifyNoInteractions(ai);
    }
    @Test void deletedSourceAllowsOriginalCreateReplayButRejectsNewAndChangedRequests()throws Exception{
        String alice=token("alice"),bob=token("bob");UUID source=source("alice"),request=UUID.randomUUID();JsonNode first=create(source,alice,request);
        db.update("UPDATE student_coach_session SET deleted_at=CURRENT_TIMESTAMP WHERE id=?",source);
        assertThat(create(source,alice,request).path("id")).isEqualTo(first.path("id"));
        mvc.perform(post("/api/v1/coach/sessions/"+source+"/materials").header("Authorization","Bearer "+alice).contentType("application/json").content(json.writeValueAsString(Map.of("requestId",request,"instructions","变更要求")))).andExpect(status().isConflict());
        for(String auth:List.of(alice,bob))mvc.perform(post("/api/v1/coach/sessions/"+source+"/materials").header("Authorization","Bearer "+auth).contentType("application/json").content(json.writeValueAsString(Map.of("requestId",UUID.randomUUID())))).andExpect(status().isNotFound());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM mq_outbox",Integer.class)).isEqualTo(1);
    }
    @Test void rolledOutQuestionReceivesTopLevelPublicGradeFromHistoryAndCheckpoint()throws Exception{
        String alice=token("alice");
        for(boolean checkpoint:List.of(false,true)){
            UUID source=source("alice");var early=json.createObjectNode();var question=json.readTree("{\"id\":\"q1\",\"type\":\"single_choice\",\"stemMd\":\"题干\",\"options\":[{\"key\":\"A\",\"textMd\":\"甲\"},{\"key\":\"B\",\"textMd\":\"乙\"}]}");
            early.putArray("messages").addObject().put("id","old-question").put("role","assistant").put("text","来一道题").set("question",question);
            db.update("INSERT INTO student_coach_turn(id,session_id,request_id,request_hash,status,response_json,created_at) VALUES (?,?,?,?,'done',?,?)",UUID.randomUUID(),source,UUID.randomUUID(),"early",json.writeValueAsString(early),java.time.OffsetDateTime.now().minusMinutes(2));
            var later=json.createObjectNode();var messages=later.putArray("messages");for(int i=0;i<100;i++)messages.addObject().put("id","later-"+i).put("role","user").put("text","知识点"+i);
            var grade=json.readTree("{\"correct\":true,\"selectedKeys\":[\"B\"],\"answerKeys\":[\"B\"],\"explanationMd\":\"公开解析\"}");
            later.set("result",grade);
            if(checkpoint)later.putObject("current").put("id","q1").putObject("snapshot").put("answer","HIDDEN_SECRET");
            else {later.set("currentQuestion",question);db.update("INSERT INTO student_coach_turn(id,session_id,request_id,request_hash,status,response_json) VALUES (?,?,?,?,'done',?)",UUID.randomUUID(),source,UUID.randomUUID(),"later",json.writeValueAsString(later));}
            db.update("UPDATE student_coach_session SET state_json=? WHERE id=?",json.writeValueAsString(later),source);
            UUID id=UUID.fromString(create(source,alice,UUID.randomUUID()).path("id").asText());String raw=db.queryForObject("SELECT snapshot_json FROM learning_material WHERE id=?",String.class,id);JsonNode snapshot=json.readTree(raw);
            assertThat(snapshot.size()).isEqualTo(101);assertThat(snapshot.get(0).path("result")).isEqualTo(grade);assertThat(raw).doesNotContain("HIDDEN_SECRET");
        }
    }
    @Test void streamMaterialBudgetFailureStaysFailedAndNewRequestCanRecover()throws Exception{
        String alice=token("alice");UUID source=source("alice"),request=UUID.randomUUID();
        db.update("UPDATE student_coach_session SET state_json=? WHERE id=?",json.writeValueAsString(Map.of("messages",List.of(Map.of("id","big","role","assistant","text","学".repeat(1000001))))),source);
        org.mockito.Mockito.when(ai.coachRoute(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenReturn(json.readTree("{\"action\":\"material\",\"keys\":[],\"reply\":\"整理\",\"suggestions\":[]}"));
        var body=Map.of("requestId",request,"action","chat","text","整理学习资料");
        var pending=mvc.perform(post("/api/v1/coach/sessions/"+source+"/turns/stream").header("Authorization","Bearer "+alice).contentType("application/json").content(json.writeValueAsString(body))).andExpect(request().asyncStarted()).andReturn();
        String stream=mvc.perform(asyncDispatch(pending)).andReturn().getResponse().getContentAsString();
        assertThat(stream).contains("event:error","41340").doesNotContain("event:done");
        assertThat(db.queryForObject("SELECT status FROM student_coach_turn WHERE session_id=? AND request_id=?",String.class,source,request)).isEqualTo("failed");
        assertThat(db.queryForObject("SELECT processing_request FROM student_coach_session WHERE id=?",UUID.class,source)).isNull();
        assertThat(db.queryForObject("SELECT state_json FROM student_coach_session WHERE id=?",String.class,source)).contains("学习资料整理任务创建失败");
        mvc.perform(post("/api/v1/coach/sessions/"+source+"/turns/stream").header("Authorization","Bearer "+alice).contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isServiceUnavailable());
        db.update("UPDATE student_coach_session SET state_json=? WHERE id=?","{\"messages\":[{\"id\":\"small\",\"role\":\"user\",\"text\":\"复习财政政策\"}]}",source);
        var retry=mvc.perform(post("/api/v1/coach/sessions/"+source+"/turns/stream").header("Authorization","Bearer "+alice).contentType("application/json").content(json.writeValueAsString(Map.of("requestId",UUID.randomUUID(),"action","chat","text","整理学习资料")))).andExpect(request().asyncStarted()).andReturn();
        assertThat(mvc.perform(asyncDispatch(retry)).andReturn().getResponse().getContentAsString()).contains("event:done","queued");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM learning_material",Integer.class)).isEqualTo(1);org.mockito.Mockito.verify(ai,org.mockito.Mockito.times(2)).coachRoute(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
    }
    @Test void concurrentIdenticalCreatesShareOneTaskAfterWaitingForSourceLock()throws Exception{
        String alice=token("alice");UUID source=source("alice"),request=UUID.randomUUID();
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try(var connection=db.getDataSource().getConnection()){
            connection.setAutoCommit(false);
            try(var statement=connection.prepareStatement("SELECT id FROM student_coach_session WHERE id=? FOR UPDATE")){statement.setObject(1,source);statement.executeQuery().close();}
            var first=pool.submit(()->create(source,alice,request));var second=pool.submit(()->create(source,alice,request));
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);int blocked=0;
            while(System.nanoTime()<deadline){blocked=db.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSIONS WHERE BLOCKER_ID IS NOT NULL",Integer.class);if(blocked>=2)break;Thread.yield();}
            assertThat(blocked).isGreaterThanOrEqualTo(2);connection.commit();
            assertThat(first.get(5,java.util.concurrent.TimeUnit.SECONDS).path("id")).isEqualTo(second.get(5,java.util.concurrent.TimeUnit.SECONDS).path("id"));
        }finally{pool.shutdownNow();}
        assertThat(db.queryForObject("SELECT COUNT(*) FROM learning_material",Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM learning_material_request",Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM mq_outbox",Integer.class)).isEqualTo(1);
    }
    @Test void concurrentCreateAndRetryLockSourceBeforeMaterial()throws Exception{
        String alice=token("alice");UUID source=source("alice"),id=UUID.fromString(create(source,alice,UUID.randomUUID()).path("id").asText());
        db.update("UPDATE learning_material SET status='failed' WHERE id=?",id);
        var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
        try(var connection=db.getDataSource().getConnection()){
            connection.setAutoCommit(false);
            try(var statement=connection.prepareStatement("SELECT id FROM student_coach_session WHERE id=? FOR UPDATE")){statement.setObject(1,source);statement.executeQuery().close();}
            var retry=pool.submit(()->mvc.perform(post("/api/v1/study/materials/"+id+"/retry").header("Authorization","Bearer "+alice).contentType("application/json").content(json.writeValueAsString(Map.of("requestId",UUID.randomUUID())))).andReturn().getResponse().getStatus());
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);boolean blocked=false;
            while(System.nanoTime()<deadline){
                blocked=db.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSIONS WHERE BLOCKER_ID IS NOT NULL",Integer.class)>0;
                if(blocked)break;Thread.yield();
            }
            assertThat(blocked).isTrue();
            // 模拟持有来源锁的创建请求继续访问资料；重试不得反向持有资料锁。
            try(var statement=connection.createStatement()){statement.execute("SET LOCK_TIMEOUT 250");}
            try(var statement=connection.prepareStatement("SELECT id FROM learning_material WHERE id=? FOR UPDATE")){statement.setObject(1,id);statement.executeQuery().close();}
            connection.commit();assertThat(retry.get(5,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(200);
        }finally{pool.shutdownNow();}
        assertThat(db.queryForObject("SELECT COUNT(*) FROM learning_material WHERE session_id=? AND status IN ('queued','processing')",Integer.class,source)).isEqualTo(1);
    }
    @Test void createQueuesWithoutModelAndIsOwnerOnlyAndIdempotent()throws Exception{
        String alice=token("alice"),bob=token("bob"),admin=token("admin");UUID source=source("alice"),request=UUID.randomUUID();
        JsonNode first=create(source,alice,request),again=create(source,alice,request);
        assertThat(first.path("status").asText()).isEqualTo("queued");assertThat(again.path("id").asText()).isEqualTo(first.path("id").asText());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM mq_outbox",Integer.class)).isEqualTo(1);
        org.mockito.Mockito.verifyNoInteractions(ai);
        mvc.perform(get("/api/v1/study/materials/"+first.path("id").asText()).header("Authorization","Bearer "+bob)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/study/materials").header("Authorization","Bearer "+admin)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/coach/sessions/"+source+"/materials").header("Authorization","Bearer "+bob).contentType("application/json").content(json.writeValueAsString(Map.of("requestId",UUID.randomUUID())))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/study/materials")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/study/materials/"+first.path("id").asText()+"/download").header("Authorization","Bearer "+alice)).andExpect(status().isConflict());
    }
}
