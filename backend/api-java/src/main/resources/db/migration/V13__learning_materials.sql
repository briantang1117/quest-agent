-- 私人学习资料与 RocketMQ 可靠投递；执行共享库迁移须独立授权。
CREATE TABLE learning_material (
    id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_user(id),
    session_id UUID NOT NULL REFERENCES student_coach_session(id),
    source_title VARCHAR(300) NOT NULL,
    subject VARCHAR(64) NOT NULL,
    title VARCHAR(300) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('queued','processing','ready','failed')),
    format VARCHAR(16) NOT NULL DEFAULT 'markdown' CHECK (format IN ('markdown','pdf')),
    instructions TEXT NOT NULL,
    snapshot_json TEXT NOT NULL,
    markdown TEXT,
    generation UUID NOT NULL,
    event_id UUID NOT NULL UNIQUE,
    lease_token UUID,
    lease_until TIMESTAMP WITH TIME ZONE,
    error VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_material_owner ON learning_material(user_id,created_at);
CREATE INDEX idx_material_session ON learning_material(session_id,status);
CREATE TABLE learning_material_request (
    user_id BIGINT NOT NULL REFERENCES app_user(id),
    request_id UUID NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    material_id UUID NOT NULL REFERENCES learning_material(id),
    PRIMARY KEY(user_id,request_id)
);
ALTER TABLE mq_outbox ADD COLUMN lease_token UUID;
ALTER TABLE mq_outbox ADD COLUMN lease_until TIMESTAMP WITH TIME ZONE;
GRANT SELECT, INSERT, UPDATE ON learning_material TO quest_app;
GRANT SELECT, INSERT ON learning_material_request TO quest_app;
REVOKE ALL ON learning_material,learning_material_request FROM quest_ai;
