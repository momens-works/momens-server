-- MOM-1017: 레거시 000001_init.sql의 decisions를 같은 컬럼·제약으로 기술합니다.
-- prod에는 이미 존재하므로 생성은 빈 환경에서만 수행합니다. DDL 소유권 전환과
-- momens_server의 기존 테이블 DML 권한 확인은 운영 전환 전에 별도로 수행합니다.
CREATE TABLE IF NOT EXISTS decisions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    title TEXT NOT NULL,
    context TEXT NOT NULL,
    alternatives TEXT,
    rationale TEXT NOT NULL,
    reversibility TEXT NOT NULL DEFAULT 'reversible'
        CHECK (reversibility IN ('reversible', 'irreversible')),
    decision_maker UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 새로 만든 공유 테이블에서도 레거시 writer가 전환 전까지 동작할 수 있어야 합니다.
-- Testcontainers처럼 해당 운영 계정이 없는 환경에서는 권한 부여를 생략합니다.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'postgres') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON decisions TO postgres;
    END IF;
END;
$$;
