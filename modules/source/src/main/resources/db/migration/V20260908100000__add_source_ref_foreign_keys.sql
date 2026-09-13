-- source_refs에 외래 키 두 개를 local과 test 환경에도 추가합니다(MOM-0918).
--
-- 운영 환경의 source_refs에는 레거시 000002_retrieval_projection.sql과 000006_fe_contract.sql에서
-- 인라인 REFERENCES로 생성한 외래 키가 이미 있습니다. 이 서버가 source_refs에 데이터를 저장하기
-- 시작하면서(MOM-0868의 manual link, H096 검증), 존재하지 않는 워크스페이스나 사용자를 참조하는
-- 행이 local과 test 환경에서는 저장되지만 운영 환경에서는 실패하는 문제가 발생했습니다.
--
-- ADD CONSTRAINT는 IF NOT EXISTS를 지원하지 않으므로 pg_constraint에서 conname과 conrelid를 함께
-- 확인합니다. 운영 환경에서 기존 제약을 건너뛸 수 있도록 제약 이름은 레거시가 자동 생성한 이름과
-- 동일하게 지정합니다. 제약 이름은 테이블별로만 유일하므로 conrelid도 함께 확인합니다
-- (V20260823110000과 동일한 방식).
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conname = 'source_refs_workspace_id_fkey'
           AND conrelid = 'source_refs'::regclass
    ) THEN
        ALTER TABLE source_refs
            ADD CONSTRAINT source_refs_workspace_id_fkey
            FOREIGN KEY (workspace_id) REFERENCES workspaces(id) ON DELETE CASCADE;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conname = 'source_refs_verified_by_user_id_fkey'
           AND conrelid = 'source_refs'::regclass
    ) THEN
        ALTER TABLE source_refs
            ADD CONSTRAINT source_refs_verified_by_user_id_fkey
            FOREIGN KEY (verified_by_user_id) REFERENCES users(id);
    END IF;
END $$;
