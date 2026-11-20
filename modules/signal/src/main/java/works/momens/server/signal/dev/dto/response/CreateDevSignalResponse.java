package works.momens.server.signal.dev.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "dev 데모용 Signal 생성 응답")
public record CreateDevSignalResponse(@Schema(description = "생성된 Signal 식별자") UUID id) {}
