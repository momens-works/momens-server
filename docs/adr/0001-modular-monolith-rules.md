# 0001. 모듈러 모놀리스 아키텍처 룰

- 상태: Accepted (결정 1의 모듈 경계 기준 중 비즈니스 capability 항목은 [0025](0025-module-boundary-criteria.md)로 개정)
- 날짜: 2026-06-23
- 작성자: Kimgyuilli

## 맥락

새 WAS 서버(`momens-server`)에 모듈러 모놀리스와 Spring Modulith를 적용하기로 하고,
모듈 경계·내부 구조·모듈 간 의존·Spring Modulith 적용 범위·검증 전략을 회의로 확정했다.
하나의 배포 가능한 Spring Boot 애플리케이션을 유지하면서 내부 경계를 명시적으로 두는 것이
목표다. 상시 룰(무엇을 지킬지)은 [아키텍처 규칙](../rules/architecture.md)에 반영하고,
이 ADR은 그 결정의 근거(왜·어떤 대안을 탈락시켰는지)를 남긴다.

## 결정 1. 모듈 경계 기준

결정:

- application module은 기본적으로 비즈니스 capability 기준으로 나눈다.
- 기술 계층(controller, service, repository 등)은 모듈 경계가 될 수 없다.
- 동일 용어가 영역마다 다른 의미를 갖거나 모델 경계가 달라지면 bounded context 관점으로
  경계를 재검토한다(보조 기준).
- workflow는 1차 모듈 경계로 삼지 않고, 필요하면 각 capability의 public API만 조합하는
  얇은 orchestration module로만 둔다. orchestration module은 도메인 정책을 소유하지 않는다.

대안:

- bounded context를 1차 기준으로: 도메인 모델이 단순한 초기에는 과한 경계가 된다.
- workflow를 루트 모듈 기준으로: orchestration hub가 비대해질 위험이 있다.

결과:

- capability 기준은 모듈러 모놀리스 취지와 맞고 팀이 이해하기 쉽다.
- bounded context는 모델이 복잡해질 때 경계를 재검토하는 보조 기준으로 남는다.

## 결정 2. 모듈 내부 구조

결정:

- 모듈 내부 구조의 기본값은 layered architecture(presentation / application / domain /
  infrastructure)다.
- 작은 모듈은 `internal` 아래 단순 package-private 구조를 허용한다.
- 다른 모듈·외부 시스템 의존이 application/domain을 오염시키면 port/adapter를 부분 적용한다.
- 모든 모듈에 hexagonal/clean architecture를 일괄 강제하지 않는다.

대안:

- 전 모듈 hexagonal 강제: 초기 코드량과 학습 비용이 과하다.
- 전 모듈 단순 구조: 복잡한 모듈에서 역할 경계가 흐려진다.

결과:

- layered는 Spring MVC/JPA 기반 WAS와 잘 맞고 이해하기 쉽다.
- 알림·감사 로그·단순 조회성 reference 모듈은 단순 구조, 주문·결제·정산처럼 정책과 상태
  전이가 복잡한 모듈은 layered + 필요한 의존에 port/adapter를 둔다.

## 결정 3. 모듈 간 의존 방식

결정:

- 기본은 application event 기반 협력으로 둔다.
- external 연동(minsu, slack 등)은 port/adapter 적용을 고려한다.
- 단순한 경우 개인 판단에 따라 상대 모듈 public API 직접 참조를 허용하되, 이때는 리뷰
  단계에서 세부 논의한다.
- 다른 모듈의 `internal` package 참조와 순환 의존은 금지한다.
- event listener는 얇게 유지하고 application service를 호출한다.

대안:

- 모든 의존을 port/adapter로: 단순 의존까지 감싸면 코드량·학습 비용이 커진다.
- 모든 의존을 직접 호출로: 모듈 간 결합도가 높아진다.

결과:

- 복잡도 기준으로 direct call / event / port/adapter를 나눠 과설계를 피하면서 중요한
  의존을 통제한다.
- "단순 조회"·"복잡한 동기 의존"·"event publisher를 port로 감싸는" 세부 기준과 이벤트
  실패·재처리 정책은 실제 적용 과정과 리뷰에서 구체화한다.

## 결정 4. Spring Modulith 적용 범위

결정:

- `ApplicationModules.of(Application.class).verify()` 테스트를 추가하고 CI에 포함한다.
- 다른 모듈의 `internal` package 참조를 금지하고, 모듈 root package를 public API 영역으로 본다.
- 모듈 간 event 협력에는 persisted event publication registry를 사용하기로 의도를 확정한다
  (fire-and-forget 아님). 단 registry 인프라(events-jpa 의존성, `event_publication` Flyway
  마이그레이션, completion 설정)는 첫 application event 도입 시 함께 추가한다.
- `allowedDependencies`, `NamedInterface`, `@ApplicationModuleTest`, Documenter는 필요
  시점에 점진 도입한다(데모 리팩토링 전까지는 verify만 사용).

대안:

- 모든 기능 즉시 강제: 첫 도입 단계에서 학습 비용이 과하다.
- verify만 두고 예방 룰 없음: 이후 `allowedDependencies`·`NamedInterface` 도입 시 기존
  의존 정리 비용이 커진다.

결과:

- 경계 검증부터 CI에 넣고 나머지는 점진 도입하되, root package 외 하위 package를 외부에
  공개하지 않는 예방 룰로 이후 강화 비용을 낮춘다.

## 결정 5. PoC 전략

결정:

- 별도 PoC는 진행하지 않고, 본 프로젝트의 첫 기능 구현을 Architecture Spike로 사용한다.

대안:

- 버려지는 별도 PoC: 2주 일정(세팅·학습·데모)에서 비용이 크다.

결과:

- 레퍼런스가 있는 조합이라 미지의 기술 검증보다 첫 기능에서 룰을 검증하는 편이 효율적이다.
- Architecture Spike에서 capability 패키지 구조, public API / internal 분리,
  `ApplicationModules.verify()` 통과, layered 적절성, direct call·event·port/adapter
  기준의 실제 적용 가능성을 확인한다.

## 미결정

- external / persistence 모듈을 별도 분리할지, 각 도메인에서 다룰지.
- 도메인 모듈 목록과 분리 최종안.
- 이벤트 실패·재처리 정책 정의 시점.
