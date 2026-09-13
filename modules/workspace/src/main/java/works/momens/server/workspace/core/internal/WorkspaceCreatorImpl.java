package works.momens.server.workspace.core.internal;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import works.momens.server.common.api.BusinessException;
import works.momens.server.common.api.FieldValidationException;
import works.momens.server.workspace.WorkspaceErrorCode;
import works.momens.server.workspace.core.CreateWorkspaceCommand;
import works.momens.server.workspace.core.WorkspaceCreator;
import works.momens.server.workspace.core.WorkspaceDetail;
import works.momens.server.workspace.membership.AddMembershipCommand;
import works.momens.server.workspace.membership.WorkspaceMembershipWriter;
import works.momens.server.workspace.membership.WorkspaceRole;

/**
 * 레거시 워크스페이스 생성 서비스의 검증과 저장 동작을 이관한 구현입니다.
 *
 * <p>slug 검증에는 조회(H021)와 수정(H024)에서 사용하는 {@link WorkspaceSlugPolicy}를 적용합니다. 생성과 수정의 차이는 값이 없을 때의
 * 처리 방식입니다. 수정은 기존 slug를 유지하고, 생성은 워크스페이스 이름을 기준으로 slug를 만든 뒤 이미 사용 중이면 다음 후보를 찾습니다.
 *
 * <p>워크스페이스를 먼저 flush합니다. {@code workspace_members.workspace_id}가 {@code workspaces}를 참조하므로 워크스페이스
 * 행을 DB에 반영한 뒤 멤버십을 저장해야 합니다.
 *
 * <p>중복 확인과 INSERT 사이에 다른 요청이 같은 slug를 저장하면 {@code workspaces.slug}의 UNIQUE 제약이 두 번째 요청을 거부합니다.
 * 레거시도 같은 순서로 처리하므로 동일한 동시성 동작을 유지합니다.
 */
@Service
@RequiredArgsConstructor
class WorkspaceCreatorImpl implements WorkspaceCreator {

  private static final String FIELD_NAME = "name";

  private final WorkspaceRepository workspaceRepository;
  private final WorkspaceMembershipWriter workspaceMembershipWriter;

  @Override
  @Transactional
  public WorkspaceDetail create(CreateWorkspaceCommand command) {
    if (command.name() == null || command.name().isEmpty()) {
      throw FieldValidationException.forField(FIELD_NAME);
    }
    Workspace workspace =
        workspaceRepository.saveAndFlush(
            Workspace.builder()
                .name(command.name())
                .slug(resolveSlug(command.name(), command.slug()))
                .description(emptyToNull(command.description()))
                .build());
    workspaceMembershipWriter.addIfAbsent(
        new AddMembershipCommand(workspace.getId(), command.requesterId(), WorkspaceRole.OWNER));
    return WorkspaceDetailMapper.toDetail(workspace);
  }

  /**
   * 저장할 slug를 결정합니다.
   *
   * <p>값이 없으면 워크스페이스 이름으로 생성한 slug를 기준으로 사용 가능한 후보를 찾습니다. 값이 있으면 형식, 예약어, 중복 여부 순으로 검증하고 위반한 규칙에 맞는
   * 에러를 던집니다. 검증 순서는 레거시와 같습니다.
   */
  private String resolveSlug(String name, String rawSlug) {
    String slug = WorkspaceSlugPolicy.normalize(rawSlug);
    if (slug.isEmpty()) {
      return WorkspaceSlugPolicy.nextAvailable(
          WorkspaceSlugPolicy.slugify(name), workspaceRepository::existsBySlug);
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

  private static String emptyToNull(String value) {
    return value == null || value.isEmpty() ? null : value;
  }
}
