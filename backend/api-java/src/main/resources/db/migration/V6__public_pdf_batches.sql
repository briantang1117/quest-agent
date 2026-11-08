-- PDF原文件仅通过业务鉴权访问；首版限20MiB，后续对象存储迁移另行设计。
CREATE TABLE public_import_document (
    id UUID PRIMARY KEY,
    bank_id UUID NOT NULL REFERENCES public_bank(id),
    created_by BIGINT NOT NULL REFERENCES app_user(id),
    source_name VARCHAR(256) NOT NULL,
    sha256 CHAR(64) NOT NULL,
    file_bytes BYTEA NOT NULL,
    report_json TEXT NOT NULL,
    candidate_count INTEGER NOT NULL CHECK (candidate_count BETWEEN 0 AND 100),
    status VARCHAR(24) NOT NULL CHECK (status IN ('staged','queued','needs_attention')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (octet_length(file_bytes) BETWEEN 1 AND 20971520)
);
CREATE INDEX idx_public_document_bank ON public_import_document(bank_id,created_at);
ALTER TABLE public_import_task ADD COLUMN document_id UUID REFERENCES public_import_document(id);
ALTER TABLE public_import_task ADD COLUMN candidate_json TEXT;
ALTER TABLE public_import_task ADD COLUMN seq INTEGER;
-- 子任务沿用queued，处理器还必须核对所属批次已由导入人明确启动。
ALTER TABLE public_import_task ADD CONSTRAINT public_import_task_document_seq UNIQUE(document_id,seq);
ALTER TABLE public_import_task ADD CONSTRAINT public_import_task_document_fields CHECK (
    (document_id IS NULL AND seq IS NULL AND candidate_json IS NULL)
    OR (document_id IS NOT NULL AND seq IS NOT NULL AND seq > 0 AND candidate_json IS NOT NULL)
);
GRANT SELECT, INSERT, UPDATE ON public_import_document TO quest_app;
REVOKE ALL ON public_import_document FROM quest_ai;
