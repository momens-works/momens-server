# prod 스키마 주도권 이전 설계

작성일: 2026-08-23 · 완료: 2026-09-07 · 상태: 실행 완료, 기록으로 보존 ·
근거 ADR: [ADR-0019](../adr/0019-prod-schema-ownership-transfer.md)

prod의 스키마 DDL 주도권을 레거시 `momens-api`에서 이 서버로 옮기는 설계다. 다섯 가지 결정을
확정하고, 실행 티켓(MOM-0909·MOM-0910)이 그대로 입력으로 쓸 수 있는 형태로 사실을 남긴다.

> **이전은 끝났고 실행 도구는 제거됐다(MOM-0947).** 8절이 부르는
> `scripts/prod-flyway-bootstrap*`·`scripts/prod-ownership-transfer*`와 쌍둥이 리허설 환경
> `scripts/prod-twin/`은 저장소에 없다. 전부 1회용이었고 부트스트랩이 성공한 뒤 다시 쓸 일이
> 없어 걷어냈다. 필요하면 `git log -- scripts/prod-twin` 으로 꺼낼 수 있다.
>
> **이 문서는 절차를 재실행하기 위한 것이 아니라 무엇을 왜 그렇게 했는지 남기기 위한 것이다.**
> 심기 28건과 실행 14건이 어떻게 갈렸는지, `outOfOrder`를 왜 1회만 켰는지, 접속 대상이 왜 direct
> 가 아니라 session pooler 인지가 여기에만 있다. 적용 결과는 8절 끝의
> 「부트스트랩 적용 결과」에 있다.

## 요약

| # | 결정 | 내용 |
| --- | --- | --- |
| 1 | 소유 범위 | 서버가 prod 스키마를 소유한다. 레거시 DDL은 동결한다 |
| 2 | baseline 방식 | `flyway_schema_history`에 기존 적용분을 심고 나머지만 실행한다. `outOfOrder`는 부트스트랩 1회만 켠다. **토글은 리포가 아니라 배포 ConfigMap에 둔다** |
| 3 | 레거시 정책 | 동결 대상은 **이 리포에 마이그레이션 파일이 있는 객체**로 한정한다 |
| 4 | 헤더·게이트 | `prod-schema` 헤더 4종과 스키마 릴리스 게이트를 폐지한다. **폐지 머지가 부트스트랩 릴리스보다 먼저다** |
| 5 | 롤백 | fix-forward를 기본으로 한다. 완전 원복은 이력 심기 단계까지만 보장한다 |

## 1. 배경

prod는 레거시와 공유 DB를 쓰는 전환기라 이 서버의 Flyway가 꺼져 있고 `ddl-auto: validate`로 매핑만
검증한다. 서버가 만든 스키마가 prod에 없으면 **애플리케이션이 기동하지 않는다.** 현재 미반영 16건이
남아 있어 서버를 prod에 배포할 수 없고, 릴리스 게이트가 `develop` → `main` 병합도 막고 있다.

원래 계획(MOM-0840)은 이 16건을 `momens-api` 마이그레이션으로 옮겨 심는 것이었다. 두 가지로 멈췄다.
`signals` 계열의 반영 저장소가 확정되지 않았고(근거 티켓 MOM-74가 Momens 이관 때 유실), 시도했던
`momens-api#28`은 머지되지 않고 닫혔다.

방향을 바꿔 서버가 주도권을 가져간다. [이관 전략](legacy-product-api-migration/strategy.md)은
"DDL 소유권 이전 시점은 모든 Product API 이관이 끝난 뒤 별도로 결정한다"고 적고 있었는데, 이 설계가
그 시점을 앞당긴다. 근거는 ADR-0019에 있다.

## 2. 조사로 확인한 사실

모두 코드와 실제 DB 형상에서 확인했다. 관찰 대상은 `scripts/legacy-diff`의 컨테이너 두 개다 —
`legacy-db`는 레거시 러너가 `000001`~`000019`를 적용해 만든 **prod와 같은 형상**이고, `server-db`는
이 서버의 Flyway가 만든 형상이다.

### 2.1 공유 DB의 DDL writer는 둘이다

`persistence.md`는 "공유 운영 스키마는 레거시가 단일 소유"라고 적고 있었으나 사실이 아니다(이 PR에서
고쳤다). 대장은 단일 소유를 주장하지 않고 "담당 저장소는 스키마 소유자에 따라 갈립니다"라고 적으므로
이 지적 대상이 아니다.

| 주체 | 러너 | 이력 | 소유 | 마지막 DDL |
| --- | --- | --- | --- | --- |
| `momens-api` | 자체 Go 러너 (`internal/platform/db/migrations.go`) | `schema_migrations` | 제품 스키마 `000001`~`000019` | 2026-07-12 |
| `momens-worker` | 같은 러너 | `schema_migrations` | `raw_source_events` 등 worker 전용 | 2026-06-27 |
| `momens-retrieval` | 없음 (Flyway 미사용) | — | 없음. 읽기만 한다 | — |
| `momens-server` | Flyway 12.4.0 (prod off) | `flyway_schema_history` | 부트스트랩 후 | — |

`k8s`의 ConfigMap이 `momens-api`(`configmap.yaml:29`)와 `momens-worker`(`configmap.yaml:33`) **양쪽 모두**
`MIGRATIONS_ENABLED: "true"`를 명시적으로 켠다. worker 쪽은 `ENV=production`의 기본값이 `false`인데도
자기 전용 스키마를 소유하므로 뒤집은 것이고, 그 이유가 주석에 적혀 있다.
(`k8s/docs/secrets.md`는 아직 "the API stays the single migration owner"라고 적고 있어 매니페스트와
어긋난다. 배포 리포 변경은 8절이 정리한다.)

세 서비스가 **같은 Neon DB**를 쓴다는 것은 배포 리포에 명시돼 있다. `momens-server`의
`secret.example.yaml`은 "Same database as momens-api", `momens-worker`의 것은 "shared Neon DB with api"라고
적는다.

레거시 러너(`momens-api/internal/platform/db/migrations.go`)의 동작을 확인한 결과는 다음과 같다.

- `pg_advisory_lock(7236451890217)`으로 동시 실행을 직렬화한다. api와 worker가 같은 키를 쓰므로 경합하지 않는다.
- `schema_migrations(version TEXT PRIMARY KEY, applied_at TIMESTAMPTZ)` — 체크섬 컬럼이 없다.
- 각 마이그레이션을 **자기 트랜잭션 안에서 version 행과 함께** 커밋한다. 실패하면 그 파일은 통째로 롤백된다.
- 임베드된 FS에서 `*.sql`을 glob 해 사전순으로 적용하고, version은 확장자를 뗀 파일명이다. api의
  `000001_init`과 worker의 `000001_worker_schema`가 다른 키라 한 표에서 충돌하지 않는다.
- **`flyway_schema_history`를 전혀 알지 못한다.** 우리가 심는 행을 읽지도 지우지도 않는다.

worker가 소유하는 테이블은 `raw_source_events`, `normalized_source_events`, `webhook_deliveries`,
`dead_letters`, `curation_tasks`, `backfill_runs` 6개다.

### 2.2 두 이력 테이블은 충돌하지 않는다

레거시 러너는 `schema_migrations`(TEXT version PK, 체크섬 없음), Flyway는 `flyway_schema_history`를
쓴다. 서로를 읽지 않으므로 한 DB에서 독립적으로 공존한다. `schema_migrations`의 version은 파일명
전체(`000001_init`, `000001_worker_schema`)라 api와 worker도 같은 표에서 충돌하지 않는다.

### 2.3 마이그레이션 40건의 분류

| 헤더 | 건수 | prod 실물 |
| --- | --- | --- |
| `mirror` | 23 | 있다 (레거시가 만듦) |
| `applied momens-api#10` | 1 | 있다 (`refresh_tokens`, 레거시 `000018`) |
| `required` | 16 | 없다 |

**부트스트랩에서 심어야 할 집합은 미러 23건이 아니라 24건이다.** `V20260626023000__create_refresh_token.sql`은
미러가 아니지만 prod에 실물이 있다. 빠뜨리면 Flyway가 이 파일을 실행해 `relation already exists`로 죽는다.

### 2.4 version 배치가 섞여 있다

```
V20260624090000 ~ V20260707090000   심기 (미러 8 + applied 1)
V20260707100000 ~ V20260811090000   실행 (required 16)  ※ 중간에 미러 1건(V20260715100000)
V20260819090000 ~ V20260822000600   심기 (미러 14)
```

미러 14건이 실행 대상보다 **뒤에** 있다. 24건을 심으면 이력의 최고 version이 `20260822000600`이 되고,
실행해야 할 required는 전부 그보다 낮아진다. Flyway는 기본값 `outOfOrder=false`에서 이미 적용된 최고
version보다 낮은 미적용 마이그레이션을 실행하지 않는다.

이 동작은 추론이 아니라 이 리포에서 실제로 일어나 있다. `legacy-diff`의 `server-db` 이력을 보면
`20260821100200`과 `20260821140000`은 있는데 그 사이의 `20260821110000`(workspace invitations mirror)만
없다. 그 DB가 `...140000`까지 진행한 뒤에 `...110000` 파일이 리포에 들어왔고, Flyway가 **에러 없이
`success=t`로 건너뛰었다.** 테이블만 존재하지 않는다.

미러는 계속 위쪽에서 늘고 있다(8/19 3건, 8/21 7건, 8/22 1건). 부트스트랩을 미루면 격차만 벌어진다.

### 2.5 서버가 파일을 갖지 않는 레거시 테이블이 11개 있다

```
decisions, sync_states,
oauth_access_tokens, oauth_authorization_codes, oauth_clients,
oauth_grants, oauth_interactions, oauth_refresh_tokens,
retrieval_cursors, retrieval_documents, retrieval_events
```

`oauth_*` 6개는 MCP OAuth(레거시 `000019`, 레거시의 가장 최근 DDL)이고 아직 이관 전이다(MOM-0871).
`retrieval_*`는 worker/retrieval 투영 테이블이다. 이 서버에는 엔티티도 마이그레이션도 없다.

이 목록은 `momens-api` 형상만 본 것이라 **worker 소유 6개(2.1절)가 빠져 있다.** prod 기준으로는
서버가 파일을 갖지 않는 테이블이 17개다.

### 2.5.1 required 12개 테이블은 전부 엔티티로 매핑돼 있다

`signals`, `signal_actions`, `signal_evidence`, `signal_digests`, `outbox_events`, `push_installations`,
`push_deliveries`, `notification_consumer_offsets`, `minsu_task_draft_generations`, `user_identities`,
`task_checklist_items`, `task_open_questions` — 모두 `@Table(name = ...)`을 가진 엔티티가 있다.

`ddl-auto=validate`는 매핑된 엔티티 전체를 검사하므로 **현재 `develop`을 prod에 배포하면 확실히
기동에 실패한다.** "기동하지 않는다"는 추정이 아니다.

다만 prod에 지금 떠 있는 이미지는 별개 문제다. 배포 워크플로가 롤아웃 시점에 이미지를 덮어쓰므로
(`k8s/scripts/deploy-service.sh`) 리포의 `newTag` 값으로는 알 수 없고 `kubectl`로 확인해야 한다.
`momens-server`는 이미 `api.momens.works`의 `/api` prefix로 라우팅되지만 FE가 cutover하지 않아
트래픽이 없다(`deployment.yaml` 주석).

### 2.6 미러가 재현하지 않는 prod 컬럼이 5개 있다

미러는 "엔티티가 매핑하는 컬럼 + 기본값 없는 `NOT NULL` 컬럼"만 재현한다는 규칙 때문에, prod에는 있으나
서버 파일이 만들지 않는 컬럼이 있다.

| 테이블 | 컬럼 |
| --- | --- |
| `entity_relations` | `metadata`, `source_ref_ids`, `weight` |
| `source_refs` | `content_hash` |
| `workspace_members` | `onboarding_state` |

의도된 것이고 `validate`가 매핑된 컬럼만 보므로 기동을 막지 않는다. 다만 주도권 이전 후에도
**local과 prod가 문자 그대로 같아지지는 않는다**는 뜻이므로 기록해 둔다. `tasks`는 미러가 레거시 컬럼을
전부 재현하고 있어 해당이 없다.

### 2.7 `tasks`를 건드리는 required 5건의 객체 단위 판정

