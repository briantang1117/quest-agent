-- 账号续期、后台元数据审计与累计模型额度。仅新增，部署前须明确授权执行。
CREATE TABLE auth_refresh (
    session_id UUID PRIMARY KEY REFERENCES auth_session(id),
    token_hash CHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    remember BOOLEAN NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE account_event (
    id BIGSERIAL PRIMARY KEY,
    actor BIGINT REFERENCES app_user(id),
    username VARCHAR(64) NOT NULL DEFAULT '',
    action VARCHAR(80) NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    target_id VARCHAR(64),
    source VARCHAR(200) NOT NULL DEFAULT '',
    user_agent VARCHAR(500) NOT NULL DEFAULT '',
    detail VARCHAR(1000) NOT NULL DEFAULT '',
    trace_id VARCHAR(64) NOT NULL DEFAULT '',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_account_event_created ON account_event(created_at DESC);
CREATE INDEX idx_account_event_actor ON account_event(actor,created_at DESC);
CREATE TABLE api_call_metric (
    id BIGSERIAL PRIMARY KEY,
    actor BIGINT REFERENCES app_user(id),
    method VARCHAR(10) NOT NULL,
    route VARCHAR(200) NOT NULL,
    status INTEGER NOT NULL,
    elapsed_ms BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_api_metric_actor ON api_call_metric(actor,created_at DESC);
CREATE TABLE user_feature (
    user_id BIGINT NOT NULL REFERENCES app_user(id),
    feature VARCHAR(64) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(user_id,feature)
);
CREATE TABLE model_balance (
    user_id BIGINT NOT NULL REFERENCES app_user(id),
    model VARCHAR(300) NOT NULL,
    credited BIGINT NOT NULL DEFAULT 0 CHECK(credited>=0),
    consumed BIGINT NOT NULL DEFAULT 0 CHECK(consumed>=0),
    reserved BIGINT NOT NULL DEFAULT 0 CHECK(reserved>=0),
    PRIMARY KEY(user_id,model)
);
CREATE TABLE model_credit (
    request_id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_user(id),
    model VARCHAR(300) NOT NULL,
    amount BIGINT NOT NULL CHECK(amount>0),
    actor BIGINT NOT NULL REFERENCES app_user(id),
    reason VARCHAR(500) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE ai_usage (
    id UUID PRIMARY KEY,
    user_id BIGINT REFERENCES app_user(id),
    model VARCHAR(300) NOT NULL,
    capability VARCHAR(100) NOT NULL,
    reservation BIGINT NOT NULL CHECK(reservation>=0),
    input_tokens BIGINT,
    output_tokens BIGINT,
    status VARCHAR(16) NOT NULL CHECK(status IN ('reserved','settled','unknown')),
    outcome VARCHAR(16) NOT NULL DEFAULT 'pending',
    trace_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_usage_actor_model ON ai_usage(user_id,model,created_at DESC);
GRANT SELECT,INSERT,UPDATE,DELETE ON auth_refresh TO quest_app;
GRANT SELECT,INSERT,UPDATE ON user_feature,model_balance,ai_usage TO quest_app;
GRANT SELECT,INSERT ON account_event,api_call_metric,model_credit TO quest_app;
GRANT USAGE,SELECT ON SEQUENCE account_event_id_seq,api_call_metric_id_seq TO quest_app;
REVOKE ALL ON auth_refresh,account_event,api_call_metric,user_feature,model_balance,model_credit,ai_usage FROM quest_ai,PUBLIC;
