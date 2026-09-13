-- entity_relations.workspace_id의 외래 키를 local과 test 환경에도 추가합니다(MOM-0918).
--
-- 운영 환경의 entity_relations에는 레거시 000002_retrieval_projection.sql에서 인라인 REFERENCES로
-- 생성한 외래 키가 이미 있습니다. 이 서버가 entity_relations에 데이터를 저장하기 시작하면서
-- (MOM-0869), 존재하지 않는 워크스페이스를 참조하는 행이 local과 test 환경에서는 저장되지만 운영
-- 환경에서는 실패하는 문제가 발생했습니다.
--
-- ADD CONSTRAINT는 IF NOT EXISTS를 지원하지 않으므로 pg_constraint에서 conname과 conrelid를 함께
-- 확인합니다(V20260823110000과 동일한 방식). 따라서 dev처럼 레거시 DDL로 테이블을 생성해 외래 키가
-- 이미 있는 환경에서는 제약 추가를 건너뜁니다.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conname = 'entity_relations_workspace_id_fkey'
           AND conrelid = 'entity_relations'::regclass
    ) THEN
        ALTER TABLE entity_relations
            ADD CONSTRAINT entity_relations_workspace_id_fkey
            FOREIGN KEY (workspace_id) REFERENCES workspaces(id) ON DELETE CASCADE;
    END IF;
END $$;
