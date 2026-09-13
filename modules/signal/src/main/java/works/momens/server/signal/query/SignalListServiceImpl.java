package works.momens.server.signal.query;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import works.momens.server.common.api.BusinessException;
import works.momens.server.common.api.CommonErrorCode;
import works.momens.server.project.core.ProjectErrorCode;
import works.momens.server.project.core.ProjectReader;
import works.momens.server.signal.SignalListService;
import works.momens.server.signal.SignalSummary;
import works.momens.server.signal.SignalSummaryPage;
import works.momens.server.workspace.membership.WorkspaceMembershipReader;

/**
 * Signal 목록 조회 서비스. 프로젝트가 속한 workspace를 해석하고 요청자의 멤버십을 검사한 뒤 Signal을 반환한다. 시그널 탭은 미처리 Signal을 조회하고,
 * 브리프는 당일 생성 범위의 Signal을 조회한다.
 *
 * <p>Signal 처리 여부는 사용자별이 아니라 프로젝트 단위로 관리하므로 ({@code docs/design/mobile-mvp-server-requirements.md}의
 * 「Signal 요구사항」 참고), 요청자의 역할만 한 번 조회하면 충분합니다. 프로젝트 멤버 목록 조회와 달리 멤버 목록을 응답에 함께 포함하지 않습니다.
 *
 * <p>커서 페이지 조회는 전량 조회 뒤 메모리에서 자른다. 프로젝트당 시그널 규모가 크지 않아 지금은 이 방식으로 충분하고, AIP-158도 초기 규모가 작은 컬렉션에는 전량
 * 조회 뒤 자르는 구현을 인정한다. 목록이 커지면 응답 형식은 그대로 두고 조회 쿼리만 keyset 방식으로 바꾼다. 커서는 마지막으로 본 항목의 생성 시각과 id를
 * base64로 감싼 문자열이고, 정렬 동률을 id로 고정하므로 페이지 사이에 Signal이 바뀌어도 다음 페이지의 위치가 밀리지 않는다.
 */
@Service
@RequiredArgsConstructor
class SignalListServiceImpl implements SignalListService {

  /** 페이지 크기 상한. 넘겨도 에러 대신 상한으로 줄인다(AIP-158). */
  private static final int MAX_PAGE_SIZE = 50;

  private final SignalRepository signalRepository;
  private final ProjectReader projectReader;
  private final WorkspaceMembershipReader workspaceMembershipReader;

  @Override
  @Transactional(readOnly = true)
  public List<SignalSummary> listUnprocessed(UUID projectId, UUID userId) {
    requireMember(projectId, userId);
    return signalRepository.findUnprocessedByProjectId(projectId).stream()
        .map(SignalListServiceImpl::toSummary)
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public SignalSummaryPage listByCreatedRange(
      UUID projectId,
      UUID userId,
      Collection<String> types,
      Instant createdFrom,
      Instant createdToExclusive,
      String cursor,
      int limit) {
    requireMember(projectId, userId);
    // 잘못된 limit이나 커서는 조회 전에 거른다(불필요한 DB 조회 방지).
    int pageSize = resolvePageSize(limit);
    Cursor position = Cursor.decode(cursor);
    List<Signal> base =
        signalRepository.findByProjectIdAndCreatedRange(projectId, createdFrom, createdToExclusive);
    return page(base, types, position, pageSize);
  }

  @Override
  @Transactional(readOnly = true)
  public Map<String, Long> countByCreatedRange(
      UUID projectId, UUID userId, Instant createdFrom, Instant createdToExclusive) {
    requireMember(projectId, userId);
    return signalRepository
        .findByProjectIdAndCreatedRange(projectId, createdFrom, createdToExclusive)
        .stream()
        .collect(Collectors.groupingBy(Signal::getType, Collectors.counting()));
  }

  /** limit을 검사하고 상한으로 줄인다. 1 미만은 인터페이스 계약 위반이라 COMMON_VALIDATION_FAILED로 실패한다. */
  private static int resolvePageSize(int limit) {
    if (limit < 1) {
      throw new BusinessException(
          CommonErrorCode.COMMON_VALIDATION_FAILED, Map.of("limit", String.valueOf(limit)));
    }
    return Math.min(limit, MAX_PAGE_SIZE);
  }

  /** 전량 조회한 base를 type과 커서로 거른 뒤 한 페이지로 자른다. 페이지 조회의 공통 처리다. */
  private static SignalSummaryPage page(
      List<Signal> base, Collection<String> types, Cursor position, int pageSize) {
    boolean allTypes = types == null || types.isEmpty();
    List<Signal> filtered =
        base.stream()
            .filter(signal -> allTypes || types.contains(signal.getType()))
            .filter(signal -> position == null || position.isBefore(signal))
            .toList();
    List<Signal> paged = filtered.subList(0, Math.min(pageSize, filtered.size()));
    String nextCursor = filtered.size() > paged.size() ? Cursor.encode(paged.getLast()) : null;
    return new SignalSummaryPage(
        paged.stream().map(SignalListServiceImpl::toSummary).toList(), nextCursor);
  }

  private void requireMember(UUID projectId, UUID userId) {
    UUID workspaceId =
        projectReader
            .workspaceIdOf(projectId)
            .orElseThrow(
                () ->
                    new BusinessException(
                        ProjectErrorCode.PROJECT_NOT_FOUND,
                        Map.of("project_id", projectId.toString())));
    if (workspaceMembershipReader.roleOf(workspaceId, userId).isEmpty()) {
      throw new BusinessException(
          CommonErrorCode.AUTH_FORBIDDEN, Map.of("project_id", projectId.toString()));
    }
  }

  private static SignalSummary toSummary(Signal signal) {
    return new SignalSummary(
        signal.getId(),
        signal.getType(),
        signal.getTitle(),
        signal.getImpact(),
        signal.getMinsuSuggestion());
  }

  /**
   * 마지막으로 본 항목의 생성 시각과 id를 감싼 커서. 정렬(생성 시각 내림차순, id 내림차순)에서 커서 위치보다 뒤에 오는 행만 다음 페이지에 담는다.
   *
   * <p>id 비교는 canonical hex 문자열로 한다. PostgreSQL의 uuid 정렬은 바이트(부호 없음) 순서라 Java {@link
   * UUID#compareTo(UUID)}의 부호 있는 비교와 다르고, hex 문자열 사전순이 바이트 순서와 일치한다.
   */
  private record Cursor(Instant createdAt, UUID id) {

    static Cursor decode(String cursor) {
      if (cursor == null) {
        return null;
      }
      try {
        String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        String[] parts = raw.split("\\|");
        if (parts.length != 2) {
          throw new IllegalArgumentException("커서 구성 요소가 2개가 아니다");
        }
        return new Cursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
      } catch (RuntimeException e) {
        throw new BusinessException(
            CommonErrorCode.COMMON_VALIDATION_FAILED, Map.of("cursor", cursor));
      }
    }

    static String encode(Signal signal) {
      String raw = signal.getCreatedAt() + "|" + signal.getId();
      return Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** 내림차순 정렬에서 이 커서 위치보다 signal이 뒤(다음 페이지 쪽)에 오면 true. */
    boolean isBefore(Signal signal) {
      int byCreatedAt = signal.getCreatedAt().compareTo(createdAt);
      if (byCreatedAt != 0) {
        return byCreatedAt < 0;
      }
      return signal.getId().toString().compareTo(id.toString()) < 0;
    }
  }
}
