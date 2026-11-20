package works.momens.server.auth.internal.config;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import works.momens.server.common.config.DevOnly;

/** dev 토큰의 공유 시크릿과 발급 대상 allowlist입니다. {@link DevOnly} 환경에서만 로드하며 누락·길이 미달은 설정 검증으로 거부합니다. */
@Validated
@ConfigurationProperties("momens.auth.dev-token")
public record DevTokenProperties(
    @NotBlank @Size(min = 32) String secret,
    @NotEmpty List<@Email @NotBlank String> allowedEmails) {

  public DevTokenProperties {
    allowedEmails = allowedEmails == null ? List.of() : List.copyOf(allowedEmails);
  }
}
