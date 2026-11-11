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

@SpringBootTest(properties={"spring.config.import=","spring.datasource.url=jdbc:h2:mem:coach;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password=","spring.flyway.enabled=false","spring.autoconfigure.exclude=com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration","quest.persistence.mode=postgresql","quest.security.jwt.secret=student-coach-test-secret-at-least-32-bytes","quest.security.login.account-limit=100","quest.security.login.source-limit=1000"})
@ActiveProfiles("test") @AutoConfigureMockMvc
class StudentCoachTest {
    @Autowired MockMvc mvc; @Autowired JdbcTemplate db; @Autowired ObjectMapper json;
    @Autowired com.quest.api.coach.StudentCoachService coach; @Autowired UserService users;
    @org.springframework.test.context.bean.override.mockito.MockitoBean com.quest.api.coach.CoachAiClient ai;
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
    @Test void deletedSessionIsHiddenAndCannotReplayOrRecreate()throws Exception{
        String alice=token("alice");preference(alice,"vocational_aptitude");
        UUID session=UUID.randomUUID();create(alice,session,"chat");
        var input=Map.of("requestId",UUID.randomUUID(),"action","chat","text","私人历史");
        postData("/api/v1/coach/sessions/"+session+"/turns",alice,input,200);
        String state=db.queryForObject("SELECT state_json FROM student_coach_session WHERE id=?",String.class,session);
        mvc.perform(delete("/api/v1/coach/sessions/"+session).header("Authorization","Bearer "+alice).header("X-Trace-Id","delete-session-test"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.traceId").value("delete-session-test"));
        assertThat(db.queryForObject("SELECT deleted_at FROM student_coach_session WHERE id=?",java.time.OffsetDateTime.class,session)).isNotNull();
        assertThat(db.queryForObject("SELECT state_json FROM student_coach_session WHERE id=?",String.class,session)).isEqualTo(state);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM student_coach_turn WHERE session_id=?",Integer.class,session)).isEqualTo(1);
        mvc.perform(get("/api/v1/coach/sessions").header("Authorization","Bearer "+alice)).andExpect(status().isOk()).andExpect(jsonPath("$.data.list.length()").value(0));
        mvc.perform(get("/api/v1/coach/sessions/"+session).header("Authorization","Bearer "+alice)).andExpect(status().isNotFound());
        postData("/api/v1/coach/sessions/"+session+"/turns",alice,input,404);
        postData("/api/v1/coach/sessions/"+session+"/turns",alice,Map.of("requestId",UUID.randomUUID(),"action","next"),404);
        mvc.perform(post("/api/v1/coach/sessions/"+session+"/turns/stream").header("Authorization","Bearer "+alice).contentType("application/json").content(json.writeValueAsString(input))).andExpect(status().isNotFound());
        postData("/api/v1/coach/sessions",alice,Map.of("requestId",session,"mode","chat"),404);
        mvc.perform(delete("/api/v1/coach/sessions/"+session).header("Authorization","Bearer "+alice)).andExpect(status().isNotFound());
        turnCount(1);
    }
    @Test void deleteIsOwnerOnlyAndRequiresValidUnrestrictedLogin()throws Exception{
        String alice=token("alice"),bob=token("bob"),admin=token("admin");preference(alice,"vocational_aptitude");
        UUID session=UUID.randomUUID();create(alice,session,"chat");String path="/api/v1/coach/sessions/"+session;
        mvc.perform(delete(path)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(40100));
        mvc.perform(delete(path).header("Authorization","Bearer invalid")).andExpect(status().isUnauthorized());
        mvc.perform(delete(path).header("Authorization","Bearer "+bob)).andExpect(status().isNotFound());
        mvc.perform(delete(path).header("Authorization","Bearer "+admin)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/coach/sessions/"+UUID.randomUUID()).header("Authorization","Bearer "+alice)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/coach/sessions/not-a-uuid").header("Authorization","Bearer "+alice)).andExpect(status().isBadRequest());
        db.update("UPDATE app_user SET must_change_password=TRUE WHERE username='alice'");
        mvc.perform(delete(path).header("Authorization","Bearer "+alice)).andExpect(status().isForbidden());
        db.update("UPDATE app_user SET must_change_password=FALSE WHERE username='alice'");
        mvc.perform(post("/api/v1/auth/logout").header("Authorization","Bearer "+alice)).andExpect(status().isOk());
        mvc.perform(delete(path).header("Authorization","Bearer "+alice)).andExpect(status().isUnauthorized());
        assertThat(db.queryForObject("SELECT deleted_at FROM student_coach_session WHERE id=?",java.time.OffsetDateTime.class,session)).isNull();
    }
    @Test void deleteCancelsActiveStreamAndFailsClaimWithoutRevivingSession()throws Exception{
        String alice=token("alice"),bob=token("bob");preference(alice,"vocational_aptitude");UUID session=UUID.randomUUID();create(alice,session,"chat");
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);var interrupted=new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.when(ai.route(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenAnswer(call->{
            entered.countDown();
            try{assertThat(release.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}
            catch(InterruptedException cancelled){interrupted.countDown();}
            // 模拟上游忽略取消而迟到返回，仍不能继续讲解或恢复会话。
            return json.readTree("{\"action\":\"chat\",\"keys\":[],\"reply\":\"\"}");
        });
        UUID request=UUID.randomUUID();var input=Map.of("requestId",request,"action","chat","text","你好");
        var result=mvc.perform(post("/api/v1/coach/sessions/"+session+"/turns/stream").header("Authorization","Bearer "+alice).contentType("application/json").content(json.writeValueAsString(input))).andExpect(status().isOk()).andReturn();
        String preserved;
        try{
            assertThat(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            mvc.perform(delete("/api/v1/coach/sessions/"+session).header("Authorization","Bearer "+bob)).andExpect(status().isNotFound());
            assertThat(interrupted.getCount()).isEqualTo(1);
            preserved=db.queryForObject("SELECT state_json FROM student_coach_session WHERE id=?",String.class,session);
            mvc.perform(delete("/api/v1/coach/sessions/"+session).header("Authorization","Bearer "+alice)).andExpect(status().isOk());
            assertThat(Thread.currentThread().isInterrupted()).isFalse();
            assertThat(interrupted.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }finally{release.countDown();result.getAsyncResult(5000);}
        String events=mvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(events).doesNotContain("event:done","event:delta");
        assertDeletedClaim(session,request,preserved);
        org.mockito.Mockito.verify(ai,org.mockito.Mockito.never()).explain(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        mvc.perform(get("/api/v1/coach/sessions/"+session).header("Authorization","Bearer "+alice)).andExpect(status().isNotFound());
    }
    private void assertDeletedClaim(UUID session,UUID request,String state){
        assertThat(db.queryForObject("SELECT deleted_at FROM student_coach_session WHERE id=?",java.time.OffsetDateTime.class,session)).isNotNull();
        assertThat(db.queryForObject("SELECT processing_request FROM student_coach_session WHERE id=?",UUID.class,session)).isNull();
        assertThat(db.queryForObject("SELECT status FROM student_coach_turn WHERE session_id=? AND request_id=?",String.class,session,request)).isEqualTo("failed");
        assertThat(db.queryForObject("SELECT response_json FROM student_coach_turn WHERE session_id=? AND request_id=?",String.class,session,request)).isNull();
        assertThat(db.queryForObject("SELECT state_json FROM student_coach_session WHERE id=?",String.class,session)).isEqualTo(state);
    }
    @Test void deleteStaleProcessingClaimWithoutActiveControl()throws Exception{
        String alice=token("alice");preference(alice,"vocational_aptitude");UUID session=UUID.randomUUID();create(alice,session,"chat");UUID request=UUID.randomUUID();
        db.update("INSERT INTO student_coach_turn(id,session_id,request_id,request_hash,status,created_at) VALUES (?,?,?,?,'processing',?)",UUID.randomUUID(),session,request,"old",java.time.OffsetDateTime.now().minusDays(1));
        db.update("UPDATE student_coach_session SET processing_request=? WHERE id=?",request,session);
        String state=db.queryForObject("SELECT state_json FROM student_coach_session WHERE id=?",String.class,session);
        mvc.perform(delete("/api/v1/coach/sessions/"+session).header("Authorization","Bearer "+alice)).andExpect(status().isOk());
        assertDeletedClaim(session,request,state);
    }
    @Test void deleteClosesUpstreamAndLateGradedCompletionCannotOverwriteDeletion()throws Exception{
        String alice=token("alice");preference(alice,"vocational_aptitude");seed("vocational_aptitude","published",false);UUID session=UUID.randomUUID();var initial=create(alice,session,"practice");
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);var closed=new java.util.concurrent.CountDownLatch(1);
        var active=new java.util.concurrent.atomic.AtomicReference<com.quest.api.coach.CoachStreamControl>();
        org.mockito.Mockito.doAnswer(call->{
            com.quest.api.coach.CoachStreamControl control=call.getArgument(4);active.set(control);control.attach(closed::countDown);entered.countDown();
            // 保持上游未返回到删除之后，验证已提交判分也不能走迟到finish/降级finish复活。
            while(true)try{assertThat(release.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();break;}catch(InterruptedException ignored){/* 模拟上游迟到 */}
            java.util.function.Consumer<String> sink=call.getArgument(3);sink.accept("迟到的讲解");
            return json.readTree("{\"items\":[]}");
        }).when(ai).explain(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        UUID request=UUID.randomUUID();var input=Map.of("requestId",request,"action","answer","questionId",initial.path("currentQuestion").path("id").asText(),"keys",List.of("B"));
        var result=mvc.perform(post("/api/v1/coach/sessions/"+session+"/turns/stream").header("Authorization","Bearer "+alice).contentType("application/json").content(json.writeValueAsString(input))).andExpect(status().isOk()).andReturn();
        String preserved;
        try{
            assertThat(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            preserved=db.queryForObject("SELECT state_json FROM student_coach_session WHERE id=?",String.class,session);
            assertThat(json.readTree(preserved).path("result").path("correct").asBoolean()).isTrue();
            mvc.perform(delete("/api/v1/coach/sessions/"+session).header("Authorization","Bearer "+alice)).andExpect(status().isOk());
            assertThat(closed.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();assertThat(active.get().cancelled()).isTrue();
            assertDeletedClaim(session,request,preserved);
        }finally{release.countDown();result.getAsyncResult(5000);}
        assertThat(mvc.perform(asyncDispatch(result)).andReturn().getResponse().getContentAsString()).doesNotContain("event:done","迟到的讲解");
        assertDeletedClaim(session,request,preserved);
    }
    @Test void deletionBetweenClaimAndStreamRegistrationPreventsCheckpointAndModelCalls()throws Exception{
        String alice=token("alice");preference(alice,"vocational_aptitude");UUID session=UUID.randomUUID();create(alice,session,"chat");
        long user=db.queryForObject("SELECT id FROM app_user WHERE username='alice'",Long.class);
        var identity=users.session(Long.toString(user),db.queryForObject("SELECT id FROM auth_session WHERE user_id=?",String.class,user));
        var input=new com.quest.api.coach.StudentCoachService.Turn(UUID.randomUUID(),"chat","不能开始",null,null);
        var claim=com.quest.api.coach.StudentCoachService.class.getDeclaredMethod("claim",UserModels.SessionIdentity.class,String.class,com.quest.api.coach.StudentCoachService.Turn.class);claim.setAccessible(true);
        var pending=claim.invoke(coach,identity,session.toString(),input);
        String preserved=db.queryForObject("SELECT state_json FROM student_coach_session WHERE id=?",String.class,session);
        coach.delete(identity,session.toString());
        var control=new com.quest.api.coach.CoachStreamControl();
        var stream=com.quest.api.coach.StudentCoachService.class.getMethod("streamTurn",UserModels.SessionIdentity.class,pending.getClass(),String.class,java.util.function.BiConsumer.class,com.quest.api.coach.CoachStreamControl.class);
        assertThatThrownBy(()->stream.invoke(coach,identity,pending,"late-registration",(java.util.function.BiConsumer<String,JsonNode>)(event,data)->{throw new AssertionError("不应发送状态");},control))
            .isInstanceOf(java.lang.reflect.InvocationTargetException.class).hasCauseInstanceOf(UserFailure.class);
        assertThat(control.cancelled()).isTrue();assertThat(Thread.currentThread().isInterrupted()).isFalse();
        assertDeletedClaim(session,input.requestId(),preserved);
        org.mockito.Mockito.verify(ai,org.mockito.Mockito.never()).route(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
    }
    @Test void lateStreamCannotRestoreDeletedSessionAfterLeaseWasReplaced()throws Exception{
        String alice=token("alice");preference(alice,"vocational_aptitude");UUID session=UUID.randomUUID();create(alice,session,"chat");
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.when(ai.route(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenAnswer(call->{entered.countDown();assertThat(release.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();return json.readTree("{\"action\":\"chat\",\"keys\":[],\"reply\":\"\"}");});
        streamReply("迟到的回复");UUID old=UUID.randomUUID();
        var input=Map.of("requestId",old,"action","chat","text","旧轮次");
        var pending=mvc.perform(post("/api/v1/coach/sessions/"+session+"/turns/stream").header("Authorization","Bearer "+alice).contentType("application/json").content(json.writeValueAsString(input))).andExpect(status().isOk()).andReturn();
        String preserved;
        try{
            assertThat(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            // 只修改本地H2模拟旧进程超过租约；新轮次完成后允许删除，旧进程仍不能回写。
            db.update("UPDATE student_coach_turn SET created_at=? WHERE session_id=? AND request_id=?",java.time.OffsetDateTime.now().minusMinutes(10),session,old);
            done(stream(alice,session,Map.of("requestId",UUID.randomUUID(),"action","next")));
            mvc.perform(delete("/api/v1/coach/sessions/"+session).header("Authorization","Bearer "+alice)).andExpect(status().isOk());
            preserved=db.queryForObject("SELECT state_json FROM student_coach_session WHERE id=?",String.class,session);
        }finally{release.countDown();pending.getAsyncResult(5000);}
        String events=mvc.perform(asyncDispatch(pending)).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(events).doesNotContain("event:done");
        assertThat(db.queryForObject("SELECT state_json FROM student_coach_session WHERE id=?",String.class,session)).isEqualTo(preserved).doesNotContain("迟到的回复");
        assertThat(db.queryForObject("SELECT deleted_at FROM student_coach_session WHERE id=?",java.time.OffsetDateTime.class,session)).isNotNull();
        assertThat(db.queryForObject("SELECT status FROM student_coach_turn WHERE request_id=?",String.class,old)).isEqualTo("failed");
        mvc.perform(get("/api/v1/coach/sessions/"+session).header("Authorization","Bearer "+alice)).andExpect(status().isNotFound());
    }
    @Test void subjectRequiredAndAdminCannotUseStudentTools()throws Exception{
        String alice=token("alice");postData("/api/v1/coach/sessions",alice,Map.of("requestId",UUID.randomUUID(),"mode","practice"),409);
        mvc.perform(get("/api/v1/me/study-preference").header("Authorization","Bearer "+token("admin"))).andExpect(status().isForbidden());
        preference(alice,"vocational_aptitude");var s=create(alice,UUID.randomUUID(),"practice");assertThat(s.get("messages").toString()).contains("暂无");
    }
    private String stream(String token,UUID session,Object input)throws Exception{
        var result=mvc.perform(post("/api/v1/coach/sessions/"+session+"/turns/stream").header("Authorization","Bearer "+token)
            .contentType("application/json").content(json.writeValueAsString(input))).andExpect(status().isOk()).andReturn();
        result.getAsyncResult(5000);
        var completed=mvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andReturn();
        return completed.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }
    private JsonNode done(String stream){
        for(String block:stream.split("\n\n"))if(block.startsWith("event:done\n"))return json.readTree(block.substring(block.indexOf("data:")+5));
        throw new AssertionError("没有完成事件: "+stream);
    }
    private void streamReply(String text)throws Exception{
        org.mockito.Mockito.doAnswer(call->{
            java.util.function.Consumer<String> sink=call.getArgument(3);
            sink.accept(text.substring(0,1));sink.accept(text.substring(1));
            return json.readTree("{\"items\":[{\"action\":\"chat\",\"label\":\"举例\",\"text\":\"请举个例子\"},{\"action\":\"next\",\"label\":\"下一题\"}]}");
        }).when(ai).explain(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
    }
    @Test void toolSourcesPersistAndReloadWithSuccessfulMessage()throws Exception{
        String alice=token("alice");preference(alice,"vocational_aptitude");
        UUID session=UUID.randomUUID();create(alice,session,"chat");
        org.mockito.Mockito.when(ai.route(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenReturn(json.readTree("{\"action\":\"chat\",\"keys\":[],\"reply\":\"\"}"));
        org.mockito.Mockito.doAnswer(call->{
            java.util.function.Consumer<String> sink=call.getArgument(3);
            com.quest.api.coach.CoachStreamControl control=call.getArgument(4);
            control.toolStatus(json.readTree("{\"tool\":\"search_web\",\"status\":\"running\"}"));sink.accept("有出处的解释");
            return json.readTree("{\"items\":[],\"sources\":[{\"tool\":\"search_web\",\"title\":\"公开资料\",\"url\":\"https://example.com/reference\",\"retrieved_at\":\"2026-11-11T00:00:00Z\"}]}");
        }).when(ai).explain(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        String wire=stream(alice,session,Map.of("requestId",UUID.randomUUID(),"action","chat","text","解释概念"));
        assertThat(wire).contains("event:tool_status");
        JsonNode messages=done(wire).path("messages");
        assertThat(messages.get(messages.size()-1).path("sources").get(0).path("title").asText()).isEqualTo("公开资料");
        JsonNode saved=json.readTree(db.queryForObject("SELECT state_json FROM student_coach_session WHERE id=?",String.class,session));
        assertThat(saved.path("messages")).isEqualTo(messages);
    }
    @Test void unifiedAnswerStreamPersistsOnceAndKeepsSuggestions()throws Exception{
        String alice=token("alice");preference(alice,"vocational_aptitude");seed("vocational_aptitude","published",false);seed("vocational_aptitude","published",false);
        UUID session=UUID.randomUUID();JsonNode initial=create(alice,session,"practice");String q=initial.path("currentQuestion").path("id").asText();
        streamReply("流式权威讲解");
        var input=new com.quest.api.coach.StudentCoachService.Turn(UUID.randomUUID(),"answer","",q,List.of("A"));
        String events=stream(alice,session,input);JsonNode result=done(events);
        assertThat(events).contains("event:state","event:delta");
        assertThat(events.indexOf("event:state")).isLessThan(events.indexOf("event:delta"));
        assertThat(result.path("processing").asBoolean()).isFalse();
        assertThat(result.path("result").path("correct").asBoolean()).isFalse();
        var last=result.path("messages").get(result.path("messages").size()-1);
        assertThat(last.path("text").asText()).isEqualTo("流式权威讲解");
        assertThat(last.path("suggestions").size()).isEqualTo(2);
        var saved=json.readTree(db.queryForObject("SELECT state_json FROM student_coach_session WHERE id=?",String.class,session));
        assertThat(saved.path("messages")).isEqualTo(result.path("messages"));
        assertThat(done(stream(alice,session,input))).isEqualTo(result);
        org.mockito.Mockito.verify(ai,org.mockito.Mockito.times(1)).explain(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(ai,org.mockito.Mockito.never()).route(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        turnCount(0);
    }
    @Test void unifiedChatUsesShortRouteThenOneStreamAndNeverRevealsUnansweredSecrets()throws Exception{
        String alice=token("alice");preference(alice,"vocational_aptitude");seed("vocational_aptitude","published",false);seed("vocational_aptitude","published",false);
        UUID session=UUID.randomUUID();create(alice,session,"practice");
        org.mockito.Mockito.when(ai.route(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenReturn(json.readTree("{\"action\":\"chat\",\"keys\":[],\"reply\":\"\"}"));
        streamReply("概念解释");
        JsonNode result=done(stream(alice,session,Map.of("requestId",UUID.randomUUID(),"action","chat","text","这个词什么意思")));
        assertThat(result.toString()).doesNotContain("PRIVATE_EXPLANATION","PRIVATE_SOURCE");
        var last=result.path("messages").get(result.path("messages").size()-1);
        assertThat(last.path("text").asText()).isEqualTo("概念解释");
        // 未作答时不能给下一题，即使模型推荐也过滤。
        assertThat(last.path("suggestions").toString()).doesNotContain("next");
        var capture=org.mockito.ArgumentCaptor.forClass(Object.class);
        org.mockito.Mockito.verify(ai).route(capture.capture(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        assertThat(json.writeValueAsString(capture.getValue())).doesNotContain("PRIVATE_EXPLANATION","answer_keys");
        turnCount(0);
    }
    @Test void brokenStreamKeepsGradeAndDropsPartialWithoutRegenerating()throws Exception{
        String alice=token("alice");preference(alice,"vocational_aptitude");seed("vocational_aptitude","published",false);
        UUID session=UUID.randomUUID();JsonNode initial=create(alice,session,"practice");String q=initial.path("currentQuestion").path("id").asText();
        org.mockito.Mockito.doAnswer(call->{
            // 付费生成前，判分已经写库；哪怕网络断开也不会丢失。
            var saved=json.readTree(db.queryForObject("SELECT state_json FROM student_coach_session WHERE id=?",String.class,session));
            assertThat(saved.path("result").path("answerKeys").get(0).asText()).isEqualTo("B");
            java.util.function.Consumer<String> sink=call.getArgument(3);sink.accept("PRIVATE_PARTIAL");
            throw new UserFailure(503,50204,"PRIVATE_UPSTREAM");
        }).when(ai).explain(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        var input=Map.of("requestId",UUID.randomUUID(),"action","answer","text","","questionId",q,"keys",List.of("B"));
        String events=stream(alice,session,input);JsonNode result=done(events);
        assertThat(events).contains("event:reset");
        assertThat(result.path("result").path("correct").asBoolean()).isTrue();
        assertThat(result.path("messages").toString()).doesNotContain("PRIVATE_PARTIAL","PRIVATE_UPSTREAM");
        assertThat(done(stream(alice,session,input))).isEqualTo(result);
        org.mockito.Mockito.verify(ai,org.mockito.Mockito.times(1)).explain(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
    }
    @Test void parallelTurnsAreRejectedBeforeAnotherModelCall()throws Exception{
        String alice=token("alice");preference(alice,"vocational_aptitude");UUID session=UUID.randomUUID();create(alice,session,"chat");
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.when(ai.route(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenAnswer(call->{entered.countDown();assertThat(release.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();return json.readTree("{\"action\":\"chat\",\"keys\":[],\"reply\":\"\"}");});
        streamReply("回答正文");
        var input=Map.of("requestId",UUID.randomUUID(),"action","chat","text","你好");
        var first=mvc.perform(post("/api/v1/coach/sessions/"+session+"/turns/stream").header("Authorization","Bearer "+alice).contentType("application/json").content(json.writeValueAsString(input))).andExpect(status().isOk()).andReturn();
        try{
            assertThat(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            mvc.perform(post("/api/v1/coach/sessions/"+session+"/turns/stream").header("Authorization","Bearer "+alice).contentType("application/json").content(json.writeValueAsString(input))).andExpect(status().isConflict());
            var second=Map.of("requestId",UUID.randomUUID(),"action","next","text","");
            mvc.perform(post("/api/v1/coach/sessions/"+session+"/turns/stream").header("Authorization","Bearer "+alice).contentType("application/json").content(json.writeValueAsString(second))).andExpect(status().isConflict());
        }finally{release.countDown();first.getAsyncResult(5000);}
        org.mockito.Mockito.verify(ai,org.mockito.Mockito.times(1)).route(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
    }
    @Test void cancelledGenerationKeepsCommittedGradeAndUnlocksSession()throws Exception{
        String alice=token("alice");preference(alice,"vocational_aptitude");seed("vocational_aptitude","published",false);UUID session=UUID.randomUUID();var initial=create(alice,session,"practice");
        org.mockito.Mockito.doAnswer(call->{com.quest.api.coach.CoachStreamControl control=call.getArgument(4);control.close();control.check();return null;}).when(ai).explain(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        var input=Map.of("requestId",UUID.randomUUID(),"action","answer","text","","questionId",initial.path("currentQuestion").path("id").asText(),"keys",List.of("B"));
        stream(alice,session,input);
        assertThat(db.queryForObject("SELECT processing_request FROM student_coach_session WHERE id=?",UUID.class,session)).isNull();
        var saved=json.readTree(db.queryForObject("SELECT state_json FROM student_coach_session WHERE id=?",String.class,session));
        assertThat(saved.path("result").path("correct").asBoolean()).isTrue();
        assertThat(db.queryForObject("SELECT status FROM student_coach_turn WHERE session_id=?",String.class,session)).isEqualTo("done");
    }
    @Test void logoutDuringGenerationDoesNotLeaveProcessingClaim()throws Exception{
        String alice=token("alice");preference(alice,"vocational_aptitude");seed("vocational_aptitude","published",false);UUID session=UUID.randomUUID();var initial=create(alice,session,"practice");
        org.mockito.Mockito.doAnswer(call->{
            mvc.perform(post("/api/v1/auth/logout").header("Authorization","Bearer "+alice)).andExpect(status().isOk());
            throw new UserFailure(503,50204,"stream failed");
        }).when(ai).explain(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        var input=Map.of("requestId",UUID.randomUUID(),"action","answer","text","","questionId",initial.path("currentQuestion").path("id").asText(),"keys",List.of("B"));
        stream(alice,session,input);
        assertThat(db.queryForObject("SELECT processing_request FROM student_coach_session WHERE id=?",UUID.class,session)).isNull();
        assertThat(json.readTree(db.queryForObject("SELECT state_json FROM student_coach_session WHERE id=?",String.class,session)).path("result").path("correct").asBoolean()).isTrue();
    }
    @Test void expiredClaimIsReapedWithoutReplayingPaidOperation()throws Exception{
        String alice=token("alice");preference(alice,"vocational_aptitude");UUID session=UUID.randomUUID();create(alice,session,"chat");UUID old=UUID.randomUUID();
        db.update("INSERT INTO student_coach_turn(id,session_id,request_id,request_hash,status,created_at) VALUES (?,?,?,?,'processing',?)",UUID.randomUUID(),session,old,"old",java.time.OffsetDateTime.now().minusMinutes(10));
        db.update("UPDATE student_coach_session SET processing_request=? WHERE id=?",old,session);
        var result=done(stream(alice,session,Map.of("requestId",UUID.randomUUID(),"action","next","text","")));
        assertThat(result.path("processing").asBoolean()).isFalse();
        assertThat(db.queryForObject("SELECT status FROM student_coach_turn WHERE request_id=?",String.class,old)).isEqualTo("failed");
    }
    @Test void clientDisconnectReleasesClaimInsteadOfLeavingSessionStuck()throws Exception{
        String alice=token("alice");preference(alice,"vocational_aptitude");UUID session=UUID.randomUUID();create(alice,session,"chat");
        // 客户端在生成过程中断开：不能把会话永久留在 processing。
        org.mockito.Mockito.doAnswer(call->{
            com.quest.api.coach.CoachStreamControl control=call.getArgument(4);
            control.close();
            control.check();
            return null;
        }).when(ai).explain(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.when(ai.route(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any()))
            .thenReturn(json.readTree("{\"action\":\"chat\",\"keys\":[],\"reply\":\"\"}"));
        stream(alice,session,Map.of("requestId",UUID.randomUUID(),"action","chat","text","判断推理主要考什么"));
        assertThat(db.queryForObject("SELECT processing_request FROM student_coach_session WHERE id=?",UUID.class,session)).isNull();
    }
    @Test void streamIsPrivateAndLegacyIndependentExplainIsRemoved()throws Exception{
        String alice=token("alice"),bob=token("bob");preference(alice,"vocational_aptitude");UUID session=UUID.randomUUID();create(alice,session,"chat");
        var input=Map.of("requestId",UUID.randomUUID(),"action","chat","text","你好");
        mvc.perform(post("/api/v1/coach/sessions/"+session+"/turns/stream").header("Authorization","Bearer "+bob).contentType("application/json").content(json.writeValueAsString(input))).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/coach/sessions/"+session+"/turns/stream").header("Authorization","Bearer "+token("admin")).contentType("application/json").content(json.writeValueAsString(input))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/coach/sessions/"+session+"/explain").header("Authorization","Bearer "+alice)).andExpect(status().isNotFound());
    }

    @Test void quickSuggestionsComeFromBackendAndUnsafeOnesAreDropped()throws Exception{
        String alice=token("alice");preference(alice,"vocational_aptitude");seed("vocational_aptitude","published",false);seed("vocational_aptitude","published",false);
        // 两道题：出题后仍有未练过的题，next 建议才有意义。
        UUID session=UUID.randomUUID();create(alice,session,"practice");
        // 模型给的文案照用，但 "answer"/"publish" 这类动作不在白名单，必须丢弃。
        var reply=json.readTree("{\"action\":\"chat\",\"keys\":[],\"reply\":\"讲解内容。\",\"suggestions\":["
            +"{\"action\":\"next\",\"label\":\"继续下一题\"},"
            +"{\"action\":\"chat\",\"label\":\"再讲讲\",\"text\":\"再讲讲这个知识点\"},"
            +"{\"action\":\"publish\",\"label\":\"发布题目\"},"
            +"{\"action\":\"answer\",\"label\":\"替你提交\"}]}");
        org.mockito.Mockito.when(ai.turn(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenReturn(reply);
        JsonNode after=postData("/api/v1/coach/sessions/"+session+"/turns",alice,Map.of("requestId",UUID.randomUUID(),"action","chat","text","讲讲"),200);
        var last=after.get("messages").get(after.get("messages").size()-1);
        var labels=new java.util.ArrayList<String>();for(JsonNode s:last.path("suggestions"))labels.add(s.get("action").asText()+":"+s.get("label").asText());
        assertThat(labels).containsExactly("next:继续下一题","chat:再讲讲");
        // chat 建议带上预置话术，且都由前端渲染，服务端不代发。
        assertThat(last.path("suggestions").get(1).get("text").asText()).isEqualTo("再讲讲这个知识点");
    }

    @Test void nextSuggestionIsDroppedWhenNoQuestionRemains()throws Exception{
        String alice=token("alice");preference(alice,"vocational_aptitude");UUID q=seed("vocational_aptitude","published",false);UUID session=UUID.randomUUID();create(alice,session,"practice");
        // 唯一一道题已经在 seen 里，不能再说"下一题"。
        var reply=json.readTree("{\"action\":\"chat\",\"keys\":[],\"reply\":\"讲完了。\",\"suggestions\":[{\"action\":\"next\",\"label\":\"继续下一题\"}]}");
        org.mockito.Mockito.when(ai.turn(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenReturn(reply);
        postData("/api/v1/coach/sessions/"+session+"/turns",alice,Map.of("requestId",UUID.randomUUID(),"action","answer","questionId",q,"keys",List.of("A"),"text",""),200);
        // 判分讲解后的建议必须是"还能下一题"才给 next；本题是唯一题，已练过。
        var state=db.queryForObject("SELECT state_json FROM student_coach_session WHERE id=?",String.class,session);
        var last=json.readTree(state).get("messages").get(json.readTree(state).get("messages").size()-1);
        assertThat(last.path("suggestions").toString()).doesNotContain("继续下一题");
    }

    @Test void practiceCrossesPapersAndNeverReturnsUnansweredSecretsOrWrongSubject()throws Exception{
        String alice=token("alice");preference(alice,"vocational_aptitude");UUID q=seed("vocational_aptitude","published",false);seed("administrative_aptitude","published",false);seed("vocational_aptitude","draft",true);
        UUID request=UUID.randomUUID();JsonNode s=create(alice,request,"practice");assertThat(s.get("currentQuestion").get("id").asText()).isEqualTo(q.toString());assertThat(s.toString()).doesNotContain("PRIVATE_EXPLANATION","PRIVATE_SOURCE","answer_keys","snapshot");
        JsonNode again=create(alice,request,"practice");assertThat(again).isEqualTo(s);assertThat(db.queryForObject("SELECT COUNT(*) FROM student_coach_session",Integer.class)).isEqualTo(1);
        preference(alice,"administrative_aptitude");assertThat(create(alice,request,"practice").get("subject").asText()).isEqualTo("vocational_aptitude");
    }
    @Test void answersGradedByJavaExactlyOnceAndResultSurvivesModelFailure()throws Exception{
        String alice=token("alice");preference(alice,"vocational_aptitude");UUID q=seed("vocational_aptitude","published",false);UUID session=UUID.randomUUID();create(alice,session,"practice");
        stubFailure(new UserFailure(503,50322,"PRIVATE_UPSTREAM"));
        UUID req=UUID.randomUUID();Map<String,Object> body=Map.of("requestId",req,"action","answer","questionId",q,"keys",List.of("A"),"text","");
        JsonNode result=postData("/api/v1/coach/sessions/"+session+"/turns",alice,body,200);
        assertThat(result.get("result").get("correct").asBoolean()).isFalse();assertThat(result.get("result").get("answerKeys").get(0).asText()).isEqualTo("B");assertThat(result.toString()).doesNotContain("PRIVATE_UPSTREAM");
        assertThat(postData("/api/v1/coach/sessions/"+session+"/turns",alice,body,200)).isEqualTo(result);turnCount(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM student_coach_turn",Integer.class)).isEqualTo(1);
    }
    @Test void gradedResultStaysOnTheQuestionMessageAfterFollowUpChat()throws Exception{
        String alice=token("alice");preference(alice,"vocational_aptitude");UUID q=seed("vocational_aptitude","published",false);UUID session=UUID.randomUUID();create(alice,session,"practice");
        postData("/api/v1/coach/sessions/"+session+"/turns",alice,Map.of("requestId",UUID.randomUUID(),"action","answer","questionId",q,"keys",List.of("A"),"text",""),200);
        JsonNode after=postData("/api/v1/coach/sessions/"+session+"/turns",alice,Map.of("requestId",UUID.randomUUID(),"action","chat","text","这个词什么意思？"),200);
        // 追问后题目仍是那条出题消息，判分结果跟着它，不再是一个浮在末尾的区块。
        var carrying=java.util.stream.StreamSupport.stream(after.get("messages").spliterator(),false)
            .filter(m->m.path("question").path("id").asText().equals(q.toString())).findFirst().orElseThrow();
        assertThat(carrying.get("result").get("answerKeys").get(0).asText()).isEqualTo("B");
        assertThat(carrying.get("result").get("selectedKeys").get(0).asText()).isEqualTo("A");
        assertThat(after.get("messages").get(after.get("messages").size()-1).path("question").isMissingNode()).isTrue();
    }
    @Test void realDraftOptionMetadataIsNotSentAsUnsupportedOrSecretFields()throws Exception{
        String alice=token("alice");preference(alice,"vocational_aptitude");UUID q=seed("vocational_aptitude","published",false);UUID session=UUID.randomUUID();
        var snapshot=(tools.jackson.databind.node.ObjectNode)json.readTree(db.queryForObject("SELECT draft_json FROM public_question WHERE id=?",String.class,q));
        ((tools.jackson.databind.node.ObjectNode)snapshot.path("options").get(0)).putNull("image");
        db.update("UPDATE public_question SET draft_json=? WHERE id=?",json.writeValueAsString(snapshot),q);
        create(alice,session,"practice");
        postData("/api/v1/coach/sessions/"+session+"/turns",alice,Map.of("requestId",UUID.randomUUID(),"action","chat","text","这题考什么知识点？"),200);
        var capture=org.mockito.ArgumentCaptor.forClass(Object.class);org.mockito.Mockito.verify(ai).turn(capture.capture(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        var context=json.valueToTree(capture.getValue());
        assertThat(context.path("question").toString()).doesNotContain("image","PRIVATE_EXPLANATION","answer");
    }
    @Test void maximumLengthChatFitsStorageAndFailureIsNotAutomaticallyRetried()throws Exception{
        String alice=token("alice");preference(alice,"vocational_aptitude");UUID session=UUID.randomUUID();create(alice,session,"chat");
        stubFailure(new UserFailure(503,50322,"SECRET"));
        var request=Map.of("requestId",UUID.randomUUID(),"action","chat","text","长".repeat(2000));
        postData("/api/v1/coach/sessions/"+session+"/turns",alice,request,503);
        postData("/api/v1/coach/sessions/"+session+"/turns",alice,request,503);
        turnCount(1);
        assertThat(db.queryForObject("SELECT status FROM student_coach_turn",String.class)).isEqualTo("failed");
    }
    @Test void malformedRequestIdIsClientErrorNotServiceUnavailable()throws Exception{
        String alice=token("alice");preference(alice,"vocational_aptitude");UUID session=UUID.randomUUID();create(alice,session,"chat");
        var body=Map.of("requestId","not-a-uuid","action","chat","text","你好");
        mvc.perform(post("/api/v1/coach/sessions/"+session+"/turns").header("Authorization","Bearer "+alice)
            .contentType("application/json").content(json.writeValueAsString(body)))
            .andExpect(status().isBadRequest());
    }
    @Test void adminCanReadAiCallAuditButStudentsCannot()throws Exception{
        String admin=token("admin"),alice=token("alice");
        long actor=db.queryForObject("SELECT id FROM app_user WHERE username='alice'",Long.class);
        db.update("INSERT INTO audit_log(actor,action,target_type,target_id,after_data,trace_id) VALUES (?,?,?,?,CAST(? AS JSONB),?)",
            actor,"ai.coach_turn","ai_call","c1","{\"outcome\":\"failed\",\"elapsedMs\":1200,\"attempts\":3,\"model\":\"m\",\"error\":\"类型=ServiceError\"}","trace-1");
        Long auditId=db.queryForObject("SELECT id FROM audit_log WHERE action='ai.coach_turn'",Long.class);
        db.update("INSERT INTO ai_call_exchange(id,audit_id,request_json,response_text,model) VALUES (?,?,CAST(? AS JSONB),?,?)",
            UUID.randomUUID(),auditId,"{\"message\":\"PRIVATE_PROMPT\"}","PRIVATE_RAW_OUTPUT","m");

        // 普通用户不能读审计。
        mvc.perform(get("/api/v1/admin/ai-calls").header("Authorization","Bearer "+alice)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/ai-calls/"+auditId).header("Authorization","Bearer "+alice)).andExpect(status().isForbidden());

        // 列表只给元数据，不带原文，避免一次拉回大量对话内容。
        String list=mvc.perform(get("/api/v1/admin/ai-calls").header("Authorization","Bearer "+admin))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(list).contains("ai.coach_turn","failed","alice").doesNotContain("PRIVATE_RAW_OUTPUT","PRIVATE_PROMPT");
        JsonNode row=json.readTree(list).get("data").get("list").get(0);
        assertThat(row.get("attempts").asInt()).isEqualTo(3);
        assertThat(row.get("elapsedMs").asLong()).isEqualTo(1200);

        // 单条详情才返回原文。
        String detail=mvc.perform(get("/api/v1/admin/ai-calls/"+auditId).header("Authorization","Bearer "+admin))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(detail).contains("PRIVATE_RAW_OUTPUT","PRIVATE_PROMPT");
    }

    @Test void auditCanFilterByActionAndOutcome()throws Exception{
        String admin=token("admin");
        db.update("INSERT INTO audit_log(action,target_type,after_data,trace_id) VALUES ('ai.coach_turn','ai_call',CAST('{\"outcome\":\"ok\"}' AS JSONB),'t1')");
        db.update("INSERT INTO audit_log(action,target_type,after_data,trace_id) VALUES ('ai.public_import','ai_call',CAST('{\"outcome\":\"failed\"}' AS JSONB),'t2')");
        var failed=mvc.perform(get("/api/v1/admin/ai-calls").param("outcome","failed").header("Authorization","Bearer "+admin))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(failed).contains("ai.public_import").doesNotContain("ai.coach_turn");
        var coach=mvc.perform(get("/api/v1/admin/ai-calls").param("action","ai.coach_turn").header("Authorization","Bearer "+admin))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(coach).contains("ai.coach_turn").doesNotContain("ai.public_import");
    }

    @Test void sessionsRemainPrivateAndDraftsNeverEnterSelection()throws Exception{
        String alice=token("alice"),bob=token("bob");preference(alice,"vocational_aptitude");seed("vocational_aptitude","draft",false);UUID session=UUID.randomUUID();create(alice,session,"practice");
        mvc.perform(get("/api/v1/coach/sessions/"+session).header("Authorization","Bearer "+bob)).andExpect(status().isNotFound());
        postData("/api/v1/coach/sessions/"+session+"/turns",bob,Map.of("requestId",UUID.randomUUID(),"action","next","text",""),404);
        mvc.perform(get("/api/v1/coach/sessions").header("Authorization","Bearer "+bob)).andExpect(status().isOk()).andExpect(jsonPath("$.data.list.length()").value(0));
    }
}