| 파일 | prod 실행 | 이유 |
| --- | --- | --- |
| `V20260707120000` add_task_detail_and_checklist | **부분 충돌** | `description`·`assignee_id`와 인덱스 `idx_tasks_assignee_id`가 prod에 이미 있다(레거시 `000001_init.sql:109`). `task_checklist_items`와 그 인덱스만 필요하다 |
| `V20260707150000` task_role_single_value | **실행 불가** | 아래 참조 |
| `V20260714091000` add_task_origin | 안전 | `origin_type`·`origin_signal_id`가 prod에 없다 |
| `V20260715090000` task_role_drop_not_null | **실행 불가** | 위 파일이 만드는 컬럼에 의존한다 |
| `V20260716090000` add_task_minsu_fields | 안전 | `next_action`·`task_open_questions`가 prod에 없다 |

나머지 11건은 prod에 아예 없는 순수 신규 테이블이라 충돌 여지가 없다.

### 2.8 `task_roles`는 prod에 존재한 적이 없다

`V20260707150000__task_role_single_value.sql`은 `task_roles`를 읽어 `tasks.role`을 백필하고
`DROP TABLE task_roles`로 끝난다. 그런데 `task_roles`를 만드는 것은 이 리포의
`V20260706120000__create_task.sql`이고, **그 파일의 헤더는 `mirror`다.**

- `momens-api/migrations/` 전체에 `task_roles` 문자열이 없다.
- 레거시 형상 DB의 `task_roles` 개수: `0`

`tasks`는 레거시 소유가 맞지만 `task_roles`는 이 서버가 자체적으로 만든 테이블이고 prod에 간 적이 없다.
한 파일이 두 성격을 갖는 사례이며, 이번에는 `required`가 아니라 **`mirror` 헤더가 잘못 붙어 있던 경우**다.
`persistence.md`가 경고한 "잘못 `mirror`로 두면 게이트가 조용히 통과시킨다"가 실제로 일어나 있었다.

prod에서 이 파일은 두 번 실패한다. `UPDATE`에서 `relation "task_roles" does not exist`로 한 번,
그것을 넘겨도 기존 `tasks` 행의 `role`이 전부 `NULL`이라 `SET NOT NULL`에서 다시 한 번.
**어떤 조건에서도 실행할 수 없다.**

#### 실행되는 파일의 주석 하나가 낡는다

실행 집합에 든 파일 중 둘이 그렇다. `V20260716090000__add_task_minsu_fields.sql`은 *"local/test와 dev에
… 추가한다"*로, `V20260714091000__add_task_origin.sql`은 *"기존 행(local/test 데이터뿐)은 전부 사람이
만든 태스크라"*로 적혀 있는데 두 파일 모두 부트스트랩에서 **prod에서도 실행된다.** 후자의 동작 자체는
안전하다 — `NOT NULL DEFAULT 'manual'`이라 기존 prod 행이 채워지고 `tasks_origin_signal_check`도
`(false) = (false)`로 통과한다. 낡는 것은 주석의 사실 진술뿐이다. checksum 때문에 고칠 수 없으므로
`create_memory_read_mirror` 주석을 `persistence.md`가 대체한 것과 같은 방식으로 여기에 남긴다.
그 문구는 작성 시점의 사실이고 지금은 이 문서가 정본이다.

### 헤더가 아니라 주석이 진실을 갖고 있었다

`V20260706120000__create_task.sql`의 7행은 이렇게 적는다.

```
-- (local/test 전용 Flyway. prod 공유 스키마의 task_roles 는 컷오버 시 레거시 마이그레이션으로 추가합니다.)
```

**파일 주석은 이미 알고 있었다.** 헤더 어휘로 표현할 수 없는 상태였을 뿐이다. `mirror`는 "레거시가 이미
만들었다"는 뜻이고, `required`는 "우리가 만든 것을 레거시가 반영해야 한다"는 뜻이다. `task_roles`는
**"아직 prod에 없고 나중에 레거시가 만들어 줄 것"** 이라 둘 중 어느 것도 아니었고, 그 상태가 헤더를
빠져나가 주석으로 흘러갔다.

MOM-0909의 객체 대조에 값싼 힌트가 된다. **같은 종류는 헤더가 아니라 마이그레이션 주석에서 찾는다.**

## 3. 결정 1 — 소유 범위

**서버가 prod 스키마를 소유하고, 레거시 DDL을 동결한다.**

`tasks`를 ALTER 하는 required가 5건이므로 "서버 신규 객체만 소유"는 성립하지 않는다. 실질 선택지는
전체 소유이거나 객체별 분할 규칙이었고, 전체 소유로 정했다.

객체별 분할(레거시 테이블은 레거시가, 서버가 필요한 컬럼은 서버가)을 택하지 않은 이유는 두 가지다.
첫째, 한 객체에 writer가 둘이 되고 두 이력이 서로를 모르므로 순서 보장이 없다. `tasks`가 이미 그
상태가 된다. 둘째, **미러가 살아남으면서 prod에서 실행되기 시작한다.** 지금 미러가 안전한 유일한
이유는 prod에서 Flyway가 꺼져 있어서다. 부트스트랩 이후에 새로 쓰는 미러는 prod에서 실제로 돌아가므로
전부 `IF NOT EXISTS` 방어를 달아야 한다.

레거시가 지키려는 DDL 자율성은 **2026-07-12 이후 행사되지 않았다.** 그 대가로 위 두 비용을 영구화할
이유가 없다.

## 4. 결정 2 — baseline 방식

**`flyway_schema_history`에 기존 적용분을 심고 나머지만 실행한다. `outOfOrder`는 부트스트랩 릴리스에서만 켠다.**

### 심는 방법

빈 스크래치 DB에 Flyway를 그대로 돌려 전체를 적용시킨 뒤, 생성된 `flyway_schema_history`에서 해당
행들을 그대로 뽑아 prod에 `INSERT` 한다. **체크섬을 직접 계산하지 않는다.**

`--verify`는 대상 DB의 `flyway_schema_history`를 psql로 직접 읽는다. `flyway info -outputType=json`은
**12.4.0 기준으로 `checksum` 필드를 내보내지 않는다**(실측 확인 — `version`·`state`·`installedOnUTC` 등만
있다). 그 경로로 짜면 대조 대상이 0건이 되어 검증이 무력화된다.

**산출 기준은 실제로 배포되는 커밋이다.** 미러는 지금도 계속 늘고 있으므로(2.4), 체크섬을 develop의
임의 시점에서 뽑아 두면 그 뒤 머지되는 마이그레이션이 심기 집합에서 빠진다. 빠진 파일이 레거시 소유
객체를 만드는 것이면 prod에서 실행돼 `already exists`로 죽는다. 따라서 **릴리스 대상 커밋(`main`의 그
SHA)을 체크아웃해 산출하고, 산출부터 INSERT까지 사이에 그 커밋이 바뀌지 않게 한다.** Flyway는 매 기동 시 파일을
다시 해싱해 이 값과 대조하고 다르면 `checksum mismatch`로 기동을 거부하므로, CRC32를 재구현하면
어긋났을 때 원인 추적이 어렵다. INSERT는 단일 트랜잭션으로 감싼다.

### 심는 집합은 파일 목록이 아니라 객체 대조가 정한다

2.3의 24건이 출발점이지만 최종 집합이 아니다. 2.7·2.8에서 확인했듯 실행 대상 16건 중 최소 3건이
그대로 실행될 수 없다. **실행 집합은 "이 파일이 만드는 객체 중 prod에 없는 것"으로 판정하며,
파일 단위로 옮기지 않는다.**

방향은 실행 불가·부분 충돌 파일을 심기 쪽으로 옮기고, 그 **순효과만** 만드는 보정 마이그레이션을
오늘 날짜 version으로 새로 쓰는 것이다. 예를 들어 `task_role_single_value`와 `task_role_drop_not_null`의
prod 순효과는 `tasks.role TEXT`(nullable) + `tasks_role_check` 뿐이다. 이렇게 하면 실행 집합이 위쪽
version으로 올라가므로 `outOfOrder` 의존도 함께 줄어든다.

정확한 분할과 보정 마이그레이션 작성은 MOM-0909가 소유한다.

#### 확정된 분할 (MOM-0909, 2026-08-23)

required 16건이 만들거나 바꾸는 모든 객체를 prod 형상(`legacy-diff`의 `legacy-db`)과 기계적으로
대조했다. **충돌은 한 파일에만 있다.**

| 파일 | 판정 |
| --- | --- |
| `V20260707120000` add_task_detail_and_checklist | `tasks.description`·`assignee_id`·`idx_tasks_assignee_id` 이미 존재. `task_checklist_items`와 그 인덱스만 필요 |
| `V20260707150000` task_role_single_value | 실행 불가(2.8) |
| `V20260715090000` task_role_drop_not_null | 위 파일에 의존 |
| 나머지 13건 | 만드는 객체가 prod에 하나도 없다. 그대로 실행 가능 |

따라서 최종 분할은 다음과 같다.

- **심기 28건** — 레거시가 만든 객체 25 + 위 세 파일 3
- **실행 13건**
- **보정 1건** — `V20260823110000__prod_bootstrap_task_role_and_checklist.sql`

보정 마이그레이션의 **version은 리포 최고 version보다 위여야 한다.** 아래로 두면 이미 그 위까지
진행한 DB(오늘 `develop`을 받은 로컬)가 다음 기동에서 `Detected resolved migration not applied`로
죽는다. 실제로 처음에 `20260823090000`으로 두었다가 이 실패를 재현하고 `20260823110000`으로 올렸다.
4절이 세운 "이후 새 마이그레이션은 항상 그보다 뒤에 온다"는 원칙이 보정 파일 자신에게도 적용된다.

보정 마이그레이션은 세 파일의 순효과만 만든다. `tasks.role TEXT`(nullable) + `tasks_role_check`,
그리고 `task_checklist_items`와 그 인덱스다. `NOT NULL`을 거쳤다 푸는 중간 단계와 `task_roles`
조인 테이블은 prod에 존재한 적이 없으므로 재현하지 않고, 백필도 하지 않는다(읽어 올 원본이 없다).
같은 판단이 `momens-api#28`의 `000019`에 남아 있다.

local/dev에는 원본 세 파일이 이미 적용돼 있으므로 보정 마이그레이션의 모든 구문을 idempotent하게
썼다. `ADD CONSTRAINT`에는 `IF NOT EXISTS`가 없어 `pg_constraint`를 보고 건다.

**두 형상에서 실측했다.** prod 형상에 적용하면 세 객체가 생기고, local/dev 형상에 적용하면 아무것도
바뀌지 않으며, 재적용해도 실패하지 않는다. 적용 후 두 형상의 `tasks.role` 컬럼 정의, `tasks_role_check`
정의, `task_checklist_items` 전체 컬럼 정의가 **문자 그대로 일치한다.**

#### FK를 넣지 않는다 (의도된 차이)

`momens-api#28`은 prod 용 DDL에 FK를 넣었고, 이 리포의 파일이 FK를 뺀 것을 *"a mirror-only artifact"*
라고 적었다. 주도권이 넘어오면 **이 리포의 파일이 곧 prod DDL**이므로 그 차이가 prod로 그대로 간다.

required 16건 중 FK를 가진 것은 3건뿐이다 — `task_checklist_items`→`tasks`,
`task_open_questions`→`tasks`, `user_identities`→`users`. `signals`·`signal_actions`·`signal_evidence`·
`outbox_events`·`push_*`·`minsu_*`에는 없다.

**이것은 미러 부산물이 아니라 모듈 경계 결정으로 본다.** `persistence.md`가 "읽기 전용 테이블은 새 모듈
의존이 생기면 FK를 두지 않습니다"라고 적고 있고, FK를 가진 3건은 전부 모듈 안에서 닫히거나 core를
가리킨다. `signal` 모듈이 `workspaces`에 FK를 걸면 모듈 의존이 새로 생긴다.

그대로 둔다. 파일을 고치면 checksum이 깨지고, 필요해지면 나중에 새 마이그레이션으로 FK를 더할 수 있다.

**대가는 참조 무결성이 DB 차원에 없다는 것이다.** 워크스페이스를 지워도 `signals`·`outbox_events`가
`ON DELETE CASCADE`로 따라 지워지지 않는다. 지금은 무해하다 — `momens-worker`가 아직 `signals`에
INSERT 하지 않아 생산자가 없다. **worker가 생산을 시작하기 전에 재검토한다**(MOM-0916).

##### 대조 방법의 한계

