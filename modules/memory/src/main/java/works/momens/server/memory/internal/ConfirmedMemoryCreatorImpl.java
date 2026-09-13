package works.momens.server.memory.internal;

import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import works.momens.server.memory.ConfirmedMemoryCreator;
import works.momens.server.memory.CreateConfirmedMemoryCommand;
import works.momens.server.workspace.label.LabelAllocator;

/**
 * 확정 메모리 한 건을 저장하는 구현입니다.
 *
 * <p>{@link ConfirmedMemory} 엔티티로 저장합니다. 같은 모듈의 {@link MemoryWriterImpl}이 네이티브 SQL을 사용하는 이유는 값이 비어
 * 있지 않은 컬럼만 덮어쓰는 부분 UPDATE, 후보 행의 값을 복사하는 INSERT, 행 잠금이 필요하기 때문입니다. 단순 생성만 수행하는 해당 경로에는 해당 조건이 적용되지
 * 않습니다. 엔티티에 컬럼 매핑과 타입 변환이 이미 정의되어 있으므로 같은 내용을 SQL에 중복해서 선언하지 않습니다.
 *
 * <p>{@code MEM} 라벨은 {@link LabelAllocator}를 사용해 직접 발급합니다. 레거시는 라벨 없이 INSERT하면 트리거가 값을 채우지만 신규 서버의
 * local 스키마에는 해당 트리거가 없습니다.
 *
 * <p>{@code confirmed_at}과 {@code valid_from}에는 같은 시각을 저장합니다. 레거시 {@code BuildConfirmedMemory}도 두
 * 컬럼을 생성 시각으로 채웁니다. {@code created_at}은 JPA Auditing이 저장 시점에 별도로 채우므로 두 값과 밀리초 단위로 차이가 날 수 있습니다.
 * 응답에 포함되지 않는 값이므로 별도로 일치시키지 않습니다.
 */
@Service
@RequiredArgsConstructor
class ConfirmedMemoryCreatorImpl implements ConfirmedMemoryCreator {

  private final ConfirmedMemoryRepository confirmedMemoryRepository;
  private final LabelAllocator labelAllocator;

  @Override
  @Transactional
  public UUID create(CreateConfirmedMemoryCommand command) {
    Instant confirmedAt = Instant.now();
    ConfirmedMemory memory =
        confirmedMemoryRepository.save(
            ConfirmedMemory.builder()
                .workspaceId(command.workspaceId())
                .label(labelAllocator.allocateMemoryLabel(command.workspaceId()))
                .memoryType(command.memoryType())
                .title(command.title())
                .body(command.body())
                .relatedEntityIds(command.relatedEntityIds())
                .confirmedByUserId(command.confirmedByUserId())
                .confirmedAt(confirmedAt)
                .validFrom(confirmedAt)
                .metadata(command.metadata())
                .build());
    return memory.getId();
  }
}
