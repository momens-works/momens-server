-- 레거시 writer가 종료되어 V20261118170500의 트리거가 대비하던 레거시 SQL 수정이 더 이상 없다.
-- 트리거가 없으면 version은 JPA writer만 올린다. FK의 ON DELETE SET NULL처럼 SQL로 직접 바뀌는
-- 변경은 version을 올리지 않는다.
DROP TRIGGER advance_unversioned_task_update ON tasks;
DROP TRIGGER advance_unversioned_milestone_update ON milestones;
DROP FUNCTION advance_unversioned_update();
