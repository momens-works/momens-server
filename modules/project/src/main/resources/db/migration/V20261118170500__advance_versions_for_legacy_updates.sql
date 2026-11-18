-- 레거시 API는 tasks와 milestones를 직접 수정하며 version을 쓰지 않는다. 기존 클라이언트가
-- writer 전환 뒤 오래된 version으로 저장해 레거시 변경을 덮어쓰지 않도록 한다.
-- 레거시 writer를 영구 종료하면 새 migration으로 두 트리거와 함수를 제거한다.
CREATE FUNCTION advance_unversioned_update() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog
AS $$
BEGIN
    NEW.version := OLD.version + 1;
    RETURN NEW;
END;
$$;

CREATE TRIGGER advance_unversioned_task_update
BEFORE UPDATE OF milestone_id, title, description, status, priority, assignee_id, due_date, deleted_at
ON tasks
FOR EACH ROW
WHEN (
    NEW.version = OLD.version
    AND ROW(NEW.milestone_id, NEW.title, NEW.description, NEW.status, NEW.priority,
            NEW.assignee_id, NEW.due_date, NEW.deleted_at)
        IS DISTINCT FROM
        ROW(OLD.milestone_id, OLD.title, OLD.description, OLD.status, OLD.priority,
            OLD.assignee_id, OLD.due_date, OLD.deleted_at)
)
EXECUTE FUNCTION advance_unversioned_update();

CREATE TRIGGER advance_unversioned_milestone_update
BEFORE UPDATE OF name, description, target_date, status, health_status, progress, summary,
    last_context_at, deleted_at
ON milestones
FOR EACH ROW
WHEN (
    NEW.version = OLD.version
    AND ROW(NEW.name, NEW.description, NEW.target_date, NEW.status, NEW.health_status,
            NEW.progress, NEW.summary, NEW.last_context_at, NEW.deleted_at)
        IS DISTINCT FROM
        ROW(OLD.name, OLD.description, OLD.target_date, OLD.status, OLD.health_status,
            OLD.progress, OLD.summary, OLD.last_context_at, OLD.deleted_at)
)
EXECUTE FUNCTION advance_unversioned_update();
