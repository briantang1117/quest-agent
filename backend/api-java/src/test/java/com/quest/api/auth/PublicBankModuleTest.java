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
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.config.import=", "spring.datasource.url=jdbc:h2:mem:publicbanks;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=", "spring.flyway.enabled=false",
    "spring.autoconfigure.exclude=com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration",
    "quest.persistence.mode=postgresql", "quest.security.jwt.secret=public-bank-test-secret-at-least-32-bytes",
    "quest.security.login.account-limit=100", "quest.security.login.source-limit=1000"
})
@ActiveProfiles("test") @AutoConfigureMockMvc
class PublicBankModuleTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired org.springframework.beans.factory.ObjectProvider<org.springframework.transaction.PlatformTransactionManager> managers;
    @org.springframework.test.context.bean.override.mockito.MockitoBean com.quest.api.publicbank.PublicAiClient ai;
    @org.springframework.test.context.bean.override.mockito.MockitoBean com.quest.api.publicbank.PublicBatchExecutor batches;
    static final String PASSWORD = "Initial-password-123";
    @BeforeEach void schema() {
        org.mockito.Mockito.when(batches.start(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenAnswer(call->((java.util.function.Supplier<?>)call.getArgument(1)).get());
        org.mockito.Mockito.when(batches.startText(org.mockito.ArgumentMatchers.any())).thenAnswer(call->((java.util.function.Supplier<?>)call.getArgument(0)).get());
        db.execute("DROP ALL OBJECTS");
        db.execute("CREATE ROLE quest_app"); db.execute("CREATE ROLE quest_ai");
        db.execute("CREATE SEQUENCE app_user_id_seq"); db.execute("CREATE SEQUENCE audit_log_id_seq");
        db.execute("CREATE TABLE app_user(id BIGSERIAL PRIMARY KEY, username VARCHAR(64) UNIQUE NOT NULL,password_hash VARCHAR(128) NOT NULL,display_name VARCHAR(128) NOT NULL,status VARCHAR(16) DEFAULT 'active' NOT NULL,created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP)");
        db.execute("CREATE TABLE audit_log(id BIGSERIAL PRIMARY KEY,actor BIGINT,action VARCHAR(64),target_type VARCHAR(64),target_id VARCHAR(64),trace_id VARCHAR(64))");
        TestUserSchema.applyV4(db);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V5__public_banks_and_imports.sql")).execute(db.getDataSource());
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V6__public_pdf_batches.sql")).execute(db.getDataSource());
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V7__public_question_soft_delete.sql")).execute(db.getDataSource());
        var bcrypt = new BCryptPasswordEncoder();
        for (String user : new String[]{"admin", "other", "alice"})
            db.update("INSERT INTO app_user(username,password_hash,display_name,account_type,must_change_password) VALUES (?,?,?,?,FALSE)",user,bcrypt.encode(PASSWORD),user,user.equals("alice")?"student":"admin");
    }
    String token(String name) throws Exception {
        return postData("/api/v1/auth/login",null,Map.of("username",name,"password",PASSWORD),200).get("accessToken").asText();
    }
    JsonNode postData(String url,String token,Object data,int statusCode) throws Exception {
        var req=post(url).contentType("application/json").content(json.writeValueAsString(data));
        if(token!=null)req.header("Authorization","Bearer "+token);
        return json.readTree(mvc.perform(req).andExpect(status().is(statusCode)).andReturn().getResponse().getContentAsString()).get("data");
    }
    String bank(String token) throws Exception {
        return postData("/api/v1/admin/public-banks",token,Map.of("name","公共职测","examType","career_a","subject","vocational_aptitude"),200).get("id").asText();
    }
    @Test void pdfUploadStagesManyQuestionsAndOnlyImporterCanStart() throws Exception {
        String admin=token("admin"),id=bank(admin);
        byte[] pdf="%PDF-1.7 synthetic-only".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        org.mockito.Mockito.when(ai.inspectPdf(org.mockito.ArgumentMatchers.any())).thenReturn(json.readTree("{\"page_count\":2,\"warnings\":[],\"candidates\":[{\"question_no\":1,\"source_text\":\"题一 A.甲 B.乙\",\"page_numbers\":[1],\"reference_keys\":[\"A\"],\"reference_explanation\":\"原解析\",\"warnings\":[]},{\"question_no\":2,\"source_text\":\"题二 A.甲 B.乙\",\"page_numbers\":[1,2],\"reference_keys\":[],\"warnings\":[\"missing_reference_answer\"]}]}"));
        var upload=new org.springframework.mock.web.MockMultipartFile("file","paper.pdf","application/pdf",pdf);
        var response=mvc.perform(multipart("/api/v1/admin/public-banks/"+id+"/pdf-imports").file(upload).header("Authorization","Bearer "+admin)).andExpect(status().isOk()).andReturn();
        var document=json.readTree(response.getResponse().getContentAsString()).get("data");
        assertThat(document.get("candidateCount").asInt()).isEqualTo(2);
        assertThat(document.get("status").asText()).isEqualTo("staged");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM public_import_task",Integer.class)).isEqualTo(2);
        mvc.perform(get("/api/v1/admin/public-banks/"+id+"/questions").header("Authorization","Bearer "+admin)).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        var processor=new com.quest.api.publicbank.PublicImportProcessor(db,managers.getObject(),ai,json);
        processor.processOne();
        org.mockito.Mockito.verify(ai,org.mockito.Mockito.never()).extractCandidate(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        String start="/api/v1/admin/public-documents/"+document.get("id").asText()+"/start";
        postData(start,token("other"),Map.of(),403);
        postData(start,admin,Map.of(),200);
        postData(start,admin,Map.of(),409);
        org.mockito.Mockito.when(ai.extractCandidate(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenReturn(json.readTree("{\"draft\":null,\"warnings\":[\"test_block\"],\"provenance\":{}}"));
        assertThat(processor.processDocumentOne(java.util.UUID.fromString(document.get("id").asText()))).isTrue();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM public_import_task WHERE status='failed'",Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM public_import_task WHERE status='queued'",Integer.class)).isEqualTo(1);
        mvc.perform(get("/api/v1/admin/public-documents/"+document.get("id").asText()+"/file").header("Authorization","Bearer "+admin)).andExpect(status().isOk()).andExpect(content().bytes(pdf));
        mvc.perform(multipart("/api/v1/admin/public-banks/"+id+"/pdf-imports").file(upload).header("Authorization","Bearer "+token("alice"))).andExpect(status().isForbidden());
    }
    @Test void startButtonExecutesOnlySelectedBatchAndProducesNumberedUnpublishedDrafts() throws Exception {
        String admin=token("admin"),bank=bank(admin);
        org.mockito.Mockito.when(ai.inspectPdf(org.mockito.ArgumentMatchers.any())).thenReturn(json.readTree("{\"candidates\":[{\"question_no\":12,\"source_text\":\"12.题干 A.甲 B.乙\",\"page_numbers\":[1],\"reference_keys\":[\"A\"],\"warnings\":[]}]}"));
        var file=new org.springframework.mock.web.MockMultipartFile("file","synthetic.pdf","application/pdf","%PDF-synthetic".getBytes());
        var result=mvc.perform(multipart("/api/v1/admin/public-banks/"+bank+"/pdf-imports").file(file).header("Authorization","Bearer "+admin)).andExpect(status().isOk()).andReturn();
        String document=json.readTree(result.getResponse().getContentAsString()).get("data").get("id").asText();
        org.mockito.Mockito.when(ai.extractCandidate(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenReturn(json.readTree("{\"draft\":{\"type\":\"single_choice\",\"stem_md\":\"题干\",\"options\":[{\"key\":\"A\",\"text_md\":\"甲\"},{\"key\":\"B\",\"text_md\":\"乙\"}],\"answer\":{\"keys\":[\"A\"],\"source\":\"imported\"},\"explanation_md\":\"解析\",\"knowledge_points\":[\"GK\"],\"difficulty\":3},\"provenance\":{\"question_no\":12},\"warnings\":[]}"));
        var work=new java.util.ArrayList<Runnable>();
        var processor=new com.quest.api.publicbank.PublicImportProcessor(db,managers.getObject(),ai,json);
        var real=new com.quest.api.publicbank.PublicBatchExecutor(work::add,id->{while(processor.processDocumentOne(id)){};});
        org.mockito.Mockito.doAnswer(call->real.start(call.getArgument(0),call.getArgument(1))).when(batches).start(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        postData("/api/v1/admin/public-documents/"+document+"/start",admin,Map.of(),200);
        org.mockito.Mockito.verify(ai,org.mockito.Mockito.never()).extractCandidate(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        work.removeFirst().run();
        mvc.perform(get("/api/v1/admin/public-banks/"+bank+"/questions").header("Authorization","Bearer "+admin)).andExpect(status().isOk()).andExpect(jsonPath("$.data.list[0].questionNo").value(12)).andExpect(jsonPath("$.data.list[0].status").value("draft"));
        mvc.perform(get("/api/v1/admin/public-banks/"+bank+"/documents").header("Authorization","Bearer "+admin)).andExpect(status().isOk()).andExpect(jsonPath("$.data.list[0].totalCount").value(1)).andExpect(jsonPath("$.data.list[0].reviewingCount").value(1)).andExpect(jsonPath("$.data.list[0].queuedCount").value(0));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM public_question WHERE status='published'",Integer.class)).isZero();
    }
    @Test void questionNumberFallsBackToEvidenceButExplicitNullStaysEmpty() throws Exception {
        String admin=token("admin"),bank=bank(admin);
        var task=postData("/api/v1/admin/public-banks/"+bank+"/imports",admin,Map.of("text","单题"),200);
        var id=java.util.UUID.randomUUID();
        String draft="{\"type\":\"single_choice\",\"stem_md\":\"题干\",\"options\":[{\"key\":\"A\",\"text_md\":\"甲\"},{\"key\":\"B\",\"text_md\":\"乙\"}],\"answer\":{\"keys\":[\"A\"],\"source\":\"imported\"},\"explanation_md\":\"解析\",\"knowledge_points\":[\"GK\"],\"difficulty\":3}";
        db.update("INSERT INTO public_question(id,bank_id,task_id,imported_by,draft_json,evidence_json) VALUES (?,?,?,?,?,?)",id,java.util.UUID.fromString(bank),java.util.UUID.fromString(task.get("id").asText()),db.queryForObject("SELECT id FROM app_user WHERE username='admin'",Long.class),draft,"{\"provenance\":{\"question_no\":7}}");
        db.update("UPDATE public_question SET draft_json=? WHERE id=?",draft.substring(0,draft.length()-1)+",\"source\":{\"question_no\":null}}",id);
        String path="/api/v1/admin/public-questions/"+id;
        mvc.perform(get(path).header("Authorization","Bearer "+admin)).andExpect(status().isOk()).andExpect(jsonPath("$.data.draft.source.question_no").value(7));
        mvc.perform(get("/api/v1/admin/public-banks/"+bank+"/questions").header("Authorization","Bearer "+admin)).andExpect(status().isOk()).andExpect(jsonPath("$.data.list[0].questionNo").value(7));
        var node=(tools.jackson.databind.node.ObjectNode)json.readTree(draft);node.putObject("source").putNull("question_no");
        mvc.perform(put(path).header("Authorization","Bearer "+admin).contentType("application/json").content(json.writeValueAsString(Map.of("version",1,"draft",node)))).andExpect(status().isOk()).andExpect(jsonPath("$.data.draft.source.question_no").isEmpty());
        for(String invalid:new String[]{"0","-1","true","1.5","\"3\""}){
            node.set("source",json.readTree("{\"question_no\":"+invalid+"}"));
            assertThatThrownBy(()->com.quest.api.publicbank.PublicBankService.validateDraft(node)).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void pdfAndTextTasksAreSeparatedAndReviewCountsFollowQuestionState() throws Exception {
        String admin=token("admin"),bank=bank(admin);
        long actor=db.queryForObject("SELECT id FROM app_user WHERE username='admin'",Long.class);
        var document=java.util.UUID.randomUUID();
        db.update("INSERT INTO public_import_document(id,bank_id,created_by,source_name,sha256,file_bytes,report_json,candidate_count,status) VALUES (?,?,?,?,?,?,?,?,?)",document,java.util.UUID.fromString(bank),actor,"paper.pdf","a".repeat(64),new byte[]{1},"{}",2,"queued");
        for(int i=0;i<2;i++){
            var task=java.util.UUID.randomUUID();
            db.update("INSERT INTO public_import_task(id,bank_id,created_by,source_text,status,document_id,candidate_json,seq) VALUES (?,?,?,?,'reviewing',?,?,?)",task,java.util.UUID.fromString(bank),actor,"题目",document,"{}",i+1);
            db.update("INSERT INTO public_question(id,bank_id,task_id,imported_by,draft_json,evidence_json,status,confirmed_by,confirmed_at,confirmed_version,published_at) VALUES (?,?,?,?,?, ?,?,?,CURRENT_TIMESTAMP,1,?)",java.util.UUID.randomUUID(),java.util.UUID.fromString(bank),task,actor,"{}","{}",i==0?"confirmed":"published",actor,i==0?null:java.time.OffsetDateTime.now());
        }
        postData("/api/v1/admin/public-banks/"+bank+"/imports",admin,Map.of("text","文本单题"),200);
        mvc.perform(get("/api/v1/admin/public-banks/"+bank+"/imports").param("source","text").header("Authorization","Bearer "+admin)).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.list.length()").value(1));
        mvc.perform(get("/api/v1/admin/public-banks/"+bank+"/documents").header("Authorization","Bearer "+admin)).andExpect(status().isOk()).andExpect(jsonPath("$.data.list[0].reviewingCount").value(0)).andExpect(jsonPath("$.data.list[0].confirmedCount").value(1)).andExpect(jsonPath("$.data.list[0].publishedCount").value(1));
        mvc.perform(get("/api/v1/admin/public-banks/"+bank+"/imports").param("source","invalid").header("Authorization","Bearer "+admin)).andExpect(status().isBadRequest());
        String deleted=db.queryForObject("SELECT id FROM public_question WHERE status='confirmed'",String.class);
        mvc.perform(delete("/api/v1/admin/public-questions/"+deleted).header("Authorization","Bearer "+admin).contentType("application/json").content("{\"version\":1}")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/public-banks/"+bank+"/documents").header("Authorization","Bearer "+admin)).andExpect(status().isOk()).andExpect(jsonPath("$.data.list[0].reviewingCount").value(0)).andExpect(jsonPath("$.data.list[0].confirmedCount").value(0)).andExpect(jsonPath("$.data.list[0].deletedCount").value(1)).andExpect(jsonPath("$.data.list[0].publishedCount").value(1));
    }
    @Test void textEntrySchedulesOnlyItsOwnTaskThenAppearsAsUnconfirmedQuestion() throws Exception {
        String admin=token("admin"),bank=bank(admin);
        org.mockito.Mockito.when(ai.extract(org.mockito.ArgumentMatchers.any())).thenReturn(json.readTree("{\"draft\":{\"type\":\"single_choice\",\"stem_md\":\"题干\",\"options\":[{\"key\":\"A\",\"text_md\":\"甲\"},{\"key\":\"B\",\"text_md\":\"乙\"}],\"answer\":{\"keys\":[\"A\"],\"source\":\"ai_generated\"},\"explanation_md\":\"解析\",\"knowledge_points\":[\"GK\"],\"difficulty\":3},\"provenance\":{\"question_no\":null},\"warnings\":[]}"));
        java.util.List<Runnable> queued=new java.util.ArrayList<>();
        var processor=new com.quest.api.publicbank.PublicImportProcessor(db,managers.getObject(),ai,json);
        org.mockito.Mockito.doAnswer(call->{var task=(com.quest.api.publicbank.PublicBankService.Task)((java.util.function.Supplier<?>)call.getArgument(0)).get();queued.add(()->processor.processTaskOne(java.util.UUID.fromString(task.id())));return task;}).when(batches).startText(org.mockito.ArgumentMatchers.any());
        var task=postData("/api/v1/admin/public-banks/"+bank+"/imports",admin,Map.of("text","非结构化单题原文"),200);
        org.mockito.Mockito.verifyNoInteractions(ai);
        queued.removeFirst().run();
        mvc.perform(get("/api/v1/admin/public-imports/"+task.get("id").asText()).header("Authorization","Bearer "+admin)).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("reviewing")).andExpect(jsonPath("$.data.questionStatus").value("draft"));
        mvc.perform(get("/api/v1/admin/public-banks/"+bank+"/questions").header("Authorization","Bearer "+admin)).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.list[0].status").value("draft"));
        assertThat(processor.processTaskOne(java.util.UUID.fromString(task.get("id").asText()))).isFalse();
        org.mockito.Mockito.verify(ai,org.mockito.Mockito.times(1)).extract("非结构化单题原文");
    }
    @Test void scopedProcessorCannotConsumeUnrelatedQueuedTask() throws Exception {
        String admin=token("admin"),id=bank(admin);
        postData("/api/v1/admin/public-banks/"+id+"/imports",admin,Map.of("text","不在本次授权范围的题目"),200);
        var processor=new com.quest.api.publicbank.PublicImportProcessor(db,managers.getObject(),ai,json);
        assertThat(processor.processDocumentOne(java.util.UUID.randomUUID())).isFalse();
        org.mockito.Mockito.verify(ai,org.mockito.Mockito.never()).extract(org.mockito.ArgumentMatchers.any());
        assertThat(db.queryForObject("SELECT status FROM public_import_task",String.class)).isEqualTo("queued");
    }
    @Test void bankDetailLoadsByIdAndStillRejectsStudents() throws Exception {
        String admin=token("admin"),id=bank(admin);
        mvc.perform(get("/api/v1/admin/public-banks/"+id).header("Authorization","Bearer "+admin)).andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(id)).andExpect(jsonPath("$.data.name").value("公共职测"));
        mvc.perform(get("/api/v1/admin/public-banks/"+java.util.UUID.randomUUID()).header("Authorization","Bearer "+admin)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/admin/public-banks/"+id).header("Authorization","Bearer "+token("alice"))).andExpect(status().isForbidden());
    }
    @Test void bankSearchFiltersBeforePagingAndEscapesWildcards() throws Exception {
        String admin=token("admin"); bank(admin);
        postData("/api/v1/admin/public-banks",admin,Map.of("name","上海行测","examType","shanghai_civil","subject","administrative_aptitude"),200);
        postData("/api/v1/admin/public-banks",admin,Map.of("name","职测%专项","examType","career_a","subject","vocational_aptitude"),200);
        mvc.perform(get("/api/v1/admin/public-banks").param("keyword","职测").param("examType","career_a").param("size","1").param("page","2").header("Authorization","Bearer "+admin))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2)).andExpect(jsonPath("$.data.list.length()").value(1));
        mvc.perform(get("/api/v1/admin/public-banks").param("keyword","%").header("Authorization","Bearer "+admin))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.list[0].name").value("职测%专项"));
        mvc.perform(get("/api/v1/admin/public-banks").param("examType","unknown").header("Authorization","Bearer "+admin)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/admin/public-banks").param("keyword","x".repeat(129)).header("Authorization","Bearer "+admin)).andExpect(status().isBadRequest());
    }
    @Test void listsHavePaginationRatherThanSilentlyHidingOldTasks() throws Exception {
        String admin=token("admin"),bank=bank(admin);
        long actor=db.queryForObject("SELECT id FROM app_user WHERE username='admin'",Long.class);
        for(int i=0;i<101;i++) db.update("INSERT INTO public_import_task(id,bank_id,created_by,source_text,status) VALUES (?,?,?,?,'queued')",java.util.UUID.randomUUID(),java.util.UUID.fromString(bank),actor,"题目"+i);
        mvc.perform(get("/api/v1/admin/public-banks/"+bank+"/imports?page=6&size=20").header("Authorization","Bearer "+admin)).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(101)).andExpect(jsonPath("$.data.list.length()").value(1));
        mvc.perform(get("/api/v1/admin/public-banks?page=0").header("Authorization","Bearer "+admin)).andExpect(status().isBadRequest());
    }
    @Test void nonStringExplanationCannotEnterReviewOrPublication() {
        for(String explanation:new String[]{"{}","[]","12","null"}){
            var draft=json.readTree("{\"type\":\"single_choice\",\"stem_md\":\"题干\",\"options\":[{\"key\":\"A\",\"text_md\":\"甲\"},{\"key\":\"B\",\"text_md\":\"乙\"}],\"answer\":{\"keys\":[\"A\"],\"source\":\"manual\"},\"knowledge_points\":[\"GK\"],\"difficulty\":3,\"explanation_md\":"+explanation+"}");
            assertThatThrownBy(()->com.quest.api.publicbank.PublicBankService.validateDraft(draft)).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void processorClaimsDurablyAndNeverRetriesFailedTask() throws Exception {
        String admin=token("admin"),bank=bank(admin);
        var task=postData("/api/v1/admin/public-banks/"+bank+"/imports",admin,Map.of("text","单题"),200);
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var provider=new com.quest.api.publicbank.PublicAiClient(json,"http://127.0.0.1:1","token","secret-secret-secret-secret-secret") {
            @Override public JsonNode extract(String text){calls.incrementAndGet();throw new UserFailure(503,50322,"SECRET_PROVIDER");}
        };
        var processor=new com.quest.api.publicbank.PublicImportProcessor(db,managers.getObject(),provider,json);
        processor.processOne();processor.processOne();
        assertThat(calls.get()).isEqualTo(1);
        assertThat(db.queryForObject("SELECT status FROM public_import_task WHERE id=?",String.class,java.util.UUID.fromString(task.get("id").asText()))).isEqualTo("failed");
        assertThat(db.queryForObject("SELECT error_message FROM public_import_task WHERE id=?",String.class,java.util.UUID.fromString(task.get("id").asText()))).doesNotContain("SECRET");
    }
    @Test void adminCreatesPublicBankWithoutLearningOrPrivateAccess() throws Exception {
        String admin=token("admin"); String bank=bank(admin);
        mvc.perform(get("/api/v1/admin/public-banks").header("Authorization","Bearer "+admin)).andExpect(status().isOk()).andExpect(jsonPath("$.data.list[0].id").value(bank));
        mvc.perform(get("/api/v1/admin/public-banks").header("Authorization","Bearer "+token("alice"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/practice").header("Authorization","Bearer "+admin)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/private-banks").header("Authorization","Bearer "+admin)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/public-banks")).andExpect(status().isUnauthorized());
    }
    @Test void submittedTaskIsDurableAndDoesNotCallProviderInHttpThread() throws Exception {
        String admin=token("admin"),bank=bank(admin);
        var task=postData("/api/v1/admin/public-banks/"+bank+"/imports",admin,Map.of("text","单题正文 A.甲 B.乙"),200);
        assertThat(task.get("status").asText()).isEqualTo("queued");
        assertThat(db.queryForObject("SELECT source_text FROM public_import_task WHERE id=?",String.class,java.util.UUID.fromString(task.get("id").asText()))).contains("单题正文");
        mvc.perform(get("/api/v1/admin/public-banks/"+bank+"/imports").header("Authorization","Bearer "+admin)).andExpect(status().isOk()).andExpect(jsonPath("$.data.list[0].status").value("queued"));
    }
    @Test void questionSearchSortsNumericNumbersBeforePagingAndSummaryIgnoresFilters() throws Exception {
        String admin=token("admin"),bank=bank(admin),other=bank(admin);
        var tenth=seedPublishQuestion(bank,"confirmed");var second=seedPublishQuestion(bank,"draft");var empty=seedPublishQuestion(bank,"draft");var deleted=seedPublishQuestion(bank,"confirmed");seedPublishQuestion(other,"draft");
        for(var id:java.util.List.of(tenth,second,empty,deleted)){
            var draft=(tools.jackson.databind.node.ObjectNode)json.readTree(db.queryForObject("SELECT draft_json FROM public_question WHERE id=?",String.class,id));
            draft.put("stem_md",id.equals(empty)?"另一道题":"专项%题目");
            if(id.equals(second))draft.putObject("source").put("question_no",2);
            if(id.equals(tenth))draft.putObject("source").put("question_no",10);
            db.update("UPDATE public_question SET draft_json=?,version=2,confirmed_version=CASE WHEN status='confirmed' THEN 2 ELSE NULL END WHERE id=?",json.writeValueAsString(draft),id);
        }
        db.update("UPDATE public_question SET deleted_at=CURRENT_TIMESTAMP,deleted_by=imported_by WHERE id=?",deleted);
        db.update("UPDATE public_question SET status='published',published_at=CURRENT_TIMESTAMP WHERE id=?",tenth);
        String root="/api/v1/admin/public-banks/"+bank;
        mvc.perform(get(root+"/questions").param("size","1").param("page","1").header("Authorization","Bearer "+admin)).andExpect(status().isOk()).andExpect(jsonPath("$.data.list[0].questionNo").value(2)).andExpect(jsonPath("$.data.total").value(3));
        mvc.perform(get(root+"/questions").param("size","1").param("page","2").header("Authorization","Bearer "+admin)).andExpect(status().isOk()).andExpect(jsonPath("$.data.list[0].questionNo").value(10));
        mvc.perform(get(root+"/questions").param("size","1").param("page","3").header("Authorization","Bearer "+admin)).andExpect(status().isOk()).andExpect(jsonPath("$.data.list[0].id").value(empty.toString()));
        mvc.perform(get(root+"/questions").param("keyword","%").param("status","published").header("Authorization","Bearer "+admin)).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.list[0].id").value(tenth.toString()));
        mvc.perform(get(root+"/questions").param("keyword","stem_md").header("Authorization","Bearer "+admin)).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get(root+"/questions").param("keyword","甲").header("Authorization","Bearer "+admin)).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get(root+"/questions").param("page","2147483647").header("Authorization","Bearer "+admin)).andExpect(status().isOk()).andExpect(jsonPath("$.data.list.length()").value(0));
        mvc.perform(get(root+"/question-summary").header("Authorization","Bearer "+admin)).andExpect(status().isOk()).andExpect(jsonPath("$.data.totalCount").value(3)).andExpect(jsonPath("$.data.draftCount").value(2)).andExpect(jsonPath("$.data.confirmedCount").value(0)).andExpect(jsonPath("$.data.publishedCount").value(1));
        mvc.perform(get(root+"/question-summary").header("Authorization","Bearer "+token("alice"))).andExpect(status().isForbidden());
        mvc.perform(get(root+"/questions").param("status","deleted").header("Authorization","Bearer "+admin)).andExpect(status().isBadRequest());
        mvc.perform(get(root+"/questions").param("keyword","x".repeat(129)).header("Authorization","Bearer "+admin)).andExpect(status().isBadRequest());
    }
    java.util.UUID seedPublishQuestion(String bank,String state) {
        long actor=db.queryForObject("SELECT id FROM app_user WHERE username='admin'",Long.class);
        var task=java.util.UUID.randomUUID();var id=java.util.UUID.randomUUID();
        db.update("INSERT INTO public_import_task(id,bank_id,created_by,source_text,status) VALUES (?,?,?,?,'reviewing')",task,java.util.UUID.fromString(bank),actor,"合成题目");
        String draft="{\"type\":\"single_choice\",\"stem_md\":\"题干\",\"options\":[{\"key\":\"A\",\"text_md\":\"甲\"},{\"key\":\"B\",\"text_md\":\"乙\"}],\"answer\":{\"keys\":[\"A\"],\"source\":\"manual\"},\"explanation_md\":\"解析\",\"knowledge_points\":[\"GK\"],\"difficulty\":3}";
        db.update("INSERT INTO public_question(id,bank_id,task_id,imported_by,draft_json,evidence_json) VALUES (?,?,?,?,?,?)",id,java.util.UUID.fromString(bank),task,actor,draft,"{}");
        if(state.equals("confirmed"))db.update("UPDATE public_question SET status='confirmed',confirmed_by=?,confirmed_at=CURRENT_TIMESTAMP,confirmed_version=1 WHERE id=?",actor,id);
        return id;
    }
    @Test void unconfirmedQuestionCanBeSoftDeletedAndCannotBeAccessedAgain() throws Exception {
        String admin=token("admin"),bank=bank(admin);
        var id=seedPublishQuestion(bank,"draft");
        var node=json.readTree(db.queryForObject("SELECT draft_json FROM public_question WHERE id=?",String.class,id));
        String path="/api/v1/admin/public-questions/"+id;
        mvc.perform(delete(path).header("Authorization","Bearer "+admin).contentType("application/json").content("{\"version\":2}")).andExpect(status().isConflict());
        mvc.perform(delete(path).header("Authorization","Bearer "+admin).contentType("application/json").content("{\"version\":1}")).andExpect(status().isOk());
        mvc.perform(get(path).header("Authorization","Bearer "+admin)).andExpect(status().isNotFound());
        postData(path+"/confirm",admin,Map.of("version",1),404);
        postData(path+"/publish",admin,Map.of("version",1),404);
        postData("/api/v1/admin/public-banks/"+bank+"/publish",admin,Map.of("items",java.util.List.of(Map.of("id",id,"version",1))),404);
        mvc.perform(put(path).header("Authorization","Bearer "+admin).contentType("application/json").content(json.writeValueAsString(Map.of("version",1,"draft",node)))).andExpect(status().isNotFound());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM public_question WHERE deleted_at IS NOT NULL AND deleted_by IS NOT NULL",Integer.class)).isEqualTo(1);
    }
    @Test void publishedQuestionMustWithdrawBeforeEditingOrDeletionAndClearConfirmation() throws Exception {
        String admin=token("admin"),bank=bank(admin);var id=seedPublishQuestion(bank,"confirmed");String path="/api/v1/admin/public-questions/"+id;
        postData(path+"/publish",admin,Map.of("version",1),200);
        mvc.perform(delete(path).header("Authorization","Bearer "+admin).contentType("application/json").content("{\"version\":1}")).andExpect(status().isConflict());
        postData(path+"/withdraw",admin,Map.of("version",2),409);
        var withdrawn=postData(path+"/withdraw",admin,Map.of("version",1),200);
        assertThat(withdrawn.get("status").asText()).isEqualTo("draft");assertThat(withdrawn.get("version").asInt()).isEqualTo(2);
        assertThat(db.queryForObject("SELECT confirmed_by FROM public_question WHERE id=?",Long.class,id)).isNull();
        assertThat(db.queryForObject("SELECT published_at FROM public_question WHERE id=?",java.time.OffsetDateTime.class,id)).isNull();
        postData(path+"/publish",admin,Map.of("version",2),409);
        postData(path+"/confirm",admin,Map.of("version",1),409);
        postData(path+"/confirm",admin,Map.of("version",2),200);
        postData(path+"/publish",admin,Map.of("version",2),200);
        postData(path+"/withdraw",admin,Map.of("version",2),200);
        mvc.perform(delete(path).header("Authorization","Bearer "+token("alice")).contentType("application/json").content("{\"version\":2}")).andExpect(status().isForbidden());
        mvc.perform(delete(path).header("Authorization","Bearer "+admin).contentType("application/json").content("{\"version\":3}")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/public-banks/"+bank+"/questions").header("Authorization","Bearer "+admin)).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
    }
    @Test void batchPublishRequiresConfirmedCurrentVersionsWithinOneBankAndIsAtomic() throws Exception {
        String admin=token("admin"),bank=bank(admin),other=bank(admin);
        var first=seedPublishQuestion(bank,"confirmed");var second=seedPublishQuestion(bank,"confirmed");var draft=seedPublishQuestion(bank,"draft");var foreign=seedPublishQuestion(other,"confirmed");
        String url="/api/v1/admin/public-banks/"+bank+"/publish";
        postData(url,admin,Map.of("items",java.util.List.of(Map.of("id",first,"version",1),Map.of("id",second,"version",2))),409);
        postData(url,admin,Map.of("items",java.util.List.of(Map.of("id",first,"version",1),Map.of("id",draft,"version",1))),409);
        postData(url,admin,Map.of("items",java.util.List.of(Map.of("id",first,"version",1),Map.of("id",foreign,"version",1))),404);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM public_question WHERE status='published'",Integer.class)).isZero();
        postData(url,admin,Map.of("items",java.util.List.of(Map.of("id",first,"version",1),Map.of("id",first,"version",1))),400);
        postData(url,admin,Map.of("items",java.util.List.of()),400);
        postData(url,token("alice"),Map.of("items",java.util.List.of(Map.of("id",first,"version",1))),403);
        var result=postData(url,admin,Map.of("items",java.util.List.of(Map.of("id",second,"version",1),Map.of("id",first,"version",1))),200);
        assertThat(result.get("publishedCount").asInt()).isEqualTo(2);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM public_question WHERE status='published'",Integer.class)).isEqualTo(2);
        postData(url,admin,Map.of("items",java.util.List.of(Map.of("id",first,"version",1))),409);
        assertThat(db.queryForObject("SELECT status FROM public_question WHERE id=?",String.class,draft)).isEqualTo("draft");
    }
    @Test void confirmationRequiresImporterAndCurrentVersionAndEditingResetsIt() throws Exception {
        String admin=token("admin"),other=token("other"),bank=bank(admin);
        var task=postData("/api/v1/admin/public-banks/"+bank+"/imports",admin,Map.of("text","单题"),200);
        var id=java.util.UUID.randomUUID();
        String draft="{\"type\":\"single_choice\",\"stem_md\":\"合成题干\",\"options\":[{\"key\":\"A\",\"text_md\":\"甲\"},{\"key\":\"B\",\"text_md\":\"乙\"}],\"answer\":{\"keys\":[\"A\"],\"source\":\"ai_generated\"},\"explanation_md\":\"建议解析\",\"knowledge_points\":[\"GK\"],\"difficulty\":3}";
        db.update("INSERT INTO public_question(id,bank_id,task_id,imported_by,draft_json,evidence_json) VALUES (?,?,?,?,?,?)",id,java.util.UUID.fromString(bank),java.util.UUID.fromString(task.get("id").asText()),db.queryForObject("SELECT id FROM app_user WHERE username='admin'",Long.class),draft,"{}");
        String path="/api/v1/admin/public-questions/"+id;
        postData(path+"/publish",admin,Map.of("version",1),409);
        postData(path+"/confirm",other,Map.of("version",1),403);
        postData(path+"/confirm",admin,Map.of("version",2),409);
        postData(path+"/confirm",admin,Map.of("version",1),200);
        var edited=mvc.perform(put(path).header("Authorization","Bearer "+admin).contentType("application/json").content(json.writeValueAsString(Map.of("version",1,"draft",json.readTree(draft))))).andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(edited.getResponse().getContentAsString()).get("data").get("status").asText()).isEqualTo("draft");
        postData(path+"/publish",admin,Map.of("version",2),409);
        postData(path+"/confirm",admin,Map.of("version",2),200);
        var published=postData(path+"/publish",admin,Map.of("version",2),200);
        assertThat(published.get("status").asText()).isEqualTo("published");
        mvc.perform(put(path).header("Authorization","Bearer "+admin).contentType("application/json").content(json.writeValueAsString(Map.of("version",2,"draft",json.readTree(draft))))).andExpect(status().isConflict());
    }
}
