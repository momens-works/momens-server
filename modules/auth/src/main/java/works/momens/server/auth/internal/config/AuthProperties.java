package works.momens.server.auth.internal.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import java.time.Duration;
import java.util.List;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import works.momens.server.auth.internal.config.validation.ValidJwtSecret;

/**
 * 모바일 ID token 검증과 웹 Authorization Code 로그인 설정입니다. access token은 HS256, refresh token은 서버 저장형으로
 * 관리합니다.
 */
@Validated
@ConfigurationProperties("momens.auth")
public record AuthProperties(
    @NotBlank @ValidJwtSecret String jwtSecret,
    @DurationMin(seconds = 1) Duration accessTtl,
    @DurationMin(seconds = 1) Duration refreshTtl,
    @Valid Google google,
    @Valid Web web) {

  public AuthProperties {
    if (accessTtl == null) {
      accessTtl = Duration.ofMinutes(15);
    }
    if (refreshTtl == null) {
      refreshTtl = Duration.ofDays(14);
    }
    if (google == null) {
      google = new Google(List.of(), Google.DEFAULT_JWK_SET_URI);
    }
    if (web == null) {
      web = new Web(null, null, null);
    }
  }

  public record Google(@NotEmpty List<@NotBlank String> audiences, @NotBlank String jwkSetUri) {

    static final String DEFAULT_JWK_SET_URI = "https://www.googleapis.com/oauth2/v3/certs";

    public Google {
      audiences = audiences == null ? List.of() : List.copyOf(audiences);
      if (jwkSetUri == null) {
        jwkSetUri = DEFAULT_JWK_SET_URI;
      }
    }
  }

  public record Web(
      @Valid GoogleOauth googleOauth, @Valid Cookie cookie, @Valid Redirect redirect) {

    public Web {
      if (googleOauth == null) {
        googleOauth = new GoogleOauth(null, null, null, null, null, null, null);
      }
      if (cookie == null) {
        cookie = new Cookie(null, null, null, null, null);
      }
      if (redirect == null) {
        redirect = new Redirect(null, null);
      }
    }

    public record GoogleOauth(
        @NotBlank String clientId,
        @NotBlank String clientSecret,
        @NotBlank String redirectUri,
        @NotBlank String authorizationUri,
        @NotBlank String tokenUri,
        @NotBlank String userinfoUri,
        @NotEmpty List<@NotBlank String> scopes) {

      static final String DEFAULT_AUTHORIZATION_URI =
          "https://accounts.google.com/o/oauth2/v2/auth";
      static final String DEFAULT_TOKEN_URI = "https://oauth2.googleapis.com/token";
      static final String DEFAULT_USERINFO_URI = "https://openidconnect.googleapis.com/v1/userinfo";
      static final List<String> DEFAULT_SCOPES = List.of("openid", "email", "profile");

      public GoogleOauth {
        if (authorizationUri == null) {
          authorizationUri = DEFAULT_AUTHORIZATION_URI;
        }
        if (tokenUri == null) {
          tokenUri = DEFAULT_TOKEN_URI;
        }
        if (userinfoUri == null) {
          userinfoUri = DEFAULT_USERINFO_URI;
        }
        scopes = (scopes == null || scopes.isEmpty()) ? DEFAULT_SCOPES : List.copyOf(scopes);
      }
    }

    /** access/refresh HttpOnly 쿠키 속성. same-domain 배포라 CSRF는 SameSite로 충족합니다(MOM-22). */
    public record Cookie(
        Boolean secure,
        @Pattern(regexp = "Strict|Lax|None") String sameSite,
        @NotBlank String accessName,
        @NotBlank String refreshName,
        String domain) {

      public Cookie {
        if (secure == null) {
          secure = Boolean.TRUE;
        }
        if (sameSite == null || sameSite.isBlank()) {
          sameSite = "Lax";
        }
        if (accessName == null || accessName.isBlank()) {
          accessName = "access_token";
        }
        if (refreshName == null || refreshName.isBlank()) {
          refreshName = "refresh_token";
        }
        if (domain != null && domain.isBlank()) {
          domain = null;
        }
      }
    }

    public record Redirect(@NotBlank String successUri, @NotBlank String failureUri) {}
  }
}
