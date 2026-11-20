package works.momens.server.project.decision.internal;

import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import works.momens.server.common.api.BusinessException;
import works.momens.server.outbox.OutboxAppender;
import works.momens.server.project.core.ProjectErrorCode;
import works.momens.server.project.core.ProjectReader;
import works.momens.server.project.decision.CreateDecisionCommand;
import works.momens.server.project.decision.DecisionDetail;
import works.momens.server.project.decision.DecisionWriter;

@Service
@RequiredArgsConstructor
class DecisionWriterImpl implements DecisionWriter {
  private final ProjectReader projectReader;
  private final DecisionRepository decisionRepository;
  private final OutboxAppender outboxAppender;

  @Override
  @Transactional
  public DecisionDetail create(CreateDecisionCommand command) {
    // 생성 트랜잭션 동안 삭제 UPDATE와 직렬화하고, 다른 workspace에 쓰는 것도 막습니다.
    UUID workspaceId =
        projectReader
            .lockWorkspaceIdOf(command.projectId())
            .filter(command.workspaceId()::equals)
            .orElseThrow(() -> new BusinessException(ProjectErrorCode.PROJECT_NOT_FOUND));
    Decision decision = decisionRepository.saveAndFlush(new Decision(command));
    outboxAppender.append(
        workspaceId, "decision", decision.getId().toString(), "decision.created", Map.of());
    return decision.toDetail();
  }
}
