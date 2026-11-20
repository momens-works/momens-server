package works.momens.server.web.task.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import works.momens.server.memory.ConfirmedMemoryDetail;
import works.momens.server.source.LegacySourceRefDetail;

@Schema(description = "작업에 연결된 메모리와 소스(source ref)")
public record TaskContextResponse(
    @Schema(description = "작업 식별자") UUID taskId,
    @Schema(description = "생성 시각 내림차순 메모리 목록") List<MemoryResponse> memories,
    @Schema(description = "생성 시각 내림차순 소스(source ref) 목록") List<SourceRefResponse> sourceRefs) {
  public static TaskContextResponse from(
      UUID taskId, List<ConfirmedMemoryDetail> memories, List<LegacySourceRefDetail> sourceRefs) {
    return new TaskContextResponse(
        taskId,
        memories.stream().map(MemoryResponse::from).toList(),
        sourceRefs.stream().map(SourceRefResponse::from).toList());
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @Schema(description = "확정 memory")
  public record MemoryResponse(
      @Schema(description = "memory 식별자") UUID id,
      @Schema(description = "워크스페이스 식별자") UUID workspaceId,
      @Schema(description = "memory 레이블", nullable = true) String label,
      @Schema(description = "memory 유형", example = "DECISION") String memoryType,
      @Schema(description = "제목", example = "결정") String title,
      @Schema(description = "요약", nullable = true) String summary,
      @Schema(description = "본문", nullable = true) String body,
      @Schema(description = "상태") String status,
      Instant createdAt,
      Instant updatedAt) {
    static MemoryResponse from(ConfirmedMemoryDetail memory) {
      return new MemoryResponse(
          memory.id(),
          memory.workspaceId(),
          memory.label(),
          memory.memoryType(),
          memory.title(),
          memory.summary(),
          memory.body(),
          memory.status(),
          memory.createdAt(),
          memory.updatedAt());
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @Schema(description = "source ref")
  public record SourceRefResponse(
      @Schema(description = "source ref 식별자") UUID id,
      @Schema(description = "워크스페이스 식별자") UUID workspaceId,
      @Schema(description = "source 유형", example = "NOTION") String sourceType,
      @Schema(description = "원본 객체 유형") String sourceObjectType,
      @Schema(description = "원본 객체 식별자") String sourceObjectId,
      @Schema(description = "원본 URL", nullable = true) String sourceUrl,
      @Schema(description = "제목", nullable = true) String title,
      @Schema(description = "발췌", nullable = true) String snippet,
      String authorName,
      String authorEmail,
      Instant sourceCreatedAt,
      String visibility,
      String permissionKey,
      UUID verifiedByUserId,
      Instant verifiedAt,
      Instant createdAt,
      Instant updatedAt) {
    static SourceRefResponse from(LegacySourceRefDetail source) {
      return new SourceRefResponse(
          source.id(),
          source.workspaceId(),
          source.sourceType(),
          source.sourceObjectType(),
          source.sourceObjectId(),
          source.sourceUrl(),
          source.title(),
          source.snippet(),
          source.authorName(),
          source.authorEmail(),
          source.sourceCreatedAt(),
          source.visibility(),
          source.permissionKey(),
          source.verifiedByUserId(),
          source.verifiedAt(),
          source.createdAt(),
          source.updatedAt());
    }
  }
}
