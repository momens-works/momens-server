package works.momens.server.project.decision;

import java.util.UUID;

/** 호출자가 member 권한을 확인한 workspace와 인증 사용자 ID를 전달합니다. */
public record CreateDecisionCommand(
    UUID projectId,
    UUID workspaceId,
    UUID decisionMaker,
    String title,
    String context,
    String alternatives,
    String rationale,
    DecisionReversibility reversibility) {}
