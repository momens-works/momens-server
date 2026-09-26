# 로컬 개발

이 문서는 로컬에서 `momens-server`를 실행하고 검증하는 방법을 정리합니다.

## 필요 도구

- JDK 21
- Git
- Docker
- 이 레포의 Gradle wrapper

전역 Gradle 대신 레포에 포함된 Gradle wrapper를 사용합니다.

## 기본 명령어

```bash
./gradlew test
./gradlew bootJar
./gradlew bootRun --args='--spring.profiles.active=local'
```

## 로컬 설정

로컬 개발에는 `.env`와 `application-local.yml`을 사용합니다.
`.env`는 커밋하지 않고, 필요한 키는 `.env.example`에 기록합니다.

초기 profile:

```text
application.yml
application-local.yml
application-test.yml
application-prod.yml
```

예상 설정 그룹:

- `server.port`
- `spring.datasource`
- `spring.flyway`
- `management.endpoints.web.exposure`
- `momens.auth`
- `momens.cors`
- `momens.retrieval`

## 민감 정보 관리

설정 파일은 **구조를 담는 파일**과 **실제 값을 담는 파일**을 분리합니다.

커밋하는 파일:

```text
application.yml
application-local.yml
application-test.yml
application-prod.yml
.env.example
```

커밋하지 않는 파일:

```text
.env
.env.*
application-secret.yml
application-*-secret.yml
```

`application-*.yml`에는 실제 secret을 넣지 않습니다.
대신 환경변수 placeholder만 둡니다.

예:

```yaml
spring:
  datasource:
    url: ${DATABASE_URL}
    username: ${DATABASE_USERNAME}
    password: ${DATABASE_PASSWORD}

momens:
  auth:
    jwt-secret: ${JWT_SECRET}
```

실제 값은 로컬에서는 `.env`, CI에서는 GitHub Actions Secrets, 운영에서는
Kubernetes Secret 또는 이후 도입할 External Secrets를 통해 주입합니다.

개인 DM, 개인 메모, private Git submodule, 별도 private repo로 secret을
전달하거나 저장하지 않습니다.

`.env.example`에는 실제 운영 secret을 두지 않습니다. 로컬 실행을 위한 기본 예시값은
둘 수 있지만, 공유가 필요한 민감 값은 별도 secret 관리 채널을 사용합니다.

## DB

PostgreSQL을 사용합니다.
로컬 개발용 `docker-compose.yml`은 `pgvector/pgvector` 이미지를 사용합니다.

테스트에서는 PostgreSQL Testcontainers를 사용합니다.
H2 호환성을 가정하지 않습니다.

### 마이그레이션 checksum 불일치

Flyway checksum은 주석을 포함한 파일 내용 전체로 계산합니다. 이미 적용된 마이그레이션 파일이
바뀌면(MOM-0839의 `prod-schema` 헤더 추가처럼) 다음 기동에서 checksum 불일치로 실패합니다.
로컬 DB는 `docker-compose.yml`의 `momens-postgres-data` 볼륨에 남아 있으므로 컨테이너를 다시
띄우는 것만으로는 해소되지 않습니다.

```bash
docker compose down -v && docker compose up -d
```

테스트는 Testcontainers로 매번 새 DB를 쓰므로 영향을 받지 않습니다. `dev`와 `prod`는 Flyway가
켜져 있어 같은 문제를 만나는데, DB를 다시 만들 수 없는 `prod`에서는 **이미 적용된 파일을 고치는
순간 다음 배포의 기동이 실패합니다.** 적용된 마이그레이션은 고치지 말고 새 파일을 추가합니다
([데이터 규칙](rules/persistence.md)).

## 레거시 차등 비교

[이관 전략](design/legacy-product-api-migration/strategy.md)의 characterization은 같은 요청을 레거시
`momens-api`와 신규 서버에 적용해 결과를 비교하도록 정합니다. 그 비교를 실행하는 하네스가
`scripts/legacy-diff/`입니다(`MOM-0877`).

```bash
./scripts/legacy-diff/run.sh                    # 전체 실행 후 정리
KEEP=1 ./scripts/legacy-diff/run.sh             # 스택을 남겨 반복 실행
./scripts/legacy-diff/run.sh --only H020-member # 한 케이스만
```

레거시 저장소 위치는 `MOMENS_API_DIR`로 지정합니다. 기본값은 `<repo>/../momens-api`입니다.
worktree에서 실행하면 기본값이 맞지 않으므로 명시해야 합니다.

