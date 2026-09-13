# 아키텍처

`momens-server`는 **modular modulith**입니다. 하나의 배포 가능한 Spring Boot 애플리케이션을
유지하면서 내부 모듈 경계를 명시적으로 둡니다.

- 하나의 배포 가능한 애플리케이션을 유지합니다(마이크로서비스로 분리하지 않음).
- **물리 모듈 경계는 Gradle 서브프로젝트**로 둡니다(기능/도메인 단위). 실행 모듈은 `app`입니다.
- **Spring Modulith는 병행**하며, 패키지 단위로 모듈 간 의존·경계를 검증/문서화합니다.

Gradle 멀티모듈(빌드 타임 컴파일 격리)과 Spring Modulith(런타임/테스트 패키지 경계 검증)는
별개의 축이며, 여기서는 둘을 함께 씁니다.

결정 근거는 [ADR-0001](../adr/0001-modular-monolith-rules.md)(모듈러 모놀리스 룰)과
[ADR-0002](../adr/0002-gradle-multi-module-boundaries.md)(Gradle 멀티모듈을 물리 경계로)에 있습니다.

## 모듈의 물리 구조 (Gradle)

- 물리 모듈 경계는 **Gradle 서브프로젝트**이고, **기능/도메인 단위**로 나눕니다.
  `controller`/`service`/`repository` 같은 **레이어 단위로 Gradle 모듈을 나누지 않습니다.**
- `app` 모듈이 각 기능 모듈을 **조립·실행**하는 유일한 실행 가능 모듈입니다. 기능 모듈은
  라이브러리(`java-library`)로 두고, 실행 jar는 `app`만 만듭니다.
- `common`/global 모듈은 **최소화**합니다. 공유 코드(예: 영속성 베이스)는 `app`이 아니라
  기능 모듈이 의존할 수 있는 별도 모듈에 둡니다(`app`에 두면 순환 의존이 생김).
- 모든 모듈은 같은 base package `works.momens.server.*` 아래 패키지를 써서, `app`
  클래스패스에서 Spring Modulith가 하나로 조립해 검증할 수 있게 합니다.
- 버전을 명시하는 의존성은 모듈 `build.gradle`이 아니라 **버전 카탈로그**
  (`gradle/libs.versions.toml`)에 추가합니다. Spring Boot BOM이 버전을 관리하는 의존성은
  카탈로그에 두지 않습니다. 루트 `build.gradle`의 `verifyDependencyVersionsInCatalog` 태스크는
  모든 프로젝트의 빌드 스크립트에서 버전 카탈로그 외부의 버전 선언을 검사하며, 위반이 있으면
  `check`를 실패 처리합니다.

```text
root
├── app              # 실행·조립
├── common           # 최소화된 공유 모듈
└── modules          # 기능/capability 모듈의 물리적 그룹
    ├── user
    └── …
```

`modules` 디렉터리는 물리적 정리를 위한 그룹일 뿐 Gradle 서브프로젝트가 아닙니다. 기능 모듈의
Gradle 논리 경로는 `:user`, `:auth`처럼 평면으로 유지합니다.

## 모듈 경계

- 모듈은 기본적으로 비즈니스 capability 기준으로 나눕니다.
- 기술 계층(controller, service, repository 등)은 모듈 경계가 될 수 없습니다.
- bounded context는 모델과 언어의 의미가 달라질 때 보조 기준으로 사용합니다.
- workflow는 1차 모듈 경계로 삼지 않습니다. 필요하면 각 capability의 public API만 조합하는
  얇은 orchestration module로 두며, orchestration module은 도메인 정책을 소유하지 않습니다.
- 모듈별로 담당 영역을 나누지 않습니다. 각 모듈을 변경했을 때 어느 쪽에 영향을 미치는지는
  [모듈 맵](../design/module-map.md)의 `영향 범위`에서 확인합니다.

## 모듈 내부 구조

- 기본 구조는 presentation · application · domain · infrastructure 레이어로 나눕니다.
  - presentation: HTTP 요청/응답, Controller, Request/Response DTO, 예외 응답 매핑
  - application: use case orchestration, 트랜잭션 경계, 모듈 간 협력
  - domain: 핵심 비즈니스 규칙, entity, value object, domain service
  - infrastructure: JPA, Redis, 외부 API client, messaging adapter 등 기술 의존 구현
- 작은 모듈은 `internal` 아래 단순 package-private 구조를 허용합니다.
- 하나의 Gradle 모듈 안에서 도메인이 여러 개로 나뉘면 하위 도메인을 Spring Modulith nested
  application module로 논리 분리합니다(물리 분리를 먼저 두고, 모듈이 커지면 논리 분리로
  정리하는 순서). 트랜잭션 단위는 같은 도메인(aggregate)에 닫고, 모듈 경계를 넘는 트랜잭션
  참여는 최소화합니다.
