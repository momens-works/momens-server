package works.momens.server.web.source.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import works.momens.server.source.ConfigureFigmaCommand;

@Schema(description = "Figma 팀 webhook과 수집할 파일 허용 목록")
public record ConfigureFigmaRequest(
    @NotBlank @Schema(description = "Figma 팀 ID", example = "123456789") String teamId,
    @NotEmpty @Schema(description = "파일 키. 공백 제거 후 빈 값 제외·중복 제거, 대소문자와 순서 유지")
        List<String> fileKeys) {
  public ConfigureFigmaCommand toCommand() {
    return new ConfigureFigmaCommand(teamId, fileKeys);
  }
}
