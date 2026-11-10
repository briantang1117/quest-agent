-- 个人历史会话软删除；仅新增结构，本迁移尚未获准执行。
-- 保留原状态与轮次用于幂等性约束，业务读取必须同时限定本人和 deleted_at IS NULL。
ALTER TABLE student_coach_session ADD COLUMN deleted_at TIMESTAMP WITH TIME ZONE;
