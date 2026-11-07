-- 历史角色不确定，不自动提升任何管理员。升级后历史账号全部禁用，需逐项核验再启用/重置。
ALTER TABLE app_user ADD COLUMN account_type VARCHAR(16) NOT NULL DEFAULT 'student' CHECK (account_type IN ('admin', 'student'));
ALTER TABLE app_user ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE app_user ADD COLUMN last_login_at TIMESTAMP WITH TIME ZONE;
UPDATE app_user SET status = 'disabled', must_change_password = TRUE;

-- sid 为随机 UUID；不存 JWT 或口令，也不复用 Agent 内容会话。
CREATE TABLE auth_session (
    id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_user(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at TIMESTAMP WITH TIME ZONE,
    CHECK (expires_at > created_at)
);
CREATE INDEX idx_auth_session_user ON auth_session(user_id, expires_at);
-- V3 没有 default privileges，必须显式授权。UUID 表无新增 sequence；沿用 app_user/audit_log 序列。
GRANT SELECT, INSERT, UPDATE ON auth_session TO quest_app;
GRANT USAGE, SELECT ON SEQUENCE app_user_id_seq, audit_log_id_seq TO quest_app;
REVOKE ALL ON auth_session, app_user FROM quest_ai;
REVOKE ALL ON auth_session FROM PUBLIC;
