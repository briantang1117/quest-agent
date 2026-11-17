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

@SpringBootTest(properties={"spring.config.import=","spring.datasource.url=jdbc:h2:mem:workflow;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password=","spring.flyway.enabled=false","spring.autoconfigure.exclude=com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration","quest.persistence.mode=postgresql","INTERNAL_SERVICE_TOKEN=workflow-token","INTERNAL_HMAC_SECRET=workflow-hmac-secret-at-least-32-bytes","quest.security.jwt.secret=student-coach-test-secret-at-least-32-bytes","quest.security.login.account-limit=100","quest.security.login.source-limit=1000"})
@ActiveProfiles("test") @AutoConfigureMockMvc
class CoachWorkflowTest {
    @Autowired MockMvc mvc; @Autowired JdbcTemplate db; @Autowired ObjectMapper json;
    @Autowired com.quest.api.coach.StudentCoachService coach; @Autowired UserService users;
    @Autowired org.springframework.transaction.PlatformTransactionManager manager;
    @org.springframework.test.context.bean.override.mockito.MockitoBean com.quest.api.coach.CoachAiClient ai;
    @org.springframework.test.context.bean.override.mockito.MockitoBean com.quest.api.syllabus.SyllabusService syllabi;
    static final String PASSWORD="Initial-password-123";
    static String h2(String sql){return sql.replace("TIMESTAMPTZ","TIMESTAMP WITH TIME ZONE").replace("embedding vector","embedding TEXT").replace("AND vector_dims(embedding)=embedding_dimensions","");}
    @BeforeEach void setup() throws Exception {
        db.execute("DROP ALL OBJECTS");db.execute("CREATE ROLE quest_app");db.execute("CREATE ROLE quest_ai");db.execute("CREATE SEQUENCE app_user_id_seq");db.execute("CREATE SEQUENCE audit_log_id_seq");
        db.execute("CREATE TABLE app_user(id BIGSERIAL PRIMARY KEY,username VARCHAR(64) UNIQUE NOT NULL,password_hash VARCHAR(128) NOT NULL,display_name VARCHAR(128) NOT NULL,status VARCHAR(16) DEFAULT 'active',created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP)");
        db.execute("CREATE TABLE audit_log(id BIGSERIAL PRIMARY KEY,actor BIGINT,action VARCHAR(64),target_type VARCHAR(64),target_id VARCHAR(64),before_data JSONB,after_data JSONB,ip VARCHAR(45),user_agent TEXT,trace_id VARCHAR(64),ai_job_id BIGINT,created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP)");TestUserSchema.applyV4(db);
        for(String file:List.of("V5__public_banks_and_imports.sql","V6__public_pdf_batches.sql","V7__public_question_soft_delete.sql","V8__student_coach.sql","V9__ai_call_audit.sql","V10__syllabus_versions.sql","V12__student_coach_session_soft_delete.sql")){String sql=h2(new String(new ClassPathResource("db/migration/"+file).getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));new ResourceDatabasePopulator(new org.springframework.core.io.ByteArrayResource(sql.getBytes(java.nio.charset.StandardCharsets.UTF_8))).execute(db.getDataSource());}
        var bcrypt=new BCryptPasswordEncoder();for(String name:List.of("admin","alice","bob"))db.update("INSERT INTO app_user(username,password_hash,display_name,account_type,must_change_password) VALUES (?,?,?,?,FALSE)",name,bcrypt.encode(PASSWORD),name,name.equals("admin")?"admin":"student");
        var chatReply=json.readTree("{\"action\":\"chat\",\"keys\":[],\"reply\":\"我们一起理解这个知识点。\"}");
        // Service 走带归因的三参重载，两参重载仅为兼容保留，两者都要打桩。
        org.mockito.Mockito.when(ai.turn(org.mockito.ArgumentMatchers.any())).thenReturn(chatReply);
        org.mockito.Mockito.when(ai.turn(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenReturn(chatReply);
    }
    @org.junit.jupiter.api.AfterEach void completedStreamsLeaveNoRegisteredControls()throws Exception{
        var registry=com.quest.api.coach.StudentCoachService.class.getDeclaredField("activeStreams");registry.setAccessible(true);
        assertThat((Set<?>)registry.get(coach)).isEmpty();
    }
    /** 兼容测试辅助。 */
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
    JsonNode create(String token,UUID id,String mode)throws Exception{return postData("/api/v1/coach/sessions",token,Map.of("requestId",id,"mode",mode,"preview",false),200);}

    JsonNode callback(String grant,String step,List<String> keys)throws Exception{
        String body=json.writeValueAsString(Map.of("grant",grant,"step",step,"keys",keys));
        String stamp=Long.toString(java.time.Instant.now().getEpochSecond()),nonce=UUID.randomUUID().toString();
        String msg=String.join("\n",stamp,nonce,"POST","/internal/coach/workflow-step",HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(body.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        var mac=javax.crypto.Mac.getInstance("HmacSHA256");mac.init(new javax.crypto.spec.SecretKeySpec("workflow-hmac-secret-at-least-32-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8),"HmacSHA256"));
        return json.readTree(mvc.perform(post("/internal/coach/workflow-step").contentType("application/json").content(body).header("X-Service-Token","workflow-token").header("X-Timestamp",stamp).header("X-Nonce",nonce).header("X-Signature",HexFormat.of().formatHex(mac.doFinal(msg.getBytes(java.nio.charset.StandardCharsets.UTF_8))))).andReturn().getResponse().getContentAsString());
    }
    JsonNode stream(String token,UUID session,Map<String,Object> input)throws Exception{
        var r=mvc.perform(post("/api/v1/coach/sessions/"+session+"/turns/stream").header("Authorization","Bearer "+token).contentType("application/json").content(json.writeValueAsString(input))).andExpect(status().isOk()).andReturn();
        r.getAsyncResult(10000);mvc.perform(asyncDispatch(r));
        return json.readTree(db.queryForObject("SELECT state_json FROM student_coach_session WHERE id=?",String.class,session));
    }
    @Test void confirmedAnswerUsesJavaSelectionAndDuplicateToolDoesNotGradeAgain()throws Exception{
        String alice=token("alice");preference(alice,"vocational_aptitude");seed("vocational_aptitude","published",false);UUID session=UUID.randomUUID();var initial=create(alice,session,"practice");
        var grantRef=new java.util.concurrent.atomic.AtomicReference<String>();
        org.mockito.Mockito.doAnswer(call->{
            JsonNode payload=json.valueToTree(call.getArgument(0));String grant=payload.path("grant").asText();grantRef.set(grant);
            assertThat(payload.path("context").toString()).doesNotContain("answer_keys","PRIVATE_EXPLANATION");
            var first=callback(grant,"submit_answer",List.of("A"));assertThat(first.path("code").asInt()).isZero();
            assertThat(first.path("data").path("context").path("result").path("correct").asBoolean()).isTrue();
            assertThat(callback(grant,"submit_answer",List.of("A")).path("data")).isEqualTo(first.path("data"));
            com.quest.api.coach.CoachStreamControl control=call.getArgument(4);control.workflowState();
            java.util.function.Consumer<String> sink=call.getArgument(3);sink.accept("已根据权威判分讲解。");return json.readTree("{\"kind\":\"explain\",\"items\":[]}");
        }).when(ai).workflow(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        UUID request=UUID.randomUUID();var input=Map.<String,Object>of("requestId",request,"action","answer","questionId",initial.path("currentQuestion").path("id").asText(),"keys",List.of("B"));
        var state=stream(alice,session,input);assertThat(state.path("result").path("correct").asBoolean()).isTrue();assertThat(state.path("messages").toString()).contains("已根据权威判分讲解");
        assertThat(callback(grantRef.get(),"context",List.of()).path("code").asInt()).isNotZero();
        assertThat(callback(grantRef.get()+"x","context",List.of()).path("code").asInt()).isNotZero();
        org.mockito.Mockito.verify(ai,org.mockito.Mockito.never()).route(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
    }
    @Test void explanationFailureKeepsToolGradeAndNeverStoresPartialReply()throws Exception{
        String alice=token("alice");preference(alice,"vocational_aptitude");seed("vocational_aptitude","published",false);UUID session=UUID.randomUUID();var initial=create(alice,session,"practice");
        org.mockito.Mockito.doAnswer(call->{
            JsonNode payload=json.valueToTree(call.getArgument(0));assertThat(callback(payload.path("grant").asText(),"submit_answer",List.of()).path("code").asInt()).isZero();
            java.util.function.Consumer<String> sink=call.getArgument(3);sink.accept("PARTIAL_BODY");throw new UserFailure(503,50203,"failed");
        }).when(ai).workflow(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        var state=stream(alice,session,Map.of("requestId",UUID.randomUUID(),"action","answer","questionId",initial.path("currentQuestion").path("id").asText(),"keys",List.of("B")));
        assertThat(state.path("result").path("correct").asBoolean()).isTrue();assertThat(state.path("messages").toString()).contains("作答已保存").doesNotContain("PARTIAL_BODY");
    }
    @Test void chatCannotSubmitAnswerButCanSuggestConfirmation()throws Exception{
        String alice=token("alice");preference(alice,"vocational_aptitude");seed("vocational_aptitude","published",false);UUID session=UUID.randomUUID();create(alice,session,"practice");
        org.mockito.Mockito.doAnswer(call->{JsonNode payload=json.valueToTree(call.getArgument(0));String grant=payload.path("grant").asText();
            assertThat(callback(grant,"submit_answer",List.of("B")).path("code").asInt()).isNotZero();
            assertThat(callback(grant,"suggest_answer",List.of("B")).path("code").asInt()).isZero();
            return json.readTree("{\"kind\":\"state_only\",\"items\":[]}");
        }).when(ai).workflow(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        var state=stream(alice,session,Map.of("requestId",UUID.randomUUID(),"action","chat","text","我选B"));
        assertThat(state.hasNonNull("result")).isFalse();assertThat(state.path("suggestedKeys").get(0).asText()).isEqualTo("B");assertThat(state.path("messages").toString()).contains("确认选项后提交");
    }
    @Test void callbackRequiresExactHmacRoute()throws Exception{
        mvc.perform(post("/internal/coach/workflow-step").contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/coach/arbitrary").contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
    }
    @Test void actualPythonHttpStreamCanCallbackWithoutLockingAndRetainsGradeOnTruncation()throws Exception{
        String alice=token("alice");preference(alice,"vocational_aptitude");seed("vocational_aptitude","published",false);UUID session=UUID.randomUUID();var initial=create(alice,session,"practice");
        var errors=new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/internal/coach-workflow",exchange->{
            try{
                JsonNode payload=json.readTree(exchange.getRequestBody().readAllBytes());
                // 在另一线程回调数据库，外层若持有行锁会让此处超时。
                var task=java.util.concurrent.CompletableFuture.supplyAsync(()->{try{return callback(payload.path("grant").asText(),"submit_answer",List.of());}catch(Exception e){throw new RuntimeException(e);}});
                assertThat(task.get(3,java.util.concurrent.TimeUnit.SECONDS).path("data").path("context").path("result").path("correct").asBoolean()).isTrue();
                byte[] response="event:workflow_state\ndata:{}\n\nevent:delta\ndata:{\"text\":\"PARTIAL_HTTP\"}\n\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);
            }catch(Throwable e){errors.set(e);exchange.sendResponseHeaders(500,-1);}finally{exchange.close();}
        });server.start();
        try{
            var real=new com.quest.api.publicbank.PublicAiClient(json,"http://127.0.0.1:"+server.getAddress().getPort(),"workflow-token","workflow-hmac-secret-at-least-32-bytes",org.mockito.Mockito.mock(com.quest.api.coach.AiCallAudit.class));
            org.mockito.Mockito.doAnswer(c->real.coachWorkflow(c.getArgument(0),new com.quest.api.publicbank.PublicAiClient.Caller(Long.valueOf(c.getArgument(1)),c.getArgument(2)),c.getArgument(3),c.getArgument(4))).when(ai).workflow(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
            var state=stream(alice,session,Map.of("requestId",UUID.randomUUID(),"action","answer","questionId",initial.path("currentQuestion").path("id").asText(),"keys",List.of("B")));
            assertThat(errors.get()).isNull();assertThat(state.path("result").path("correct").asBoolean()).isTrue();assertThat(state.path("messages").toString()).contains("作答已保存").doesNotContain("PARTIAL_HTTP");
        }finally{server.stop(0);}
    }
    @Test void duplicateNextCallbackCannotAdvanceTwice()throws Exception{
        String alice=token("alice");preference(alice,"vocational_aptitude");seed("vocational_aptitude","published",false);UUID session=UUID.randomUUID();create(alice,session,"chat");
        org.mockito.Mockito.doAnswer(call->{JsonNode payload=json.valueToTree(call.getArgument(0));String grant=payload.path("grant").asText();
            var first=callback(grant,"next_question",List.of());assertThat(first.path("code").asInt()).isZero();
            assertThat(callback(grant,"next_question",List.of()).path("data")).isEqualTo(first.path("data"));
            assertThat(callback(grant,"suggest_answer",List.of("B")).path("code").asInt()).isNotZero();
            return json.readTree("{\"kind\":\"state_only\",\"items\":[]}");
        }).when(ai).workflow(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        var state=stream(alice,session,Map.of("requestId",UUID.randomUUID(),"action","next"));
        assertThat(state.path("seen").size()).isEqualTo(1);assertThat(state.path("messages").size()).isEqualTo(3);
    }
    @Test void cancelledControlRejectsCallbackBeforeClaimCleanup()throws Exception{
        String alice=token("alice");preference(alice,"vocational_aptitude");seed("vocational_aptitude","published",false);UUID session=UUID.randomUUID();var initial=create(alice,session,"practice");
        org.mockito.Mockito.doAnswer(call->{JsonNode payload=json.valueToTree(call.getArgument(0));com.quest.api.coach.CoachStreamControl control=call.getArgument(4);control.close();
            assertThat(callback(payload.path("grant").asText(),"submit_answer",List.of()).path("code").asInt()).isNotZero();throw new UserFailure(503,50332,"cancelled");
        }).when(ai).workflow(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        var state=stream(alice,session,Map.of("requestId",UUID.randomUUID(),"action","answer","questionId",initial.path("currentQuestion").path("id").asText(),"keys",List.of("B")));
        assertThat(state.hasNonNull("result")).isFalse();
    }
    @Test void deletedSessionCannotUseStillSignedGrant()throws Exception{
        String alice=token("alice");preference(alice,"vocational_aptitude");UUID session=UUID.randomUUID();create(alice,session,"chat");
        org.mockito.Mockito.doAnswer(call->{JsonNode payload=json.valueToTree(call.getArgument(0));String grant=payload.path("grant").asText();
            assertThat(callback(grant,"context",List.of()).path("code").asInt()).isZero();
            mvc.perform(delete("/api/v1/coach/sessions/"+session).header("Authorization","Bearer "+alice)).andExpect(status().isOk());
            assertThat(callback(grant,"context",List.of()).path("code").asInt()).isNotZero();
            throw new UserFailure(503,50332,"deleted");
        }).when(ai).workflow(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        stream(alice,session,Map.of("requestId",UUID.randomUUID(),"action","chat","text","继续"));
        assertThat(db.queryForObject("SELECT status FROM student_coach_turn",String.class)).isEqualTo("failed");
    }

    @Test void retrievalRunsAfterCallbackCommitAndItsFailureCannotRollbackGrade()throws Exception{
        String alice=token("alice");preference(alice,"vocational_aptitude");seed("vocational_aptitude","published",false);UUID session=UUID.randomUUID();var initial=create(alice,session,"practice");
        var state=(tools.jackson.databind.node.ObjectNode)json.readTree(db.queryForObject("SELECT state_json FROM student_coach_session WHERE id=?",String.class,session));
        state.put("syllabusVersionId",UUID.randomUUID().toString()).put("examYear",2027);db.update("UPDATE student_coach_session SET state_json=? WHERE id=?",state.toString(),session);
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.when(syllabi.context(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyInt(),org.mockito.ArgumentMatchers.any())).thenAnswer(c->{
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            calls.incrementAndGet();
            assertThat(json.readTree(db.queryForObject("SELECT state_json FROM student_coach_session WHERE id=?",String.class,session)).path("result").path("correct").asBoolean()).isTrue();
            throw new UserFailure(503,50203,"retrieval failed");
        });
        org.mockito.Mockito.doAnswer(call->{JsonNode payload=json.valueToTree(call.getArgument(0));
            assertThat(callback(payload.path("grant").asText(),"submit_answer",List.of()).path("code").asInt()).isNotZero();
            assertThat(json.readTree(db.queryForObject("SELECT state_json FROM student_coach_session WHERE id=?",String.class,session)).path("result").path("correct").asBoolean()).isTrue();
            throw new UserFailure(503,50203,"retrieval failed");
        }).when(ai).workflow(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        var finalState=stream(alice,session,Map.of("requestId",UUID.randomUUID(),"action","answer","questionId",initial.path("currentQuestion").path("id").asText(),"keys",List.of("B")));
        assertThat(finalState.path("result").path("correct").asBoolean()).isTrue();assertThat(calls.get()).isEqualTo(1);
    }

    @Test void loggedOutIdentityCannotUseStillSignedGrant()throws Exception{
        String alice=token("alice");preference(alice,"vocational_aptitude");UUID session=UUID.randomUUID();create(alice,session,"chat");
        org.mockito.Mockito.doAnswer(call->{JsonNode payload=json.valueToTree(call.getArgument(0));String grant=payload.path("grant").asText();
            assertThat(callback(grant,"context",List.of()).path("code").asInt()).isZero();
            mvc.perform(post("/api/v1/auth/logout").header("Authorization","Bearer "+alice)).andExpect(status().isOk());
            assertThat(callback(grant,"context",List.of()).path("code").asInt()).isNotZero();
            throw new UserFailure(503,50332,"logged out");
        }).when(ai).workflow(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        stream(alice,session,Map.of("requestId",UUID.randomUUID(),"action","chat","text","继续"));
        assertThat(db.queryForObject("SELECT status FROM student_coach_turn",String.class)).isEqualTo("failed");
    }

    @Test void finalizationWaitsForToolCommitAndReReadsAuthorityInsteadOfOverwritingIt()throws Exception{
        String alice=token("alice");preference(alice,"vocational_aptitude");seed("vocational_aptitude","published",false);UUID session=UUID.randomUUID();var initial=create(alice,session,"practice");
        long user=db.queryForObject("SELECT id FROM app_user WHERE username='alice'",Long.class);
        var who=users.session(Long.toString(user),db.queryForObject("SELECT id FROM auth_session WHERE user_id=?",String.class,user));
        var input=new com.quest.api.coach.StudentCoachService.Turn(UUID.randomUUID(),"answer",null,initial.path("currentQuestion").path("id").asText(),List.of("B"));
        var claim=coach.getClass().getDeclaredMethod("claim",UserModels.SessionIdentity.class,String.class,com.quest.api.coach.StudentCoachService.Turn.class);claim.setAccessible(true);Object pending=claim.invoke(coach,who,session.toString(),input);
        var sessionAccessor=pending.getClass().getDeclaredMethod("session");sessionAccessor.setAccessible(true);Object staleSession=sessionAccessor.invoke(pending);
        var finish=coach.getClass().getDeclaredMethod("finishWorkflow",UserModels.SessionIdentity.class,staleSession.getClass(),com.quest.api.coach.StudentCoachService.Turn.class,JsonNode.class,String.class,boolean.class);finish.setAccessible(true);
        var updated=new java.util.concurrent.CountDownLatch(1);var commit=new java.util.concurrent.CountDownLatch(1);
        var writer=java.util.concurrent.CompletableFuture.runAsync(()->new org.springframework.transaction.support.TransactionTemplate(manager).execute(status->{
            var state=(tools.jackson.databind.node.ObjectNode)json.readTree(db.queryForObject("SELECT state_json FROM student_coach_session WHERE id=? FOR UPDATE",String.class,session));
            state.set("result",json.readTree("{\"correct\":true,\"selectedKeys\":[\"B\"],\"answerKeys\":[\"B\"],\"explanationMd\":\"权威解析\"}"));state.set("_workflow",json.createObjectNode().put("requestId",input.requestId().toString()).put("step","submit_answer"));
            db.update("UPDATE student_coach_session SET state_json=? WHERE id=?",state.toString(),session);updated.countDown();
            try{if(!commit.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("commit timeout");}catch(InterruptedException e){throw new RuntimeException(e);}return null;
        }));
        assertThat(updated.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        var finalizer=java.util.concurrent.CompletableFuture.supplyAsync(()->{try{return (JsonNode)finish.invoke(coach,who,staleSession,input,null,"",true);}catch(Exception e){throw new RuntimeException(e);}});
        try{assertThatThrownBy(()->finalizer.get(150,java.util.concurrent.TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);}finally{commit.countDown();}
        writer.get(5,java.util.concurrent.TimeUnit.SECONDS);var result=finalizer.get(5,java.util.concurrent.TimeUnit.SECONDS);
        assertThat(result.path("result").path("correct").asBoolean()).isTrue();
        var persisted=json.readTree(db.queryForObject("SELECT state_json FROM student_coach_session WHERE id=?",String.class,session));assertThat(persisted.path("_workflow").path("step").asText()).isEqualTo("submit_answer");assertThat(persisted.path("messages").toString()).contains("作答已保存");
    }

    @Test void chatLoadsSyllabusOnlyInPrepareAndExplicitNextNeverRetrieves()throws Exception{
        String alice=token("alice");preference(alice,"vocational_aptitude");seed("vocational_aptitude","published",false);
        var retrieves=new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.when(syllabi.context(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyInt(),org.mockito.ArgumentMatchers.any())).thenAnswer(c->{assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();retrieves.incrementAndGet();return json.createArrayNode();});
        org.mockito.Mockito.doAnswer(call->{JsonNode payload=json.valueToTree(call.getArgument(0));String action=payload.path("action").asText(),grant=payload.path("grant").asText();
            assertThat(payload.path("context").has("syllabus")).isFalse();assertThat(callback(grant,"context",List.of()).path("code").asInt()).isZero();
            if(action.equals("next")){assertThat(callback(grant,"next_question",List.of()).path("code").asInt()).isZero();return json.readTree("{\"kind\":\"state_only\",\"items\":[]}");}
            java.util.function.Consumer<String> sink=call.getArgument(3);sink.accept("答疑正文");return json.readTree("{\"kind\":\"explain\",\"items\":[]}");
        }).when(ai).workflow(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        for(String action:List.of("chat","next")){
            UUID session=UUID.randomUUID();create(alice,session,"chat");var state=(tools.jackson.databind.node.ObjectNode)json.readTree(db.queryForObject("SELECT state_json FROM student_coach_session WHERE id=?",String.class,session));state.put("examYear",2027).put("syllabusVersionId",UUID.randomUUID().toString());db.update("UPDATE student_coach_session SET state_json=? WHERE id=?",state.toString(),session);
            stream(alice,session,Map.of("requestId",UUID.randomUUID(),"action",action,"text","解释知识点"));
        }
        assertThat(retrieves.get()).isEqualTo(1);
    }

    @Test void completeWorkflowStoresMemoryAndFailurePreservesPriorSummary()throws Exception{
        String alice=token("alice");preference(alice,"vocational_aptitude");UUID session=UUID.randomUUID();create(alice,session,"chat");
        org.mockito.Mockito.doAnswer(call->{java.util.function.Consumer<String> sink=call.getArgument(3);sink.accept("完整讲解");return json.readTree("{\"kind\":\"explain\",\"items\":[],\"memory\":{\"summary\":\"已经讨论过的知识结论\",\"progress\":\"\",\"questions\":\"\",\"plan\":\"\"}}");}).when(ai).workflow(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        var state=stream(alice,session,Map.of("requestId",UUID.randomUUID(),"action","chat","text","请解释"));assertThat(state.path("memory").path("summary").asText()).isEqualTo("已经讨论过的知识结论");
        org.mockito.Mockito.doAnswer(call->{java.util.function.Consumer<String> sink=call.getArgument(3);sink.accept("半截新正文");throw new UserFailure(503,50203,"interrupted");}).when(ai).workflow(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        var failed=stream(alice,session,Map.of("requestId",UUID.randomUUID(),"action","chat","text","继续"));assertThat(failed.path("memory")).isEqualTo(state.path("memory"));assertThat(failed.path("messages").toString()).doesNotContain("半截新正文");
    }

    @Test void firstDraftWorkflowDoesNotDuplicateUserAndCommitsGeneratedTitle()throws Exception{
        String alice=token("alice");preference(alice,"vocational_aptitude");UUID session=UUID.randomUUID();
        org.mockito.Mockito.doAnswer(call->{
            JsonNode payload=json.valueToTree(call.getArgument(0));assertThat(payload.path("context").path("title_pending").asBoolean()).isTrue();
            var context=callback(payload.path("grant").asText(),"context",List.of());assertThat(context.path("code").asInt()).isZero();
            java.util.function.Consumer<String> sink=call.getArgument(3);sink.accept("完整概念讲解");return json.readTree("{\"kind\":\"explain\",\"items\":[],\"title\":\"考纲重点与复习安排\"}");
        }).when(ai).workflow(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        var input=Map.<String,Object>of("requestId",UUID.randomUUID(),"action","chat","text","考纲重点怎么安排复习","draft",Map.of());
        var state=stream(alice,session,input);int count=0;for(var message:state.path("messages"))if(message.path("role").asText().equals("user"))count++;assertThat(count).isEqualTo(1);
        assertThat(state.path("titleGenerated").asBoolean()).isTrue();assertThat(db.queryForObject("SELECT title FROM student_coach_session WHERE id=?",String.class,session)).isEqualTo("考纲重点与复习安排");
        stream(alice,session,input);org.mockito.Mockito.verify(ai,org.mockito.Mockito.times(1)).workflow(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
    }

}
