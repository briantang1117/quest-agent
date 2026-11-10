-- 考纲异步任务与版本内精确向量检索；仅新增，本迁移未获执行授权。
-- 不建立 ANN/HNSW 索引，不修改旧 question_embedding 的 1024 维约束。
CREATE TABLE syllabus_pipeline_lock (
    id INTEGER PRIMARY KEY CHECK (id=1)
);
INSERT INTO syllabus_pipeline_lock(id) VALUES (1);
CREATE TABLE syllabus_job (
    id UUID PRIMARY KEY,
    version_id UUID NOT NULL REFERENCES syllabus_version(id),
    type VARCHAR(16) NOT NULL CHECK (type IN ('review','index')),
    revision INTEGER NOT NULL CHECK (revision>0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('queued','processing','ready','failed')),
    attempt INTEGER NOT NULL DEFAULT 1 CHECK (attempt>0),
    generation UUID NOT NULL,
    lease_token UUID,
    lease_until TIMESTAMP WITH TIME ZONE,
    in_flight BOOLEAN NOT NULL DEFAULT FALSE,
    indexed_count INTEGER NOT NULL DEFAULT 0 CHECK (indexed_count BETWEEN 0 AND 5000),
    chunk_count INTEGER NOT NULL DEFAULT 0 CHECK (chunk_count BETWEEN 0 AND 5000),
    embedding_model VARCHAR(300),
    embedding_dimensions INTEGER CHECK (embedding_dimensions BETWEEN 1 AND 4096),
    error VARCHAR(200),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(version_id,type)
);
CREATE INDEX idx_syllabus_job_queue ON syllabus_job(status,created_at);
CREATE TABLE syllabus_chunk (
    id UUID PRIMARY KEY,
    version_id UUID NOT NULL REFERENCES syllabus_version(id),
    generation UUID NOT NULL,
    revision INTEGER NOT NULL,
    chunk_no INTEGER NOT NULL CHECK (chunk_no BETWEEN 0 AND 4999),
    section_ordinal INTEGER NOT NULL,
    subject VARCHAR(40) NOT NULL CHECK (subject IN ('vocational_aptitude','administrative_aptitude')),
    enabled BOOLEAN NOT NULL CHECK (enabled),
    section_title VARCHAR(300) NOT NULL,
    section_path VARCHAR(1000) NOT NULL,
    page_start INTEGER NOT NULL,
    page_end INTEGER NOT NULL,
    printed_pages VARCHAR(1000) NOT NULL,
    text TEXT NOT NULL CHECK (LENGTH(text) BETWEEN 1 AND 1200),
    content_hash CHAR(64) NOT NULL,
    embedding_model VARCHAR(300),
    embedding_dimensions INTEGER CHECK (embedding_dimensions BETWEEN 1 AND 4096),
    embedding vector,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(version_id,generation,chunk_no),
    CHECK ((embedding IS NULL AND embedding_model IS NULL AND embedding_dimensions IS NULL) OR
           (embedding IS NOT NULL AND embedding_model IS NOT NULL AND embedding_dimensions IS NOT NULL AND vector_dims(embedding)=embedding_dimensions))
);
CREATE INDEX idx_syllabus_chunk_scan ON syllabus_chunk(version_id,generation,subject,embedding_model,embedding_dimensions);
CREATE INDEX idx_syllabus_chunk_cache ON syllabus_chunk(version_id,content_hash,embedding_model,embedding_dimensions);
GRANT SELECT,INSERT,UPDATE ON syllabus_job,syllabus_pipeline_lock TO quest_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON syllabus_chunk TO quest_app;
REVOKE ALL ON syllabus_job,syllabus_chunk,syllabus_pipeline_lock FROM quest_ai;
