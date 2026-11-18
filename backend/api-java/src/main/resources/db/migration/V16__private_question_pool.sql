-- 私人题池仅本人使用；新增对象不复用公共题库审核/发布状态。
CREATE TABLE private_asset (
 id UUID PRIMARY KEY,
 user_id BIGINT NOT NULL REFERENCES app_user(id),
 request_id UUID NOT NULL,
 filename VARCHAR(200) NOT NULL,
 kind VARCHAR(10) NOT NULL CHECK(kind IN ('image','pdf','text')),
 mime VARCHAR(80) NOT NULL,
 sha256 CHAR(64) NOT NULL,
 file_bytes BYTEA NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(user_id,request_id), UNIQUE(id,user_id)
);
CREATE TABLE private_import (
 id UUID PRIMARY KEY,
 user_id BIGINT NOT NULL REFERENCES app_user(id),
 session_id UUID NOT NULL REFERENCES student_coach_session(id),
 request_id UUID NOT NULL,
 subject VARCHAR(40) NOT NULL CHECK(subject IN ('vocational_aptitude','administrative_aptitude')),
 kind VARCHAR(10) NOT NULL CHECK(kind IN ('question','mistake')),
 status VARCHAR(24) NOT NULL DEFAULT 'queued' CHECK(status IN ('queued','processing','completed','needs_attention','failed','cancelled')),
 stage VARCHAR(30) NOT NULL DEFAULT 'queued',
 run_id UUID,
 lease_until TIMESTAMP WITH TIME ZONE,
 error_code INTEGER,
 warnings_json TEXT NOT NULL DEFAULT '[]',
 created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(user_id,request_id), UNIQUE(id,user_id)
);
CREATE INDEX idx_private_import_queue ON private_import(status,created_at);
CREATE INDEX idx_private_import_session ON private_import(user_id,session_id,created_at);
CREATE TABLE private_import_source (
 id UUID PRIMARY KEY,
 import_id UUID NOT NULL,
 user_id BIGINT NOT NULL,
 asset_id UUID,
 ordinal INTEGER NOT NULL,
 source_text TEXT,
 status VARCHAR(16) NOT NULL DEFAULT 'queued' CHECK(status IN ('queued','processing','done','failed')),
 error_code INTEGER,
 FOREIGN KEY(import_id,user_id) REFERENCES private_import(id,user_id),
 FOREIGN KEY(asset_id,user_id) REFERENCES private_asset(id,user_id),
 UNIQUE(import_id,ordinal)
);
CREATE TABLE private_question (
 id UUID PRIMARY KEY,
 user_id BIGINT NOT NULL REFERENCES app_user(id),
 subject VARCHAR(40) NOT NULL CHECK(subject IN ('vocational_aptitude','administrative_aptitude')),
 fingerprint CHAR(64) NOT NULL,
 kind VARCHAR(10) NOT NULL CHECK(kind IN ('question','mistake')),
 status VARCHAR(24) NOT NULL CHECK(status IN ('ready','needs_attention','excluded')),
 result_json TEXT NOT NULL,
 revision BIGINT NOT NULL DEFAULT 1,
 deleted_at TIMESTAMP WITH TIME ZONE,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(user_id,fingerprint), UNIQUE(id,user_id)
);
CREATE INDEX idx_private_question_owner ON private_question(user_id,subject,status,updated_at DESC);
CREATE TABLE private_question_version (
 question_id UUID NOT NULL,
 user_id BIGINT NOT NULL,
 revision BIGINT NOT NULL,
 result_json TEXT NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(question_id,revision),
 FOREIGN KEY(question_id,user_id) REFERENCES private_question(id,user_id)
);
CREATE TABLE private_import_item (
 id UUID PRIMARY KEY,
 import_id UUID NOT NULL,
 user_id BIGINT NOT NULL,
 source_id UUID NOT NULL REFERENCES private_import_source(id),
 ordinal INTEGER NOT NULL,
 candidate_json TEXT NOT NULL,
 status VARCHAR(24) NOT NULL DEFAULT 'queued' CHECK(status IN ('queued','processing','ready','needs_attention','duplicate','failed','excluded')),
 result_json TEXT,
 question_id UUID,
 question_revision BIGINT,
 error_code INTEGER,
 FOREIGN KEY(import_id,user_id) REFERENCES private_import(id,user_id),
 FOREIGN KEY(question_id,user_id) REFERENCES private_question(id,user_id),
 UNIQUE(import_id,ordinal)
);
GRANT SELECT,INSERT,UPDATE ON private_asset,private_import,private_import_source,private_question,private_import_item TO quest_app;
GRANT SELECT,INSERT ON private_question_version TO quest_app;
REVOKE ALL ON private_asset,private_import,private_import_source,private_question,private_question_version,private_import_item FROM quest_ai;
REVOKE ALL ON private_asset,private_import,private_import_source,private_question,private_question_version,private_import_item FROM PUBLIC;
