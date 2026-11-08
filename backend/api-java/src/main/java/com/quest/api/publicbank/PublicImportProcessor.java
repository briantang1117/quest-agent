package com.quest.api.publicbank;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.util.UUID;

/** 单任务领取后才外发；进程崩溃不自动重领processing，避免未知计费结果重复执行。 */
public class PublicImportProcessor {
    record Work(UUID id,UUID bank,long importer,String text,String candidate,UUID document) {}
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final PublicAiClient ai;
    private final ObjectMapper json;
    public PublicImportProcessor(JdbcTemplate db,PlatformTransactionManager manager,PublicAiClient ai,ObjectMapper json){this.db=db;this.tx=new TransactionTemplate(manager);this.ai=ai;this.json=json;}
    @Scheduled(fixedDelay=3000)
    public void processOne() { process(null,null); }
    /** 一次性获批批次处理，不开启全局队列，也不领取其他批次/单题。 */
    public boolean processDocumentOne(UUID documentId) { return process(java.util.Objects.requireNonNull(documentId),null); }
    public boolean processTaskOne(UUID taskId) { return process(null,java.util.Objects.requireNonNull(taskId)); }
    private boolean process(UUID documentId,UUID taskId) {
        Work work=tx.execute(status->{
            String scope=taskId!=null?" AND t.id=? AND t.document_id IS NULL":documentId==null?"":" AND t.document_id=?";
            Object[] args=taskId!=null?new Object[]{taskId}:documentId==null?new Object[0]:new Object[]{documentId};
            var rows=db.query("SELECT t.* FROM public_import_task t JOIN app_user u ON u.id=t.created_by WHERE t.status='queued' AND (t.document_id IS NULL OR EXISTS (SELECT 1 FROM public_import_document d WHERE d.id=t.document_id AND d.status='queued')) AND u.status='active' AND u.account_type='admin' AND u.must_change_password=FALSE"+scope+" ORDER BY t.created_at,t.seq,t.id LIMIT 1 FOR UPDATE",(rs,n)->new Work(rs.getObject("id",UUID.class),rs.getObject("bank_id",UUID.class),rs.getLong("created_by"),rs.getString("source_text"),rs.getString("candidate_json"),rs.getObject("document_id",UUID.class)),args);
            if(rows.isEmpty())return null;
            var item=rows.getFirst();
            if(db.update("UPDATE public_import_task SET status='processing',updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='queued'",item.id())!=1)return null;
            return item;
        });
        if(work==null)return false;
        try {
            var result=work.candidate()==null?ai.extract(work.text()):ai.extractCandidate(work.text(),json.readTree(work.candidate()));var draft=result.get("draft");
            if(work.document()!=null && result instanceof tools.jackson.databind.node.ObjectNode object){object.put("document_id",work.document().toString());object.set("source_candidate",json.readTree(work.candidate()));}
            if(draft==null || draft.isNull()){
                db.update("UPDATE public_import_task SET status='failed',error_message=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='processing'","材料存在歧义或题目不完整，请拆分并核对后重新提交",work.id());return true;
            }
            draft=PublicBankService.numberedDraft(draft,result);
            PublicBankService.validateDraft(draft);
            final var savedDraft=draft;
            tx.execute(status->{
                db.update("INSERT INTO public_question(id,bank_id,task_id,imported_by,draft_json,evidence_json) VALUES (?,?,?,?,?,?)",UUID.randomUUID(),work.bank(),work.id(),work.importer(),json.writeValueAsString(savedDraft),json.writeValueAsString(result));
                db.update("UPDATE public_import_task SET status='reviewing',updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='processing'",work.id());
                return null;
            });
        } catch(RuntimeException failure){
            db.update("UPDATE public_import_task SET status='failed',error_message=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='processing'","AI处理失败，未自动重试；请核对服务状态与材料",work.id());
        }
        return true;
    }
    @Configuration(proxyBeanMethods=false)
    @EnableScheduling
    @ConditionalOnProperty(name="quest.public-import.enabled",havingValue="true")
    public static class SchedulingConfiguration {
        @Bean PublicImportProcessor publicImportProcessor(JdbcTemplate db,PlatformTransactionManager manager,PublicAiClient ai,ObjectMapper json){return new PublicImportProcessor(db,manager,ai,json);}
    }
}
