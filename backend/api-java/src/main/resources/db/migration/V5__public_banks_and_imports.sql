-- 公共内容单独分域，不改变历史题表与用户私有数据的授权。
CREATE TABLE public_bank (
    id UUID PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    exam_type VARCHAR(32) NOT NULL CHECK (exam_type IN ('career_a', 'shanghai_civil')),
    subject VARCHAR(32) NOT NULL CHECK (subject IN ('vocational_aptitude', 'administrative_aptitude')),
    created_by BIGINT NOT NULL REFERENCES app_user(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE public_import_task (
    id UUID PRIMARY KEY,
    bank_id UUID NOT NULL REFERENCES public_bank(id),
    created_by BIGINT NOT NULL REFERENCES app_user(id),
    source_text TEXT NOT NULL,
    status VARCHAR(24) NOT NULL CHECK (status IN ('queued', 'processing', 'reviewing', 'failed')),
    error_message VARCHAR(256),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_public_import_queue ON public_import_task(status, created_at);
CREATE TABLE public_question (
    id UUID PRIMARY KEY,
    bank_id UUID NOT NULL REFERENCES public_bank(id),
    task_id UUID NOT NULL UNIQUE REFERENCES public_import_task(id),
    imported_by BIGINT NOT NULL REFERENCES app_user(id),
    draft_json TEXT NOT NULL,
    evidence_json TEXT NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'draft' CHECK (status IN ('draft', 'confirmed', 'published')),
    version INTEGER NOT NULL DEFAULT 1 CHECK (version > 0),
    confirmed_by BIGINT REFERENCES app_user(id),
    confirmed_at TIMESTAMP WITH TIME ZONE,
    confirmed_version INTEGER,
    published_at TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ((status = 'draft' AND confirmed_by IS NULL AND confirmed_at IS NULL AND confirmed_version IS NULL)
        OR (status IN ('confirmed', 'published') AND confirmed_by = imported_by AND confirmed_at IS NOT NULL AND confirmed_version = version)),
    CHECK ((status = 'published') = (published_at IS NOT NULL))
);
CREATE INDEX idx_public_question_bank ON public_question(bank_id, status);
GRANT SELECT, INSERT ON public_bank TO quest_app;
GRANT SELECT, INSERT, UPDATE ON public_import_task, public_question TO quest_app;
REVOKE ALL ON public_bank, public_import_task, public_question FROM quest_ai;
