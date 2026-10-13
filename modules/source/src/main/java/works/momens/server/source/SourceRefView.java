package works.momens.server.source;

import java.time.Instant;
import java.util.UUID;

/** 근거 카드용 최소 조회 모델입니다. 원문 전체와 provider별 표시 규칙은 포함하지 않습니다. */
public record SourceRefView(
    UUID id,
    String sourceType,
    String title,
    String snippet,
    String text,
    String sourceUrl,
    Instant sourceCreatedAt) {}