동작은 다음과 같습니다.

1. `momens-api`의 자체 `Dockerfile`로 레거시 이미지를 빌드합니다(최초 1회, 이후 재사용).
2. PostgreSQL 두 개와 레거시 서버를 띄웁니다. DB를 나누는 이유는 두 서버가 `users`,
   `workspaces`, `workspace_members`의 DDL을 각자 소유해 한 DB에서 두 마이그레이션을 함께
   돌릴 수 없기 때문입니다.
3. 신규 서버를 `bootJar` 결과로 띄워 Flyway가 스키마를 만들게 합니다.
4. `fixture.sql`을 양쪽 DB에 같은 내용으로 적용합니다. id와 시각을 고정하므로 응답 값이 문자
   그대로 같아야 합니다.
5. `cases.tsv`의 각 행을 두 서버에 보내고 status와 body를 대조합니다. write 케이스는 요청 이후의
   DB 기록도 함께 대조합니다.
6. 그 결과를 케이스별 golden과 비교해 판정합니다.

`KEEP=1`은 컨테이너와 신규 서버를 모두 남깁니다. 남은 스택에는 `diff.sh`를 직접 호출해 Gradle
빌드와 부팅을 건너뛸 수 있습니다.

```bash
KEEP=1 ./scripts/legacy-diff/run.sh
./scripts/legacy-diff/diff.sh --local-stack --only H024-noop   # 반복 검증
```

남긴 서버는 다음 `run.sh` 전에 정리해야 합니다. 포트를 쥐고 있으면 새로 띄운 서버가 바인딩에
실패하고, 헬스 체크는 살아있는 옛 서버가 응답해 통과해버립니다. `run.sh`가 프로세스 생존을 함께
확인해 이 경우 멈추지만, 종료 안내에 나오는 `kill` 명령을 먼저 실행하는 편이 빠릅니다.

한 토큰으로 양쪽을 호출할 수 있는 것은 [ADR-0017](adr/0017-transitional-legacy-session-token-acceptance.md)이
정한 `session_token` 수용과 두 서버의 동일한 HS256 서명 키 덕분입니다. 이 전제가 깨지면
하네스도 함께 깨집니다.

### 케이스 추가

슬라이스마다 `cases.tsv`에 행을 추가합니다. 열은 `id`, `as`(픽스처 사용자 키), `method`,
레거시 path, 신규 path, `ignore`, `scrub`입니다. 필요한 데이터가 없으면 `fixture.sql`에 고정
id로 추가합니다. 픽스처는 그 데이터를 필요로 하는 슬라이스가 채웁니다.

행을 추가한 뒤에는 golden을 만들어야 합니다. 아래 "golden 갱신"을 참고하세요.

`ignore`는 응답 body에서 빼고 볼 필드를 쉼표로 나열합니다. 없으면 `-`입니다. **중첩 객체와 배열
안까지 모든 깊이에서 같은 이름의 키를 제거하므로**, `name`처럼 흔한 키를 적으면 의도보다 넓게
지워집니다.

### write 케이스 추가

write 케이스는 부속 파일을 `cases/<id>/` 아래 규약으로 둡니다(`MOM-0882`). 경로를 `cases.tsv`에
적지 않는 것은 규약으로 대체 가능한 정보를 중복 기재하지 않기 위해서입니다.

| 파일 | 역할 |
| --- | --- |
| `cases/<id>/body.json` | 요청 body |
| `cases/<id>/check.sql` | 요청 이후 양쪽 DB에 그대로 실행할 조회 |

`check.sql`은 응답만 같고 기록이 다른 경우를 잡습니다. 두 DB는 스키마가 완전히 같지 않으므로
(예: `workspace_members`는 레거시에만 `onboarding_state`가 있습니다) `SELECT *`를 쓰지 말고
공통 컬럼만 나열하고, 정렬을 고정합니다. 생성 계열은 양쪽이 각자 UUID를 만들어 id가 원리적으로
다르므로 `id`를 빼고 부모 키로 필터합니다.

**비교하지 않을 컬럼은 `SELECT` 목록에서 빼면 됩니다.** 그래서 DB 쪽에는 `ignore` 같은 허용
목록이 없습니다. 무엇을 볼지가 곧 `check.sql`이므로 검증 범위가 PR diff에 그대로 남습니다.

