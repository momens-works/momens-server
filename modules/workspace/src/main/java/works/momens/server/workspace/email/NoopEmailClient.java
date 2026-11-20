package works.momens.server.workspace.email;

/**
 * 이메일을 발송하지 않는 구현입니다.
 *
 * <p>이메일 발송 provider를 지정하지 않은 환경에서 기본 구현으로 사용합니다. 호출되어도 예외를 발생시키지 않고 성공으로 처리합니다. local과 test 환경에서는
 * 실제 이메일을 발송하지 않고 초대를 생성하는 것이 정상 동작이기 때문입니다.
 */
class NoopEmailClient implements EmailClient {

  @Override
  public void send(EmailMessage message) {}
}