객체 추출을 정규식으로 했기 때문에 인용 식별자를 놓친다. `V20260713120000`의 `"change"` 컬럼이
그 예다(해당 테이블 자체가 prod에 없어 판정에는 영향이 없었다). 구문 유형을 따로 열거해
`ADD CONSTRAINT`·`ALTER COLUMN`·`DROP TABLE`·`UPDATE`가 `tasks` 세 파일에만 있는 것을 확인했으나,
**스캔은 보조 수단이고 판정의 근거는 파일을 읽는 것이다.**

### `outOfOrder`

2.4 때문에 심기 방식은 `spring.flyway.out-of-order=true`를 동반한다. 없으면 실행 대상이 조용히
건너뛰어져 기동 후 `validate`에서 죽거나, `validateOnMigrate`가 먼저 실패한다.

#### `group=true`도 함께 켠다

Flyway의 `group` 기본값은 `false`라 **마이그레이션마다 별도 트랜잭션**이다. 14건 중 8번째가 실패하면
앞 7건은 커밋된 채 남는다. prod가 반쯤 적용된 상태로 남아 두 번째 시도의 형상이 리허설과 달라진다.

`group=true`면 전부 되거나 전부 안 된다. PostgreSQL은 DDL도 트랜잭션에 넣으므로 성립하고, 실패 시
이력 행까지 함께 롤백되므로 **7절의 "완전 원복은 이력 심기 단계까지"라는 경계가 실행 단계까지
넓어진다.** `out-of-order`와 같은 자리에 두고 7단계에서 함께 뺀다.

**대가는 `tasks` 락 보유 시간이다.** 실행 14건 중 다섯이 `tasks`에 `ACCESS EXCLUSIVE`를 건다
(`add_task_origin`, `add_task_minsu_fields`, 보정, 그리고 `task_open_questions`·`task_checklist_items`의
FK가 부모를 잠근다). 실측으로 확인했다 — `ADD COLUMN NOT NULL DEFAULT` + `CHECK`는
`AccessExclusiveLock on tasks`를 잡는다. `group=false`면 각 락이 그 마이그레이션 커밋과 함께 풀리고,
`group=true`면 첫 `ALTER TABLE tasks`부터 배치 끝까지 계속 잡는다.

**`tasks`는 레거시가 지금도 트래픽을 받는 테이블이다.** 7절의 "사용자 영향 0"은 momens-server 파드
기준이고 레거시에는 적용되지 않는다.

그럼에도 `group=true`를 택한다. 막히는 위험은 **락을 잡는 횟수**에 비례하는데, `group=false`는 `tasks`
락 획득 창이 다섯 번 생기고 `group=true`는 한 번이다. 배치 자체는 리허설에서 0.2초였다.

**대신 `lock_timeout`이 반드시 있어야 한다.** 지금 리포 어디에도 없다(0건). 없으면 레거시의 긴 트랜잭션
하나가 `ALTER`를 막을 때 그 뒤의 모든 `tasks` 쿼리가 함께 줄을 선다. `lock_timeout`이 있으면 락을 못
잡을 때 빨리 실패하고, `group=true` 덕에 통째로 롤백돼 시딩 직후 상태로 돌아간다 — 두 설정이 여기서
맞물린다. 지정 수단은 `spring.flyway.init-sqls`이며, **정확한 전달 형태는 ConfigMap 바인딩 확인과 함께
닫는다**(아래 "리허설로 닫지 못한 것").

부트스트랩이 끝나면 이력의 최고 version이 리포 최고 version과 같아지고 이후 새 마이그레이션은 항상 그보다
뒤에 온다. 따라서 **부트스트랩에서만 켜고 끝나면 되돌린다.** 상시로 두면 오래된 브랜치의
낮은 version 마이그레이션이 나중에 조용히 적용되는 통로가 영구히 남는다. 이 리포는 여러 브랜치가 병렬로
진행되므로(하루에 미러 7건이 들어온 적이 있다) 현실적인 위험이다.

### 토글은 리포가 아니라 배포 ConfigMap에 둔다

**`application-prod.yml`의 `flyway.enabled`를 `true`로 바꿔 `develop`에 머지하면 위험 구간이 생긴다.**
이력 INSERT가 끝나기 전에 그 커밋이 prod에 뜨는 순간 Flyway가 40건 전부를 미적용으로 보고 첫 파일부터
실행하려 한다.

그리고 **prod로 가는 길은 `main` push 하나가 아니다.** `build-and-deploy.yml`에는 `workflow_dispatch`가
있고, 이미지를 push한 뒤 `k8s`로 배포를 요청하는 `Request production deploy` 스텝은 ref가 아니라
`K8S_DISPATCH_TOKEN` 존재 여부로만 걸린다. 즉 **어느 브랜치에서 dispatch해도 그 커밋이 prod에 뜬다.**
릴리스 게이트도 함께 우회된다.

따라서 "릴리스 시점만 사람이 통제하면 된다"는 완화가 성립하지 않는다. dispatch 한 번이면 통제가 무너진다.
리포에 위험한 상태를 아예 두지 않는 편이 안전하다.

대신 **k8s ConfigMap 환경변수로 토글한다.** `k8s` 리포 push 자체는 배포를 시작하지 않는다.
`k8s/scripts/deploy-service.sh`가 호출됐을 때만 매니페스트를 적용하고, 이미지 변경 여부와 무관하게
`kubectl rollout restart`를 건다. 따라서 ConfigMap 커밋과 rollout 시점은 분리된다.

- 리포의 `application-prod.yml`은 부트스트랩이 성공할 때까지 `flyway.enabled: false`를 유지한다.
  리포에 위험한 상태가 존재하지 않는다.
- 부트스트랩은 이력 INSERT 직후 ConfigMap 토글 PR만 머지하고 **아직 rollout하지 않는다.** 토글 머지와
  서버 릴리스 머지 사이에는 수동 dispatch를 금지한다.
- 최초 rollout은 검증·심기에 사용한 고정 릴리스 내용으로 `main` 이미지를 만든 뒤에만 수행한다.
- 성공 후 `outOfOrder`·`group`·`initSqls`를 ConfigMap에서 제거하고 같은 검증 이미지로 다시
  dispatch한다. 이후 별도 서버 릴리스로 `application-prod.yml`을 `true`로 정본화한 뒤
  `SPRING_FLYWAY_ENABLED` 오버라이드를 걷어낸다.

**환경변수 이름 주의.** `spring.flyway.enabled` → `SPRING_FLYWAY_ENABLED`는 모호함이 없다.
`spring.flyway.out-of-order`는 대시가 있어 relaxed binding 변환형이 애매하므로, ConfigMap이 이미 쓰고 있는
`JAVA_TOOL_OPTIONS`에 `-Dspring.flyway.out-of-order=true`를 더하는 편이 안전하다. 시스템 프로퍼티는
정규 이름을 그대로 쓴다. 어느 쪽을 택하든 **리허설에서 실제로 적용되는지 확인한다.**

### 채택하지 않은 대안 — 실행 대상 재번호 + `baselineVersion`

실행 대상 파일들을 현재 최고 version 뒤로 rename 하면 미러와 version 구간이 분리되어 `baselineVersion`
설정만으로 끝나고, INSERT 스크립트도 `outOfOrder`도 필요 없다.

택하지 않은 이유는 파일명이 Flyway에게 신원이기 때문이다. rename 하면 local은 DB 재생성으로 넘어가지만
**dev는 서버 Flyway가 실제로 소유·운영 중인 배포 환경**이라 이력에 옛 이름이 남는다. 결국 일회성
스크립트가 사라지는 게 아니라 prod에서 dev로 옮겨갈 뿐이고, 그 대가로 7월에 쓴 마이그레이션이 8월
번호를 갖게 되어 version이 작성 시점을 뜻하지 않게 된다. 심기 방식은 **파일을 한 글자도 건드리지
않으므로** local·dev·test에 영향이 없다.

## 5. 결정 3 — 레거시 마이그레이션 정책

**동결 대상은 "이 리포에 그 객체를 만드는 마이그레이션 파일이 있는가"로 판정한다.**

부트스트랩 시점의 파일 집합이 경계를 정의한다. 그 안쪽은 레거시와 worker가 DDL을 하지 않는다.
바깥쪽은 각자가 계속 소유한다. 이후 새로 만드는 객체는 만든 쪽이 소유한다.

**동결은 기간이 아니라 범위의 문제다.** 부트스트랩 기간 한정이 아니라 영구적인 소유권 이전이다.

### 실제 목록

서버 마이그레이션 40건이 만들거나 바꾸는 객체는 33개다. 그중 레거시와 겹쳐 **실제로 동결을 요청해야
하는 것은 20개**다.

```
blockers, confirmed_memories, entity_relations, memory_candidates,
milestone_owners, milestones, project_owners, projects, refresh_tokens,
review_actions, source_connections, source_credentials, source_refs,
task_updates, tasks, users, workspace_invitations,
workspace_label_sequences, workspace_members, workspaces
```

나머지 13개 중 12개는 서버 신규(`signals` 계열 4, `outbox_events`, `push_installations`,
`push_deliveries`, `notification_consumer_offsets`, `minsu_task_draft_generations`, `user_identities`,
`task_checklist_items`, `task_open_questions`)라 레거시가 애초에 건드리지 않으므로 실질 제약이 아니다.
남은 하나는 `task_roles`로 prod에 없는 local/test 전용이다(2.8).

**계속 각자 소유하는 것은 17개다.** 레거시 전용 11개(2.5)와 worker 전용 6개(2.1)다. MCP OAuth
(`oauth_*`)가 여기 속하는 것이 전면 동결을 택하지 않은 이유다.

### 부트스트랩 기간에 특별히 조심할 대상은 레거시가 아니다

레거시나 worker가 부트스트랩 진행 중에 **자기 전용 객체**를 바꾸는 것은 안전하다. 심을 이력은 이 리포의
파일에서 산출되므로 그쪽이 새로 만든 객체는 애초에 집합에 없고, `validate`는 매핑된 엔티티만 본다.
두 이력은 서로를 모른다(2.2).

기간 한정으로 위험한 것은 **이 리포에 새 마이그레이션이 머지되는 것**이다. 체크섬 산출과 INSERT 사이에
파일이 늘면 그 파일이 심기 집합에서 빠지고, 레거시 소유 객체를 만드는 것이었다면 prod에서
`already exists`로 죽는다. 4절의 산출 기준 커밋 고정이 이것을 막는다.

레거시를 전면 동결하지 않는 이유는 `oauth_*` 때문이다. MCP OAuth는 레거시의 가장 최근 DDL 영역이고 아직
이관 계획도 확정되지 않았다(MOM-0871). 서버가 매핑하지도 않는 테이블의 마이그레이션을 이 리포가 들고
있으면 죽은 DDL만 늘어난다.

위험의 원천은 "한 DB에 러너가 여럿"이 아니라 **"한 객체에 writer가 둘"**이다. api와 worker는 이미 한
DB에서 문제없이 공존해 왔다(2.1·2.2). 막아야 하는 것은 겹치는 객체뿐이다.

**강제 수단은 없다.** 레거시 러너는 계속 동작하므로 동결은 규율로 지켜진다. 위반은 다음 서버 배포의
`ddl-auto: validate`가 기동에서 사후에 잡는다. `validate`는 매핑된 컬럼의 존재와 타입만 보고 제약·기본값·
UNIQUE는 보지 않으므로 완전한 탐지가 아니다. 결정 1을 택한 이상 감수하는 비용이다.

동결은 레거시(신진수)와 worker 양쪽의 합의가 필요하다.

## 6. 결정 4 — 헤더 체계와 릴리스 게이트

**`prod-schema` 헤더 4종과 스키마 릴리스 게이트를 폐지한다.**

헤더 4종(`mirror`/`required`/`pending`/`applied`)은 전부 "다른 저장소가 이 스키마를 prod에 반영해 줘야
한다"는 크로스 리포 의무의 상태다. 부트스트랩 이후에는 그 의무가 존재하지 않는다. 서버 Flyway가 배포
때 직접 적용하므로 "반영 여부"가 "배포됐는가"와 같아진다. dev가 이미 그렇게 돌아가고 있다.

**미러 충실도 규칙은 다르다. 폐지되지 않는다.** 초안은 "이후에는 네 환경이 같은 파일에서 나오므로
문제가 성립하지 않는다"고 적었으나 틀렸다. 그 말이 맞는 것은 **이 서버가 만드는 객체**뿐이다.

