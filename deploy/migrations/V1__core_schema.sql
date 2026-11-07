-- 基于 v1.4 方案的核心域；结构化数据为唯一事实源。

-- 题目主表
CREATE TABLE question (
    id              BIGSERIAL PRIMARY KEY,
    type            VARCHAR(32)  NOT NULL,
    stem_md         TEXT         NOT NULL,
    stem_images     JSONB        NOT NULL DEFAULT '[]',
    answer_keys     JSONB        NOT NULL,          -- ["A"] / ["A","C"]
    answer_source   VARCHAR(16)  NOT NULL DEFAULT 'manual',
    answer_conf     NUMERIC(4,3),
    explanation_md  TEXT,
    expl_source     VARCHAR(16),
    difficulty      SMALLINT     NOT NULL DEFAULT 3,
    exam_type       VARCHAR(32),
    exam_name       VARCHAR(128),
    exam_year       SMALLINT,
    region          VARCHAR(64),
    question_no     INTEGER,
    status          VARCHAR(24)  NOT NULL DEFAULT 'draft',
    quality_score   NUMERIC(4,3),
    tags            JSONB        NOT NULL DEFAULT '[]',
    content_hash    CHAR(64),
    simhash         BIGINT,
    current_version INTEGER      NOT NULL DEFAULT 1,
    index_status    SMALLINT     NOT NULL DEFAULT 0,  -- 0待建 1成功 2失败
    created_by      BIGINT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ
);
CREATE INDEX idx_q_status_type ON question (status, type);
CREATE INDEX idx_q_exam ON question (exam_type, exam_year);
CREATE INDEX idx_q_hash ON question (content_hash);
CREATE INDEX idx_q_updated ON question (updated_at);
CREATE INDEX idx_q_tags ON question USING gin (tags);

-- 选项表
CREATE TABLE question_option (
    id          BIGSERIAL PRIMARY KEY,
    question_id BIGINT NOT NULL REFERENCES question(id) ON DELETE CASCADE,
    opt_key     CHAR(1) NOT NULL,
    text_md     TEXT NOT NULL,
    image_url   VARCHAR(512),
    sort_no     SMALLINT NOT NULL,
    UNIQUE (question_id, opt_key)
);

-- 知识点树
CREATE TABLE knowledge_point (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(64) NOT NULL UNIQUE,   -- GK/FL/MF/0003
    parent_id   BIGINT REFERENCES knowledge_point(id),
    name        VARCHAR(128) NOT NULL,
    level       SMALLINT NOT NULL,
    path        VARCHAR(512) NOT NULL,         -- 物化路径，便于子树查询
    exam_type   VARCHAR(32),
    sort_no     INTEGER DEFAULT 0,
    status      SMALLINT DEFAULT 1
);
CREATE INDEX idx_kp_path ON knowledge_point (path);

-- 题目-知识点 多对多
CREATE TABLE question_knowledge (
    question_id BIGINT NOT NULL REFERENCES question(id) ON DELETE CASCADE,
    kp_id       BIGINT NOT NULL REFERENCES knowledge_point(id),
    PRIMARY KEY (question_id, kp_id)
);
CREATE INDEX idx_qk_kp ON question_knowledge (kp_id);

-- 题目版本快照（发布即冻结，供试卷引用）
CREATE TABLE question_version (
    id          BIGSERIAL PRIMARY KEY,
    question_id BIGINT NOT NULL REFERENCES question(id),
    version     INTEGER NOT NULL,
    snapshot    JSONB NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (question_id, version)
);

