package works.momens.server.workspace.core.internal;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import works.momens.server.common.api.BusinessException;
import works.momens.server.workspace.WorkspaceErrorCode;
import works.momens.server.workspace.core.UpdateWorkspaceCommand;
import works.momens.server.workspace.core.WorkspaceDetail;
import works.momens.server.workspace.core.WorkspaceEditor;

@Service
@RequiredArgsConstructor
class WorkspaceEditorImpl implements WorkspaceEditor {

  private final WorkspaceRepository workspaceRepository;

  @Override
  @Transactional
  public WorkspaceDetail update(UpdateWorkspaceCommand command) {
    Workspace workspace =
        workspaceRepository
            .findById(command.workspaceId())
            .orElseThrow(
                () ->
                    new BusinessException(
                        WorkspaceErrorCode.WORKSPACE_NOT_FOUND,
                        Map.of("workspace_id", command.workspaceId().toString())));
    workspace.update(
        keepWhenEmpty(command.name()),
        keepWhenEmpty(command.description()),
        resolveSlug(workspace.getSlug(), command.slug()));
    // updated_at을 채우는 @LastModifiedDate는 flush 시점에 적용됩니다. 여기서 flush하지 않으면 아래
    // 매핑이 변경 전 updated_at을 읽어, DB에는 갱신된 값이 들어가는데 응답만 한 박자 밀립니다(MOM-0891).
    // 변경이 없으면 UPDATE 자체가 나가지 않으므로 no-op 요청의 기존 동작은 그대로입니다.
    workspaceRepository.flush();
    return WorkspaceDetailMapper.toDetail(workspace);
  }

  /** 레거시와 동일하게 빈 문자열을 기존 값 유지로 처리하기 위해 null로 변환합니다. */
  private static String keepWhenEmpty(String value) {
    return value == null || value.isEmpty() ? null : value;
  }

  /**
   * 최종적으로 반영할 slug를 결정합니다.
   *
   * <p>값이 없거나 현재 slug와 같으면 별도의 검증 없이 기존 값을 유지합니다. 레거시에서도 slug가 변경되는 경우에만 검증합니다. 값이 변경되면 형식, 예약어, 중복
   * 여부를 순서대로 확인하고, 위반한 규칙에 따라 서로 다른 에러를 던집니다.
   */
  private String resolveSlug(String currentSlug, String rawSlug) {
    String slug = WorkspaceSlugPolicy.normalize(rawSlug);
    if (slug.isEmpty() || slug.equals(currentSlug)) {
      return null;
    }
    if (!WorkspaceSlugPolicy.isValid(slug)) {
      throw new BusinessException(WorkspaceErrorCode.WORKSPACE_INVALID_SLUG, Map.of("slug", slug));
    }
    if (WorkspaceSlugPolicy.isReserved(slug)) {
      throw new BusinessException(WorkspaceErrorCode.WORKSPACE_RESERVED_SLUG, Map.of("slug", slug));
    }
    if (workspaceRepository.existsBySlug(slug)) {
      throw new BusinessException(
          WorkspaceErrorCode.WORKSPACE_SLUG_ALREADY_EXISTS, Map.of("slug", slug));
    }
    return slug;
  }
}