레거시가 만든 20개 테이블은 prod의 실물이 레거시 DDL에서 나왔고, 이 리포의 파일은 local/test용
재구성이다. 부트스트랩에서 그 24건(미러 23 + `create_refresh_token`)은 실행되지 않고 이력에만 심기므로, 주도권이 넘어와도
**prod의 그 테이블들은 여전히 우리 파일이 만든 것이 아니다.** `ddl-auto=validate`는 공통 설정이라
local에서도 돌지만 local이 검증하는 대상은 우리 파일이 만든 테이블이고 prod가 검증하는 대상은
레거시가 만든 실물이다. 둘이 갈리면 local만 통과한다 — **"local은 통과하는데 prod에서 깨진다"는
위험이 이 20개에 대해서는 그대로 남는다.**

바뀌는 것은 절차뿐이다. 레거시 소유 테이블에 컬럼이 필요하면 이제 이 리포에서 직접 `ALTER` 하고
그 컬럼은 prod에도 우리 Flyway가 만든다. 충실도 규칙은 **레거시가 만든 부분**에만 계속 적용된다.
규칙 본문은 `persistence.md`가 소유한다.

폐지 후의 안전망은 **Flyway 자체**다. 잘못된 마이그레이션은 배포 시점에 Flyway가 실패시킨다. 지금 dev가
보호받는 방식과 같다.

### 폐지가 파일 수정을 뜻해서는 안 된다

기존 40건의 헤더 주석은 **지우지 않는다.** 주석 한 줄이라도 고치면 Flyway checksum이 바뀌어 local·dev
이력이 깨진다. 헤더는 역사적 주석으로 그대로 두고, 부트스트랩 이후 새로 쓰는 파일에만 헤더를 달지 않는다.

### 순서 — 게이트 폐지가 부트스트랩 릴리스보다 **먼저** 들어가야 한다

직관과 반대다. 처음에는 "게이트 폐지는 부트스트랩 적용 뒤"로 두려 했으나 그 순서는 성립하지 않는다.

정규 배포 경로는 `build-and-deploy.yml`의 `push: branches: [main]`이다. 부트스트랩을 그 경로로 올리려면
`develop` → `main` 릴리스 PR이 필요한데, `pr-check.yml`의 `Block release with unreflected prod requirements`가
`github.base_ref == 'main'`인 모든 PR에서 `required` 헤더가 남아 있으면 실패시킨다. 헤더는 checksum 때문에
고칠 수 없다(「폐지가 파일 수정을 뜻해서는 안 된다」). **게이트를 남겨 두면 부트스트랩 릴리스 자체가 통과하지 못한다.**

`workflow_dispatch`로 게이트를 우회해 `develop`을 바로 올리는 길은 있다(4절). **그 길을 택하지 않는다.**
리뷰와 승인을 거치지 않은 트리를 prod에 올리지 않는다는 원칙이 게이트보다 상위이고, 부트스트랩은
되돌리기 어려운 작업이라 더욱 그렇다. dispatch는 정규 경로가 막혔을 때의 우회로가 아니라 사고 경로로 취급한다.

```
게이트 폐지  →  부트스트랩 적용이 선행이라 대기
부트스트랩 적용  →  릴리스 PR이 게이트에 막혀 대기
```

해소 근거는 CI 동작이다. `pr-check.yml`은 `actions/checkout@v7`을 `pull_request`에서 쓰므로 **PR이 머지된
상태의 트리를 체크아웃한다.** 즉 릴리스 PR은 자기 자신이 실어 나르는 스크립트로 검사받는다. 게이트 제거가
이미 `develop`에 있으면 그 릴리스 PR은 통과한다.

따라서 **MOM-0910의 게이트 제거는 부트스트랩 릴리스 이전에 `develop`에 머지한다.**

게이트를 먼저 없애는 위험(미반영 스키마가 prod로 나감)은 ConfigMap 토글만으로 막히지 않는다.
`flyway.enabled=false`는 마이그레이션만 막고, 새 코드의 `ddl-auto=validate`는 실행된다. 실행 집합이
만드는 테이블이 아직 없으므로 부트스트랩 전에 `main` 릴리스가 나가면 새 Pod가 기동하지 못한다.

따라서 릴리스 PR은 미리 열어 CI와 리뷰를 끝내되 **머지는 이력 INSERT와 ConfigMap 토글 머지 뒤로
미룬다.** 그동안 PR head SHA를 릴리스 대상으로 고정해 `--verify`와 `--generate`를 수행한다. head가
바뀌면 심기 전에 1단계부터 다시 검증하고, 심은 뒤라면 릴리스하지 않고 이력을 원복한 뒤 다시 시작한다.
게이트 폐지 뒤의 이 수기 의무는 `docs/prod-readiness-ledger.md`가 추적한다.

대장 문서(`docs/prod-readiness-ledger.md`) 자체는 남는다. 선언 구간(prod 필수 환경변수)과 수기 구간은
MOM-0841 소유이며 이 결정의 영향을 받지 않는다. 제거 대상은 생성 구간과 `--release-check`의 스키마 검사다.

## 7. 결정 5 — 롤백

**fix-forward를 기본으로 한다.**

### 단계와 복구

| 단계 | 실패 시 |
| --- | --- |
| ① 릴리스 고정·체크섬 산출·리허설 | prod 무관 |
| ② prod에 이력 INSERT(8절 4단계) | **완전 원복 가능.** `DROP TABLE flyway_schema_history`. 이 시점 Flyway는 아직 꺼져 있어 그 표를 읽는 주체가 없다 |
| ③ ConfigMap 토글 PR 머지(8절 5단계) | 아직 rollout하지 않았으므로 스키마와 실행 Pod는 그대로다. 중단하려면 토글 PR을 되돌린 뒤 이력 테이블을 원복한다 |
| ④ 고정 릴리스 머지·rollout — Flyway 실행 전 실패 | 스키마 무변경. 옛 Pod는 유지된다. ConfigMap 토글을 되돌리고 이전 정상 이미지로 dispatch해 실패한 ReplicaSet을 정리한 뒤 ①부터 재시도한다 |
| ④ 고정 릴리스 머지·rollout — Flyway 실행 중 실패 | `group=true` 덕에 14건이 통째로 롤백된다(4절). 실행 대상 이력 행도 함께 사라져 심기 직후 상태로 돌아간다. 그래도 남는 부분 적용이 있으면 **되돌리지 않고** fix-forward 한다 |
| ⑤ 임시 설정 제거 | `outOfOrder`·`group`·`initSqls` 제거 후 검증한 같은 이미지 SHA로 dispatch한다. `SPRING_FLYWAY_ENABLED`는 `application-prod.yml` 정본화 릴리스가 배포될 때까지 유지한다 |

fix-forward가 기본인 이유는 **부트스트랩 중 사용자 영향이 0**이기 때문이다. 근거는 배포 매니페스트에서
확인했다.

- `momens-server`는 `api.momens.works`의 `/api` prefix로 라우팅되지만 **FE가 cutover하지 않아 트래픽이
  없다.** 레거시 경로(`/`)는 그대로 서비스된다.
- `replicas: 1` + 기본 RollingUpdate라 `maxUnavailable`이 0이다. **새 Pod가 Ready 되기 전에는 옛 Pod가
  종료되지 않는다.** `startupProbe`가 `failureThreshold: 30 × periodSeconds: 5`로 150초를 주고,
  `deploy-service.sh`의 `rollout status --timeout=300s`가 그 뒤에 실패한다.

즉 부트스트랩이 실패해도 옛 Pod가 계속 떠 있고 사용자는 영향을 받지 않는다. 서두른 원복보다 정확한
수정이 낫다. 부분 적용된 객체들은 prod에 없던 신규 테이블·컬럼이라 레거시가 읽지도 쓰지도 않으므로
남아 있어도 무해하다.

**복구는 자동이 아니다.** `deploy-service.sh`에 `rollout undo`가 없다. 실패하면 워크플로만 실패하고
새 ReplicaSet의 Pod가 Ready 되지 못한 채 남는다. 정리는 사람이 `kubectl rollout undo` 또는 ConfigMap
원복으로 수행한다. 절차 문서에 이 단계를 명시한다.

역스크립트를 준비하는 방식(roll-backward)은 택하지 않았다. 스크립트 자체가 `DROP`문 덩어리라 사고 시
손실이 더 크고, 되돌려서 얻는 것이 "레거시가 안 쓰는 빈 테이블이 사라지는 것"뿐이다.

### 소유권을 되돌린다면 DML GRANT를 함께 재발급한다

**되돌리는 경우가 위 표에 없어서 따로 적는다.** 소유권 이전을 나중에 되돌리기로 하면 — 부트스트랩
실패와 무관한 이유로도 그럴 수 있다 — 그 `ALTER TABLE`이 `momens_server`의 DML을 함께 가져간다.
8절 3단계에서 20개를 넘겼으므로 되돌릴 때도 20개 전부에 재발급이 필요하다.

```sql
-- 넘긴 것과 대칭이어야 한다. 한 테이블이라도 재발급이 빠지면 그 테이블에서 서비스가 끊긴다.
ALTER TABLE <t> OWNER TO postgres;
GRANT SELECT, INSERT, UPDATE, DELETE ON <t> TO momens_server;
```

미리 GRANT를 넣어 두어도 소용없다. `ALTER TABLE ... OWNER TO`는 현재 소유자를 grantee로 하는
ACL 항목을 새 소유자로 옮기므로, `momens_server`가 소유자가 되는 순간 그 GRANT는 소유자 항목에
흡수되고 소유권이 떠날 때 함께 떠난다. 제3자가 부여한 항목도 마찬가지다 — 쌍둥이에서 ACL을 직접
보며 확인했다.

**이 손실은 배포에서 잡히지 않는다.** `ddl-auto=validate`는 카탈로그만 보고 DML 권한을 보지 않아
**기동이 성공한다.** 끊기는 것은 첫 요청이다. 쌍둥이의 `ownership-reverted` 시나리오가 이 성질을
그대로 재현한다.

### 롤백 절차보다 우선하는 원칙

**부트스트랩 실행 집합에는 `DROP`과 파괴적 `UPDATE`가 하나도 없어야 한다.** 2.8의 `DROP TABLE task_roles`
같은 문장이 prod에서 한 번 돌면 어떤 사후 절차로도 되돌릴 수 없다.

이 확인은 리허설이 아니라 **객체 대조에서 끝나야 한다.** 리허설 DB에는 prod 데이터가 없어서
`SET NOT NULL` 실패 같은 것을 재현하지 못한다.

## 8. 실행 순서

```
MOM-0908 (이 문서 + ADR-0019)
   ├─ MOM-0909  부트스트랩 스크립트 + 보정 마이그레이션 + 리허설   → develop
   └─ MOM-0910  헤더·대장·게이트 폐지                            → develop
        └─ [릴리스 PR develop → main 생성·검증, head SHA 고정]
             └─ [운영 조작] verify/generate → 이력 INSERT → ConfigMap 토글 머지
                  └─ [릴리스 PR 머지] → main push 배포 → 확인 → 토글 정리
```

두 실행 티켓은 순서 없이 병행하고 **둘 다 `develop`에 머지된 뒤 하나의 릴리스로 나간다.**
게이트 폐지가 먼저 들어가야 하는 이유는 6절에 있다.

릴리스 PR을 여는 것과 prod 릴리스는 다르다. `application-prod.yml`의 `flyway.enabled: false`는
Flyway 실행만 막고 Hibernate의 `ddl-auto=validate`는 막지 않는다. 실행 집합이 만들 12개 테이블이
없는 상태에서 릴리스하면 새 Pod가 기동하지 못하므로, **PR은 미리 검증하되 머지는 4단계 심기와
5단계 ConfigMap 토글 머지 뒤에 한다.**

