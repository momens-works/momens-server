# 0016. 사용자 신원 식별 키를 이메일에서 Google `sub`으로 전환

- 상태: Accepted (prod 스키마 소유권에 관한 결정은 [ADR-0019](0019-prod-schema-ownership-transfer.md)로 대체)
- 날짜: 2026-08-09
- 작성자: jsshin8128

> **2026-08-23**: prod 스키마의 DDL 소유권에 관한 결정은 [ADR-0019](0019-prod-schema-ownership-transfer.md)로 대체되었습니다. 스키마 주도권이 momens-server로 이전되어 이 레포지토리의 마이그레이션이 운영 DDL로 사용되므로, 본문에서 "prod에는 레거시 마이그레이션으로 반영한다"고 설명하는 네 곳은 더 이상 현재 사실과 맞지 않습니다. `provider`의 CHECK 제약에 허용 값을 추가하거나 `user_identities(user_id)` 인덱스를 생성할 때 레거시 마이그레이션도 함께 수정해야 한다는 설명도 마찬가지입니다.
>
> 본문에서 참조하는 `MOM-0831`은 취소되었으며, 해당 범위는 `MOM-0909`의 부트스트랩 작업에 포함되었습니다. `V20260810090000__create_user_identities.sql`은 부트스트랩 실행 집합에 포함되어 2026-09-04에 momens-server의 Flyway가 운영 환경에 적용했습니다.
>
> 식별 키를 Google `sub`으로 전환하는 결정, `users`와 `user_identities`를 같은 트랜잭션에서 생성하는 결정, `user_identities`를 먼저 생성한 뒤 서버를 배포하는 순서와 그 근거, `user_identities(user_id)` 인덱스를 생성하지 않는 결정은 계속 유효합니다.

## 맥락

우리 서버는 Google 로그인을 두 가지 경로로 처리한다. 모바일에서는 앱이 Google Sign-In으로 받은 ID 토큰을
서버가 검증한다(`AuthService.loginWithGoogleToken`). 웹에서는 브라우저가 콜백
엔드포인트(`/api/auth/google/callback`)로 이동하며 URL에 authorization code를 실어 오고, 서버는 이를 받아
검증한 뒤, Google access token을 발급 받아 userinfo를 조회한다(`WebAuthService.completeLogin`).

구체적인 흐름은 다음과 같다. `AuthService.loginWithGoogleToken`은 토큰 검증 결과에서 이메일, 이름, 프로필
이미지 URL을 추출해 `userService.findOrCreate(email, name, picture)`에 전달한다.
`WebAuthService.completeLogin`도 userinfo 응답에서 동일한 세 값을 추출해 같은 API를 호출한다. 전달된 정보는
`UserRepository.upsertByEmail`에서 `INSERT ... ON CONFLICT (email) DO UPDATE`로 처리된다.

이 upsert 방식은 동일한 이메일로 최초 로그인 요청이 동시에 들어오더라도 하나의 사용자로 수렴하도록 원자성을
보장한다. 따라서 식별 키를 변경하더라도 이 동시성 보장은 유지되어야 한다.

현재 구조는 레거시를 기준으로 설계되었다. `teams` 레포지토리의 2026-06-26 4차 기획 회의록에서는 모바일 인증
방침을 "계정은 이메일 기반으로 통합한다(구글 계정 이메일과 기존 계정 매핑)"로 정했다는 사실과 후속 작업으로
"이메일을 기존 계정과 정확히 매핑하는 로직"을 남겨뒀다는 사실을 확인할 수 있다. 따라서 이번 ADR에서는 Google
로그인을 기존 계정에 연결한다는 방향은 유지하되, 계정 매핑 기준을 변경한다.

문제는 이메일이 Google에서 관리하는 값이라는 점이다. Google 공식 문서에서는 사용자 식별자로 이메일이 아니라
`sub`(Subject) 사용을 권장한다.

> An identifier for the user, unique among all Google Accounts and never reused.
> Always use the `sub` field as it is unique to a Google Account even if the user changes their email address.

