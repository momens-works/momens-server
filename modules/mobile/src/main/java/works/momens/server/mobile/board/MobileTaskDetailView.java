package works.momens.server.mobile.board;

import works.momens.server.minsu.DraftStatus;

/**
 * 태스크 상세 조회 결과. 상세와 draft 생성 상태를 함께 담습니다.
 *
 * <p>{@link MobileTaskDetail}에 상태를 넣지 않은 이유는 완료기준 토글도 같은 타입을 쓰지만 그 응답은 완료기준만 노출하기 때문입니다. 넣으면 토글 경로가
 * 쓰지도 않을 원장 조회를 하거나 값을 비운 채 만들어야 합니다.
 *
 * <p>이 쌍은 <b>원장을 먼저, task를 나중에</b> 읽어 만든 것입니다. 역순으로 조립하면 반영이 끝났는데도 이전 title과 {@code ready}가 함께 나가
 * 앱이 재조회를 멈춘 채 그 title에 갇힙니다.
 */
public record MobileTaskDetailView(DraftStatus draftStatus, MobileTaskDetail detail) {}
