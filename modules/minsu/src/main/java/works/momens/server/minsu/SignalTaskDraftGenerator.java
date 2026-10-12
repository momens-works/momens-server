package works.momens.server.minsu;

import java.util.UUID;

/**
 * Signal 근거로 검증된 task draft를 만드는 Minsu 공개 유스케이스.
 *
 * <p>두 진입점의 트랜잭션 경계가 다르다. {@link #prepare}는 동기 모드에서 LLM을 호출하므로 느린 네트워크가 DB connection을 점유하지 않도록 쓰기
 * 트랜잭션 <b>밖</b>에서 부르고, {@link #enroll}은 {@code tasks}·{@code signal_actions}와 원자적이어야 하므로 쓰기 트랜잭션
 * <b>안</b>에서 부른다. 하나로 합치면 둘 중 하나를 포기하게 된다.
 *
 * <p>비동기 활성 여부는 Minsu가 소유하고 호출자는 판정하지 않는다. 호출자는 {@link #prepare} 결과를 그대로 {@link #enroll}에 넘기기만 하며,
 * {@link PreparedTaskDraft}가 활성 여부를 구현 타입에만 담으므로 그렇게 하는 것 외에 선택지가 없다. 한 요청 안에서 판정이 두 번 갈리는 경로가 계약상
 * 존재하지 않는다. 판정이 갈리면 LLM을 한 번도 부르지 않은 채 {@code ready}가 되는 조용한 품질 손실이 생긴다.
 */
public interface SignalTaskDraftGenerator {

  /**
   * task에 쓸 draft를 확보한다. 비동기가 활성이면 LLM을 호출하지 않고 고정 fallback을 돌려주며, 실제 생성은 {@link #enroll}이 적재한 원장을
   * scheduler가 처리한다.
   */
  PreparedTaskDraft prepare(SignalTaskDraftInput input);

  /**
   * 준비 결과에 비동기 의사가 담겨 있으면 생성 원장을 {@code pending}으로 적재한다. 비동기가 비활성이면 아무것도 하지 않는다.
   *
   * <p>적재했으면 {@link DraftStatus#GENERATING}, 하지 않았으면 {@link DraftStatus#READY}를 돌려준다. 신규 convert
   * 응답은 이 값을 그대로 쓰고 원장을 <b>다시 읽지 않는다</b>. 재조회하면 응답에 담는 title은 방금 쓴 fallback인데 그 사이 scheduler가 끝나
   * {@code ready}가 나올 수 있고, 그 조합은 앱이 재조회를 멈춘 채 fallback title에 영구히 갇히는 경우다.
   *
   * <p>활성 여부를 미리 묻는 것이 아니라 <b>수행한 일을 보고하는</b> 값이므로, 판정을 {@link #prepare} 한 번으로 묶은 계약은 그대로다.
   *
   * <p>fail-closed다. 적재에 실패하면 예외를 던져 호출자 트랜잭션 전체를 롤백시킨다. 원장이 없는데 응답만 성공하면 그 task는 조용히 풍부화되지 않고 아무도
   * 무엇이 빠졌는지 알 수 없다.
   *
   * <p>반영 CAS의 기준값(baseline)은 {@code prepared.draft()}를 그대로 복사한다. 호출자가 그 draft를 {@code tasks}에 썼다는
   * 것이 이 계약의 전제이고, 다른 값을 썼다면 반영 시점에 사용자 편집으로 오분류된다.
   *
   * <p><b>호출자 트랜잭션 안에서만 부를 수 있다</b>({@code MANDATORY}). 트랜잭션 밖에서 부르면 원장 insert가 자기 트랜잭션을 열어 커밋하므로
   * {@code tasks}가 롤백돼도 원장만 남는다. 그 행은 적재에 성공했으므로 실패율 지표에도 잡히지 않는다. 계약을 문서가 아니라 전파 속성으로 강제한다.
   *
   * @throws TaskDraftEnrollmentException 원장 적재에 실패한 경우
   * @throws org.springframework.transaction.IllegalTransactionStateException 활성 트랜잭션 없이 호출한 경우
   */
  DraftStatus enroll(PreparedTaskDraft prepared, UUID taskId, UUID workspaceId);
}
