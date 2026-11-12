package works.momens.server.context.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import works.momens.server.context.EntityRelationReader;
import works.momens.server.context.TaskContextLinks;

@Service
@RequiredArgsConstructor
class EntityRelationReaderImpl implements EntityRelationReader {

  private final EntityRelationRepository entityRelationRepository;

  @Override
  @Transactional(readOnly = true)
  public Map<UUID, List<UUID>> findLinkedSourceRefIds(UUID workspaceId, Collection<UUID> taskIds) {
    if (taskIds.isEmpty()) {
      return Map.of();
    }
    // 쿼리가 표시 순서로 정렬해 주므로, 순서를 유지하는 자료구조로 묶어 태스크별 목록에 그대로 담는다.
    return entityRelationRepository.findLinkedSourceRefIdsByTaskIds(workspaceId, taskIds).stream()
        .collect(
            Collectors.groupingBy(
                row -> (UUID) row[0],
                LinkedHashMap::new,
                Collectors.mapping(row -> (UUID) row[1], Collectors.toList())));
  }

  @Override
  @Transactional(readOnly = true)
  public Map<UUID, TaskContextLinks> findTaskContextLinks(
      UUID workspaceId, Collection<UUID> taskIds) {
    if (taskIds.isEmpty()) {
      return Map.of();
    }
    Map<UUID, List<UUID>> memoryIds = new LinkedHashMap<>();
    Map<UUID, List<UUID>> sourceRefIds = new LinkedHashMap<>();
    entityRelationRepository
        .findContextLinks(workspaceId, taskIds)
        .forEach(row -> {
          UUID taskId = (UUID) row[0];
          if ("MEMORY".equals(row[1])) {
            memoryIds.computeIfAbsent(taskId, ignored -> new ArrayList<>()).add((UUID) row[2]);
          } else if ("SOURCE_OBJECT".equals(row[1])) {
            sourceRefIds.computeIfAbsent(taskId, ignored -> new ArrayList<>()).add((UUID) row[2]);
          }
        });
    Map<UUID, TaskContextLinks> links = new LinkedHashMap<>();
    memoryIds.forEach((taskId, ids) ->
        links.put(taskId, new TaskContextLinks(ids, sourceRefIds.getOrDefault(taskId, List.of()))));
    sourceRefIds.forEach(
        (taskId, ids) -> links.putIfAbsent(taskId, new TaskContextLinks(List.of(), ids)));
    return links;
  }

  @Override
  @Transactional(readOnly = true)
  public TaskContextLinks findTaskContextLinks(UUID workspaceId, UUID taskId) {
    List<UUID> memoryIds = new ArrayList<>();
    List<UUID> sourceRefIds = new ArrayList<>();
    entityRelationRepository
        .findContextLinks(workspaceId, List.of(taskId))
        .forEach(row -> {
          if ("MEMORY".equals(row[1])) {
            memoryIds.add((UUID) row[2]);
          } else if ("SOURCE_OBJECT".equals(row[1])) {
            sourceRefIds.add((UUID) row[2]);
          }
        });
    return new TaskContextLinks(memoryIds, sourceRefIds);
  }
}
