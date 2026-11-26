package works.momens.server.workspace.label.internal;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import works.momens.server.workspace.label.LabelAllocator;

@Service
@RequiredArgsConstructor
class LabelAllocatorImpl implements LabelAllocator {

  private static final String LABEL_FORMAT = "%s-%04d";

  private final WorkspaceLabelSequenceRepository sequenceRepository;

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public String allocateMomLabel(UUID workspaceId) {
    return allocate(workspaceId, LabelPrefix.MOM);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public String allocateMemoryLabel(UUID workspaceId) {
    return allocate(workspaceId, LabelPrefix.MEM);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public String allocateProjectLabel(UUID workspaceId) {
    return allocate(workspaceId, LabelPrefix.PRJ);
  }

  /**
   * 이 메서드는 호출한 쪽의 트랜잭션 안에서만 동작합니다({@code MANDATORY}). 라벨 발급은 태스크나 프로젝트와 같은 대상을 생성하는 작업의 일부이므로, 대상
   * 생성이 중간에 실패해 롤백되면 발급한 번호도 함께 롤백되어야 합니다. 호출한 쪽과 동일한 트랜잭션에 참여하므로 이 동작을 보장할 수 있습니다. 트랜잭션 없이 단독으로
   * 호출하면 예외를 발생시켜 잘못된 사용을 조기에 감지합니다. 같은 번호가 중복으로 발급되지 않도록 보장하는 책임은 repository의 단일 {@code ON
   * CONFLICT} 구문에 있습니다.
   *
   * <p>번호가 네 자리를 초과하더라도 자르지 않고 그대로 사용합니다. 잘린 값은 이미 발급된 라벨과 중복됩니다.
   */
  private String allocate(UUID workspaceId, LabelPrefix prefix) {
    long allocated = sequenceRepository.allocate(workspaceId, prefix.value());
    return String.format(LABEL_FORMAT, prefix.value(), allocated);
  }
}
