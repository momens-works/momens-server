package works.momens.server.minsu.draft.generation;

import works.momens.server.minsu.TaskDraft;

/**
 * 비동기 경로의 내부 실행 결과.
 *
 * <p>공개 {@code SignalTaskDraftGenerator}는 실패를 전파하지 않고 항상 유효한 {@link TaskDraft}를 돌려준다. 반환값만 보면 그
 * draft가 모델 결과인지, 재시도하면 될 일시 실패인지, 재시도해도 같은 확정 fallback인지 구분할 수 없어 <b>그 계약만으로는 재시도를 구현할 수 없다.</b>
 *
 * <p>그래서 공개 계약은 그대로 두고 내부 실행만 outcome을 함께 돌려준다. 동기 경로의 호출자({@code signal})에게는 지금 형태가 정확하고, 비동기
 * consumer는 {@code minsu} 내부에 있으므로 계약을 하나 더 두면 된다.
 *
 * @param draft 성공이면 모델 결과, 그 외에는 고정 fallback
 * @param outcome 재시도 판정과 종료 사유의 입력. 매핑은 원장이 소유한다
 */
public record AsyncGenerationResult(TaskDraft draft, GenerationOutcome outcome) {}