- 다른 모듈·외부 시스템 의존이 application/domain을 오염시키면 port/adapter를 도입합니다.
- 모든 모듈에 hexagonal/clean architecture를 일괄 강제하지 않습니다.
- 레이어 책임(controller 얇게·service 트랜잭션·repository 캡슐화)은
  [Spring](code-conventions.md#spring)을 따릅니다.

## 모듈 간 의존

- 기본은 application event 기반 협력입니다.
- external 연동(minsu, slack 등)은 port/adapter 적용을 고려합니다.
- 단순한 경우 상대 모듈의 public API 직접 참조를 허용하되, 리뷰 단계에서 세부 논의합니다.
- 다른 모듈의 `internal` package 참조와 순환 의존은 금지합니다.
- event listener는 얇게 유지하고 application service를 호출합니다.

## Spring Modulith 적용

- `ApplicationModules.of(Application.class).verify()` 테스트를 두고 CI에 포함합니다.
- application module의 root package를 public API 영역으로 보고, 다른 모듈의 `internal` package
  참조를 금지합니다.
- application module root 외 하위 package를 외부 모듈에 공개하지 않습니다. 하나의 Gradle 모듈 안에서
  하위 도메인별 공개 API package가 필요하면 `NamedInterface`를 사용합니다. nested application
  module은 다른 상위 모듈이 직접 참조할 수 없으므로 외부 공개 API 분리 수단으로 사용하지 않습니다.
- `NamedInterface`로 나눈 하위 도메인은 Modulith가 여전히 한 모듈로 보므로 하위 도메인 사이의 의존
  방향과 순환은 검증되지 않습니다. 이 공백은 ArchUnit slice 규칙으로 메웁니다
  (`ProjectSubDomainBoundaryTests`, `WorkspaceSubDomainBoundaryTests`). 하위 도메인 경계를 새로 만들면 같은 형태의 방향 테스트를 함께
  둡니다.
- 모듈 간 event 협력에는 persisted event publication registry를 사용합니다(fire-and-forget
  아님). registry 인프라(events-jpa 의존성, `event_publication` Flyway 마이그레이션,
  completion 설정)는 첫 application event 도입 시 함께 추가합니다([데이터](persistence.md)).
- Spring Modulith는 런타임 클래스패스에 포함하지 않습니다(MOM-0921). main 소스에서는 모듈 경계 선언
  애너테이션만 사용하므로 `:common`에서 `spring-modulith-api`를 `compileOnlyApi`로 선언합니다. 모듈
  경계 검증에 필요한 `spring-modulith-core`는 `:app`에 `testImplementation`으로 선언한
  `spring-modulith-starter-test`를 통해 전이 의존성으로 제공합니다. `spring-modulith-starter-core`는
  Moments와 같은 런타임 기능을 제공하는 묶음 의존성이므로 사용하지 않습니다.
- event publication registry, `ApplicationModuleInitializer`, actuator, observability 등 Modulith
  런타임 기능을 하나라도 도입하면 `:app`에 해당 starter를 `implementation`으로 추가하고 `:common`의
  의존성은 그대로 유지합니다. 이때 `spring-modulith-moments`가 전이 의존성으로 다시 추가되면
  `MomentsAutoConfiguration`의 `@EnableScheduling`도 활성화됩니다. 따라서
  `spring.modulith.moments.enabled=false`를 `application.yml`과 `SchedulingConfigIntegrationTest`에
  다시 설정하거나, `spring-modulith-moments`를 전이 의존성에서 `exclude`해야 합니다.
- `allowedDependencies`, `@ApplicationModuleTest`, Documenter는 필요 시점에 점진 도입합니다.

## Architecture Spike

- 별도 PoC 대신 첫 기능 구현을 Architecture Spike로 사용해 위 룰을 검증합니다.

## 패키지

- base package는 `works.momens.server`입니다.
- 분리 방식은 기능/도메인 단위 Gradle 서브프로젝트로 확정했습니다([ADR-0002](../adr/0002-gradle-multi-module-boundaries.md)).
  공유 코드는 최소화된 `common` 모듈에 둡니다. 구체적인 도메인 모듈 목록과 책임 경계는
  [상세설계 > 모듈 맵](../design/module-map.md)을 단일 출처로 두고, 기능이 추가되며 점진적으로
  갱신합니다.

시스템 맥락(레포 역할)은 [AGENTS.md](../../AGENTS.md)와 [온보딩](../onboarding.md)을
참고합니다.
