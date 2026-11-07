package works.momens.server.project.task;

/** baseline 비교와 실제 반영이 공유하는 필드 집합입니다. 반영 필드를 추가하면 비교 필드도 함께 추가해야 사용자 값을 검증 없이 덮어쓰지 않습니다. */
public record TaskDraftValues(String title, TaskRole role, TaskPriority priority) {}