벽시계처럼 값이 매번 달라지는 컬럼도 `SELECT`에서 뺄 필요는 없습니다. 아래 "비결정적 값 — scrub"이
값을 자리표시자로 바꿔주므로, 넣어두면 "바뀌었는가"까지 검증됩니다. H024가 그 예입니다.

- `H024-rename`은 양쪽 모두 `updated_at`을 갱신합니다. scrub 덕분에 양쪽 다 `Time_1, Time_2`로
  수렴해 비교가 성립합니다
- `H024-noop`은 레거시만 갱신해 `Time_1, Time_2` 대 `Time_1, Time_1`로 갈립니다. 변경할 값이 없는
  요청에도 레거시는 갱신하지만 신규 서버는 갱신하지 않는다는
  [이관 원장](design/legacy-product-api-migration/ledger.md) H024 행의 확정 사항이 그대로 드러납니다

write 케이스는 실행 직전마다 `fixture.sql`을 다시 적용합니다. `fixture.sql`이 `TRUNCATE ...
CASCADE`로 시작해 멱등하기 때문에 가능하고, 덕분에 `cases.tsv`의 행 순서에 의존하지 않습니다.
write 뒤에 오는 read 케이스도 같은 이유로 되돌린 상태에서 시작합니다.

write는 `--local-stack`에서만 실행합니다. `run.sh`가 이 플래그를 넘기고, 없으면 건너뜁니다.
dev 실서버를 가리킨 채로 돌면 실데이터를 바꾸기 때문입니다.

`--local-stack`은 `--legacy-base`/`--server-base`가 로컬이 아니면 종료합니다. 픽스처 되돌리기와
DB 비교는 compose 스택의 DB에 직접 실행하므로, base URL만 원격이면 write는 원격에 나가고 검증은
로컬 DB를 보게 됩니다. 실데이터를 바꾸면서 비교 결과까지 무의미해지는 조합입니다.

### golden 판정

판정 기준은 **golden과 다르면 실패**입니다(`MOM-0881`). "레거시와 다르면 차이"가 아닙니다.

레거시와 신규가 다르다는 사실 자체는 실패가 아닙니다. 계약 문서가 확정한 의도된 차이가 11건이나
되고 매 실행 그대로 출력되기 때문에, 그 방식으로는 12번째 차이가 새로 생겨도 사람이 눈으로
대조해야만 잡힙니다. 그래서 의도된 차이를 `cases/<id>/golden.txt`에 담아두고, 거기서 벗어난
것만 실패로 드러냅니다.

golden에는 케이스의 관찰 결과가 통째로 들어갑니다.

```
legacy_status: 200
server_status: 200
--- body ---
동일
--- db ---
@@ -1,2 +1,2 @@
 name,slug,description,created_at,updated_at
-Alpha,ws-alpha,,Time_1,Time_2      ← 레거시만 updated_at 을 갱신
+Alpha,ws-alpha,,Time_1,Time_1
```

무엇이 왜 다른지가 파일에 남으므로, 계약이 바뀌면 그 변경이 PR diff에 드러납니다.

golden에 담기는 것은 응답 원문이 아니라 **두 서버의 diff**입니다. 그래서 `동일`은 "계약대로다"가
아니라 **"레거시와 신규가 같다"**는 뜻입니다. 이 비교만으로는 실제 응답이 계약과 일치하는지 확인할 수 없습니다. 대표 엔드포인트인 `/api/me`의 실제 응답과 OpenAPI 문서는 `OpenApiActualResponseContractTest`에서 대조합니다. OpenAPI 문서의 변경 여부는 `docs/spec/openapi.json` 스냅샷으로 확인합니다. 레거시가 동결돼 있어 신규 서버가 바뀌면 diff로 드러나므로
회귀 검출에는 충분합니다.

불일치가 있으면 종료 코드가 `1`입니다.

### golden 갱신

갱신은 명시적으로만 일어납니다. 실패를 갱신으로 조용히 덮지 않기 위해서입니다.

```bash
./scripts/legacy-diff/diff.sh --local-stack --update-golden              # 전체
./scripts/legacy-diff/diff.sh --local-stack --update-golden --only H024-noop
```

golden 파일이 없으면 만들어주지 않고 실패로 보고합니다. `--update-golden`은 `--local-stack`에서만
동작합니다. 픽스처가 값을 고정한 상태를 전제하기 때문입니다.

