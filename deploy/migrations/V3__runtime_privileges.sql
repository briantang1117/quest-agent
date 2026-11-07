-- Flyway 使用 quest_owner；运行服务不得使用此迁移账号或超级用户。
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO quest_app, quest_ai;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO quest_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO quest_app;
-- AI 不得读取用户、作答记录、会话、审计或未来新增业务表。
GRANT SELECT ON question, question_option, knowledge_point, question_knowledge,
    question_version TO quest_ai;
GRANT SELECT, INSERT, UPDATE, DELETE ON question_embedding, kb_doc TO quest_ai;
GRANT USAGE, SELECT ON SEQUENCE kb_doc_id_seq TO quest_ai;
-- 发布快照与审计日志由业务以追加方式写入，不允许运行期覆盖历史。
REVOKE UPDATE, DELETE ON question_version, audit_log FROM quest_app;
-- 迁移历史不属于运行期业务数据。
REVOKE ALL ON flyway_schema_history FROM quest_app;
-- 不给 AI 设置 ALL TABLES 默认授权；新表必须逐项审查。
