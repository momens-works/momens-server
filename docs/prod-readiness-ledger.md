# prod 운영 준비 대장

이 대장은 prod 배포 전에 함께 확인해야 하는 필수 설정과 파일 밖 운영 의무를 한곳에 모읍니다.

- **선언 구간**: prod 필수 환경변수의 주입 위치와 반영 상태를 사람이 관리합니다. CI가
  `application.yml`과 `application-prod.yml`의 기본값 없는 placeholder와 정확히 일치하는지 검사하고,
  `required` 상태가 남은 릴리스를 차단합니다.
- **수기 구간**: 코드로 감지할 수 없는 외부 등록물과 배포 순서 의무를 사람이 확인합니다.

## 스키마 구간은 없습니다

prod 스키마 주도권이 이 서버로 넘어오면서([ADR-0019](adr/0019-prod-schema-ownership-transfer.md))
이 문서가 추적하던 **스키마 반영 구간을 폐지했습니다.** 반영을 기다릴 다른 저장소가 없어졌기
때문입니다. 이제 마이그레이션은 배포 때 이 서버의 Flyway가 직접 적용하고, 잘못된 마이그레이션은
그 시점에 Flyway가 막습니다. dev가 이미 그렇게 동작합니다.

마이그레이션 첫 줄의 `-- prod-schema:` 헤더도 함께 폐지했습니다. 기존 파일에 남아 있는 헤더는
Flyway checksum 때문에 지우지 않고 역사적 주석으로 둡니다. **새 마이그레이션에는 달지 않습니다.**

완료된 부트스트랩 경위·리허설·적용 결과는 MOM-0908·0909와 관련 PR에 보존합니다.
과거 절차를 재실행하지 않으며, 변경 전 원문은 MOM-1057에 고정 커밋 링크로 남겼습니다.

## prod 필수 설정 선언

공통 설정과 prod 프로필을 함께 적용했을 때 기본값이 없는 `${VAR}`만 강제합니다. local/dev 전용
설정은 prod provisioning 의무가 아니므로 이 선언과 스캔 대상에서 제외합니다.

상태는 `required`(prod 반영 필요, 릴리스 차단) 또는 `applied`(prod 반영 완료)입니다. 실제 값이나
secret 이름은 기록하지 않습니다. `applied`는 운영 환경 반영을 확인한 사람이 갱신하는 선언이며,
CI가 실제 Secret·ConfigMap의 존재나 값을 조회해 증명하지는 않습니다.

<!-- BEGIN DECLARATION: prod-required-config -->
| 환경변수 | 주입 위치 | prod 상태 |
| --- | --- | --- |
| `DATABASE_PASSWORD` | `secret` | `applied` |
| `DATABASE_URL` | `secret` | `applied` |
| `DATABASE_USERNAME` | `secret` | `applied` |
| `MOMENS_AUTH_GOOGLE_AUDIENCES` | `secret` | `applied` |
| `MOMENS_AUTH_GOOGLE_CLIENT_ID` | `secret` | `applied` |
| `MOMENS_AUTH_GOOGLE_CLIENT_SECRET` | `secret` | `applied` |
| `MOMENS_AUTH_GOOGLE_REDIRECT_URI` | `configmap` | `applied` |
| `MOMENS_AUTH_JWT_SECRET` | `secret` | `applied` |
| `MOMENS_AUTH_WEB_FAILURE_REDIRECT_URI` | `configmap` | `applied` |
| `MOMENS_AUTH_WEB_SUCCESS_REDIRECT_URI` | `configmap` | `applied` |
<!-- END DECLARATION: prod-required-config -->

## 수기 prod 의무

이 구간은 자동 생성하거나 릴리스 게이트로 차단하지 않습니다. 코드에서 감지할 수 없거나, 차단하면
역방향 배포 순서가 교착되는 의무를 기록합니다. 릴리스 전에 상태와 근거를 사람이 다시 확인합니다.

