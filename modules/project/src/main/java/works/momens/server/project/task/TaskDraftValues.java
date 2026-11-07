package works.momens.server.project.task;

/**
 * baseline 비교와 실제 반영이 공유하는 필드 집합입니다. 반영 필드를 추가하면 비교 필드도 함께 추가해야 사용자 값을 검증 없이 덮어쓰지 않습니다.
 *
 * <p>이 record는 저장된 값을 전달하는 용도이므로 제목 길이를 검증하지 않습니다. 기존에 저장된 긴 제목을 비교 대상으로 읽더라도 반영 과정에서 오류가 발생하지 않도록
 * 하기 위함 입니다.
 */
public record TaskDraftValues(String title, TaskRole role, TaskPriority priority) {}
