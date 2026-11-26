package works.momens.server.workspace.email;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 이메일 템플릿에 초대 정보를 적용해 발송할 이메일을 생성합니다.
 *
 * <p>템플릿의 값 치환은 직접 처리합니다. HTML 본문에 삽입하는 값은 이스케이프하고, 텍스트 본문에는 값을 그대로 삽입합니다.
 *
 * <p>만료 시각에는 Java 표준 RFC 1123 형식을 쓰지 않습니다. 해당 형식은 날짜를 0으로 채우지 않고 UTC를 GMT로 표기합니다.
 */
class InvitationEmailSenderImpl implements InvitationEmailSender {

  private static final String SUBJECT_FORMAT = "You're invited to join %s on Momens";
  private static final String DEFAULT_INVITER_NAME = "A workspace admin";
  private static final DateTimeFormatter EXPIRES_AT_FORMAT =
      DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'UTC'", Locale.ENGLISH);

  private final String htmlTemplate = readTemplate("workspace_invite.html.tmpl");
  private final String textTemplate = readTemplate("workspace_invite.txt.tmpl");

  private final EmailClient emailClient;
  private final InvitationEmailProperties properties;

  InvitationEmailSenderImpl(EmailClient emailClient, InvitationEmailProperties properties) {
    this.emailClient = emailClient;
    this.properties = properties;
  }

  @Override
  public void send(InvitationEmail invitation) {
    Map<String, String> values = templateValues(invitation);
    emailClient.send(
        new EmailMessage(
            invitation.recipientEmail(),
            SUBJECT_FORMAT.formatted(invitation.workspaceName()),
            render(htmlTemplate, values, true),
            render(textTemplate, values, false)));
  }

  private Map<String, String> templateValues(InvitationEmail invitation) {
    String inviterName =
        invitation.inviterName() == null || invitation.inviterName().isBlank()
            ? DEFAULT_INVITER_NAME
            : invitation.inviterName();
    Map<String, String> values = new LinkedHashMap<>();
    values.put("WorkspaceName", invitation.workspaceName());
    values.put("InviterName", inviterName);
    values.put("InviterEmail", invitation.inviterEmail());
    values.put("Role", invitation.role());
    values.put("AcceptURL", acceptUrl(invitation.rawToken()));
    values.put(
        "ExpiresAt", EXPIRES_AT_FORMAT.format(invitation.expiresAt().atOffset(ZoneOffset.UTC)));
    values.put("ReplyToEmail", properties.replyTo());
    return values;
  }

  private String acceptUrl(String rawToken) {
    if (properties.acceptUrl() == null || properties.acceptUrl().isBlank()) {
      return "";
    }
    return UriComponentsBuilder.fromUriString(properties.acceptUrl())
        .replaceQueryParam("token", rawToken)
        .build()
        .toUriString();
  }

  private static String render(String template, Map<String, String> values, boolean escapeHtml) {
    String rendered = template;
    for (Map.Entry<String, String> value : values.entrySet()) {
      String raw = value.getValue() == null ? "" : value.getValue();
      rendered =
          rendered.replace(
              "{{." + value.getKey() + "}}", escapeHtml ? HtmlUtils.htmlEscape(raw) : raw);
    }
    return rendered;
  }

  private static String readTemplate(String name) {
    try {
      return StreamUtils.copyToString(
          new ClassPathResource("email/templates/" + name).getInputStream(),
          StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
