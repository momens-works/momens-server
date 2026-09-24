package works.momens.server.support.persistence;

import java.util.List;

/**
 * `ValueSetLiteralTest`에서 값 집합의 값을 둘 이상 포함해도 허용하는 파일의 목록입니다. 각 항목에는 파일과 값 집합, 그리고 enum을 참조할 수 없는
 * 이유를 적습니다.
 */
final class ValueSetLiteralAllowlist {

  static final List<AllowedLiteralFile> ALLOWED_LITERAL_FILES =
      List.of(
          new AllowedLiteralFile(
              "modules/minsu/src/main/java/works/momens/server/minsu/draft/ledger/TaskDraftGenerationRepository.java",
              "minsu_task_draft_generations.status",
              "부분 인덱스 조건과 일치해야 하는 SQL 리터럴입니다. 파라미터로 바꾸면 PostgreSQL이 부분 인덱스를 사용할 수 없습니다. 스냅샷 쿼리에서 인덱스를 사용하는지는 `MinsuLedgerMetricsIntegrationTest`에서 확인합니다."),
          new AllowedLiteralFile(
              "modules/minsu/src/main/resources/prompts/signal-task-draft-v1.txt",
              "tasks.role",
              "LLM에 role별 의미를 설명하는 지시문입니다. 값 목록이 `Role`과 일치하는지는 `SignalTaskDraftPromptTest`에서 확인합니다."),
          new AllowedLiteralFile(
              "modules/minsu/src/main/resources/prompts/signal-task-draft-v1.txt",
              "tasks.priority",
              "LLM에 priority별 의미를 설명하는 지시문입니다. 값 목록이 `Priority`와 일치하는지는 `SignalTaskDraftPromptTest`에서 확인합니다."),
          new AllowedLiteralFile(
              "modules/minsu/src/main/java/works/momens/server/minsu/draft/generation/GenerationOutcome.java",
              "minsu_task_draft_generations.completion_reason",
              "생성 시도의 결과를 관측 태그로 남기는 값이며, 저장 값 집합이 아닙니다. `completion_reason`에 저장할 값은 `CompletionReason`에서 정의합니다."));

  private ValueSetLiteralAllowlist() {}

  /** 값 집합의 값을 둘 이상 포함해도 허용하는 파일 하나를 나타냅니다. `column`은 해당 파일이 포함하는 값 집합에 속한 컬럼 하나입니다. */
  record AllowedLiteralFile(String path, String column, String reason) {

    AllowedLiteralFile {
      if (reason.isBlank()) {
        throw new IllegalArgumentException("허용 목록에 등록한 파일에는 근거를 작성해야 합니다.");
      }
    }
  }
}
