package works.momens.server.project.milestone;

import java.util.UUID;

/** milestone aggregate의 MCP용 쓰기 계약입니다. 인증과 입력 표면별 정규화는 호출하는 쪽이 담당합니다. */
public interface MilestoneWriter {

  MilestoneDetail create(CreateMilestoneCommand command);

  MilestoneDetail update(UpdateMilestoneCommand command);

  void delete(UUID milestoneId);
}
