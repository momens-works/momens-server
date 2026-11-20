package works.momens.server.minsu.draft.ledger;

import works.momens.server.minsu.PreparedTaskDraft;
import works.momens.server.minsu.SignalTaskDraftInput;
import works.momens.server.minsu.TaskDraft;

/**
 * 적재를 기다리는 준비 결과.
 *
 * <p>draft 확보 시점에 만들어져 호출자를 거쳐 적재 시점으로 돌아온다. draft와 함께 담는 것은 convert 시점 입력 snapshot뿐이고, 실행 시점에
 * Signal을 다시 읽지 않으므로 사용자가 convert를 누른 시점의 근거가 그대로 보존된다.
 *
 * <p>이 타입은 패키지 밖으로 나가지 않는다. 호출자는 {@link PreparedTaskDraft}로만 들고 다니므로 이 준비 결과가 적재 대상인지 알 수 없다.
 */
record LedgerBoundDraft(TaskDraft draft, SignalTaskDraftInput snapshot)
    implements PreparedTaskDraft {}
