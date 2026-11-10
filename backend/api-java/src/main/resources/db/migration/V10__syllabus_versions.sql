-- 考纲版本和原文：仅新增，不更改已执行迁移；本版迁移尚未授权执行。
CREATE TABLE syllabus_version (
    id UUID PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    exam_type VARCHAR(40) NOT NULL CHECK (exam_type IN ('career_a','shanghai_civil')),
    exam_year INTEGER NOT NULL CHECK (exam_year BETWEEN 2000 AND 2100),
    version_label VARCHAR(100) NOT NULL,
    issuer VARCHAR(200) NOT NULL,
    published_date DATE,
    source_url VARCHAR(2000) NOT NULL DEFAULT '',
    source_name VARCHAR(255) NOT NULL,
    sha256 CHAR(64) NOT NULL,
    page_count INTEGER NOT NULL CHECK (page_count BETWEEN 1 AND 200),
    pages_json TEXT NOT NULL,
    sections_json TEXT NOT NULL,
    warnings_json TEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'draft' CHECK (status IN ('draft','published','archived')),
    revision INTEGER NOT NULL DEFAULT 1 CHECK (revision>0),
    created_by BIGINT NOT NULL REFERENCES app_user(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_syllabus_lookup ON syllabus_version(exam_type,exam_year,status);
-- 同考试同年份只能一个活动版本；历史归档允许保留多个版本。
CREATE TABLE syllabus_current (
    exam_type VARCHAR(40) NOT NULL,
    exam_year INTEGER NOT NULL,
    version_id UUID NOT NULL UNIQUE REFERENCES syllabus_version(id),
    PRIMARY KEY(exam_type,exam_year)
);
CREATE TABLE syllabus_file (
    version_id UUID PRIMARY KEY REFERENCES syllabus_version(id),
    file_bytes BYTEA NOT NULL
);
ALTER TABLE student_study_preference ADD COLUMN exam_year INTEGER CHECK (exam_year BETWEEN 2000 AND 2100);
GRANT SELECT,INSERT,UPDATE ON syllabus_version,syllabus_current TO quest_app;
GRANT DELETE ON syllabus_current TO quest_app;
GRANT SELECT,INSERT ON syllabus_file TO quest_app;
REVOKE ALL ON syllabus_version,syllabus_current,syllabus_file FROM quest_ai;
