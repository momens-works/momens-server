package works.momens.server.signal;

import java.util.UUID;

/**
 * Signal 목록 카드 한 건. impact·minsuSuggestion은 worker/Minsu가 아직 생산하지 않았으면 null일 수 있다.
 *
 * <p>목록·브리프 모두 프로젝트로 이미 스코프되어 조회하므로 project_id는 응답에 담지 않는다.
 */
public record SignalSummary(
    UUID id, String type, String title, String impact, String minsuSuggestion) {}
