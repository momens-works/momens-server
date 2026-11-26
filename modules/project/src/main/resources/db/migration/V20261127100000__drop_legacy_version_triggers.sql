-- 레거시 writer가 종료되어 tasks와 milestones는 version을 직접 올리는 JPA 경로로만 수정된다.
DROP TRIGGER advance_unversioned_task_update ON tasks;
DROP TRIGGER advance_unversioned_milestone_update ON milestones;
DROP FUNCTION advance_unversioned_update();
