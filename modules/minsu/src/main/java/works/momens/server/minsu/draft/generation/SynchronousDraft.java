package works.momens.server.minsu.draft.generation;

import works.momens.server.minsu.PreparedTaskDraft;
import works.momens.server.minsu.TaskDraft;

/**
 * 적재할 것이 없는 준비 결과.
 *
 * <p>비동기가 비활성이면 이 draft가 곧 최종값이므로 뒤이은 {@code enroll}은 아무것도 하지 않는다. 원장 행이 없는 task는 조회 시 {@code
 * ready}로 판정된다.
 */
record SynchronousDraft(TaskDraft draft) implements PreparedTaskDraft {}