**갱신한 golden이 계약 문서가 확정한 내용과 맞는지 PR diff에서 확인하세요.** 구현이 틀린 것을
golden으로 덮으면 그 시점부터 하네스는 틀린 동작을 기대값으로 지키게 됩니다.

### 종료 코드

| 코드 | 뜻 |
| --- | --- |
| `0` | 전부 golden과 일치 |
| `1` | golden 불일치가 있음 |
| `2` | 실행 자체가 성립하지 않음 (잘못된 옵션 조합, 매칭되지 않는 `--only`) |

종료 코드가 판정이므로 **"아무것도 검증하지 않았는데 0"** 을 막습니다. `--only`에 오타를 내면 0건이
돌고 초록으로 끝나던 것을 `exit 2`로 바꿨습니다(`MOM-0893`).

`cases.tsv`에 없는 `cases/<id>/` 디렉터리가 있으면 경고로 알립니다. 행을 지웠거나 id를 바꾼
흔적입니다. 판정을 망치는 것이 아니라 정리가 안 된 상태라서 **종료 코드는 건드리지 않습니다.**

### 비결정적 값 — scrub

벽시계나 무작위 UUID는 golden에 그대로 담을 수 없습니다. `cases.tsv`의 `scrub` 열이 값을 등장 순서
자리표시자로 바꿉니다(`time`, `uuid`).

**값은 지우되 같은 값은 같은 토큰, 다른 값은 다른 토큰**이 됩니다. 동일성 구조가 남는 것이 핵심입니다.

```
                 created_at              updated_at
레거시   2026-01-01 00:00:00+00   2026-08-19 17:01:23+00   →  Time_1, Time_2
신규     2026-01-01 00:00:00+00   2026-01-01 00:00:00+00   →  Time_1, Time_1
```

모든 타임스탬프를 하나의 자리표시자로 뭉개면 `created_at`까지 함께 치환돼 두 행이 완전히 같아지고,
이관 원장이 확정한 차이가 사라진 채 매 실행 초록으로 통과합니다. 검증하고 있다고 믿는데 아무것도
보지 않는 상태가 됩니다.

컬럼 이름이 아니라 **값의 모양**으로 찾으므로 응답 body와 DB 행에 같은 규칙이 적용되고, 컬럼명을
모르는 값도 잡힙니다.

전역이 아니라 케이스별로 켭니다. read 케이스는 픽스처가 값을 고정해 지금 문자 그대로 비교하고
있는데, 전역으로 켜면 신규 서버가 `created_at`을 엉뚱하게 돌려줘도 가려집니다.

**치환 맵은 각 측 안에서만 유효합니다.** "같은지 다른지를 본다"는 것은 한 응답 안에서
`created_at`과 `updated_at`이 같은 값인지를 본다는 뜻이지, 레거시 값과 신규 값이 같은지를 본다는
뜻이 아닙니다. scrub을 켠 필드에서는 양측 값의 일치를 포기합니다.

그래서 **꼭 필요한 케이스에만 켭니다.** 값이 바뀌지 않아야 하는 케이스에 걸면 검증이 후퇴합니다.
예를 들어 권한 부족으로 아무것도 기록하지 않아야 하는 케이스에 `scrub=time`을 걸면, 양쪽 서버가
모두 잘못 갱신했을 때 각자 `Time_1, Time_2`가 되어 동일로 통과합니다. 이런 케이스는 픽스처 값이
리터럴로 고정돼 있으므로 scrub 없이도 golden이 결정적입니다.

### dev 실서버 모드

`--normalize`는 UUID와 타임스탬프를 자리표시자로 바꿉니다. 픽스처가 값을 고정하는 로컬
모드에서는 쓰지 않고, `--legacy-base`/`--server-base`로 dev 실서버를 가리켜 shape만 비교할 때
씁니다. 이 모드에서는 write 케이스와 DB 비교, golden 판정이 모두 꺼지고 예전처럼 레거시 대 신규
diff만 출력합니다.

### 신규 필수 설정이 추가된 경우

신규 서버 부팅에 필요한 환경변수는 `scripts/legacy-diff/harness.conf`에 더미 값으로 둡니다.
기본값 없는 설정이 새로 생기면 부팅이 그 설정을 지목하며 실패하므로, 이 파일에 한 줄을
추가하면 됩니다.

## MCP OAuth 동의 화면

`MOMENS_MCP_CONSENT_URI`에 로컬 FE의 `/oauth/authorize` 절대 주소를 지정합니다.
미설정 시 authorize는 `server_error`를 반환합니다.
