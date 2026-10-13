package works.momens.server.source;

import java.time.Instant;
import java.util.UUID;

/** 근거 카드용 조회 모델이며, snippet이 없을 때 사용할 text도 포함합니다. provider별 표시 규칙은 호출자가 결정합니다. */
public record SourceRefView(
    UUID id,
    String sourceType,
    String title,
    String snippet,
    String text,
    String sourceUrl,
    Instant sourceCreatedAt) {}