0. **DB 접속 정보를 Supabase로 교체한다. 아래 어느 단계보다 먼저다.**

   `MOMENS_SERVER_DATABASE_URL`·`_USERNAME`·`_PASSWORD`(`k8s` 리포의 GitHub Secret)가 옛 Neon을
   가리키고 있었다. **그 상태로 진행하면 소유권 이전과 심기는 Supabase에서 일어나고 Pod는
   Neon에 접속한다.** 토글이 켜지는 순간 Flyway가 이력이 없는 Neon에서 42건을 처음부터
   실행하려 하고, Supabase에는 새 12개 테이블이 생기지 않는다. 어느 쪽도 의도한 결과가 아니다.

   **주소는 session pooler여야 하고, 판별은 포트로 한다.** 배제할 것은 transaction
   pooler(`:6543`)다 — 세션을 유지하지 않아 Flyway의 세션 단위 잠금과 `init-sqls`가 성립하지
   않는다. session pooler는 `5432`를 쓰고 username에 `.<ref>` 접미사가 붙는다.

   direct(`db.<ref>.supabase.co`)는 쓸 수 없다. **IPv6 전용이라 IPv4 노드로 구성된 prod GKE에서
   도달하지 못한다** — direct host는 A 레코드 없이 AAAA만 반환하고 `k8s`의 GKE Terraform은 IPv4
   CIDR만 선언한다.

   ```
   jdbc:postgresql://aws-0-<region>.pooler.supabase.com:5432/postgres?sslmode=require
   ```

   **교체 후 앱 자격증명으로 대상을 확인하기 전에는 토글을 켜지 않는다.**

   ```sql
   SELECT current_database(), current_user, inet_server_addr(), inet_server_port();
   SELECT count(*) FROM schema_migrations;              -- > 0 (레거시가 마이그레이션한 그 DB)
   SELECT to_regclass('public.flyway_schema_history');  -- 심기 전이면 NULL
   ```

   `docs/prod-readiness-ledger.md`의 수기 의무가 이 단계를 추적한다.

1. 릴리스 PR의 **head SHA·tree hash·main base SHA를 고정**하고 그 **릴리스 대상 커밋**을 체크아웃해
   `--verify`로 체크섬을
   대조한다. 심기 목록 전건이 검증돼야
   한다 — 대조되지 못한 항목이 남으면 그 체크섬이 검증 없이 prod로 들어간다.
   릴리스 PR은 merge commit으로 들어가므로 6단계에서 `main`에 생기는 commit SHA는
   `release_head`와 다르지만, 릴리스 내용이 같다면 tree hash는 같아야 한다. base SHA는 검증 뒤
   `main`이 바뀌지 않았음을 확인하는 기준이다.

   ```bash
   release_pr=210
   release_head="$(gh pr view "$release_pr" --json headRefOid --jq .headRefOid)"
   release_base="$(gh pr view "$release_pr" --json baseRefOid --jq .baseRefOid)"
   release_tree="$(gh api "repos/Momens-Works/momens-server/git/commits/$release_head" --jq .tree.sha)"
   printf 'release_head=%s\nrelease_tree=%s\nrelease_base=%s\n' \
     "$release_head" "$release_tree" "$release_base"
   gh pr checkout "$release_pr" --detach
   test "$(git rev-parse HEAD)" = "$release_head"
   ```

   - 6단계 머지까지 `develop`에 다른 변경을 넣지 않는다. head SHA가 바뀌면 기존 검증은 릴리스
     대상을 증명하지 못한다. 심기 전이면 1단계부터 다시 하고, 심기 뒤면 릴리스를 멈추고 7절에
     따라 이력 테이블을 원복한 뒤 새 SHA로 다시 시작한다.
   - **대조 대상은 dev가 아니다.** dev는 `main` 릴리스로만 전진하는데 부트스트랩 전까지 릴리스를
     내지 않으므로 최신 마이그레이션이 없고, `--verify`가 구조적으로 실패한다.
   - **대조 대상의 이력은 앱의 Flyway가 만든 것이어야 한다.** 이것이 요점이다 — 스크래치 DB를
     CLI로 만들어 대조하면 CLI끼리 비교하는 순환이 된다.
   - **릴리스 대상 커밋의 bootJar를 빈 PostgreSQL에 직접 띄워 만든다.** 빈 DB에 앱을 붙이면
     Flyway가 전건을 적용하며 이력을 남기고, 그것이 대조 대상이다.

     ```bash
     ./gradlew :app:bootJar

     docker run -d --name verify-db \
       -e POSTGRES_DB=verifydb -e POSTGRES_USER=momens -e POSTGRES_PASSWORD=momens \
       -p 127.0.0.1:15498:5432 pgvector/pgvector:pg16
     until docker exec verify-db pg_isready -U momens -d verifydb >/dev/null 2>&1; do sleep 1; done

     # prod 프로필은 flyway.enabled=true 라 별도 토글이 필요 없다. auth 값은 @NotBlank
     # 검증만 통과하면 되므로 더미다 — 이 DB는 체크섬 산출에만 쓰고 버린다.
     SPRING_PROFILES_ACTIVE=prod \
     DATABASE_URL='jdbc:postgresql://127.0.0.1:15498/verifydb' \
     DATABASE_USERNAME=momens DATABASE_PASSWORD=momens \
     MOMENS_AUTH_JWT_SECRET="$(printf '0%.0s' {1..64})" \
     MOMENS_AUTH_GOOGLE_CLIENT_ID=dummy MOMENS_AUTH_GOOGLE_CLIENT_SECRET=dummy \
     MOMENS_AUTH_GOOGLE_AUDIENCES=dummy \
     MOMENS_AUTH_GOOGLE_REDIRECT_URI='http://localhost:8080/api/auth/google/callback' \
     MOMENS_AUTH_WEB_SUCCESS_REDIRECT_URI='http://localhost/' \
     MOMENS_AUTH_WEB_FAILURE_REDIRECT_URI='http://localhost/login' \
     CORS_ALLOWED_ORIGINS='http://localhost' SERVER_PORT=18096 \
       java -jar app/build/libs/app-0.1.0-SNAPSHOT.jar &

     # 이력이 리포의 마이그레이션 수만큼 찰 때까지 기다린 뒤 앱을 내린다. 기동 완료까지 갈
     # 필요는 없다 — 체크섬은 Flyway 가 이력을 쓰는 시점에 확정된다.
     expected="$(find . -path '*/src/main/resources/db/migration/V*.sql' \
       -not -path '*/build/*' | wc -l | tr -d ' ')"
     until [ "$(docker exec verify-db psql -U momens -d verifydb -tAc \
       'select count(*) from flyway_schema_history' 2>/dev/null)" = "$expected" ]; do sleep 2; done
     pkill -f 'app-0.1.0-SNAPSHOT.jar'

     PGPASSWORD=momens scripts/prod-flyway-bootstrap.sh --verify \
       'postgresql://momens@127.0.0.1:15498/verifydb'

     docker rm -f verify-db
     ```

     `--verify` 의 psql 은 컨테이너에서 돌므로 URL 의 loopback 호스트를 `host.docker.internal`
     로 바꿔 접속한다. 위 주소를 그대로 넣으면 된다.

     `scripts/legacy-diff`의 `server-db`도 `run.sh`가 bootJar를 띄우므로 같은 조건을 만족하지만,
     레거시 이미지 빌드와 계약 케이스까지 도는 무거운 경로다. 조건을 만족하는 최단 경로를 쓴다.
2. 같은 커밋에서 `--generate`로 INSERT 문을 만든다
3. **DDL 선행 조건 세 가지를 관리자가 적용한다.** 하나라도 없으면 부트스트랩이 죽는다. 쌍둥이
   리허설이 각각을 실패로 재현했다(아래 "쌍둥이 리허설").

   **소유권을 20개 일괄로 넘긴다.** 애초에 필요한 것은 `tasks` 하나였지만, ADR-0019가 정한 최종
   상태가 "서버가 prod 스키마를 소유한다"이고 `ALTER TABLE`은 GRANT 체계 밖이라 앞으로 레거시
   테이블을 건드리는 마이그레이션이 나올 때마다 같은 요청이 반복된다. 관리자 왕복이 이 작업의
   병목이었으므로 최종 상태를 한 번에 받는다.

   대상과 제외 근거는 `scripts/prod-ownership-transfer-tables.txt`에 있다. **서버가 마이그레이션
   파일을 갖는 레거시 테이블 20개**이고, `schema_migrations`(레거시 러너의 이력)·retrieval·
   MCP OAuth 테이블은 제외한다. 실행 집합이 만드는 12개는 서버가 직접 만들므로 자동으로 서버
   소유가 된다.

   ```sql
   -- (1) 창구가 momens_server 로 SET ROLE 할 수 있게 한다. **가장 먼저 실행한다** — 아래
   --     소유권 이전이 여기에 의존한다. Supabase 의 postgres 는 superuser 가 아니고,
   --     비-superuser 가 `ALTER TABLE ... OWNER TO X` 를 하려면 X 로 SET ROLE 할 수 있어야 한다.
   --
   --     **이전이 끝나면 다시 끈다** — GRANT momens_server TO postgres WITH SET FALSE.
   --     상시로 두면 창구 세션이 언제든 서버 role 로 갈아탈 수 있는 통로가 남는다. 4단계 심기가
   --     이 능력에 의존하지 않도록 만든 이유이기도 하다.
   GRANT momens_server TO postgres WITH SET TRUE;

   -- (2) 소유권 일괄 이전. scripts/prod-ownership-transfer.sh --generate 로 만든다.
   --     생성물은 이전과 `GRANT ... TO postgres` 재발급을 짝으로 묶고 전체를 한 트랜잭션에
   --     담는다 — 이유는 아래 참조.

   -- (3) 확장 스키마 접근. 상세는 3.5 단계에 있다. 둘 다 필요하다.
   GRANT USAGE ON SCHEMA extensions TO momens_server;
   ALTER ROLE momens_server SET search_path = "$user", public, extensions;
   ```

   **(2)가 앞선 개별 요청 둘을 흡수한다.** `tasks` 소유권은 물론이고, `users`의 `REFERENCES`도
   소유자가 되면서 불필요해진다. 쌍둥이의 `bulk-ownership` 시나리오가 이 상태에서 부트스트랩이
   끝까지 도는 것을 확인한다.

   **재발급은 `momens_server`로 갈아타서 해야 한다.** `ALTER TABLE ... OWNER TO` 직후 창구
   세션은 더 이상 그 테이블의 소유자가 아니고, `GRANT`는 소유자만 할 수 있다. 그대로 두면
   `permission denied for table`로 죽는다. 생성물이 각 테이블마다
   `SET LOCAL ROLE momens_server` → `GRANT` → `RESET ROLE`로 감싸는 이유다. `SET ROLE` 능력은
   위 `ALTER`가 이미 요구하므로 새 선행 조건이 아니다.

   > 이것은 쌍둥이의 멤버십을 prod 형상(`inherit=false`)으로 맞추자 드러났다. 그 전에는
   > `inherit=true`라 창구가 소유자 권한을 **상속**해 `GRANT`가 우연히 통과했다. 같은 상속이
   > `has_table_privilege` 검사도 통과시켜서, 재발급이 아예 빠져도 쌍둥이가 잡지 못했다.
   >
   > **`pg_auth_members.inherit_option`의 prod 값은 `false`다**(2026-08-29 재확인). 값이 무엇이든
   > 성립하도록 고쳤으므로 절차는 이 값에 의존하지 않지만, 쌍둥이가 결함 셋을 덮었던 축의 prod
   > 실측값으로 남긴다.

   **재발급이 짝으로 붙어야 하는 이유.** `ALTER TABLE ... OWNER TO`는 이전 소유자를 grantee로 하는
   ACL 항목을 새 소유자로 옮긴다. 그래서 소유권을 넘기는 순간 `postgres`가 그 테이블의 권한을
   잃는다. **레거시가 `postgres`로 접속하고 지금 실제 트래픽을 받는 쪽이 레거시다** — 재발급 누락은
   곧 레거시 장애다. `anon`·`authenticated` 등 다른 role은 grantee가 다르므로 영향이 없다(쌍둥이
   확인).

   **`GRANT ALL PRIVILEGES`는 대안이 아니다.** 7개 권한을 전부 줘도 `must be owner of table`이
   난다. `ALTER TABLE`은 GRANT로 도달할 수 없다 — 쌍둥이가 그것부터 확인한다.

   **(1)에는 전제가 하나 더 있다.** `GRANT <role>`은 그 role에 대한 `ADMIN OPTION`을 요구한다.
   `postgres`가 `CREATEROLE`로 `momens_server`를 직접 만들었다면 PG16+에서 자동으로 붙는다.

   ```sql
   -- (1) 이 통과할지 미리 본다. postgres 가 admin_option = true 로 나와야 한다.
   SELECT r.rolname AS member, m.admin_option, m.inherit_option, m.set_option
     FROM pg_auth_members m
     JOIN pg_roles r ON r.oid = m.member
     JOIN pg_roles g ON g.oid = m.roleid
    WHERE g.rolname = 'momens_server';
   ```

   prod 실측(2026-08-29 재확인): `postgres`가 `admin_option = true`, `inherit_option = false`,
   `set_option = false`. (1)이 통과하고, `set_option = false`가 소유권 이전이 막히던 원인이다.
   절차는 `inherit_option`에 의존하지 않도록(3단계 재발급) 유지한다.

   **소유권을 되돌린다면 DML 재발급이 함께 가야 한다.** `ALTER TABLE ... OWNER TO`가 소유자를
   grantee로 하는 ACL을 함께 가져가므로, 사전 GRANT로는 막을 수 없다(제3자가 부여해도 같다 —
   쌍둥이 확인). 20개를 되돌린다면 20개 전부에 재발급이 필요하다. 7절에 절차로 적었다.

   (1)이 없으면 **소유권 이전이** `must be able to SET ROLE "momens_server"` 로 죽는다. 심기는
   영향을 받지 않는다 — 생성물이 이력 테이블에 DML 만 부여하고 소유권을 넘기지 않기 때문이다
   (4단계). 쌍둥이의 `no-set-option` 이 셋을 나란히 확인한다: 소유권 이전은 막히고, 심기는
   되고, 부트스트랩도 완주한다. 다만 소유권이 없으면 그 뒤 실행 집합이 `must be owner of
   table tasks` 로 죽으므로 절차는 결국 그 자리에서 멈춘다.
