package works.momens.server.web.memory.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "메모리 후보 병합 요청")
public record MergeMemoryCandidateRequest(
    @Schema(
            description = "병합 대상 메모리 식별자",
            format = "uuid",
            requiredMode = Schema.RequiredMode.REQUIRED)
        UUID mergeTargetMemoryId) {}
