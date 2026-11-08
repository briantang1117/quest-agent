-- 学员偏好和陪练会话独立分域，所有查询必须绑定当前会话用户。
CREATE TABLE student_study_preference (
    user_id BIGINT PRIMARY KEY REFERENCES app_user(id),
    subject VARCHAR(40) NOT NULL CHECK (subject IN ('vocational_aptitude','administrative_aptitude')),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE student_coach_session (
    id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_user(id),
    subject VARCHAR(40) NOT NULL CHECK (subject IN ('vocational_aptitude','administrative_aptitude')),
    title VARCHAR(128) NOT NULL,
    state_json TEXT NOT NULL,
    processing_request UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_student_coach_owner ON student_coach_session(user_id,updated_at);
CREATE TABLE student_coach_turn (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES student_coach_session(id),
    request_id UUID NOT NULL,
    request_hash CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('processing','done','failed')),
    response_json TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (session_id,request_id)
);
CREATE INDEX idx_student_coach_turn_order ON student_coach_turn(session_id,created_at,id);
GRANT SELECT, INSERT, UPDATE ON student_study_preference,student_coach_session,student_coach_turn TO quest_app;
REVOKE ALL ON student_study_preference,student_coach_session,student_coach_turn FROM quest_ai;