[Google OpenID Connect 문서](https://developers.google.com/identity/openid-connect/openid-connect#obtainuserinfo)

이메일 변경은 드문 사례지만 실제로 발생할 수 있다. Google 계정 도움말에 따르면 회사나 학교 계정은 관리자가
이메일을 변경할 수 있고, 개인 `gmail.com` 계정도 12개월에 한 번, 최대 3회까지 변경할 수 있다. 우리 서비스
사용자 대부분이 회사 계정을 사용한다는 점을 고려하면, 도메인 변경이나 조직 변경으로 이메일이 바뀔 가능성도
충분히 존재한다.

[Google 계정 이메일 변경 안내](https://support.google.com/accounts/answer/19870)

현재 구조에서는 동일한 사용자가 이메일이 변경된 상태로 로그인하면 `ON CONFLICT (email)`이 동작하지 않아
새로운 `users` 행이 생성된다. 사용자 ID를 참조하는 데이터는 두 종류로 나뉜다.

- FK로 참조되는 데이터
  - 워크스페이스 멤버십(`workspace_members.user_id`)
  - 프로젝트 소유자(`projects.owner_id`)
  - 태스크 담당자(`tasks.assignee_id`)
- FK 없이 사용자 ID만 저장하는 데이터
  - refresh token(`refresh_tokens.user_id`)
  - 푸시 설치 정보(`push_installations.user_id`)
  - 푸시 발송 이력(`push_deliveries.target_user_id`)
  - 시그널 처리 이력(`signal_actions.processed_by_user_id`)

새로운 `users` 행이 생성되면 FK 관계가 끊어진다. FK 없이 저장된 데이터도 기존 사용자 ID를 계속 참조하므로
새로운 사용자와 연결되지 않는다. 사용자가 직접 복구할 수 없으며 운영자가 수동으로 수정해야 한다.

현재 운영 환경에서는 레거시 `momens-api`와 공유 DB를 사용하고 있으며, `momens-server`로 이관하는
단계이다([데이터](../rules/persistence.md)).

한편, prod 매니페스트에는 레거시 `momens-api`와 신규 `momens-server`가 함께 존재한다. 레거시 `users`
테이블에는 provider 관련 컬럼이 없어 레거시 서버에서는 새로운 식별 체계를 사용할 수 없는 상황이다. 또한
레거시는 이관이 완료될 때까지 새 서버와 같은 `users` 테이블에서 쓰기를 수행한다.

따라서 이번 결정으로 해결할 수 있는 범위는 레거시 이관이 완료되기 전까지 새 서버의 로그인 경로로 한정된다.

이번 ADR에서는 다음 사항을 결정한다.

1. 로그인 시 사용할 사용자 식별 키
2. 식별 키를 `users`에 둘지 별도 테이블로 분리할지와 해당 테이블을 소유할 모듈
3. 기존 사용자를 새로운 식별 체계로 전환하는 방법
4. 신규 사용자 생성 시 동시성을 보장하는 방법
5. `users.email`의 역할과 UNIQUE 제약 처리 방안
6. Google 로그인을 거치지 않는 사용자 생성 경로의 처리 방안

## 결정

로그인 시 사용자 식별 키를 이메일에서 `(provider, provider_user_id)` 조합으로 변경한다. 이를 저장하기 위해
`user_identities` 테이블을 추가한다. 이 변경은 모든 Google 로그인 경로에 적용한다.

1. **식별 키는 `(provider, provider_user_id)`로 정의한다.**

   Google의 `sub`은 계정 단위로 고유하며 재사용되지 않고, 이메일이 변경되어도 유지된다. 따라서 동일한
   사용자를 안정적으로 식별하기 위해 `sub`을 식별 키로 사용하는 것이 적절하다. `provider`를 함께 저장하는
   이유는 발급 주체가 다르면 동일한 식별자 값이라도 다른 사용자를 의미할 수 있기 때문이다.

   컬럼명은 `sub` 대신 `provider_user_id`를 사용한다. ADR-0003에서 우리 서버가 발급하는 access token의
   `sub`을 내부 사용자 ID로 정의하고 있으므로, 외부 provider의 `sub`과 혼동하지 않도록 구분한다.

2. **식별 정보는 `user_identities` 테이블에서 관리하고 `user` 모듈이 소유한다.**

   스키마는 다음과 같다.

   ```sql
   CREATE TABLE user_identities (
       id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
       user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
       provider TEXT NOT NULL CHECK (provider IN ('google')),
       provider_user_id TEXT NOT NULL,
       created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
       updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
       UNIQUE (provider, provider_user_id)
   );
   ```

   한 사용자가 여러 로그인 수단을 가질 수 있으므로 `users`의 단일 컬럼만으로는 이 관계를 표현하기 어렵다.
   UNIQUE 제약은 동일한 Google 계정이 서로 다른 사용자에게 중복으로 연결되는 것을 DB 수준에서 방지한다.

   `provider`에는 CHECK 제약을 두어 허용되지 않은 값이 저장되는 것을 막는다. 이는 `signals.type`,
   `signal_actions.action_type`, `tasks.status`, `tasks.priority`, `push_installations.platform`과 동일한
   패턴이다. 새로운 로그인 수단을 추가할 때는 허용 값도 함께 확장한다.

   `id`에는 `DEFAULT uuid_generate_v4()`를 지정한다. 애플리케이션에서는 식별자를 직접 생성하므로 일반적인
   실행 경로에서 이 기본값을 사용하지 않는다. 하지만 시드나 수동 SQL처럼 애플리케이션을 거치지 않고 행을
   생성할 때도 식별자가 자동으로 할당되도록 기본값을 유지한다([데이터](../rules/persistence.md)).
   `uuid-ossp` 확장은 `common`이 소유하며, 레거시 스키마에도 이미 적용되어 있다.

   이 테이블을 `user` 모듈이 소유하는 이유는 사용자를 생성할 때 `users`와 `user_identities`가 반드시 함께
   생성되어야 하기 때문이다. `auth`가 소유하면 두 테이블의 생성이 서로 다른 모듈로 분리되어 하나의
   트랜잭션에서 작업이 수행될 수 없고, 중간에 실패할 경우 identity가 없는 사용자가 남을 수 있다. 이는 모듈
   간 트랜잭션 참여를 최소화한다는 아키텍처 원칙([아키텍처](../rules/architecture.md))에도 맞지 않는다.

   또한 `user` 모듈이 소유하면 `user_id` FK 관계가 모듈 내부에서 닫힌다. 반대로 `auth`가 소유하면
   `refresh_tokens`와 마찬가지로 `users`에 대한 FK를 제외해야 하는 구조가 된다.

   `auth`는 기존과 동일하게 `user` 모듈의 public API만 사용한다. Google 로그인 경로에서 사용할
   `findOrCreateByIdentity`를 새로 추가하고, 기존 `findOrCreate`는 로그인 수단 없이 사용자를 생성하는
   API로 유지한다. 두 경로는 레거시 이관 기간 동안 함께 사용되어야 하므로 하나의 메서드로 합치지 않는다.

   마이그레이션은 환경별로 분리한다. local, test, dev 환경에서는 `modules/user`의 Flyway로 테이블을
   생성하고, prod에서는 레거시가 스키마를 소유하므로 레거시 마이그레이션으로
   반영한다([데이터](../rules/persistence.md)).

3. **`users`와 `user_identities`는 같은 트랜잭션에서 생성한다.**

   `users` 행은 기존 `upsertByEmail`로 확보한다. `ON CONFLICT (email) DO UPDATE`가 단일 SQL로 동작하기
   때문에, 동일한 이메일로 최초 로그인 요청이 동시에 들어와도 최종적으로 하나의 행으로 수렴한다.

   이후 로그인 수단을 삽입할 때는 `ON CONFLICT (provider, provider_user_id) DO NOTHING`을 사용한다.
   `DO NOTHING`은 충돌이 발생해도 예외를 던지지 않고 삽입만 무시한 채 종료된다. 따라서 삽입 결과가 0건이면
   동시에 들어온 다른 요청이 먼저 해당 로그인 수단을 연결한 것으로 판단한다. 이 경우에는 로그인 수단을 다시
   조회해 해당 사용자로 로그인 흐름을 이어간다. 경쟁에서 이긴 요청 역시 동일한 `users` 행에 로그인 수단을
   연결하므로, 두 요청 모두 최종적으로 같은 사용자를 반환하게 된다.

   이는 기존 `upsertByEmail`이 보장하던 "동시 최초 로그인 요청에서도 단일 사용자로 수렴"하는 동작을 새로운
   로그인 수단 구조에서도 그대로 유지하기 위한 결정이다.

   처음에는 두 요청이 각각 `users` 행을 생성한 뒤, 로그인 수단 삽입 결과가 0건이면 트랜잭션을 롤백하는
   방식을 검토했다. 하지만 이 방식은 결정 6에 따라 유지하기로 한 `users.email`의 UNIQUE 제약과 함께
   사용할 수 없다. 동일한 이메일로 두 요청이 동시에 `users` 행을 삽입하는 경우, 로그인 수단 단계까지
   가지 않고 UNIQUE 제약 위반으로 트랜잭션이 먼저 중단되기 때문이다. 따라서 `users` 행은 처음부터
   하나로 수렴시키고, 로그인 수단 삽입 결과를 기준으로 경합 여부만 판단하는 방식으로 변경했다.

4. **기존 사용자는 최초 한 번만 이메일을 기준으로 identity를 생성한다.**

   기존 사용자의 경우 Google `sub`이 저장되어 있지 않다. 따라서 전환 후 첫 로그인에서 `(google, sub)` 조회
   결과가 없으면 이메일로 `users`를 조회한 뒤 identity를 생성한다. 이후부터는 identity를 기준으로 사용자를
   조회한다. 이 fallback은 모바일과 웹 로그인 경로에 모두 적용한다.

   이 매칭은 해당 이메일이 현재 로그인한 사용자의 이메일이라는 전제에서만 성립한다. 이를 보장하기 위해
   다음 두 가지 조건을 함께 확인한다.

   첫째, 이메일 fallback은 `email_verified`가 보장된 경우에만 수행한다. 모바일은 `GoogleIdTokenVerifier`가
   `email_verified` 클레임을 확인하고, 웹은 userinfo 응답의 `email_verified` 값을 확인한다. 이 검증이 없으면
   다른 사용자의 계정에 identity가 잘못 연결될 수 있다. 따라서 fallback을 유지하는 동안에는 두 경로의
   `email_verified` 검증도 반드시 유지한다.

   둘째, **이메일로 조회된 사용자에게 이미 다른 로그인 수단이 연결되어 있는 경우에는 해당 사용자를 매칭하지
   않고 로그인을 거부한다.**

   회사 계정 환경에서는 관리자가 이메일 주소를 다른 사용자에게 재할당할 수 있다. 예를 들어 기존 사용자가
   사용하던 이메일이 회수된 뒤 다른 사용자에게 다시 할당되는 경우가 있다. 이 상태에서 새로운 사용자가 처음
   로그인하면 아직 연결된 로그인 수단이 없기 때문에 이메일 fallback이 동작하고, 단순히 이메일이 같다는
   이유만으로 기존 사용자의 행을 조회하게 된다.

   Google 입장에서는 해당 이메일이 현재 로그인한 사용자의 검증된 이메일로 인식되기 때문에,
   `email_verified` 여부만으로는 이러한 재할당 상황을 구분할 수 없다.

   이미 로그인 수단이 연결된 계정을 매칭 대상에서 제외함으로써, 최소 한 번이라도 전환이 완료된 계정이 다른
   사용자에게 잘못 연결되는 상황을 방지한다. 전환 기간에는 `users.email`에 대한 UNIQUE 제약이 유지되므로,
   동일한 이메일로 새로운 사용자를 생성하는 것도 불가능하다.

   따라서 이 경우에는 사용자를 새로 생성하지 않고 전용 에러 코드로 요청을 거부한다. 계정이 잘못 연결되는
   것보다 로그인을 실패시키는 편이 더 안전하며, 실패 원인이 로그와 응답에 명확히 남기 때문에 운영자가 원인을
   추적할 수 있다.

5. **`users.email`은 식별자가 아니라 표시 및 동기화를 위한 값으로 유지한다.**

   이름과 프로필 이미지 URL처럼 로그인할 때마다 최신값으로 갱신한다. 현재 사용자가 이메일을 직접 수정하는
   기능은 제공하지 않는다.

   하지만 이메일 갱신 실패가 로그인 흐름을 막아서는 안 된다. 레거시 이관 기간에는 UNIQUE 제약이 남아 있어
   이메일 갱신 과정에서 충돌할 수 있으므로 다음과 같이 조건부 UPDATE로 처리한다.

   ```sql
   UPDATE users
   SET email = :email,
       updated_at = NOW()
   WHERE id = :id
     AND NOT EXISTS (
         SELECT 1
         FROM users u2
         WHERE u2.email = :email
           AND u2.id <> :id
     );
   ```

   이메일 충돌이 발생하는 경우에는 이메일 갱신만 건너뛰고 로그인 흐름은 진행한다. PostgreSQL에서는 제약
   위반이 발생하면 해당 트랜잭션이 abort 상태가 되므로, UPDATE 조건에서 이미 다른 사용자가 사용 중인
   이메일을 사전에 제외하도록 처리한다.

   하지만 이 조건만으로 이메일 충돌을 완전히 제거할 수는 없다. `READ COMMITTED` 격리 수준에서는 SQL 문
   단위로 스냅샷이 갱신되기 때문에, `NOT EXISTS`를 평가한 직후 다른 트랜잭션이 동일한 이메일을 커밋하면
   해당 행이 조건에 반영되지 않을 수 있다. 현재는 레거시 `momens-api`도 동일한 `users` 테이블에 쓰기
   작업을 수행하고 있어 이러한 경합 가능성이 존재한다. 이관이 완료되어 신규 서버가 유일한 writer가 되면
   이 부분을 자연스럽게 정리하도록 한다.

   또한 레거시 `momens-api`의 워크스페이스 초대 수락 로직은 이메일을 기준으로 동작한다. 이메일이 변경되면
   기존 주소로 발송된 대기 중인 초대는 매칭에 실패한다.

6. **`users.email`의 UNIQUE 제약은 레거시 이관이 완료될 때까지 유지한다.**

   이메일은 더 이상 식별 키가 아니지만, 현재 UNIQUE 제약은 레거시 read-then-insert 구조에서 유일하게
   동시성을 보장하는 장치로서 기능하고 있다.

   레거시 `FindOrCreateUser`는 사용자를 조회한 뒤 결과가 없으면 삽입하며 별도의 락을 사용하지 않는다. 두
   요청이 동시에 들어오면 모두 삽입을 시도할 수 있고, 이때 UNIQUE 제약이 없으면 중복 사용자가 생성된다.

   중복 사용자는 새 서버에도 영향을 준다. `UserRepository.findByEmail`은 Optional 값을 리턴하기 때문에 같은
   이메일을 사용하는 사용자가 여러 명이면 `IncorrectResultSizeDataAccessException`이 발생한다.

   반면 UNIQUE 제약을 유지하면서 발생하는 이메일 갱신 충돌은 앞서 정의한 조건부 UPDATE로 처리할 수 있다.
   따라서 이관 기간에는 제약을 유지하는 편이 더 안전하다.

   이관이 완료되면 새 서버가 유일한 writer가 되고 `user_identities`를 기준으로 동시성이 보장된다. 이 시점에
   `users.email`의 UNIQUE 제약을 제거한다.

7. **Google 로그인을 거치지 않는 사용자 생성 경로는 별도 API로 분리한다.**

   `DevTokenService.issueForTestUser`는 기존 `findOrCreate(email, email, null)`을 사용한다. Provider 기반
   구조로 변경하면 이 방식은 더 이상 사용할 수 없다.

   이를 해결하기 위해 `provider = 'dev'`와 같은 임의의 identity를 생성하지는 않는다. Dev 전용 값을 운영
   스키마의 CHECK 목록에 포함해야 하고, 해당 사용자는 애초에 identity 검증 대상도 아니기 때문이다.

   따라서 Google 로그인 경로에서는 provider 기반 API를 사용하고, dev 및 demo 경로에서는 identity 없이
   사용자를 생성하는 별도 API를 사용한다.

8. **HTTP 계약은 기존 요청과 성공 응답을 유지하고, 다음 에러 응답을 추가한다.**

   `POST /api/auth/google/token`, `GET /api/auth/google/login`, `GET /api/auth/google/callback`,
   `GET /api/mobile/bootstrap`의 요청과 성공 응답은 모두 유지한다.

   | HTTP 상태 | 에러 코드 | 설명 |
   | --- | --- | --- |
   | 409 | `USER_EMAIL_LINKED_TO_ANOTHER_IDENTITY` | 이메일로 조회한 사용자에게 이미 다른 로그인 수단이 연결되어 있어 Google 계정을 연결할 수 없음 |

## 대안

- **이메일을 식별자로 유지한다.** 구현은 단순하지만 이메일이 변경되면 동일한 사용자가 분리된다. 사용자가
  직접 복구할 수 없고 운영자의 수동 개입이 필요하다.
- **`users`에 `google_sub` 컬럼을 추가한다.** 변경 범위가 작고 구현도 단순하지만 provider가 늘어날 때마다
  컬럼을 추가해야 한다. 여러 로그인 수단을 하나의 사용자에게 연결하는 관계도 표현하기 어렵다.
- **`user_identities`를 `auth` 모듈이 소유한다.** 인증 관점에서는 자연스럽지만 사용자 생성이 두 모듈에 걸쳐
  처리되어 트랜잭션 설계 원칙에 위배된다.
- **`users.email`의 UNIQUE 제약을 제거한다.** 레거시 read-then-insert 구조를 폐기할 경우 동시성에 대한 보호
  로직이 존재하지 않게 된다. 따라서 중복 사용자가 생성될 수 있다.
- **이메일을 갱신하지 않는다.** 갱신 충돌은 방지할 수 있지만 실제 Google 이메일과 DB 값이 달라지게 된다.
- **별도의 이메일 변경 기능을 제공한다.** Google이 원본 이메일을 관리하므로 실효성이 낮고, 확인 메일 발송을
  위한 인프라를 추가해야 하기 때문에 불필요하게 복잡성이 높아진다.

## 결과

- Google 계정의 이메일이 변경되어도 동일한 사용자로 인식한다.
- DB의 이메일은 다음 로그인 시 최신값으로 갱신한다. 다른 사용자와 충돌하면 갱신을 건너뛰고 기존 값을
  유지한다.
- 로그인 과정에 provider 기반 조회가 추가되지만 `(provider, provider_user_id)` UNIQUE 인덱스를 사용하므로
  조회 비용은 크지 않다.
- API 계약이 변경되지 않으므로 클라이언트 수정은 필요하지 않다.
- 향후 Google 외의 로그인 수단을 추가할 수 있는 구조가 마련된다. 실제로 로그인 수단을 추가할 때는 계정 연결
  API와 인증 수단 보호 로직이 별도로 필요하다.
- 기존 사용자 데이터는 첫 로그인 과정에서 자연스럽게 전환하므로 별도의 재처리 로직을 두지 않는다.
- 이번 결정으로 해결되는 범위는 레거시 이관이 완료되기 전까지 신규 서버의 로그인 경로로 한정된다.
- 레거시 `momens-api`는 계속 이메일을 기준으로 사용자를 조회한다. 레거시를 통해 로그인한 사용자는 identity
  없이 생성될 수 있으며, 이메일이 변경되면 새로운 `users` 행이 생성될 가능성도 남아 있다.
- 이메일 fallback은 레거시 이관이 완료될 때까지 유지한다. 이관 일정이 길어질수록 아직 전환되지 않은 계정이
  다른 Google 계정에 잘못 연결될 위험도 커진다. 이관 완료 시 fallback도 함께 제거한다.
- 이미 로그인 수단이 연결된 계정은 재할당된 이메일로 매칭되지 않기 때문에, 한 번이라도 전환이 완료된 계정은
  이 위험에서 벗어난다. 반대로 아직 전환되지 않은 계정은 보호되지 않는다. 서버 입장에서는 이메일 재할당
  여부를 알 방법이 없으므로, 이 구간의 위험을 줄이려면 fallback을 허용하는 기간을 최대한 짧게 가져가야 한다.
- 신규 사용자 생성 과정에서 동일한 Google 계정 요청이 서로 다른 이메일로 동시에 들어오는 경우, 경합에서
  밀린 요청이 생성한 `users` 행이 로그인 수단 없이 남을 수 있다. 이 행이 해당 이메일을 계속 점유하게
  되면서 이후 이메일 갱신도 수행되지 않는다. 발생 조건이 제한적이고, 보상 삭제를 추가할 경우 또 다른
  경합을 유발할 수 있어 별도 처리는 두지 않았다.
- `DevTokenService`와 데모 시드는 Google 로그인을 거치지 않으므로 identity가 없는 사용자를 생성한다. 두
  기능은 dev와 demo 환경에서만 사용하며 identity 점검 대상에서도 제외한다.
- 이메일이 변경되면 기존 주소로 발송된 대기 중인 초대를 수락하지 못할 수 있다. 레거시 초대 기능을 새 서버로
  이관할 때 매칭 기준을 사용자 ID로 변경할지도 함께 결정한다.
- `provider` 컬럼의 CHECK 제약으로 인해 새로운 로그인 수단을 추가할 때는 허용 값 목록을 확장하는 레거시
  마이그레이션도 함께 적용해야 한다.
- `user_identities` 테이블은 prod 환경에서 신규 서버의 Flyway가 아니라 레거시 마이그레이션으로 생성하며
  MOM-0831에서 처리한다.
- 운영 환경에는 `ddl-auto=validate`가 적용되어 있으므로 `user_identities` 테이블을 먼저 생성한 뒤 서버 구현
  작업을 완료하고 배포해야 한다. 순서가 바뀌면 테이블이 없어 서버 부팅이 실패한다.
- 향후 `users.email`의 UNIQUE 제약을 제거할 때는 반대 순서로 진행한다. 현재 `UserRepository.upsertByEmail`은
  `ON CONFLICT (email)`을 사용하므로, 먼저 코드에서 해당 구문을 제거한 뒤 UNIQUE 제약을 제거해야 한다.
- 두 배포 순서의 근거는 다르다. `user_identities` 테이블을 먼저 생성하는 이유는 `ddl-auto=validate`가 테이블의
  존재 여부를 확인하기 때문이다. 반면 `users.email`의 UNIQUE 제약을 코드 변경 후 제거하는 이유는
  PostgreSQL의 unique index inference 때문이다. `ddl-auto=validate`는 `AbstractSchemaValidator`를 기준으로
  테이블, 컬럼, 컬럼 타입, 시퀀스는 확인하지만 UNIQUE 제약은 검증하지 않는다.
- 2026-06-26 기획 결정에는 "이메일 기반 계정 통합"으로 기록되어 있으므로 `teams` 레포지토리의 관련 문서에도
  이번 변경을 반영한다. 계정을 하나로 통합한다는 기존 취지는 유지하고 매핑 기준만 변경한다는 점을 기획과
  논의한다.
- 전환에 대한 fallback 과정에서 해당 사용자에게 이미 다른 로그인 수단이 연결되어 있는지 확인하기 위해
  `user_id` 기준으로 조회한다. 이번 작업에서는 `user_identities(user_id)` 인덱스를 추가하지 않는다.
  이 조회는 아직 전환되지 않은 계정에서만 발생하며 fallback 로직과 함께 제거될 예정이기 때문이다. 또한
  인덱스를 운영 환경에 반영하려면 레거시 마이그레이션도 함께 수정해야 한다. PostgreSQL은 FK 컬럼에 대해
  인덱스를 자동으로 생성하지 않으므로, 향후 계정 연결 API를 추가해 한 사용자의 전체 로그인 수단을
  조회하게 되는 시점에 인덱스 추가 여부를 함께 검토한다.
- `user` 모듈의 책임이 프로필 관리에서 로그인 수단 관리까지 확장된다. `docs/design/module-map.md`의 `user`
  항목과 `findOrCreate` public API 설명을 MOM-0830에서 함께 갱신한다.
- 프로필 화면에 이메일을 표시할지와, 표시한다면 수정할 수 없는 값으로 제공할지는 기획과 논의한다.
