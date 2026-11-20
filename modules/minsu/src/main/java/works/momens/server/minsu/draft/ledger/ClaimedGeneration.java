package works.momens.server.minsu.draft.ledger;

import java.util.UUID;
import works.momens.server.minsu.SignalTaskDraftInput;

/**
 * claim을 보유한 한 건의 생성 작업.
 *
 * <p>claim 트랜잭션과 실행은 분리된다. 실행은 provider 호출이라 길고, 그동안 DB connection을 잡고 있으면 커넥션 풀이 먼저 마른다({@code
 * push_deliveries}와 같은 이유). 그래서 claim 트랜잭션은 소유권만 커밋하고 실행에 필요한 값을 이 record로 넘긴다.
 *
 * <p>{@code input}은 convert 시점 snapshot이다. 실행 시점에 Signal을 다시 읽지 않으므로 사용자가 convert를 누른 시점의 근거가 그대로
 * 쓰인다.
 *
 * @param id 원장 행 식별자. 결과 기록이 이 행을 다시 잠근다
 * @param claimToken 이 실행의 소유권 증표. 결과 기록은 이 값이 원장과 일치할 때만 반영된다
 */
record ClaimedGeneration(UUID id, UUID taskId, UUID claimToken, SignalTaskDraftInput input) {}
