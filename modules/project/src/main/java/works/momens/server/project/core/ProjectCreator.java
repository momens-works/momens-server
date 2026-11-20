package works.momens.server.project.core;

/**
 * {@code project} 모듈의 프로젝트 생성 public API입니다.
 *
 * <p>레거시 {@code POST /workspaces/:id/projects}의 저장 동작을 그대로 이관합니다. 프로젝트 라벨 발급과 {@code
 * project_owners} 저장은 호출하는 쪽에서 시작한 트랜잭션 안에서 함께 처리합니다.
 *
 * <p>요청자의 권한은 확인하지 않습니다. endpoint를 호출할 수 있는 역할은 호출하는 쪽에서 결정하므로 {@code :web} 모듈이 권한을 확인하고, 확인을 마친
 * {@code workspaceId}를 command에 담아 전달합니다. 이 모듈에서는 저장할 값의 유효성만 검증합니다. {@code TaskWriter}와 같은 방식입니다.
 */
public interface ProjectCreator {

  ProjectDetail create(CreateProjectCommand command);
}
