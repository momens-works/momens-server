package works.momens.server.project.task;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 보드 조회용 태스크 한 건.
 *
 * <p>{@code status}와 {@code priority}는 저장된 원본 문자열입니다. 보드 그룹(상태 5종) 매핑과 모바일 priority 매핑(urgent를
 * high로 반환 등)은 조회하는 쪽(mobile)이 정합니다. {@code createdAt}은 브리프의 현재 우선순위처럼 조회하는 쪽이 생성 시각 기준으로 다시 정렬할 때
 * 사용합니다(MOM-67).
 *
 * <p>{@code assigneeId}와 {@code dueDate}는 저장된 값을 그대로 반환하며, 지정되지 않은 경우 {@code null}입니다. 담당자 이름은
 * mobile 모듈에서 user 모듈의 공개 계약을 통해 조회합니다(MOM-1073).
 */
public record BoardTask(
    UUID id,
    String title,
    String status,
    String priority,
    String role,
    UUID assigneeId,
    LocalDate dueDate,
    Instant createdAt) {}
