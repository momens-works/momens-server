package works.momens.server.project.decision;

/** 인가는 호출자가 담당하고, 저장과 decision.created 발행은 하나의 트랜잭션으로 처리합니다. */
public interface DecisionWriter {
  DecisionDetail create(CreateDecisionCommand command);
}