3.5. **Supabase 고유 형상은 관측했고, 그 결과가 선행 조건 하나를 더 만들었다.** (2026-08-25 실측)

   | 관측 | 결과 |
   | --- | --- |
   | `pg_event_trigger` | **6개.** `supabase_admin` 소유, 전부 활성. `ddl_command_end` 4 + `sql_drop` 2 |
   | `postgres`의 `search_path` | `"$user", public, extensions` |
   | `momens_server`의 `search_path` | **`pg_db_role_setting`에 항목 없음** → 서버 기본값 `"$user", public` |
   | `uuid-ossp` 설치 스키마 | **`extensions`** (`vector`는 `public`, `pgcrypto`·`pg_stat_statements`는 `extensions`) |

   event trigger 넷은 우리 `CREATE TABLE`·`ALTER TABLE` 매 문장마다 발화하지만, 쌍둥이에 같은
   형상을 넣고 돌린 결과 부트스트랩에 영향이 없었다. `sql_drop` 둘은 실행 집합에 `DROP`이 하나도
   없어(7절 원칙) 발화하지 않는다.

   `search_path`는 다르다. **실행 집합 2건이 스키마 한정 없이 `uuid_generate_v4()`를 쓴다.**

   - `V20260810090000__create_user_identities.sql`
   - `V20260823110000__prod_bootstrap_task_role_and_checklist.sql`

   `uuid-ossp`가 `extensions`에 있고 `momens_server`가 그것을 보지 못하므로 **이대로면
   `function uuid_generate_v4() does not exist`(SQL State 42883)로 죽는다.** 쌍둥이에 prod 형상을
   넣자 정확히 그 지점에서 재현됐다.

   레거시가 지금 정상 동작하는 것은 반증이 아니다. 레거시는 `postgres`로 접속하고 그 role은
   `search_path`에 `extensions`를 갖는다. 기존 테이블의 `DEFAULT uuid_generate_v4()`도 생성 시점에
   OID로 굳어 있어 런타임에는 아무 신호가 없다 — **DDL 시점에만 터진다.**

   마이그레이션 파일을 `extensions.uuid_generate_v4()`로 고치는 길은 없다. 두 파일 모두 local·dev
   이력에 체크섬이 박혀 있어 고치면 그 환경들이 checksum mismatch로 죽는다. 레버는 role이다.

   ```sql
   -- (4) 확장 스키마를 momens_server 에게 보이게 한다. **둘 다 필요하다.**
   --     USAGE 가 없으면 search_path 에 넣어도 이름 해석에서 스키마가 보이지 않고,
   --     search_path 가 없으면 USAGE 가 있어도 한정 없는 호출이 해석되지 않는다.
   GRANT USAGE ON SCHEMA extensions TO momens_server;
   ALTER ROLE momens_server SET search_path = "$user", public, extensions;
   ```

   두 실패가 같은 파일(`V20260810090000`)에 있다는 점을 짚어 둔다. `search_path`를 고치면 그
   다음에 (2)의 `REFERENCES`가 드러난다 — 한 번에 하나씩 나오므로 셋을 함께 적용한다.

4. prod에 `flyway_schema_history` INSERT (단일 트랜잭션). 창구는 Supabase SQL Editor이고 그것은
   `postgres` 세션이다.

   **`momens_server`가 이력 테이블에 DML을 가져야 한다.** 이력 테이블을 만든 role이 소유자가 되고,
   `postgres` 세션이 만들면 `momens_server`는 아무 권한도 갖지 못해 다음 기동이
   `permission denied for table flyway_schema_history`로 죽는다. 심기 시점에는 신호가 없다 —
   심기는 성공한다.

   **소유권을 넘기지 않고 DML만 준다.** `--generate` 생성물이 트랜잭션 안에서 그렇게 한다.

   ```sql
   GRANT SELECT, INSERT, UPDATE, DELETE ON flyway_schema_history TO momens_server;
   ```

   소유권 이전이 아닌 이유는 3단계의 SET 능력이 **일회성이기 때문이다.** 관리자는 소유권 일괄
   이전 직후 `GRANT momens_server TO postgres WITH SET FALSE`로 그 능력을 다시 끈다(2026-08-27
   조치). 그 상태에서 `ALTER TABLE flyway_schema_history OWNER TO`는
   `must be able to SET ROLE`로 죽고 트랜잭션이 통째로 롤백된다. DML만 부여하면 SET 능력과
   무관하게 성립하고, Flyway는 이력 테이블에 DML만 하므로(잠금은 세션 advisory lock을 쓴다)
   그것으로 충분하다 — 쌍둥이의 `history-grant` 시나리오가 셋을 나란히 확인한다.

   `prod-flyway-bootstrap-verify-test.sh`가 생성물에 이 줄이 있는지, `BEGIN` 안에 있는지,
   `CREATE TABLE` 뒤인지, 그리고 소유권 이전으로 되돌아가지 않았는지를 검사한다.

   > Flyway 메이저 업그레이드가 이력 테이블 구조를 바꾸면 `ALTER`가 필요해져 소유권 문제가 다시
   > 생긴다. 그때는 업그레이드 절차가 소유권 이전을 함께 다뤄야 한다.

5. **릴리스 머지 직전 검증을 끝낸 뒤 `k8s` 토글 PR을 머지한다.** production dispatch에는 승인
   게이트가 없으므로 머지 뒤 tree 비교로 배포를 차단할 수 없다. 다음 검사를 통과한 같은 작업 창에서
   5·6단계를 연속 수행하고, 그 사이 다른 `main` 머지와 수동 `workflow_dispatch`를 금지한다.

   ```bash
   test "$(gh pr view "$release_pr" --json headRefOid --jq .headRefOid)" = "$release_head"
   test "$(gh api "repos/Momens-Works/momens-server/git/commits/$release_head" --jq .tree.sha)" = "$release_tree"
   test "$(gh pr view "$release_pr" --json baseRefOid --jq .baseRefOid)" = "$release_base"
   gh pr checks "$release_pr" --required
   # checks 조회 중 변경도 배제한다.
   test "$(gh pr view "$release_pr" --json headRefOid --jq .headRefOid)" = "$release_head"
   test "$(gh pr view "$release_pr" --json baseRefOid --jq .baseRefOid)" = "$release_base"
   ```

   `k8s` 리포 push 자체는 배포를 시작하지 않으므로 토글 PR 머지만으로는 스키마와 실행 Pod가
   바뀌지 않는다. **이 시점부터 6단계의 고정 릴리스가 dispatch를 보낼 때까지 수동 dispatch하지 않는다.**
6. **`develop` → `main` 릴리스 PR을 고정 head로 머지한다.** `k8s` 토글 PR 머지 뒤 head와 base를
   한 번 더 대조하고, 다른 head가 우연히 머지되는 것을 막는 옵션을 사용한다.

   ```bash
   test "$(gh pr view "$release_pr" --json headRefOid --jq .headRefOid)" = "$release_head"
   test "$(gh pr view "$release_pr" --json baseRefOid --jq .baseRefOid)" = "$release_base"
   gh pr merge "$release_pr" --merge --match-head-commit "$release_head"
   ```

   `main` push는 새 main SHA로 이미지를 만들고 곧바로 `repository_dispatch`로 `Deploy momens-server`를
   호출한다. 머지 직후 아래를 실행한다.

   ```bash
   main_head="$(gh api repos/Momens-Works/momens-server/branches/main --jq .commit.sha)"
   main_tree="$(gh api "repos/Momens-Works/momens-server/git/commits/$main_head" --jq .tree.sha)"
   test "$main_tree" = "$release_tree"
   ```

   이 비교는 **배포 게이트가 아니라 감사와 비상 중단 확인**이다. 다르면
   `Request production deploy` 전에 실행 중인 서버 워크플로를 즉시 취소하고 수동 dispatch하지 않는다.
   같으면 `Request production deploy`가 전달한 `image_tag`가 **merge 후 main SHA**인지 확인한다. 토큰
   부재 등으로 dispatch 단계가 건너뛰었을 때만 같은 main SHA로 수동 dispatch한다.
7. 기동과 스키마 확인
8. `out-of-order`·`group`·`init-sqls`를 제거하는 PR 머지 후 같은 main 이미지 SHA로 다시 dispatch
9. 후속 서버 릴리스로 `application-prod.yml`을 `flyway.enabled: true`로 정본화한 뒤 ConfigMap의
   `SPRING_FLYWAY_ENABLED` 오버라이드를 제거하고, 대장의 MOM-0909 수기 의무 4행을 제거

#### 토글은 수기 변경이 아니라 커밋이어야 한다

`deploy-service.sh`가 `kubectl apply -k`로 리포의 매니페스트를 적용한다. `kubectl edit`으로 넣은
ConfigMap 키는 **다음 배포에서 지워진다.** 토글은 `k8s` 리포에 커밋돼야 한다.

그것이 위험 구간을 배포 리포로 옮긴다. `Deploy momens-server`는 `workflow_dispatch`와
`repository_dispatch`로만 돌고 **`k8s` 리포 push로는 돌지 않는다.** 여기까지는 좋다 — 타이밍을 사람이
쥔다. 문제는 그 `repository_dispatch`를 보내는 것이 **momens-server의 `main` push**라는 점이다.
토글을 미리 머지해 두면 시딩 전에 나가는 무관한 릴리스가 그것을 적용해 버린다.

그래서 **토글 PR은 시딩(4단계)과 5단계의 릴리스 머지 직전 검사가 끝난 뒤에 머지한다.** 그때까지
draft로 둔다. 토글 머지 뒤에는 다른 서버 릴리스나 수동 dispatch를 끼우지 않고, 1단계에서 SHA를
고정한 릴리스 PR을 바로 머지한다.

### 배포 리포(`k8s`)도 함께 바꿔야 한다

설계 초안에 빠져 있던 항목이다. MOM-0909의 범위에 `k8s` 리포 PR이 하나 포함된다.

- `manifests/apps/momens-server/deployment.yaml` — DB env 주석의 *"read/validate only in prod: Flyway
  disabled, Hibernate ddl-auto=validate; schema is owned by legacy momens-api"* 가 사실과 어긋나게 된다.
- `manifests/apps/momens-server/configmap.yaml` — 위 5단계 토글이 들어가고 8단계에서 일부 빠진다.
- `docs/secrets.md` — *"`ENV=production` also defaults the worker's `MIGRATIONS_ENABLED` to false so the
  API stays the single migration owner on the shared database"* 는 이미 worker ConfigMap과 어긋나 있고
  (2.1절), 주도권 이전으로 두 번째로 틀리게 된다.

### 리허설 환경은 이미 있다

MOM-0909가 요구하는 "prod와 같은 형상(레거시 러너가 만든 스키마 + Flyway 이력 없음)"은
`scripts/legacy-diff`의 `legacy-db`가 그대로 만족한다. 별도 구축이 필요 없다.

단, 리허설 DB에는 prod 데이터가 없다. 기존 행이 있어야만 드러나는 실패(`SET NOT NULL`, CHECK 위반)는
리허설로 잡히지 않으므로 객체 대조로 미리 닫는다.

