/**
 * 워크스페이스 초대 이메일 발송 하위 도메인입니다.
 *
 * <p>{@code workspace} 모듈에서 외부 이메일 서비스와의 연동 경계와 이메일 본문 템플릿을 분리합니다. 초대 하위 도메인에는 {@link
 * works.momens.server.workspace.email.InvitationEmailSender}와 {@link
 * works.momens.server.workspace.email.InvitationEmail} 계약만 공개하며, HTTP 호출과 템플릿 구현은 이 패키지 외부로 노출하지
 * 않습니다.
 */
@ApplicationModule
package works.momens.server.workspace.email;

import org.springframework.modulith.ApplicationModule;
