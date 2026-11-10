package com.quest.api.auth;

import com.quest.api.publicbank.PublicAiClient;
import com.quest.api.syllabus.SyllabusPipeline;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 仅本机 H2 替身，不连接供应商或真实 PG。 */
class SyllabusPipelineTest {
    JdbcTemplate db; ObjectMapper json=new ObjectMapper(); PublicAiClient ai; SyllabusPipeline pipeline;
    TransactionTemplate tx;
    @BeforeEach void setup() throws Exception {
        var ds=new org.springframework.jdbc.datasource.SingleConnectionDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa","",true);
        db=new JdbcTemplate(ds); var manager=new DataSourceTransactionManager(ds); tx=new TransactionTemplate(manager);
        db.execute("CREATE ROLE quest_app");db.execute("CREATE ROLE quest_ai");
        db.execute("CREATE TABLE syllabus_version(id UUID PRIMARY KEY,title VARCHAR(200),exam_type VARCHAR(40),exam_year INT,status VARCHAR(16),revision INT,sections_json TEXT,warnings_json TEXT,updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP)");
        String sql=Files.readString(Path.of("src/main/resources/db/migration/V11__syllabus_pipeline.sql")).replace("embedding vector", "embedding TEXT").replace("AND vector_dims(embedding)=embedding_dimensions", "");
        for(String part:sql.split(";"))if(!part.isBlank())db.execute(part);
        var beans=new StaticListableBeanFactory();beans.addBean("jdbc",db);beans.addBean("tx",manager);
        ai=mock(PublicAiClient.class);
        pipeline=new SyllabusPipeline(beans.getBeanProvider(JdbcTemplate.class),beans.getBeanProvider(PlatformTransactionManager.class),ai,json,"postgresql");
        doAnswer(call->{var out=json.createObjectNode();out.set("sections",((JsonNode)call.getArgument(1)).deepCopy());out.putArray("issues").add("请人工核对");return out;}).when(ai).reviewSyllabus(anyString(),any());
        when(ai.embedSyllabus(anyList(),anyString())).thenAnswer(call->embedding(call.getArgument(0),"test-model",3));
    }
    @org.junit.jupiter.api.AfterEach void cleanup(){if(pipeline!=null)pipeline.close();}
    @Test void reviewTimeoutIsRecordedWithActionableReasonNotGenericText(){
        UUID id=version(sections(3,false),"draft");
        doThrow(new com.quest.api.auth.UserFailure(504,50401,"AI处理超时")).when(ai).reviewSyllabus(anyString(),any());
        enqueue(id,"review");pipeline.processOne();
        var status=pipeline.status(id);
        assertThat(status.get("reviewStatus")).isEqualTo("failed");
        assertThat(status.get("reviewError").toString()).contains("超时");
        assertThat(status.get("reviewError").toString()).isNotEqualTo("考纲处理失败，请检查服务后人工重试");
    }
    @Test void reviewBatchStaysSmallEnoughToFinishInsideModelTimeout(){
        UUID id=version(sections(40,false),"draft");
        var sizes=new ArrayList<Integer>();
        doAnswer(call->{JsonNode batch=call.getArgument(1);sizes.add(batch.size());var out=json.createObjectNode();out.set("sections",batch);out.putArray("issues");return out;}).when(ai).reviewSyllabus(anyString(),any());
        enqueue(id,"review");pipeline.processOne();
        assertThat(pipeline.status(id).get("reviewStatus")).isEqualTo("ready");
        // 单批过大会撞30秒模型超时，必须分批且每批明显小于20段。
        assertThat(sizes).allMatch(size->size<=8);
        assertThat(sizes).hasSizeGreaterThan(1);
    }
    @Test void reviewKeepsHumanEnabledWhileSendingOnlyDisabledSuggestions(){UUID id=version(sections(1,true),"draft");doAnswer(call->{JsonNode batch=call.getArgument(1);assertThat(batch.get(0).path("enabled").asBoolean()).isFalse();var out=json.createObjectNode();out.set("sections",batch);out.putArray("issues");return out;}).when(ai).reviewSyllabus(anyString(),any());enqueue(id,"review");pipeline.processOne();assertThat(pipeline.status(id).get("reviewStatus")).isEqualTo("ready");assertThat(json.readTree(db.queryForObject("SELECT sections_json FROM syllabus_version WHERE id=?",String.class,id)).get(0).path("enabled").asBoolean()).isTrue();}
    JsonNode embedding(List<String> texts,String model,int dim){var out=json.createObjectNode().put("model",model).put("dimensions",dim);var vectors=out.putArray("vectors");for(String text:texts){var vector=vectors.addArray();for(int i=0;i<dim;i++)vector.add(i==0?1.0:0.0);}return out;}
    JsonNode sections(int count,boolean enabled){var out=json.createArrayNode();for(int i=1;i<=count;i++)out.addObject().put("ordinal",i).put("title","判断推理"+i).put("path","行测/判断推理").put("kind","requirement").put("scope","administrative_aptitude").put("enabled",enabled).put("pageStart",i).put("pageEnd",i).put("printedPages","页"+i).put("text","原文逻辑能力"+i);return out;}
    UUID version(JsonNode sections,String status){UUID id=UUID.randomUUID();db.update("INSERT INTO syllabus_version(id,title,exam_type,exam_year,status,revision,sections_json,warnings_json) VALUES (?,'考纲','shanghai_civil',2027,?,1,?,'[]')",id,status,json.writeValueAsString(sections));return id;}
    void enqueue(UUID id,String type){tx.executeWithoutResult(s->pipeline.enqueue(id,type,1));}
    @Test void enqueueRollsBackWithCallerTransactionAndRejectsDuplicate(){UUID id=version(sections(1,false),"draft");tx.executeWithoutResult(s->{pipeline.enqueue(id,"review",1);s.setRollbackOnly();});assertThat(pipeline.active(id)).isFalse();enqueue(id,"review");assertThat(pipeline.status(id).get("reviewStatus")).isEqualTo("queued");assertThatThrownBy(()->enqueue(id,"review")).isInstanceOf(UserFailure.class);}
    @Test void reviewBatchesPreserveOriginalAndAtomicallySaveWarnings(){UUID id=version(sections(23,false),"draft");enqueue(id,"review");pipeline.processOne();assertThat(pipeline.status(id).get("reviewStatus")).isEqualTo("ready");var saved=json.readTree(db.queryForObject("SELECT sections_json FROM syllabus_version WHERE id=?",String.class,id));assertThat(saved).isEqualTo(sections(23,false));assertThat(db.queryForObject("SELECT warnings_json FROM syllabus_version WHERE id=?",String.class,id)).contains("请人工核对");verify(ai,times(3)).reviewSyllabus(eq("shanghai_civil"),argThat(s->s.size()<=8));}
    @Test void malformedReviewCannotOverwriteAnySectionOrLeakUpstreamError(){UUID id=version(sections(21,false),"draft");doAnswer(call->{JsonNode original=call.getArgument(1);var out=json.createObjectNode();var values=original.deepCopy();((tools.jackson.databind.node.ObjectNode)values.get(0)).put("text","伪造正文");out.set("sections",values);out.putArray("issues");return out;}).when(ai).reviewSyllabus(anyString(),any());enqueue(id,"review");pipeline.processOne();assertThat(pipeline.status(id).get("reviewStatus")).isEqualTo("failed");assertThat(json.readTree(db.queryForObject("SELECT sections_json FROM syllabus_version WHERE id=?",String.class,id))).isEqualTo(sections(21,false));pipeline.processOne();verify(ai,times(1)).reviewSyllabus(anyString(),any());}
    @Test void readyIndexRetrievesOnlyBoundVersionSubjectAndExactModel(){UUID id=version(sections(2,true),"published");assertThat(pipeline.retrieve(id,"administrative_aptitude","能力",false)).isNull();enqueue(id,"index");pipeline.processOne();assertThat(pipeline.status(id)).containsEntry("indexStatus","ready").containsEntry("indexedCount",2).containsEntry("chunkCount",2).containsEntry("embeddingModel","test-model").containsEntry("embeddingDimensions",3);var hits=pipeline.retrieve(id,"administrative_aptitude","能力",false);assertThat(hits.size()).isEqualTo(2);assertThat(hits.get(0).path("versionId").asText()).isEqualTo(id.toString());assertThat(pipeline.retrieve(id,"vocational_aptitude","能力",true).isEmpty()).isTrue();when(ai.embedSyllabus(anyList(),eq("student"))).thenAnswer(call->embedding(call.getArgument(0),"new-model",3));assertThatThrownBy(()->pipeline.retrieve(id,"administrative_aptitude","能力",false)).isInstanceOf(UserFailure.class).hasMessageContaining("重建");}
    @Test void indexFailureRequiresExplicitRetryAndCanReuseCompletedChunks(){var values=sections(10,true);UUID id=version(values,"published");final int[] calls={0};when(ai.embedSyllabus(anyList(),eq("admin"))).thenAnswer(call->{if(++calls[0]==2)throw new IllegalStateException("SECRET");return embedding(call.getArgument(0),"test-model",3);});enqueue(id,"index");pipeline.processOne();assertThat(pipeline.status(id)).containsEntry("indexStatus","failed").containsEntry("indexedCount",8);assertThat(pipeline.retrieve(id,"administrative_aptitude","能力",false)).isNull();pipeline.processOne();assertThat(calls[0]).isEqualTo(2);tx.executeWithoutResult(s->pipeline.retry(id,"index",1));pipeline.processOne();assertThat(pipeline.status(id)).containsEntry("indexStatus","ready").containsEntry("indexedCount",10);assertThat(calls[0]).isEqualTo(4);}
    @Test void retryProbesConfigurationOnceThenReusesExactHashModelDimensions(){UUID id=version(sections(10,true),"published");var requests=new ArrayList<Integer>();final int[] calls={0};when(ai.embedSyllabus(anyList(),eq("admin"))).thenAnswer(call->{List<String> values=call.getArgument(0);requests.add(values.size());if(++calls[0]==2)throw new IllegalStateException();return embedding(values,"test-model",3);});enqueue(id,"index");pipeline.processOne();pipeline.retry(id,"index",1);pipeline.processOne();assertThat(requests).containsExactly(8,2,1,2);assertThat(pipeline.status(id).get("indexStatus")).isEqualTo("ready");}
    @Test void archivedVersionCanRebuildWithoutChangingContentRevisionOrLifecycle(){var original=sections(2,true);UUID id=version(original,"archived");pipeline.retry(id,"index",1);pipeline.processOne();assertThat(pipeline.status(id)).containsEntry("indexStatus","ready").containsEntry("indexedCount",2);assertThat(db.queryForObject("SELECT status FROM syllabus_version WHERE id=?",String.class,id)).isEqualTo("archived");assertThat(db.queryForObject("SELECT revision FROM syllabus_version WHERE id=?",Integer.class,id)).isEqualTo(1);assertThat(json.readTree(db.queryForObject("SELECT sections_json FROM syllabus_version WHERE id=?",String.class,id))).isEqualTo(original);var hits=pipeline.retrieve(id,"administrative_aptitude","能力",true);assertThat(hits.size()).isEqualTo(2);assertThat(hits.get(0).path("version_id").asText()).isEqualTo(id.toString());assertThatThrownBy(()->pipeline.retry(id,"review",1)).isInstanceOf(UserFailure.class);}
    @Test void repeatedRebuildKeepsOnlyCurrentAndPreviousCacheGeneration(){UUID id=version(sections(1,true),"published");enqueue(id,"index");pipeline.processOne();for(int i=0;i<3;i++){when(ai.embedSyllabus(anyList(),eq("admin"))).thenThrow(new IllegalStateException());pipeline.retry(id,"index",1);pipeline.processOne();}assertThat(db.queryForObject("SELECT COUNT(DISTINCT generation) FROM syllabus_chunk WHERE version_id=?",Integer.class,id)).isLessThanOrEqualTo(2);}
    @Test void oldLeaseCannotOverwriteReplacementTask(){UUID id=version(sections(1,false),"draft");enqueue(id,"review");doAnswer(call->{db.update("UPDATE syllabus_job SET status='queued',lease_token=NULL,generation=? WHERE version_id=?",UUID.randomUUID(),id);var out=json.createObjectNode();out.set("sections",call.getArgument(1));out.putArray("issues");return out;}).when(ai).reviewSyllabus(anyString(),any());pipeline.processOne();assertThat(pipeline.status(id).get("reviewStatus")).isEqualTo("queued");assertThat(db.queryForObject("SELECT warnings_json FROM syllabus_version WHERE id=?",String.class,id)).isEqualTo("[]");}
    @Test void invalidVectorsNeverCreateReadyIndexOrLeakSecret(){UUID id=version(sections(1,true),"published");when(ai.embedSyllabus(anyList(),eq("admin"))).thenReturn(json.readTree("{\"model\":\"bad\",\"dimensions\":3,\"vectors\":[[0,0,0]]}"));enqueue(id,"index");pipeline.processOne();assertThat(pipeline.status(id)).containsEntry("indexStatus","failed").containsEntry("indexedCount",0);assertThat(pipeline.retrieve(id,"administrative_aptitude","能力",false)).isNull();}
    @Test void indexSlicesHaveBoundedExactTextAndOverlapAndExcludeExamples(){var values=sections(1,true);((tools.jackson.databind.node.ObjectNode)values.get(0)).put("text","甲".repeat(2600));var excluded=sections(1,true).get(0).deepCopy();((tools.jackson.databind.node.ObjectNode)excluded).put("ordinal",2).put("kind","example").put("text","不能索引");((tools.jackson.databind.node.ArrayNode)values).add(excluded);UUID id=version(values,"published");enqueue(id,"index");pipeline.processOne();assertThat(pipeline.status(id)).containsEntry("chunkCount",3).containsEntry("indexedCount",3);var texts=db.query("SELECT text FROM syllabus_chunk WHERE version_id=? ORDER BY chunk_no",(rs,n)->rs.getString(1),id);assertThat(texts.stream().map(String::length).toList()).containsExactly(1200,1200,500);assertThat(texts).allMatch(text->!text.contains("不能索引"));}
    @Test void reviewRequestsHonorTextBudgetAndFailureLeavesAllSuggestionsUncommitted(){var values=sections(3,false);for(JsonNode value:values)((tools.jackson.databind.node.ObjectNode)value).put("text","原".repeat(12000));UUID id=version(values,"draft");var sizes=new ArrayList<Integer>();final int[] n={0};doAnswer(call->{JsonNode batch=call.getArgument(1);sizes.add(batch.size());if(++n[0]==2)throw new IllegalStateException("SECRET_URL");var out=json.createObjectNode();out.set("sections",batch);out.putArray("issues").add("建议");return out;}).when(ai).reviewSyllabus(anyString(),any());enqueue(id,"review");pipeline.processOne();assertThat(sizes).containsExactly(1,1);assertThat(pipeline.status(id).get("reviewError").toString()).doesNotContain("SECRET");assertThat(json.readTree(db.queryForObject("SELECT sections_json FROM syllabus_version WHERE id=?",String.class,id))).isEqualTo(values);assertThat(db.queryForObject("SELECT warnings_json FROM syllabus_version WHERE id=?",String.class,id)).isEqualTo("[]");}
    @Test void staleRevisionAndOldLeaseCannotPublish(){UUID id=version(sections(1,false),"draft");enqueue(id,"review");doAnswer(call->{db.update("UPDATE syllabus_version SET revision=2 WHERE id=?",id);var out=json.createObjectNode();out.set("sections",call.getArgument(1));out.putArray("issues");return out;}).when(ai).reviewSyllabus(anyString(),any());pipeline.processOne();assertThat(pipeline.status(id).get("reviewStatus")).isNotEqualTo("ready");}
    @Test void expiredLeaseRequiresHumanRetryAndNeverRepeatsUncertainRequest(){UUID id=version(sections(1,true),"published");enqueue(id,"index");db.update("UPDATE syllabus_job SET status='processing',lease_token=?,lease_until=DATEADD('SECOND',-1,CURRENT_TIMESTAMP),in_flight=TRUE WHERE version_id=?",UUID.randomUUID(),id);pipeline.processOne();assertThat(pipeline.status(id).get("indexStatus")).isEqualTo("failed");verifyNoInteractions(ai);tx.executeWithoutResult(s->pipeline.retry(id,"index",1));pipeline.processOne();assertThat(pipeline.status(id).get("indexStatus")).isEqualTo("ready");}
}
