/**
 * 태스크 보드·상세·수정 화면 하위 도메인.
 *
 * <p>mobile 모듈 안에서 태스크 보드 조회·생성({@code /api/mobile/projects/{projectId}/tasks})과 태스크 상세·수정·완료기준
 * 토글({@code /api/mobile/tasks/{taskId}}, {@code .../checklist-items/{itemId}})이 담당하는 경계를 Spring
 * Modulith nested 모듈로 명시합니다(MOM-0799). {@code project} 모듈이 Task aggregate를 소유하는 nested {@code task}
 * 모듈과 이름이 겹치지 않도록, 여기는 화면 이름을 따 {@code board}로 붙였습니다. project(태스크 도메인)·workspace(멤버십)·
 * user(프로필)·context(관련자료 연결)·source(원본)의 public API만 조합하고 도메인 정책을 소유하지 않습니다. priority 저장값 해석은
 * {@code brief}와 공유하는 규칙이라 모듈 root의 {@link works.momens.server.mobile.MobileTaskPriority}를 그대로
 * 참조합니다.
 */
@org.springframework.modulith.ApplicationModule
package works.momens.server.mobile.board;
