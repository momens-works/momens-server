package works.momens.server.source.ref;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import works.momens.server.source.SourceRefWriter;

/**
 * 사용자가 입력한 링크를 source-ref로 저장하는 구현입니다.
 *
 * <p>{@code source_object_type}은 소문자 {@code link}, {@code source_object_id}는 주소, {@code
 * visibility}는 {@code WORKSPACE}, {@code metadata}는 빈 객체입니다. 주소와 제목의 앞뒤 공백은 제거하고, 제목이 비어 있으면 {@code
 * null}로 저장합니다.
 *
 * <p>{@code KNOWN_SOURCE_TYPES}는 웹이 source 종류를 식별해 표시할 때 사용하는 이름의 목록이며, 목록에 없는 값은 모두 {@code LINK}로
 * 변환합니다. {@code OAuthProviderRegistry}에 정의된 provider 네 개와 일부 값이 겹치지만 두 목록은 통합하지 않습니다. 한쪽은 승인 절차를 거쳐
 * 연결할 수 있는 provider 목록이고 다른 한쪽은 화면에 표시할 이름표 목록이므로, 어느 한쪽에 값이 추가되어도 다른 쪽에 같은 값을 추가할 필요는 없습니다.
 */
@Service
@RequiredArgsConstructor
class SourceRefWriterImpl implements SourceRefWriter {

  private static final Set<String> KNOWN_SOURCE_TYPES =
      Set.of("GITHUB", "FIGMA", "NOTION", "SLACK", "LINEAR", "JIRA", "CRM", "MEETING", "ANALYTICS");
  private static final String FALLBACK_SOURCE_TYPE = "LINK";
  private static final String MANUAL_LINK_OBJECT_TYPE = "link";
  private static final String DEFAULT_VISIBILITY = "WORKSPACE";

  private final SourceRefRepository sourceRefRepository;

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public UUID createManualLink(NewManualLink manualLink) {
    String sourceUrl = manualLink.sourceUrl().trim();
    String title = manualLink.title() == null ? null : manualLink.title().trim();
    SourceRef sourceRef =
        SourceRef.builder()
            .workspaceId(manualLink.workspaceId())
            .sourceType(normalizeSourceType(manualLink.sourceType()))
            .sourceObjectType(MANUAL_LINK_OBJECT_TYPE)
            .sourceObjectId(sourceUrl)
            .sourceUrl(sourceUrl)
            .title(title == null || title.isEmpty() ? null : title)
            .visibility(DEFAULT_VISIBILITY)
            .metadata(Map.of())
            .build();
    return sourceRefRepository.saveAndFlush(sourceRef).getId();
  }

  private static String normalizeSourceType(String rawSourceType) {
    if (rawSourceType == null) {
      return FALLBACK_SOURCE_TYPE;
    }
    String normalized = rawSourceType.trim().toUpperCase(Locale.ROOT);
    return KNOWN_SOURCE_TYPES.contains(normalized) ? normalized : FALLBACK_SOURCE_TYPE;
  }
}
