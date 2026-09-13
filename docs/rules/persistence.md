# 데이터

영속성(DB · JPA · Flyway)과 시간 · 식별자 규칙입니다.

## 영속성 (DB · JPA · Flyway)

### DB

- PostgreSQL만 지원합니다. 초기 DB 접근은 JPA. QueryDSL은 필요 시 추가합니다.
- DB 통합 테스트는 PostgreSQL Testcontainers를 사용합니다(H2 미사용).

### 엔티티

- `@Getter` 허용, `@Setter` 지양, `@NoArgsConstructor(access = AccessLevel.PROTECTED)` 필수,
  `@Builder` 허용.
- 생성자·정적 팩토리·`@Builder`는 생성 의도와 필드 수에 따라 선택합니다.
- 자기 상태·불변식 로직은 엔티티에 둡니다([코드 > Spring > 레이어 책임](code-conventions.md#레이어-책임)).
- `equals`/`hashCode`에서 연관 필드는 제외합니다.
- `BaseEntity` 상속은 엔티티가 `id`·`created_at`·`updated_at`을 모두 매핑할 때입니다. 셋 중 하나라도
  매핑하지 않으면 `@Id`를 직접 둡니다. 읽기 전용인지 여부는 기준이 아닙니다. 쓰기 경로가 없는 엔티티는
  `@Immutable`로 막되, 감사 3종을 매핑한다면 상속합니다.

### 마이그레이션 (Flyway)

- Flyway로 관리하고, 위치는 `classpath:db/migration`입니다.
- 이미 적용된 마이그레이션은 수정하지 않습니다(변경은 새 마이그레이션으로).
- 각 Gradle 모듈이 자기 마이그레이션을 소유하고(`<module>/src/main/resources/db/migration`),
  `app` 클래스패스에서 하나의 Flyway 이력으로 병합됩니다. 공유 확장(`uuid-ossp` 등)은
  `common`이 소유합니다.
- 파일명은 타임스탬프 버전 + snake_case 설명: `V<yyyyMMddHHmmss>__<설명>.sql`
  (예: `V20260624090100__create_user.sql`). 타임스탬프 버전으로 모듈 간 버전 충돌을 피하고
  병합 시 실행 순서를 보장합니다.
- Spring Modulith event publication registry의 `event_publication` 테이블도 Flyway로
  관리합니다. 첫 application event 도입 시 함께 추가합니다([아키텍처](architecture.md), [ADR-0001](../adr/0001-modular-monolith-rules.md)).
- 운영(`prod`)의 스키마는 **이 서버가 소유합니다**([ADR-0019](../adr/0019-prod-schema-ownership-transfer.md)).
  마이그레이션은 배포 때 Flyway가 직접 적용하고, 잘못된 마이그레이션은 그 시점에 Flyway가 막습니다.
  `local`·`test`·`dev`와 같은 모델입니다. 부트스트랩(MOM-0909)이 2026-09-04에 적용돼 이 모델이
  prod에서 실제로 돌고 있습니다.
  - **적용된 마이그레이션 파일은 고치지 않습니다.** prod에 이력이 남은 뒤로는 파일을 수정하면
    checksum이 어긋나 **다음 배포의 기동이 실패합니다.** 바꿔야 할 것이 있으면 새 마이그레이션을
    추가합니다.

### 값 집합(CHECK 제약과 enum)

- 컬럼의 허용 값 집합은 공유 DB의 CHECK 제약을 기준으로 하며, 이 레포지토리의 Flyway 마이그레이션을 통해서만
  변경합니다([ADR-0022](../adr/0022-column-value-set-ownership.md)).
- 테이블을 관리하는 모듈에는 컬럼별로 해당 값 집합을 표현하는 도메인 enum을 하나만 둡니다. CHECK 제약과 enum의
  값 집합이 다르면 그 차이를 `CheckConstraintEnumLinks`에 선언합니다. 값 집합을 제한하는 모든 CHECK 제약은 해당
  목록에 등록해야 하며, 등록되지 않았거나 선언한 차이와 실제 값 집합이 일치하지 않으면
  `CheckConstraintEnumConsistencyTest`가 실패합니다. 도메인 enum과 동일한 값 집합을 가진 별도의 enum은 이 목록에
  등록하지 않습니다.
- mobile과 web 모듈은 값 집합을 별도로 선언하지 않고 도메인 enum을 참조합니다. 표시 순서와 라벨, 레거시 입력
  별칭은 각 모듈에서 관리합니다. 컬럼별로 도메인 enum을 하나만 두는 원칙과 이 참조 규칙은 테스트로 검출할 수
  없으므로 리뷰에서 확인합니다.
- 값을 추가할 때는 Flyway 마이그레이션, 도메인 enum과 `CheckConstraintEnumLinks`를 같은 PR에서 변경합니다. 값을
  제거할 때는 쓰기 경로에서 해당 값을 먼저 제거하고 기존 데이터를 정리한 뒤 CHECK 제약을 축소합니다.

### 레거시·worker DDL 동결

공유 DB의 DDL writer는 이 서버 하나가 아닙니다. `momens-api`와 `momens-worker`가 자체 러너로
`schema_migrations`에 마이그레이션을 적용합니다(양쪽 `k8s` ConfigMap이 `MIGRATIONS_ENABLED=true`).
Flyway의 `flyway_schema_history`와는 별개의 이력이라 한 DB에서 공존합니다.

**이 서버가 마이그레이션 파일을 가진 객체의 DDL은 레거시와 worker가 쓰지 않습니다.** 경계는
"이 리포에 그 객체를 만드는 파일이 있는가"이고, 겹치는 20개 테이블이 실제 동결 대상입니다
(목록은 [설계 5절](../design/prod-schema-ownership-transfer.md)). 기간 한정이 아니라 영구입니다.

바깥쪽은 각자 계속 소유합니다 — 레거시 전용(`oauth_*`, `decisions`, `retrieval_*`, `sync_states`)과
worker 전용(`raw_source_events` 등)입니다. 이 서버는 그 객체들의 파일도 엔티티도 갖지 않습니다.

동결에 강제 수단은 없습니다. 위반은 다음 배포의 `ddl-auto=validate`가 기동에서 사후에 잡는데,
`validate`는 매핑된 컬럼의 존재와 타입만 보고 제약·기본값·UNIQUE는 보지 않으므로 완전하지 않습니다.

### 레거시가 만든 테이블을 기술하는 마이그레이션

레거시가 이미 생성한 객체를 `local`과 `test` 환경에서도 생성하는 파일은 **25건**입니다. 헤더 체계를
사용하던 시점에 추가한 23건과 `create_refresh_token`, `add_source_ref_content_hash`가 해당합니다.

`create_refresh_token`의 헤더는 `applied momens-api#10`이지만 운영 객체는 레거시
`000018_refresh_tokens.sql`에서 생성되었으며, 두 파일의 DDL도 현재 일치하므로 같은 유형으로
분류합니다. `add_source_ref_content_hash`는 헤더 체계를 폐지한 뒤 추가한 파일이며, 대상 컬럼과
인덱스는 레거시 `000015_source_refs_content_hash.sql`에서 생성되었습니다.
주도권이 넘어와도 **이 파일들의 성격은 바뀌지 않습니다.** prod의 실물은 레거시 DDL이 만든 것이고,
이 파일은 `local`/`test`용 재구성이기 때문입니다. 2026-09-04 부트스트랩에서 이 25건은 실행되지 않고
이력에만 심겼습니다.

그래서 **"local은 통과하는데 prod에서 깨진다"는 위험이 이 25건에 대해서는 그대로 남습니다.**
`ddl-auto=validate`는 공통 설정이라 local에서도 돌지만, local이 검증하는 대상은 이 파일이 만든
테이블이고 prod가 검증하는 대상은 레거시가 만든 실물입니다. 둘이 갈리면 local만 통과합니다.

> **재구성은 prod가 거부할 것을 거부해야 하고, prod가 허용하는 상태를 만들 수 있어야 한다.**

느슨하면 prod가 거부할 행을 테스트가 통과시키고, 엄격하면 prod에 실재하는 상태를 픽스처가 재현하지
못합니다.

| 항목 | 기준 | 이유 |
| --- | --- | --- |
| 컬럼 이름·타입 | 레거시와 **똑같이**. 예외 없습니다 | prod의 `validate`가 실제로 검사하는 유일한 항목입니다 |
| 제약 (`NOT NULL`·`CHECK`·`UNIQUE`) | 이 서버가 **쓰는** 테이블이면 레거시와 똑같이. 더 느슨해도 더 엄격해도 안 됩니다 | 위 양방향 원칙이 직접 적용됩니다 |
| 외래 키 | 쓰는 테이블이면 유지. 읽기 전용은 **새 모듈 의존이 생기면 두지 않습니다** | 읽기 전용이면 우리가 위반할 쓰기가 없어 픽스처 현실성만 좌우합니다 |
| 컬럼 범위 | 엔티티가 매핑하는 컬럼. 기본값 없는 `NOT NULL` 컬럼은 매핑하지 않아도 만듭니다 | 매핑하지 않는 컬럼은 prod 실패를 만들 수 없습니다. 단 픽스처 INSERT는 막힙니다 |
| 트리거·함수 | **재현하지 않습니다** | prod가 대신 채워 주던 값은 애플리케이션에서 명시적으로 만듭니다(라벨 발급은 `LabelAllocator`). 파일에 트리거를 두면 그 코드가 로컬에서만 우회됩니다 |

바뀐 것은 절차입니다. **레거시 소유 테이블에 컬럼이 필요하면 이제 이 리포에서 직접 `ALTER` 합니다.**
레거시가 먼저 바꾸기를 기다렸다가 베끼는 경로는 없어졌고, 그렇게 추가한 컬럼은 prod에도 이 서버의
Flyway가 만듭니다. 위 표는 **레거시가 만든 부분**에만 적용됩니다.

**감사 필드 규칙은 여기에 적용되지 않습니다.** "모든 테이블에 `created_at`/`updated_at`을 둡니다"는
**이 서버가 설계하는 스키마**에 대한 규칙입니다. 레거시가 만든 테이블은 prod에 실물이 이미 있으므로
그 규칙의 대상이 아니고, 재현 여부는 위 「컬럼 범위」 기준으로 판단합니다. 그래서
`create_source_refs_read_mirror`가 둘 다 두지 않는 것과 `create_entity_relations_read_mirror`가
매핑하지 않는 `updated_at`을 두는 것이 **둘 다 규격 안입니다.** 후자의 파일 주석은 감사 필드 규칙을
근거로 인용하지만, 실제 근거는 "레거시 실물에 있는 컬럼이라 픽스처가 재현할 수 있어야 한다"입니다.

**`create_memory_read_mirror`의 주석은 이 절이 대체합니다.** 그 파일은 "미러의 값은 prod를 얼마나
그대로 재현하느냐에 있고"를 인덱스 정의를 문자 그대로 맞추는 근거로 적었습니다. 기준은 재현
충실도가 아니라 위 양방향 원칙이고, 인덱스는 「인덱스와 존재하는 환경」의 기준을 따릅니다. 파일은
checksum 때문에 고치지 않으니 그 주석을 보고 되돌아가지 않도록 여기에 적어 둡니다.

알려진 어긋남이 둘 있습니다. 둘 다 기록만 남기고 고치지 않습니다.

- prod에는 있으나 서버 파일이 만들지 않는 컬럼이 5개 있습니다 — `entity_relations`의 `metadata`·
  `source_ref_ids`·`weight`, `source_refs.content_hash`, `workspace_members.onboarding_state`.
  매핑하지 않아 `validate` 대상이 아닙니다([설계 2.6절](../design/prod-schema-ownership-transfer.md)).
- `tasks.workspace_id`가 서버 파일에서는 `NOT NULL`이지만 레거시(`000006_fe_contract.sql`)에서는
  nullable입니다. 레거시가 백필하고 트리거로 채워 실제 `NULL` 행은 사실상 없고, `validate`도
  nullability를 보지 않습니다.

### 인덱스와 존재하는 환경

- **인덱스는 필요할 때만 만듭니다.** `local`/`test`의 데이터량에서는 seq scan이 더 싸서 인덱스가
  있어도 계획에 잡히지 않습니다. 값어치는 성능이 아니라 계획을 테스트로 고정할 수 있다는 데
  있습니다. `SET LOCAL enable_seqscan = off` 후 실제 SQL을 `EXPLAIN`해 인덱스가 후보로 오르는지 보면
  술어와 부분 인덱스 조건이 어긋나는 것을 잡을 수 있습니다(`MinsuLedgerMetricsIntegrationTest`).
  그런 테스트를 둘 때 그 인덱스를 만듭니다. 미리 만들어 두는 것은 죽은 DDL입니다.
- **대상 객체가 이미 있는 환경이 있으면 `CREATE TABLE IF NOT EXISTS`를 씁니다.** 그렇지 않으면
  실배포에서 Flyway가 `relation already exists`로 죽습니다(`entity_relations`, MOM-0795).
  `local`/`test`는 매번 빈 DB라 이 충돌이 재현되지 않으므로 대상 환경을 착수 시점에 확인합니다.

### prod에서 공유 테이블을 새로 만들 때는 GRANT를 함께 씁니다

prod 스키마 주도권이 서버로 넘어오면서(ADR-0019) `public`의 테이블 20개가 `momens_server` 소유로
넘어가고, **서버가 앞으로 만드는 테이블도 `momens_server` 소유가 됩니다.** 그 테이블들에 걸리는
기본 권한은 `postgres`의 **`SELECT`만** 부여합니다.

> 소유권 일괄 이전은 MOM-0909 부트스트랩의 선행 조건이었고 **2026-08-27에 적용됐습니다**
> (부트스트랩 자체는 2026-09-04로 날짜가 다릅니다). 이후 새로 쓰는 마이그레이션에 이 규칙이
> 적용됩니다.

`postgres`는 레거시 API·worker·retrieval이 함께 쓰는 계정입니다. 따라서 **다른 서비스가 쓰기까지
해야 하는 테이블을 새로 만든다면 그 마이그레이션이 `GRANT`를 직접 써야 합니다.**

```sql
CREATE TABLE some_shared_table (...);
GRANT SELECT, INSERT, UPDATE, DELETE ON some_shared_table TO postgres;
```

**`SERIAL`·`BIGSERIAL`·`IDENTITY` 컬럼이 있으면 backing sequence 권한도 함께 줍니다.** 테이블
DML 만으로는 `INSERT`가 `permission denied for sequence ...`로 끊깁니다. 기본값을 평가하는 쪽이
그 시퀀스에 `USAGE`를 요구하기 때문입니다.

```sql
GRANT USAGE, SELECT ON SEQUENCE some_shared_table_id_seq TO postgres;
```

빠뜨리면 그 서비스가 런타임에 `permission denied`로 끊깁니다. **배포에서 잡히지 않습니다** —
`ddl-auto: validate`는 카탈로그만 보고 DML 권한도 시퀀스 권한도 보지 않습니다.

서버만 쓰는 테이블에는 쓰지 않습니다. 기본 `SELECT`로 충분합니다.

부트스트랩이 만드는 12개 테이블 중 어디에 이 GRANT가 필요한지는 MOM-0936이 판정합니다
(최소 `outbox_events` — ADR-0009상 worker가 `signal.created`를 발행합니다). `outbox_events`는
`id BIGSERIAL PRIMARY KEY`이므로 **`outbox_events_id_seq`까지 함께 부여해야** worker의 INSERT가
성립합니다.

## 시간 · 식별자

레거시 `momens-api` 스키마와 호환을 유지합니다.

### 식별자

- PK는 UUID v4 (Java `UUID` ↔ Postgres `uuid`).
- UUID는 앱 측에서 생성합니다(엔티티 생성 시 `UUID.randomUUID()`). 스키마의
  `DEFAULT uuid_generate_v4()`는 안전망으로 둘 수 있습니다.
- 조인 테이블은 복합 PK를 사용할 수 있습니다.
- 예외: append-only 발행 로그(`outbox_events`)의 PK는 소비자의 polling watermark라
  단조 증가하는 `bigserial`을 씁니다(ADR-0008). watermark는 순서가 보장되는 정수여야 하고
  UUID로는 그 역할을 할 수 없습니다.

### 시간

- 시간 타입은 `timestamptz` ↔ Java `Instant`. 저장·처리 모두 UTC 기준이며, 표시(타임존
  변환)는 클라이언트가 담당합니다.
- 날짜만 필요한 값은 `DATE` ↔ `LocalDate`.

### 감사 필드

- 모든 테이블에 `created_at`/`updated_at`(`NOT NULL`)을 둡니다.
- 값은 JPA Auditing(`@CreatedDate`/`@LastModifiedDate`)으로 채웁니다. 스키마의
  `NOT NULL DEFAULT NOW()`는 안전망으로 둡니다.
- 예외: append-only 발행 로그(`outbox_events`)는 행이 수정되지 않으므로 `updated_at`을 두지 않고
  `created_at`만 둡니다.

### 소프트 삭제

- `deleted_at`(`timestamptz`, nullable)로 소프트 삭제를 표현합니다. 적용 범위는
  레거시 기준으로 엔티티별로 판단합니다.
