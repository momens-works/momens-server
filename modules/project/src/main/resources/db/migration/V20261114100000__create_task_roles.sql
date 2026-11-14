-- 태스크에 지정된 여러 role을 저장하는 테이블입니다. 행은 추가하거나 삭제하는 방식으로 관리하므로
-- `updated_at` 컬럼은 두지 않으며, `created_at`에는 role이 지정된 시각을 기록합니다.
--
-- 기존 `tasks.role` 값은 이 테이블로 이전합니다. 배포 중이거나 롤백 시 이전 버전의 서버가
-- `tasks.role` 컬럼을 참조할 수 있으므로, 이번 마이그레이션에서는 해당 컬럼을 제거하지 않습니다.
CREATE TABLE task_roles (
    task_id UUID NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    role TEXT NOT NULL CHECK (role IN ('pm', 'design', 'backend', 'frontend')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (task_id, role)
);

INSERT INTO task_roles (task_id, role)
SELECT id, role
FROM tasks
WHERE role IS NOT NULL;
