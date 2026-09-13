package works.momens.server.workspace.core;

import java.time.Instant;
import java.util.UUID;

/**
 * workspace 한 건의 조회 결과.
 *
 * <p>웹 워크스페이스 조회 API(MOM-0851)가 필요로 하는 read 모델입니다. 레거시 {@code domain.Workspace}와 필드가 1:1로
 * 대응합니다(docs/design/legacy-product-api-migration/slice-workspace-read.md 2절). {@code description}은
 * 미설정이면 {@code null}이며, 응답에서 생략할지는 호출하는 쪽(DTO)이 결정합니다.
 */
public record WorkspaceDetail(
    UUID id, String name, String slug, String description, Instant createdAt, Instant updatedAt) {}
