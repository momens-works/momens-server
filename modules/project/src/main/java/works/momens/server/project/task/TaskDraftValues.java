package works.momens.server.project.task;

/**
 * draft 반영이 비교하고 기록하는 세 필드.
 *
 * <p>baseline과 새 draft가 같은 모양이라 한 타입으로 둡니다. 명령이 문자열 여섯 개를 나란히 받으면 어느 셋이 baseline인지 호출부에서 보이지 않고,
 * 순서를 틀려도 컴파일됩니다.
 *
 * <p>같은 타입을 쓰는 데는 더 중요한 이유가 있습니다. <b>이 record는 비교 집합이자 기록 집합이고 둘은 갈라지면 안 됩니다.</b> 비교하지 않는 필드를 기록
 * 쪽에만 더하면 그 필드는 baseline 검사를 거치지 않은 채 덮어써지고, 전부-아니면-전무(설계 8.1절)가 그 필드에서만 조용히 깨집니다. 반영 대상을 넓히려면 비교
 * 대상도 함께 넓혀야 합니다.
 *
 * <p>`role`과 `priority`는 저장할 값을 도메인 enum으로 받습니다. minsu 모듈에서 사용하는 값은 이 record를 생성하는 쪽에서 도메인 enum으로
 * 변환합니다.
 */
public record TaskDraftValues(String title, TaskRole role, TaskPriority priority) {}
