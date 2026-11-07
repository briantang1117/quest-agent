-- 业务账号、RBAC、审计及会话；不内置任何用户或默认密码。
CREATE TABLE app_user (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    password_hash VARCHAR(128) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'disabled')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE app_role (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(32) NOT NULL UNIQUE,
    name VARCHAR(64) NOT NULL
);
CREATE TABLE app_permission (
    code VARCHAR(64) PRIMARY KEY,
    name VARCHAR(128) NOT NULL
);
CREATE TABLE user_role (
    user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    role_id BIGINT NOT NULL REFERENCES app_role(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, role_id)
);
CREATE TABLE role_permission (
    role_id BIGINT NOT NULL REFERENCES app_role(id) ON DELETE CASCADE,
    permission_code VARCHAR(64) NOT NULL REFERENCES app_permission(code),
    PRIMARY KEY (role_id, permission_code)
);
INSERT INTO app_role (code, name) VALUES
    ('super_admin', '超级管理员'), ('content_admin', '内容管理员'),
    ('reviewer', '审核员'), ('operator', '运营观察员'),
    ('agent_user', '导入工作台用户'), ('student', '学员');
INSERT INTO app_permission (code, name) VALUES
    ('question:read', '读取题库'), ('question:write', '编辑题库'),
    ('question:review', '审核题库'), ('question:publish', '发布题目'),
    ('paper:write', '编辑试卷'), ('stat:read', '读取运营统计'), ('agent:use', '使用 Agent');
-- 此处仅建立权限字典。角色绑定及管理员创建由后续认证模块完成。
CREATE TABLE audit_log (
    id BIGSERIAL PRIMARY KEY,
    actor BIGINT REFERENCES app_user(id),
    action VARCHAR(64) NOT NULL,
    target_type VARCHAR(64) NOT NULL,
    target_id VARCHAR(64),
    before_data JSONB,
    after_data JSONB,
    ip INET,
    user_agent TEXT,
    trace_id VARCHAR(64) NOT NULL,
    ai_job_id BIGINT REFERENCES ai_job(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_target ON audit_log(target_type, target_id, created_at DESC);
CREATE TABLE agent_session (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id BIGINT NOT NULL REFERENCES app_user(id),
    scope VARCHAR(16) NOT NULL CHECK (scope IN ('admin', 'student')),
    title VARCHAR(256) NOT NULL DEFAULT '新会话',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_session_user ON agent_session(user_id, updated_at DESC);
CREATE TABLE agent_message (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL REFERENCES agent_session(id) ON DELETE CASCADE,
    role VARCHAR(16) NOT NULL CHECK (role IN ('user', 'assistant', 'tool')),
    blocks JSONB NOT NULL DEFAULT '[]' CHECK (jsonb_typeof(blocks) = 'array'),
    status VARCHAR(16) NOT NULL CHECK (status IN ('streaming','done','error')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_message_session ON agent_message(session_id, created_at, id);
CREATE TABLE question_favorite (
    user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    question_id BIGINT NOT NULL REFERENCES question(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, question_id)
);
CREATE TABLE kb_doc (
    id BIGSERIAL PRIMARY KEY,
    question_id BIGINT NOT NULL UNIQUE REFERENCES question(id) ON DELETE CASCADE,
    question_version INTEGER NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    content_hash CHAR(64) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- 商业化仅占位，不提供支付能力。
CREATE TABLE membership (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_user(id),
    plan_code VARCHAR(32) NOT NULL,
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE purchase_order (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_user(id),
    order_no VARCHAR(64) NOT NULL UNIQUE,
    amount NUMERIC(12,2) NOT NULL CHECK (amount >= 0),
    status VARCHAR(16) NOT NULL DEFAULT 'pending',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
