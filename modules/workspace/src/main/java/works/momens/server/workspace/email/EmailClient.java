package works.momens.server.workspace.email;

/** 이메일 본문 구성과 외부 서비스 전송을 분리하는 경계입니다. */
interface EmailClient {

  void send(EmailMessage message);
}
