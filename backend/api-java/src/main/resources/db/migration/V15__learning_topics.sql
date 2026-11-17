-- 用户明确选择的中期学习主题；会话滚动摘要沿用 session.state_json。
CREATE TABLE student_learning_topic (
    id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_user(id),
    subject VARCHAR(40) NOT NULL CHECK (subject IN ('vocational_aptitude','administrative_aptitude')),
    title VARCHAR(100) NOT NULL,
    goal VARCHAR(1500) NOT NULL DEFAULT '',
    progress VARCHAR(1500) NOT NULL DEFAULT '',
    questions VARCHAR(1500) NOT NULL DEFAULT '',
    plan VARCHAR(1500) NOT NULL DEFAULT '',
    revision BIGINT NOT NULL DEFAULT 0,
    source_session_id UUID REFERENCES student_coach_session(id),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_learning_topic_owner ON student_learning_topic(user_id,updated_at DESC);
GRANT SELECT, INSERT, UPDATE ON student_learning_topic TO quest_app;
REVOKE ALL ON student_learning_topic FROM quest_ai;
REVOKE ALL ON student_learning_topic FROM PUBLIC;
