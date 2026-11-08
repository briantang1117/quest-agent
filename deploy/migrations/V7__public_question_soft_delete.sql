-- 题目删除保留来源/版本及原件，不物理删除；已发布须先下架。
ALTER TABLE public_question ADD COLUMN deleted_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE public_question ADD COLUMN deleted_by BIGINT REFERENCES app_user(id);
ALTER TABLE public_question ADD CONSTRAINT public_question_deletion_pair CHECK (
    (deleted_at IS NULL AND deleted_by IS NULL)
    OR (deleted_at IS NOT NULL AND deleted_by IS NOT NULL AND status <> 'published')
);
CREATE INDEX idx_public_question_visible ON public_question(bank_id,deleted_at,status);