| 의무 | 현재 상태 | 확인 근거·다음 행동 |
| --- | --- | --- |
| MCP OAuth consent 연결 (`MOM-0989`, 전환 `MOM-0992`) | `required` | MCP 전환 전에 `MOMENS_MCP_CONSENT_URI`에 환경별 FE `/oauth/authorize` 주소를 주입하고 interaction API의 FE 라우팅·ingress를 신규 서버로 전환합니다. 미설정 시 authorize는 `server_error`를 반환합니다. |
| MOM-0836 `users.email` UNIQUE 제거 | `required` | 서버 코드를 먼저 배포한 뒤 제약을 제거합니다. 선행 배포는 `MOM-0914`에서 `ON CONFLICT (email)`을 제거하는 작업입니다. 이후 웹 로그인 요청이 신규 서버로 전환되어 `momens-server`가 `users`에 쓰는 유일한 서버가 되고, `MOM-0908`에서 마이그레이션 소유 레포지토리가 정해지면 `MOM-0836`에서 제약을 제거합니다. 선행 배포와 제약 제거가 모두 완료되면 해당 행을 갱신합니다 |
| 두 서버 JWT 서명 키 동일성 (`MOM-0873`) | `확인 완료` | 별도 dev 배포 환경은 운영하지 않아 비교 대상이 없습니다. prod는 2026-09-10에 `k8s` 저장소 `production` Environment의 `MOMENS_API_JWT_SECRET`과 `MOMENS_SERVER_AUTH_JWT_SECRET`을 동일한 새 값으로 갱신하고 두 서버를 재기동했습니다. 재기동 뒤 레거시 로그인으로 발급된 `session_token`만 사용해 신규 `GET /api/workspaces`가 200을 반환하는 것을 확인했습니다. 실제 값과 해시는 기록하지 않습니다 |
| Google OAuth redirect URI 등록 | `확인 완료` | 2026-09-15에 prod web OAuth client에 신규 callback `https://api.momens.works/api/auth/google/callback`을 레거시 callback `https://api.momens.works/auth/google/callback`과 병행 등록했습니다. 등록 전에는 신규 로그인이 `redirect_uri_mismatch`로 실패했고, 등록 뒤 Google 로그인 화면까지 정상 진입합니다. 레거시 callback은 1단계 rollback 경로이므로 지우지 않습니다 |
| 웹 client ID·secret 일치 | `확인 완료` | 2026-09-15에 깨끗한 브라우저 프로필에서 prod 신규 웹 로그인 → callback → 쿠키 발급을 끝까지 통과했습니다. `access_token`(`Path=/`)과 `refresh_token`(`Path=/api/auth`)이 발급됐고, `app.momens.works`가 그 쿠키로 정상 로드됐습니다. 웹 callback은 `MOMENS_AUTH_GOOGLE_CLIENT_ID`·`MOMENS_AUTH_GOOGLE_CLIENT_SECRET`으로 code를 교환하고 사용자 정보를 조회할 뿐 audiences를 쓰지 않으므로, 이 확인은 audiences 값을 보증하지 않습니다 |
| 모바일 client ID와 audiences 일치 | `확인 필요` | `MOMENS_AUTH_GOOGLE_AUDIENCES`는 모바일 로그인(`POST /api/auth/google/token`)에서 앱이 보낸 Google ID 토큰의 `aud`를 검증할 때만 쓰입니다(`GoogleIdTokenVerifier`). 앱 client ID가 목록에 있는지 모바일 prod 로그인으로 따로 확인합니다 |
| worker Signal 생산 전 스키마 검토 (`MOM-0916`) | `required` | 작업은 backlog입니다. 생산 시작 전에 UUID 생성 방식과 FK·삭제 정책을 [worker Signal 생산 전 확인](#worker-signal-생산-전-확인)에 따라 검토하고 작업을 닫습니다. |
| FCM 프로젝트·ADC 자격증명 | `비활성` | push 기본값은 꺼져 있습니다. 활성화할 때 [FCM 활성화 전 확인](#fcm-활성화-전-확인)의 프로젝트·권한·인증 연결을 검증합니다 |
| Minsu GCP 프로젝트·리전·ADC | `비활성` | task draft와 비동기 enroll/drain 기본값은 꺼져 있습니다. 활성화 조건은 [Minsu 활성화와 복구](#minsu-활성화와-복구)를 따릅니다 |
| source provider redirect URI 등록 | `확인 필요` | GitHub, Slack, Notion, Figma의 관리 화면에 `https://api.momens.works/api/source-connections/oauth/callback`을 등록해야 합니다. 신규 경로에는 레거시 경로에 없는 `/api` 접두사가 포함되어 있어 서로 다른 주소입니다. 기존 주소를 삭제하지 않고 신규 주소를 추가하면 redirect URI 설정만 변경해 레거시 경로로 되돌릴 수 있습니다. 등록 여부는 레포지토리에서 확인할 수 없습니다. |
| 초대 이메일 발송 설정 | `비활성` | 이메일 발송은 기본적으로 비활성화되어 있어 초대를 생성해도 이메일이 발송되지 않습니다. 활성화하려면 `EMAIL_PROVIDER=resend`와 함께 `RESEND_API_KEY`, `EMAIL_FROM`, `EMAIL_REPLY_TO`, `APP_INVITE_URL`을 모두 주입해야 합니다. 필수 설정 중 하나라도 비어 있으면 서버 기동에 실패합니다 |
| source provider 자격 증명과 토큰 키 주입 | `비활성` | 관련 설정의 기본값이 비어 있어 현재는 어떤 provider도 설정되지 않은 상태입니다. 이 상태에서는 source 연결을 시작할 수 없지만 서버 기동에는 영향을 주지 않습니다. 활성화하려면 provider 네 곳의 Client ID와 secret, `MOMENS_SOURCE_OAUTH_REDIRECT_URI`, `MOMENS_SOURCE_OAUTH_SUCCESS_REDIRECT_URI`, `MOMENS_SOURCE_OAUTH_TOKEN_KEY`를 주입해야 합니다. success redirect URI가 비어 있으면 연결 완료 뒤 FE로 돌아가지 않고 callback JSON을 응답합니다. 토큰 키는 base64로 디코딩했을 때 32바이트여야 하며, `momens-worker`에서 사용하는 값과 같아야 합니다. |
| Figma webhook 설정 (`MOM-1016`, 운영 배선 `MOM-0954`) | `확인 필요` | `MOMENS_SOURCE_FIGMA_WEBHOOK_ENDPOINT`에 기존 worker 수신 URL, `MOMENS_SOURCE_FIGMA_WEBHOOK_PASSCODE`에 worker와 동일한 passcode를 주입합니다. 기본값이 비어 있으면 configure는 500을 반환합니다. 실제 provider 등록·PING·댓글 수신과 worker 연결 매칭은 운영 전환 전에 검증해야 합니다. |
| source OAuth state 서명 비밀 | `비활성` | 전환이 완료될 때까지 레거시의 인증 JWT 시크릿과 같은 값을 `MOMENS_SOURCE_OAUTH_STATE_SECRET`에 주입해야 합니다. 값이 다르면 한쪽 서버가 발급한 state를 다른 서버가 검증하지 못해 전환 중 source 연결이 실패합니다. 전환이 끝난 뒤에는 이 값을 독립적으로 교체할 수 있습니다. |
| DNS·ingress·TLS 인증서 | `응답 확인` | 2026-08-13에 `https://api.momens.works/api/health`의 TLS 응답(HTTP 401)을 확인했습니다. 애플리케이션 readiness를 뜻하지는 않습니다 |

## DB 접속·권한 변경 시 확인

다음은 MOM-0909에서 확인해 계속 필요한 운영 조건입니다. 당시 실행 목록을 현재 객체 목록으로
간주하지 않고 변경 대상의 실제 role·소유자·ACL을 확인합니다.

- 당시 prod의 IPv4 GKE 노드는 IPv6 direct endpoint에 도달하지 못해 session pooler 5432로 전환했습니다.
  접속 정보 교체 시 transaction pooler 6543과 혼동하지 않습니다. Flyway의 세션 잠금과 init SQL을
  사용할 수 있는 접속인지 확인합니다.
- 확장이 `extensions`에 있는 환경에서는 runtime role의 `USAGE`와 `search_path`를 확인합니다.
  local의 public 확장 배치만으로 prod를 검증하지 않습니다.
- 부트스트랩으로 만든 `flyway_schema_history`는 postgres 소유이고 runtime에는 DML을 부여했습니다.
  Flyway 업그레이드가 이력 테이블 ALTER를 요구하면 DML만으로 충분하다고 가정하지 않습니다.
- 소유권을 되돌릴 때 이전 runtime의 DML이 유지된다고 가정하지 않습니다. 소유권 변경 **후** 필요한
  테이블 DML·시퀀스 권한을 재부여하고 실제 읽기/쓰기를 검증합니다. `ddl-auto=validate`는 이를 검사하지 않습니다.
- 새 공유 테이블·시퀀스의 권한은 [영속성 규칙](rules/persistence.md)을 따릅니다. 기존 누락 판단과
  보완은 MOM-0936, migration Job 분리는 MOM-0917이 소유합니다.
  문서 이관으로 이 작업들이 해결된 것은 아닙니다.
- **MOM-0925는 `todo / urgent`인 익명 Data API 접근 차단 작업입니다.** 티켓의 2026-08-25 실측은
  공개 anon 키와 과도한 권한·RLS 미적용으로 서버 인증·워크스페이스 인가를 우회해 데이터를
  읽고 변경할 수 있음을 기록했습니다. 이번 문서 검토에서는 현재 운영 환경의 노출 여부를 재검증하지
  않았습니다. 소비자·사내 도구 영향을 확인하고 익명 접근을 차단한 뒤, 공개 anon 키로 `/rest/v1/*`에서
  데이터가 반환되지 않으며 서버 경유 제품 기능에 영향이 없는지 검증해야 합니다. 이 차단·검증은 미완료입니다.
- rollout 실패를 자동 undo로 간주하지 않습니다. 배포 도구 동작을 확인하고 필요 시 운영자가
  이전 deployment 또는 설정으로 복구합니다. 적용된 migration 파일과 checksum은 수정하지 않습니다.

## worker Signal 생산 전 확인

**MOM-0916은 미완료이며 worker가 Signal 생산을 시작하기 전에 닫아야 합니다.**
과거의 “생산자가 없어 무해하다”는 조사 결과를 현재 활성화 근거로 사용하지 않습니다.

- 먼저 worker가 `signals.id`를 직접 생성하는지 확인합니다. 서버의 `create_signals` migration은
  `id UUID PRIMARY KEY`에 기본값을 두지 않으므로 worker가 DB UUID 기본값을 기대하면 INSERT가 실패합니다.
- `signals`·`outbox_events`의 FK 부재에 따른 참조 무결성과 삭제 정책을 재검토합니다.
  workspace 삭제가 hard/soft delete 중 무엇인지 확인하고, 자동 cascade가 없을 때 관련 행의
  정합성을 누가 유지할지 worker 생산 경로와 대조합니다.
- FK나 ID 기본값 보완이 필요하면 새 migration으로 처리합니다. 적용된 SQL은 수정하지 않으며,
  모듈 경계와 충돌하면 ADR-0007 관점에서 재판단합니다. 이번 문서 보완은 이 결정을 확정하지 않습니다.

## Signal push 운영

### FCM 활성화 전 확인

아래 조건을 대상 환경에서 확인한 뒤 push를 활성화합니다. 과거 데모의 프로젝트·서비스 계정·클러스터
값을 현재 값으로 간주하지 않습니다.

- `MOMENS_NOTIFICATION_PUSH_FIREBASE_PROJECT_ID`의 대상 Firebase 프로젝트와 Android 앱의
  applicationId·Firebase 앱 등록·`google-services.json`이 같은 프로젝트를 가리키는지 확인합니다.
- ADC가 사용하는 서버 서비스 계정을 확인하고, 그 계정에 **대상 Firebase 프로젝트의 FCM 전송 권한**이
  있는지 검증합니다. ADC 발급 프로젝트와 전송 대상 프로젝트가 다를 수 있으며, 인증 성공만으로
  대상 프로젝트의 전송 권한을 보장하지 않습니다.
- GKE에서는 실제 Pod의 Kubernetes ServiceAccount와 서버 서비스 계정 사이의 Workload Identity
  연결을 확인합니다. 해당 Pod에서 ADC를 얻는지 확인하고 명시적 대상 project ID로 SDK를 초기화합니다.
- 로컬에서도 실행 프로세스가 사용하는 ADC의 주체와 대상 프로젝트 전송 권한을 확인합니다.
  credential·서비스 계정 키·FCM token은 저장소에 기록하지 않습니다. 실제 Android 설치로 전송을
  검증하고, 과거 dev의 키 생성 금지 정책과 인증 방식이 현재 환경에도 적용되는지 확인합니다.

### 소비와 복구

앱 메시지·설치 계약은 [모바일 명세](spec/mobile-api.md#push-설치)를 따릅니다.

- 소비는 1초 polling, id 오름차순이며 DB 시계 기준 안전 지연 2초를 지나지 않은 첫 이벤트에서
  멈춥니다(prefix-cap). 최초 watermark도 같은 prefix 끝으로 시드하므로 과거 일괄 push는 하지 않습니다.
  BIGSERIAL 순서와 commit 순서가 다르고 NOW()는 트랜잭션 시작 시각입니다. 생산자 트랜잭션이
  안전 지연보다 오래 열리면 앞선 미커밋 이벤트 누락을 막는 보장 밖입니다. worker 전환 시에도 확인합니다.
- consumer state를 잠그고 `signal.created`만 소비하며 issued_by로 필터링하지 않습니다.
  Signal·Project hydrate와 소비 시점 수신자 결정 후 기기별 pending 생성·watermark 전진을
  원자 commit합니다. `(outbox_event_id, installation_id)`로 replay를 dedup합니다.
  outbox row에 처리 상태를 기록하지 않습니다.
- commit 뒤 발송을 트리거하며 놓친 pending은 주기 스캔으로 회수합니다. DB에서 SKIP LOCKED로
  claim하고 attempt·새 token·30초 lease를 commit한 뒤 FCM을 호출합니다.
  next_attempt_at은 claim 중 lease 만료, 실패 후 다음 재시도 시각입니다.
  event별 발송 직전 현재 token 소유자의 lease를 갱신하며 소유권을 잃으면 보내지 않습니다.
  결과·취소도 token 일치 조건으로 기록합니다. 중단된 claim은 lease 만료 후 회수합니다.
- 최초 발송 목표는 생성 API 성공 후 2~4초이며 일시 실패는 결과 기록 DB 시각부터 1/5/30초로
  최대 3회 재시도합니다(최초 포함 4회). 기기별 실패는 독립적이며 최종 실패는 failed와 오류 분류를
  남기고 별도 DLQ는 없습니다. FCM multicast는 최대 500 token 단위입니다.
- 무효·만료 token은 재시도하지 않습니다. 실패한 token이 현재 token과 같을 때만 설치를 비활성화해
  갱신된 token을 닫지 않습니다. delivery는 token 복제 대신 설치를 참조하며 매 시도 현재 사용자와
  활성 상태를 확인합니다. FCM 성공 후 상태 기록 전 장애는 중복 전달 가능성이 남습니다.
- FID/token 등록·해제는 없는 행의 경합도 advisory lock으로 직렬화합니다. 제약 위반의 DB 원인 예외에
  token이 포함될 수 있으므로 그대로 로그에 넘기지 않습니다. token·credential·notification 원문을
  로그에 남기지 않습니다.

## Minsu 활성화와 복구

공개 경계·원자성은 [모듈 맵](design/module-map.md#minsu-생성반영-경계), 응답 의미는
[모바일 명세](spec/mobile-api.md), 지표는 [인벤토리](design/metrics-inventory.md#minsu)를 따릅니다.

### 실행 한계와 복구

- claim마다 새 token과 DB 시간 기준 lease를 저장한다. 결과 기록·재시도 예약·terminal 전이는
  token이 일치해야 한다. 만료 뒤 재claim한 다른 실행이 있으면 이전 결과는 stale로 버린다.
- 모델 호출은 DB 트랜잭션 밖에서 한다. lease 만료·재시작 때문에 중복 **실행**은 가능하다.
  token으로 직렬화하는 결과 기록과 모델 호출 횟수를 혼동하지 않는다.
- convert 커밋 후 종료되면 pending을, claim 후 종료되면 만료 lease를 회수한다.
  반영 트랜잭션 도중 종료되면 미커밋 변경을 롤백하고 lease 만료 후 다시 처리한다.
- 동기 SDK timeout은 8초이며 총 시도는 1회다. SDK call timeout은 ADC 탐색·client 생성·token
  refresh 전체를 덮지 않으므로 실제 provider 관측과 요청 점유는 이를 넘을 수 있다.
- 비동기는 SDK timeout과 별도로 ADC부터 provider까지 attempt 전체의 wall-clock 상한과 실행 동시성
  상한을 둔다. timeout wrapper는 대기를 끝내고 결과를 버릴 뿐 interrupt를 무시하는 호출을
  강제 종료하지 못한다. hung 호출이 모든 슬롯을 소진하면 관측 후 Pod 재시작으로 복구한다.
  원장은 남으므로 pending·만료 lease를 이어 처리한다.

| 내부 결과 | 처리 |
| --- | --- |
| generated / generated_title_fallback / generated_truncated | 성공 반영 |
| timeout / provider_error / invalid_response / invalid_output | 제한 재시도, 상한이면 retry_exhausted |
| insufficient_context | terminal |
| invalid_config | claim 이후 무효화 경합에서 terminal. claim 전 무효면 claim하지 않음 |
| disabled | 신규 적재 없음. 기존 원장은 claim하지 않고 보존 |

`provider_error`는 일시 오류와 ADC/IAM/project/quota 문제를 세분화하지 않고 모두 제한 재시도한다.
설정을 고친 뒤 terminal 과거 건을 소급 생성하는 동작은 없다.

설정의 정본은 [MinsuAsyncProperties](../modules/minsu/src/main/java/works/momens/server/minsu/draft/config/MinsuAsyncProperties.java)와
[application.yml](../app/src/main/resources/application.yml)이다. provider 20초·attempt 30초·lease 60초,
백오프 10/30/120초·최대 4회·동시성 4, deadline 1시간·margin 5분은 **MOM-0824 실측 전 잠정값**이다.
동기 8초의 실측을 비동기 운영값 확정 근거로 사용하지 않는다.

```text
attempt timeout < lease < 적재부터 apply cutoff까지의 시간 < 적재부터 read deadline까지의 시간
```

lease는 갱신하지 않는다. drain은 배치 결과 기록까지 기다리는 fixed-delay 패스이므로 주기는 실제
claim 간격이 아니다. 빈 슬롯이 생겨도 다음 패스까지 기다리며 가장 느린 attempt가 배치를 지연시킨다.
스케줄링 인프라는 app이 소유하고, drain·push·지표·원장 종료가 서로 막히지 않도록 공통 scheduler
설정도 함께 확인한다.

### 활성화와 rollback

| 설정 축 | 끄면 |
| --- | --- |
| enroll | 신규 convert는 동기 경로. 기존 원장은 유지 |
| drain | 생성 실행 중단. 읽기 deadline과 만료 원장 종료는 유지 |
| provider | 신규 적재 없음. 기존 원장도 claim하지 않음 |

provider 설정이 무효하면 비동기 적재는 하되 claim하지 않는다. 기존 작업을 실패로 닫지 않고 설정
복구 후 처리한다. 롤링 배포에서 활성 pod가 적재한 작업은 다른 활성 pod도 처리할 수 있다.
설정 rollback 후 기존 pending은 기본적으로 계속 처리하며, 별도 중단이 필요할 때 drain을 끈다.
원장/조회 기능이 없는 이전 바이너리로 rollback하면 `draft_status` 필드 자체가 없는 이전 계약이다.
활성화 이전 task에는 원장이 없으며 소급 생성하지 않는다.

prod 활성화 전에는 다음을 확인한다. 이번 문서 정리가 완료를 의미하지 않는다.

- dev 재측정으로 timeout·lease·deadline·동시성 검증: **MOM-0824**.
- `task.draft_generated` worker 소비 배포와 E2E projection: **MOM-0823**.
- `draft_status`를 처리하는 앱 배포.
- 데이터 레지던시 정책: model/location catalog는 기술적 호출 가능 목록이지 보안·법무 허용 목록이
  아니다. location 미지정 시 기본 global이 적용되므로 prod 허용 지역과 명시 강제 여부를 결정하기
  전에는 활성화하지 않는다.
- snapshot 보존 기간·정리 주체: **MOM-0912**. 이미 결정된 무절단 저장과 구분한다.

### 만료 원장과 수동 종료

반영 창을 지난 pending과 lease가 만료된 processing은 주기 작업이 `deadline_exceeded`로 닫는다.
살아 있는 processing lease는 결과 기록 경로가 처리하므로 먼저 닫지 않는다. 이 정리는 drain
설정과 독립적이며 행 잠금·엔티티 변경으로 감사 시각과 종료 지표를 남긴다.

반영 창 안의 작업까지 즉시 종료해야 할 때만 아래 수동 절차를 사용한다.
**drain을 먼저 끄고 대상을 확인한다. 종료 후 되살리지 않는다.**

```sql
-- 1. 대상 확인
SELECT status, count(*), min(created_at) AS oldest
  FROM minsu_task_draft_generations
 WHERE status <> 'completed'
 GROUP BY status;

-- 2. claim CHECK와 감사 시각을 함께 갱신
UPDATE minsu_task_draft_generations
   SET status = 'completed',
       completion_reason = 'operationally_closed',
       claim_token = NULL,
       lease_expires_at = NULL,
       updated_at = NOW()
 WHERE status <> 'completed';
```

설정 오류와 구분하기 위해 `invalid_config`를 재사용하지 않는다. 이미 claim한 호출의 늦은 결과는
token 불일치로 버려진다. 수동 SQL은 JPA Auditing을 거치지 않으므로 `updated_at`을 직접 갱신한다.

### Deadline과 설정 변경

apply_cutoff_at은 적재 시각 + deadline - margin, read_deadline_at은 적재 시각 + deadline으로 저장합니다.
과거 원장에 설정 변경을 소급하지 않습니다. scheduler 정지 중에도 읽기 deadline은 작동하지만,
NOW()가 트랜잭션 시작 시각이므로 cutoff 전에 평가된 트랜잭션의 commit이 margin보다 지연되면
ready 응답 뒤 title이 바뀔 수 있습니다. margin은 이 창을 줄이며 절대 보장은 아닙니다.

설정 오류의 처리는 검증 범위에 따라 다릅니다. 값을 기본값으로 clamp하지 않습니다.

- `momens.minsu.llm`의 바인딩이 성공한 뒤 [LlmConfigStatus](../modules/minsu/src/main/java/works/momens/server/minsu/llm/LlmConfigStatus.java)가
  판정하는 의미 설정 오류(provider/model 지원 여부, Google project/location, 동기 timeout 범위)는
  기동을 차단하지 않습니다. 동기 요청은 fallback을 사용하고, 비동기 원장은 적재하되 claim하지 않습니다.
  이는 provider·enroll이 활성인 경우이며 비활성이면 신규 적재하지 않습니다.
- `momens.minsu.task-draft.async`의 실행 설정은 [MinsuAsyncProperties](../modules/minsu/src/main/java/works/momens/server/minsu/draft/config/MinsuAsyncProperties.java)가
  기동 시 검증합니다. `provider-timeout < attempt-timeout < lease < generation-deadline - apply-margin`,
  `0 < apply-margin < generation-deadline` 등의 불변식과 timeout 범위·재시도·동시성 조건을 위반하면
  **기동에 실패합니다.** 예를 들어 attempt-timeout과 lease가 모두 60초이거나 apply-margin이 0이면
  fallback으로 계속 동작하지 않습니다. 이 검증은 enroll/drain 비활성 여부와 무관합니다.
- Duration 등 설정값의 바인딩 실패도 위 LLM 의미 검증의 fallback 대상이 아닙니다.

provider 비활성·LLM 의미 설정 무효·입력 부족이면 provider client를 생성하지 않습니다. Google client는 첫 성공만
캐시하고 ADC/client 생성 실패는 다음 호출에서 재시도하며 성공 client는 context 종료 시 닫습니다.

`task.created`가 먼저 fallback을 투영할 수 있으므로 `task.draft_generated` 소비를 worker에 먼저
배포하고 E2E projection을 검증합니다(MOM-0823). 미지원 이벤트에서 watermark를 전진시키지 않는
구현을 검증했거나 명시적 cutover/backfill이 있는 경우만 대체할 수 있습니다. worker 지원 전에
producer를 활성화하면 건너뛴 이벤트가 나중에 자동 재소비되지 않습니다.

### 적용된 migration의 과거 참조

적용된 SQL 주석의 옛 설계 파일명·절 번호는 checksum 보존을 위해 수정하지 않습니다.
판단 배경과 삭제 전 원문 링크는 [MOM-1057 PR #322 본문](https://github.com/momens-works/momens-server/pull/322)에
보존했습니다. 과거 절차를 현재 운영 지침으로 재실행하지 않습니다.
