-- AI 调用审计：元数据用于列表查询，原文单独存放、默认折叠。
-- 两层分开的原因：列表页只需要"何时、谁、哪个动作、成功否、耗时、重试几次"，
-- 原文体积大且只在排查单条失败时才需要，混在一起会让列表查询变慢。

-- 元数据：沿用 audit_log，不新增字段，action 形如 ai.coach.turn / ai.public_import。
COMMENT ON COLUMN audit_log.ai_job_id IS '关联 ai_job；AI 调用审计可为空';

CREATE TABLE ai_call_exchange (
    id UUID PRIMARY KEY,
    audit_id BIGINT NOT NULL REFERENCES audit_log(id) ON DELETE CASCADE,
    -- 请求侧：模型实际看到的输入（已按隐私边界裁剪后）与调用参数
    request_json JSONB NOT NULL,
    -- 响应侧：模型原始输出，失败时保存已被拒绝的原文，便于复现校验问题
    response_text TEXT,
    model VARCHAR(128),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX idx_ai_exchange_audit ON ai_call_exchange(audit_id);
CREATE INDEX idx_ai_exchange_created ON ai_call_exchange(created_at DESC);

-- 审计列表按时间倒序分页
CREATE INDEX idx_audit_created ON audit_log(created_at DESC);
CREATE INDEX idx_audit_action_created ON audit_log(action, created_at DESC);
CREATE INDEX idx_audit_actor_created ON audit_log(actor, created_at DESC);

-- 运行账号只追加，不可改写历史；原文同理。
GRANT SELECT, INSERT ON ai_call_exchange TO quest_app;
REVOKE UPDATE, DELETE ON ai_call_exchange FROM quest_app;
-- AI 服务不得读取审计与原文（含用户对话内容）。
REVOKE ALL ON ai_call_exchange FROM quest_ai;