CREATE TABLE import_batch (
    id           BIGSERIAL PRIMARY KEY,
    source_type  VARCHAR(16) NOT NULL,   -- text|image|docx|pdf|chat
    source_name  VARCHAR(256),
    file_url     VARCHAR(512),
    raw_text     TEXT,
    status       VARCHAR(24) NOT NULL,   -- parsing|extracting|reviewing|done|failed
    total_count  INTEGER DEFAULT 0,
    ok_count     INTEGER DEFAULT 0,
    fail_count   INTEGER DEFAULT 0,
    created_by   BIGINT NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_ib_status ON import_batch (status, created_at);

CREATE TABLE import_item (
    id           BIGSERIAL PRIMARY KEY,
    batch_id     BIGINT NOT NULL REFERENCES import_batch(id) ON DELETE CASCADE,
    seq          INTEGER NOT NULL,
    raw_text     TEXT,
    parsed_json  JSONB,
    confidence   NUMERIC(4,3),
    errors       JSONB NOT NULL DEFAULT '[]',
    status       VARCHAR(24) NOT NULL,   -- pending|valid|invalid|merged|imported|ignored
    question_id  BIGINT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (batch_id, seq)
);
CREATE INDEX idx_ii_status ON import_item (status);

-- AI 任务记录（成本、可追溯）
CREATE TABLE ai_job (
    id                BIGSERIAL PRIMARY KEY,
    job_type          VARCHAR(32) NOT NULL,   -- extract|answer|explain|dedupe|embed
    biz_ref           VARCHAR(64),
    provider          VARCHAR(32),
    model             VARCHAR(64),
    prompt_tokens     INTEGER,
    completion_tokens INTEGER,
    cost_usd          NUMERIC(10,6),
    status            VARCHAR(16) NOT NULL,
    error             TEXT,
    latency_ms        INTEGER,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_aj_type_time ON ai_job (job_type, created_at);
CREATE INDEX idx_aj_biz ON ai_job (biz_ref);

CREATE TABLE practice_session (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL,
    mode        VARCHAR(24) NOT NULL,   -- chapter|random|wrong|paper|favorite
    ref_id      BIGINT,
    total       INTEGER DEFAULT 0,
    answered    INTEGER DEFAULT 0,
    correct     INTEGER DEFAULT 0,
    started_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ
);
CREATE INDEX idx_ps_user ON practice_session (user_id, started_at DESC);

CREATE TABLE answer_record (
    id           BIGSERIAL,
    user_id      BIGINT NOT NULL,
    question_id  BIGINT NOT NULL REFERENCES question(id),
    session_id   BIGINT,
    paper_id     BIGINT,
    user_keys    JSONB NOT NULL,
    is_correct   BOOLEAN NOT NULL,
    duration_ms  INTEGER,
    answered_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id, answered_at)
) PARTITION BY RANGE (answered_at);
CREATE TABLE answer_record_default PARTITION OF answer_record DEFAULT;
CREATE INDEX idx_ar_user_time ON answer_record (user_id, answered_at DESC);
CREATE INDEX idx_ar_question ON answer_record (question_id);
-- 数据量上来后改为按月 RANGE 分区（见 12.2）

CREATE TABLE question_stat (
    question_id     BIGINT PRIMARY KEY REFERENCES question(id) ON DELETE CASCADE,
    attempt_count   INTEGER DEFAULT 0,
    correct_count   INTEGER DEFAULT 0,
    p_value         NUMERIC(5,4),
    discrimination  NUMERIC(5,4),
    avg_duration_ms INTEGER,
    updated_at      TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE stat_daily (
    stat_date    DATE NOT NULL,
    exam_type    VARCHAR(32) NOT NULL DEFAULT '',
    new_questions INTEGER DEFAULT 0,
    answer_count  INTEGER DEFAULT 0,
    active_users  INTEGER DEFAULT 0,
    correct_rate  NUMERIC(5,4),
    PRIMARY KEY (stat_date, exam_type)
);

CREATE TABLE user_knowledge_mastery (
    user_id      BIGINT NOT NULL,
    kp_id        BIGINT NOT NULL,
    mastery      NUMERIC(5,2) NOT NULL DEFAULT 0,
    sample_count INTEGER NOT NULL DEFAULT 0,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, kp_id)
);
CREATE INDEX idx_ukm_kp ON user_knowledge_mastery (kp_id);

CREATE TABLE mq_outbox (
    id            BIGSERIAL PRIMARY KEY,
    topic         VARCHAR(64) NOT NULL,
    tag           VARCHAR(64) NOT NULL,
    msg_key       VARCHAR(64) NOT NULL,
    payload       JSONB NOT NULL,
    status        SMALLINT NOT NULL DEFAULT 0, -- 0待投递 1已投递 2失败放弃
    retry_count   INTEGER NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_outbox_pending ON mq_outbox (status, next_retry_at);

CREATE TABLE mq_consume_log (
    id             BIGSERIAL PRIMARY KEY,
    msg_id         VARCHAR(64) NOT NULL,
    consumer_group VARCHAR(64) NOT NULL,
    status         SMALLINT NOT NULL DEFAULT 1,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (msg_id, consumer_group)
);

CREATE TABLE question_embedding (
    question_id BIGINT PRIMARY KEY REFERENCES question(id) ON DELETE CASCADE,
    embedding   vector(1024) NOT NULL,
    model       VARCHAR(64) NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_qe_hnsw ON question_embedding
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

CREATE TABLE paper (
    id             BIGSERIAL PRIMARY KEY,
    title          VARCHAR(256) NOT NULL,
    paper_type     VARCHAR(16) NOT NULL,   -- real|mock|custom
    exam_type      VARCHAR(32),
    exam_year      SMALLINT,
    region         VARCHAR(64),
    total_score    NUMERIC(6,2),
    duration_min   INTEGER,
    question_count INTEGER,
    status         VARCHAR(16) DEFAULT 'draft',
    created_by     BIGINT,
    created_at     TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX idx_paper_type ON paper (paper_type, exam_year);

CREATE TABLE paper_section (
    id       BIGSERIAL PRIMARY KEY,
    paper_id BIGINT NOT NULL REFERENCES paper(id) ON DELETE CASCADE,
    name     VARCHAR(128),
    sort_no  INTEGER DEFAULT 0,
    score    NUMERIC(6,2)
);

CREATE TABLE paper_question (
    id                  BIGSERIAL PRIMARY KEY,
    paper_id            BIGINT NOT NULL REFERENCES paper(id) ON DELETE CASCADE,
    section_id          BIGINT,
    question_version_id BIGINT NOT NULL REFERENCES question_version(id),
    question_no         INTEGER NOT NULL,
    score               NUMERIC(5,2) DEFAULT 1,
    UNIQUE (paper_id, question_no)
);
CREATE INDEX idx_pq_version ON paper_question (question_version_id);

CREATE TABLE exam_attempt (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT NOT NULL,
    paper_id      BIGINT NOT NULL,
    status        VARCHAR(16) NOT NULL,   -- ongoing|submitted|timeout
    score         NUMERIC(6,2),
    correct_count INTEGER,
    started_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    submitted_at  TIMESTAMPTZ,
    duration_sec  INTEGER
);
CREATE INDEX idx_ea_user ON exam_attempt (user_id, started_at DESC);
CREATE INDEX idx_ea_paper ON exam_attempt (paper_id);

CREATE TABLE exam_answer (
    id                  BIGSERIAL PRIMARY KEY,
    attempt_id          BIGINT NOT NULL REFERENCES exam_attempt(id) ON DELETE CASCADE,
    question_id         BIGINT NOT NULL,
    question_version_id BIGINT NOT NULL,
    user_keys           JSONB,
    is_correct          BOOLEAN,
    score               NUMERIC(5,2),
    answered_at         TIMESTAMPTZ,
    UNIQUE (attempt_id, question_id)
);

-- 默认分区保证首次启动可写；正式增长阶段接入滚动月分区维护。
ALTER TABLE question ADD CONSTRAINT ck_question_type CHECK (type IN ('single_choice', 'multiple_choice'));
ALTER TABLE question ADD CONSTRAINT ck_question_status CHECK (status IN ('draft', 'pending_review', 'published', 'archived'));
ALTER TABLE question ADD CONSTRAINT ck_question_stem CHECK (char_length(btrim(stem_md)) BETWEEN 1 AND 5000);
ALTER TABLE question ADD CONSTRAINT ck_question_difficulty CHECK (difficulty BETWEEN 1 AND 5);
ALTER TABLE question ADD CONSTRAINT ck_question_answers CHECK (
    jsonb_typeof(answer_keys) = 'array' AND
    CASE type WHEN 'single_choice' THEN jsonb_array_length(answer_keys) = 1
    ELSE jsonb_array_length(answer_keys) BETWEEN 2 AND 8 END
);
ALTER TABLE question ADD CONSTRAINT ck_question_tags CHECK (jsonb_typeof(tags) = 'array');
ALTER TABLE question ADD CONSTRAINT ck_question_version CHECK (current_version > 0);
ALTER TABLE question ADD CONSTRAINT ck_answer_conf CHECK (answer_conf BETWEEN 0 AND 1);
ALTER TABLE question_option ADD CONSTRAINT ck_option_key CHECK (opt_key BETWEEN 'A' AND 'H');
ALTER TABLE question_option ADD CONSTRAINT ck_option_text CHECK (char_length(btrim(text_md)) > 0);
ALTER TABLE mq_outbox ADD CONSTRAINT ck_outbox_status CHECK (status IN (0,1,2));
ALTER TABLE mq_outbox ADD CONSTRAINT ck_outbox_retry CHECK (retry_count >= 0);
ALTER TABLE mq_outbox ADD COLUMN event_id UUID NOT NULL DEFAULT gen_random_uuid();
CREATE UNIQUE INDEX idx_outbox_event ON mq_outbox(event_id);
ALTER TABLE question_embedding ADD COLUMN question_version INTEGER NOT NULL DEFAULT 1;
ALTER TABLE paper_section ADD CONSTRAINT uq_section_paper UNIQUE (id, paper_id);
ALTER TABLE paper_question ADD CONSTRAINT fk_paper_section FOREIGN KEY (section_id, paper_id) REFERENCES paper_section(id, paper_id);
ALTER TABLE exam_attempt ADD CONSTRAINT fk_attempt_paper FOREIGN KEY (paper_id) REFERENCES paper(id);
ALTER TABLE exam_answer ADD CONSTRAINT fk_exam_question FOREIGN KEY (question_id) REFERENCES question(id);
ALTER TABLE exam_answer ADD CONSTRAINT fk_exam_version FOREIGN KEY (question_version_id) REFERENCES question_version(id);
ALTER TABLE practice_session ADD CONSTRAINT ck_practice_mode CHECK (mode IN ('chapter','random','wrong','favorite','paper'));
ALTER TABLE paper ADD CONSTRAINT ck_paper_type CHECK (paper_type IN ('real','mock','custom'));
ALTER TABLE exam_attempt ADD CONSTRAINT ck_attempt_status CHECK (status IN ('ongoing','submitted','timeout'));
CREATE INDEX idx_q_stem_trgm ON question USING gin (stem_md gin_trgm_ops);