리허설이 실질적인 게이트라는 점을 짚어 둔다. 하네스의 `compose.yml`은 DB를 둘로 나눈 이유를 *"레거시와
신규 서버가 같은 테이블의 DDL을 각자 소유해 한 DB에 두 마이그레이션을 함께 돌릴 수 없습니다"* 라고
적는다. 우리 계획은 둘 다 돌리지 않고 **심어서** 그 제약을 우회하는 것이므로, 그 우회가 실제로 성립하는지는
리허설로만 증명된다.

### 리허설 결과 (MOM-0909, 2026-08-23)

레거시 마이그레이션 `000001`~`000019`를 빈 DB에 순서대로 적용해 prod 형상을 만들고(테이블 32개,
`flyway_schema_history` 없음) 전 구간을 실측했다. Flyway는 앱과 같은 12.4.0 CLI를 썼다.

| 단계 | 결과 |
| --- | --- |
| CLI와 앱의 체크섬이 같은가 | **공통 38건 전부 일치, 불일치 0건.** 앱이 만든 이력(`legacy-diff`의 `server-db`)과 대조했다 |
| 이력 28건 INSERT | 성공. 최고 version `20260823100000` |
| `outOfOrder` 없이 migrate | **거부.** *"Detected resolved migration not applied to database: 20260716091000. … To allow executing this migration, set `-outOfOrder=true`"* — 스키마는 하나도 바뀌지 않았다 |
| `outOfOrder=true` + `group=true` | **정확히 14건만 실행.** 심은 28건은 건너뛰고 실패 0건. 최종 version `20260823110000` |
| 결과 스키마 | required 12개 테이블 전부 생성, `tasks`에 `role`·`origin_type`·`origin_signal_id`·`next_action` 추가, 레거시 이력 19건과 테이블 온전 |
| `tasks_role_check` 귀속 | `tasks`에 정확히 걸림 (가드가 `conrelid`를 함께 보도록 고친 뒤 재확인) |
| 부트스트랩 후 `outOfOrder`·`group` 없이 재기동 | *"Schema is up to date. No migration necessary."* — **1회성으로 끄는 설계가 성립한다** |

2.4의 동작 예측이 Flyway의 에러 메시지로 직접 확인됐고, 그 메시지가 해법까지 지목한다.

### 쌍둥이 리허설 (MOM-0909, 2026-08-25)

위 리허설은 `legacy-db`에서 돌았는데 그 DB는 **pg16 · superuser · 빈 DB · 무트래픽**이라 prod와
네 가지가 동시에 달랐다. 그래서 재현할 수 없는 축이 남았고, 그것을 닫기 위해 prod와 같은 형상을
로컬에 세웠다. 구축과 시나리오는 `scripts/prod-twin` 이었고 이전 완료 후 제거했다(MOM-0947).
아래 형상과 결과는 그 환경에서 실측한 것이다.

PostgreSQL 17 · 레거시 `000001`~`000019` · Supabase role 형상(`anon`·`authenticated`·
`service_role` + `ALTER DEFAULT PRIVILEGES`) · **비-superuser 창구 role**(SQL Editor 대역이자
public 테이블의 소유자) · 비-superuser 무소유 `momens_server` · `tasks` 10만 행. 앱은 릴리스와 같은 prod 프로필 bootJar이고 토글은 ConfigMap이 넣을 환경변수
형태로 준다.

#### 계획을 고친 발견 다섯 가지

**1. `tasks` 소유권만으로는 부족하다 — `users`에 `REFERENCES`가 필요하다.**

`tasks` 소유권을 넘긴 정상 경로가 `permission denied for table users`로 죽었다. 죽은 위치는
`V20260810090000__create_user_identities.sql`이고, 이 파일이 `users(id)`를 참조하는 FK를 건다.
FK를 거는 쪽은 참조당하는 테이블에 `REFERENCES` 권한이 필요한데 이것은
`SELECT`/`INSERT`/`UPDATE`/`DELETE`에 포함되지 않는 **별개 권한**이다. 관리자가 마친 조치는 DML
GRANT뿐이었다.

실행 집합 전체의 FK 대상은 `tasks`와 `users` 둘뿐이고, `tasks`는 소유권 이전으로 이미 해결된다.
따라서 추가로 필요한 것은 `GRANT REFERENCES ON users TO momens_server` 한 줄이다 — 소유권 이전은
필요 없다는 것도 쌍둥이에서 확인했다.

**2. `momens_server`가 이력 테이블에 DML을 가져야 한다.**

부트스트랩 SQL이 `flyway_schema_history`를 만들고, Postgres에서는 만든 role이 소유자가 된다.
`postgres`로 실행하면 `momens_server`가 그 테이블에 아무 권한도 갖지 못하고 다음 기동이
`permission denied for table flyway_schema_history`로 죽는다. **Supabase SQL Editor는 `postgres`
세션이므로 가장 자연스러운 경로가 곧 틀린 경로다.**

`momens_server`로 psql을 여는 것으로는 해결되지 않는다 — 그 비밀번호를 아는 사람이 없다(8절
4단계). **`--generate` 생성물이 트랜잭션 안에서 DML을 부여한다.** 처음에는 소유권 이전으로 썼으나,
운영이 소유권 일괄 이전 직후 SET 능력을 다시 끄기 때문에 그 방식이 성립하지 않는다(8절 4단계).
Flyway는 이력 테이블에 DML만 하므로 소유권이 필요 없다는 것을 쌍둥이의 `history-grant` 시나리오가
확인한다.

**3. Supabase 의 `postgres`는 superuser가 아니고, 그래서 소유권 이전이 공짜가 아니다.**

첫 쌍둥이는 컨테이너의 부트스트랩 `postgres`(superuser)로 운영자 조작을 흉내냈다. superuser는
`ALTER TABLE ... OWNER TO`의 SET ROLE 검사를 통째로 건너뛰므로 **위 두 발견의 처방이 무비판적으로
통과했다.** 실제 Supabase의 `postgres`는 superuser가 아니다(기존 20개 테이블의 소유자가 그것이라는
실측과도 일치한다).

그래서 창구 대역을 `NOSUPERUSER CREATEROLE`인 별도 role로 바꾸고 public의 테이블 소유권을 그쪽으로
옮겨 다시 쟀다. 결과는 **비-superuser는 대상 role로 `SET ROLE`할 수 있어야 소유권을 넘길 수 있다**는
것이고, PG16+에서 `CREATEROLE` role이 만든 role의 자동 멤버십은 그 용도에 못 미친다.

```
grantee=sb_postgres  admin=true  inherit=false  set=false
→ ALTER TABLE t OWNER TO app  →  ERROR: must be able to SET ROLE "app"
```

`SET ROLE momens_server`가 되는지 불확실하다고 본 것과 같은 뿌리다. 둘 다 같은 멤버십에 달려 있고,
없으면 **둘 다 안 된다.** 8절 3단계에 `GRANT momens_server TO postgres WITH SET TRUE`를 세 번째
선행 조건으로 넣었다.

**4. `momens_server`는 `extensions` 스키마를 보지 못한다.**

첫 쌍둥이는 레거시 마이그레이션이 만든 그대로 확장이 `public`에 있었다. prod는 Supabase가
`uuid-ossp`를 `extensions`에 미리 깔아 둔 상태여서 레거시의 `CREATE EXTENSION IF NOT EXISTS`가
no-op이었다. 그 차이가 실행 집합 2건을 죽인다. 상세와 처방은 8절 3.5단계에 있다.

**이 축은 "재현이 어려우니 남겨 둔다"로 끝날 뻔했다.** 관측 쿼리 두 개로 형상을 받아 쌍둥이에
넣었더니 블로커가 나왔다. 재현하지 못해 남겨 두는 것과, 관측해서 닫는 것은 다르다.

**5. 개별 요청을 반복하는 대신 최종 상태를 한 번에 받는다.**

발견 1~4는 전부 "관리자에게 한 줄 더 요청"으로 끝났고, 매 왕복마다 다음 블로커가 드러났다. 관리자
왕복이 이 작업의 병목이었다.

ADR-0019가 정한 최종 상태는 서버가 prod 스키마를 소유하는 것이다. `ALTER TABLE`이 GRANT 체계 밖에
있으므로 **앞으로 레거시 테이블을 건드리는 마이그레이션이 나올 때마다 같은 요청이 반복된다.**
그래서 소유권 20개를 일괄로 넘기는 쪽으로 바꿨다(8절 3단계).

부수 효과가 둘이다. 발견 2의 `users` `REFERENCES`가 소유권에 흡수돼 **선행 조건이 오히려 줄고**,
레거시의 DDL 동결이 물리적으로 강제된다 — 9절이 "강제 수단 없음"으로 남겨 둔 위험이다.

대가는 폭발 반경이다. 소유권 이전은 이전 소유자의 권한을 가져가고 레거시는 `postgres`로 접속한다.
생성 스크립트가 이전과 재발급을 짝으로 묶어 한 트랜잭션에 담는 것이 그 대응이다.

#### 권한 표면은 이 둘로 닫혔다

같은 유형('DML도 소유권도 아닌 제3의 권한')의 누락이 더 있는지 실행 집합 14건을 전수로 봤다
(별도 세션의 독립 대조로도 같은 결과).

(아래는 *마이그레이션이 요구하는 객체 권한*의 표면이다. 발견 3의 `SET ROLE`과 발견 4의
`search_path`는 객체 권한이 아니라 role 설정이라 별개 축이다.)

- FK 참조 대상은 정확히 `tasks`와 `users` 둘뿐이다.
- 기존 객체를 건드리는 동작은 `tasks`의 `ALTER`뿐이다.
- `signals`·`signal_evidence`·`outbox_events`·`task_open_questions`에 대한 `CREATE INDEX`와
  `ALTER`는 전부 실행 집합이 스스로 만드는 테이블이라 `momens_server` 소유다.

**세 번째는 없다.**

#### 실측 결과

| 시나리오 | 결과 |
| --- | --- |
| **baseline** 선행 조건 → 심기 28행 → 부트스트랩 → `validate` 기동 | 이력 42행 = 심기 28 + 실행 14, 실패 0. `tasks`에 `role`·`origin_type`·`next_action` 셋 다, `tasks_role_check`는 `tasks`에 귀속, `task_roles`는 생성되지 않음, 기존 10만 행 온전 |
| **baseline** 토글 없이 재기동 | `is up to date. No migration necessary` — 1회성으로 끄는 설계가 PG17·비-superuser에서도 성립한다 |
| **no-ownership** `tasks` 소유권 이전 생략 | `must be owner of table tasks`로 기동 실패. 새 테이블 0개 |
| **no-references** `users` `REFERENCES` 누락 | `permission denied for table users`로 기동 실패. 새 테이블 0개 |
| **no-set-option** 창구가 `momens_server`로 `SET ROLE` 불가 | 소유권 이전이 `must be able to SET ROLE`로 실패. **심기와 부트스트랩은 SET 능력 없이도 성립한다** — 생성물이 DML만 부여하기 때문이다 |
| **bulk-ownership** 레거시 테이블 20개 일괄 이전 | `GRANT ALL PRIVILEGES`로도 `ALTER`는 불가. 이전 후 20개 전부 서버 소유이고 레거시(`postgres`)가 전부 읽고 쓴다. `anon` 영향 없음. `schema_migrations`는 레거시 소유로 유지. 그 상태에서 부트스트랩 42건 완주 |
| **no-search-path** `momens_server`가 `extensions`를 못 봄 | `function uuid_generate_v4() does not exist`로 `V20260810090000`에서 실패. `USAGE`와 `search_path` **둘 다** 줘야 통과 |
| **ownership-reverted** 부트스트랩 성공 후 `tasks` 소유권 원복 | `momens_server`의 DML이 소유권을 따라 사라짐. 조회는 `permission denied for table tasks`인데 **기동은 성공한다** — `validate`가 DML 권한을 보지 않는다. 재발급하면 복구 |
| **history-grant (a)** 이력 테이블 권한을 주지 않음 | `permission denied for table flyway_schema_history`로 기동 실패 |
| **history-grant (b)** DML 대신 소유권 이전으로 주려 함 | SET 능력이 꺼져 있어 `must be able to SET ROLE`로 심기 실패, 트랜잭션 롤백 |
| **history-grant (c)** 생성물 그대로 (DML 부여) | 이력 테이블 소유자는 `postgres` 그대로, 앱이 42건을 끝까지 돌고 기동 성공 |
| **lock** 레거시가 `tasks`를 60초간 ACCESS EXCLUSIVE로 점유 | `canceling statement due to lock timeout`으로 포기. 락이 풀릴 때까지 기다리지 않았다. 새 테이블 0개. 구간은 아래에 분해했다 |
| **checksum** 심은 체크섬 하나를 +1 | `checksum mismatch`로 기동 실패, 스키마 무변경. `--verify`가 해당 version을 이름으로 지목 |

#### 멤버십 형상이 세 결함을 동시에 가리고 있었다 (2026-08-28)

위 표는 쌍둥이의 창구 멤버십이 `inherit=true`인 상태에서 얻은 것이다. prod는 `postgres`가
`CREATEROLE`로 `momens_server`를 만들어 생긴 자동 멤버십이라 `inherit=false`다. 이 한 칸이
다르면 창구가 `momens_server`의 권한을 **상속**하고, 그것이 셋을 한꺼번에 덮는다.

| 덮인 것 | 성격 |
| --- | --- |
| 소유권 이전 직후의 `GRANT`가 `permission denied`로 죽는다 | **prod에서 실패했을 실제 버그.** 창구는 이전 직후 소유자가 아니다 |
| 생성물의 재발급 대상(`postgres`)과 시나리오 판정 대상(`sb_postgres`)이 다르다 | 검증이 애초에 성립한 적이 없다 |
| 재발급이 아예 빠져도 `has_table_privilege`가 `true`로 답한다 | 안전장치가 무력 |

처방은 셋이다. `roles.sql`이 멤버십을 prod 형상으로 명시해 만들고 `reset_db`가 그 형상으로
복원한다(매 시나리오 시작에 assert). 생성물이 재발급을 `SET LOCAL ROLE momens_server`로 감싼다.
창구 이름은 `PROD_OWNER_ROLE`로 바꿔 끼운다(기본값이 prod의 `postgres`다).

**이 축의 교훈은 "검증 코드도 조용히 틀린다"이다.** 39/39는 세 결함을 안은 채로 나온 값이었고,
어느 시나리오도 실패하지 않았다. 락 구간 값을 출력만 하고 판정하지 않던 것도 같은 유형이라
함께 hard assertion으로 바꿨다(현재 42건).

`group=true` 덕분에 **모든 실패 경로에서 스키마가 하나도 바뀌지 않았다.** 실행 집합 14건이 한
트랜잭션이라 중간에 죽으면 통째로 롤백된다. 롤백 절차(7절)가 실제로 필요해지는 구간이 좁다.

#### 락 구간의 실제 상한

락은 방향이 둘이고 창구에서 잡을 예산이 다르다. 둘 다 실측했다.

**우리가 막히는 쪽** — 레거시가 `tasks`를 이미 잡고 있을 때.

| 구간 | 값 |
| --- | --- |
| 첫 로그 → Flyway가 DB를 잡음 | 2.9~3.8초 (JVM + Spring 컨텍스트) |
| DB를 잡음 → 포기 | **5.3~5.8초** ← 절차에 적을 값 |
| 프로세스 전체 (wall-clock) | 10~11초 |

값이 흔들리는 것은 JVM 기동과 트랜잭션 abort 처리가 매 실행 같지 않기 때문이다. **하네스가
강제하는 상한은 8.0초**(`rehearse.sh`의 `lock_held_max`)이고, 위는 그 아래에서 관측된
범위다. 절차 예산은 관측값이 아니라 그 상한으로 잡는다 — 관측값을 상한으로 쓰면 다음 실행이
0.1초 느려졌을 때 근거가 무너진다.
| `lock_timeout` 소진 횟수 | **1회** |

`lock_timeout`이 한 번만 소진되는 이유가 `group=true`에 있다. 실행 집합 14건이 한 트랜잭션이라
`tasks`를 처음 건드리는 `V20260714091000`에서 막히면 5초 뒤 트랜잭션 전체가 abort하고 두 번째
시도가 없다. **따라서 상한은 `lock_timeout` × 1이지 × 5(=`tasks`를 건드리는 문장 수)가 아니다.**
프로세스 wall-clock 11초에는 JVM 기동·종료가 섞여 있으므로 예산 산정에 쓰면 과대평가가 된다.

**레거시가 막히는 쪽** — 우리가 락을 잡는 데 성공했을 때. `tasks`에 200ms 간격으로 짧은
`UPDATE`를 던지는 세션을 띄우고 각 문장의 소요를 쟀다. `pg_locks`를 폴링해 재지 않는다 —
왕복이 폴링 간격보다 커서 표본이 한두 개밖에 잡히지 않고, 그러면 구간 길이를 말할 수 없다.

**쌍둥이의 `tasks`는 10만 행이고 prod의 `tasks`는 10만 행을 넘지 않는다(TL 확인).** 따라서 아래
값은 "10만 행 기준 추정"이 아니라 **상한**이다.

| 실행 | 최장 문장 | 100ms 초과 문장 |
| --- | --- | --- |
| 1회차 | 342 ms | 1 |
| 2회차 | 316 ms | 1 |
| 3회차 | 161 ms | 1 |

**막히는 것은 언제나 정확히 한 문장이고 0.2~0.4초다.** `tasks`에 거는 `ADD COLUMN`은 PG11+에서
메타데이터 조작이라 볼륨과 무관하고, `tasks_role_check`·`tasks_origin_signal_check`의 검증
스캔만 전체를 훑는데 10만 행에서 그 정도다. 락은 첫 `ALTER`부터 커밋까지 유지되지만 그 뒤에
남은 것이 전부 신규 테이블 `CREATE`라 짧다.

즉 **레거시 트래픽의 실제 영향은 1초 미만의 지연 한 번**이고, 잡아야 할 창구는 그것이 아니라
우리가 실패했을 때의 재시도 여지다.

#### 규모가 커져도 마이그레이션 파일은 레버가 아니다

이 값이 문제가 될 만큼 `tasks`가 커진 상황을 가정하면, 교과서적 처방은 CHECK 제약을
`ADD CONSTRAINT ... NOT VALID`로 걸고 `VALIDATE CONSTRAINT`를 따로 돌려 ACCESS EXCLUSIVE 보유를
거의 없애는 것이다. **그러나 그 처방을 이 두 파일에 적용할 수 없다.**

`V20260714091000`과 `V20260823110000`은 이미 local·dev의 `flyway_schema_history`에 체크섬이
박혀 있다. 파일을 고치면 그 환경들이 checksum mismatch로 기동에 실패한다. 손댈 수 있는 것은
파일이 아니라 **새 version의 보정 마이그레이션**이고, 그것도 제약이 이미 걸린 뒤에는 의미가 없다.

정리하면 이 구간의 레버는 마이그레이션 파일이 아니라 **창구 선택**(트래픽이 적은 시각)과
`lock_timeout`이다. 다음 사람이 파일을 고치려 들지 않도록 적어 둔다.

#### 닫힌 축과 남은 축

닫힌 것: **PG17**(레거시 19건·Flyway 42건 모두 적용됨) · **`momens_server` role**(비-superuser
무소유로 전 구간 통과) · **`tasks` 소유권**(필요함을 실패로 확인) · **실제 데이터**(10만 행에서
제약 위반 없음, 행 손실 없음) · **락 경합**(양방향 모두 측정됐다. 우리가 막히면 `lock_timeout` 1회
소진으로 5.4초, 레거시가 막히면 1초 미만 한 문장. 그동안 "틀려도 조용한" 축이었다) · **실패 복구**(전 실패 경로에서 스키마 무변경).

남은 것 둘은 로컬에서 닫히지 않는다.

~~**pooler 여부.**~~ **닫혔다** (2026-08-26 관리자 확인). DB 포트가 `5432`다 — 트랜잭션
  pooler(`:6543`)가 아니므로 세션이 유지되고, Flyway의 세션 단위 advisory lock과 `init-sqls`가
  성립한다. 쌍둥이가 전제한 것은 세션 유지이고 그 조건이 맞았으므로 락 측정값이 그대로 유효하다.
- **배포 기전.** `k8s` ConfigMap → 롤아웃 경로는 `momens-k8s-dev`의 dev 클러스터에서 별도 컨펌 후
  확인한다.
~~**Supabase 고유 형상.**~~ **닫혔다.** 관측 결과 event trigger 6개와 확장 스키마 분리가 있었고,
둘 다 쌍둥이에 넣어 재현했다(8절 3.5단계). event trigger는 무해했고, 확장 스키마는 블로커였다.

#### 곁가지 관측 — Data API와 새 테이블

Supabase는 `ALTER DEFAULT PRIVILEGES FOR ROLE postgres`로 **`postgres`가 만드는** 테이블에
`anon`·`authenticated`·`service_role` 권한을 자동으로 붙인다(MOM-0925가 관측한 노출의 기전이다).
부트스트랩이 만드는 12개 테이블의 소유자는 `postgres`가 아니라 `momens_server`이므로 **여기에
걸리지 않는다.** 쌍둥이에서 `has_table_privilege('anon', 'signals', 'SELECT')`가 `false`로 나온다.

노출 면에서는 개선이지만, Data API로 서버 소유 테이블을 읽는 소비자가 있다면 부트스트랩 시점에
조용히 끊긴다. 소비자 목록은 관리자 회신 대기 중이다(MOM-0925).

### 부트스트랩 적용 결과 (MOM-0909, 2026-09-04)

릴리스 #210(`ef06ec1d`)로 prod 에 적용됐다. Pod 로그와 `flyway_schema_history` 로 실측했다.

| 확인 | 결과 |
| --- | --- |
| 심기 후 시작 version | `20260823100000` (28건) |
| 실행 | **14건**, 13건이 `[out of order]` + 보정 `20260823110000` 이 in-order. 실패 0건, 00:00.586s |
| 최종 이력 | `count = 42`, `max = 20260823110000`, `bool_and(success) = true` |
| 기동 | `ddl-auto=validate` 통과, 51.0초, ERROR 0건 |
| 롤아웃 | 61초에 Ready. 이후 재시작 0회 |

리허설이 예측한 분할(심기 28 / 실행 14)과 최종 version 이 그대로 재현됐다.

#### 접속 대상

prod 는 session pooler(`aws-0-ap-southeast-1.pooler.supabase.com:5432`)로 붙는다. direct 는
IPv6 전용이라 IPv4 노드로 구성된 GKE 에서 도달하지 못한다 — 2026-08-28 배포가 그 경로로
기동에 실패했고, `MOMENS_SERVER_DATABASE_URL` 을 session pooler 로 바꾼 09-04 재실행에서
성공했다(8절 0단계).

session pooler 가 세션을 유지하므로 Flyway 의 세션 단위 잠금과 `init-sqls` 가 성립한다 — 위
실측이 그것을 보인다.

## 9. 남은 위험

| 위험 | 대응 |
| --- | --- |
| 레거시·worker가 동결을 어긴다 | 강제 수단 없음. 합의 + 다음 배포의 `validate`로 사후 탐지. 제약·기본값·UNIQUE 변경은 탐지되지 않는다 |
| local·prod가 완전히 같지 않다 | 2.6의 5개 컬럼. 의도된 것이며 `validate` 대상이 아니다 |
| 레거시가 만든 20개 테이블에서 local과 prod의 DDL이 갈린다 | 주도권 이전으로 사라지지 않는 위험이다(6절). `persistence.md`의 충실도 규칙이 계속 적용된다 |
| 롤아웃 실패 후 정리가 안 된 채 남는다 | `deploy-service.sh`에 `rollout undo`가 없다. 수동 정리 단계를 절차에 명시한다 |
| `tasks` 소유권을 되돌리며 DML 재발급을 잊는다 | 기동이 성공해 배포에서 잡히지 않고 첫 요청에서 끊긴다. 7절에 재발급 한 줄을 절차로 적었다 |
| Data API 소비자가 서버 소유 테이블을 읽고 있다 | 새 12개 테이블은 `momens_server` 소유라 `anon`·`authenticated`에 권한이 붙지 않는다. 소비자 목록은 MOM-0925에서 확인 중이다 |
| 접속이 트랜잭션 pooler다 | 해소. prod 는 session pooler(`aws-0-ap-southeast-1.pooler.supabase.com:5432`)를 쓰고 세션이 유지된다(2026-09-04 적용 실측). 접속 정보를 교체할 때 포트가 `6543`(transaction 모드)이 되지 않도록 본다 |

## 관련 문서

- [ADR-0019 prod 스키마 주도권을 서버로 이전](../adr/0019-prod-schema-ownership-transfer.md)
- [데이터 규칙](../rules/persistence.md)
- [prod 운영 준비 대장](../prod-readiness-ledger.md)
- [레거시 Product API 이관 전략](legacy-product-api-migration/strategy.md)
