# 지표 인벤토리

코드가 직접 등록하거나 명시적으로 연결한 Micrometer 지표의 현재 목록입니다. 이름은 exporter가
변환하기 전 Micrometer 이름이며, Spring Boot가 자동으로 제공하는 JVM·HTTP·DB 지표는 포함하지
않습니다. 설정 키는 지표가 아니므로 이 목록에서 다루지 않습니다.

지표의 의미와 설계 배경은 표에 복제하지 않고 다음 문서를 따릅니다.

- 인증 전환 지표: [ADR-0017](../adr/0017-transitional-legacy-session-token-acceptance.md)
- Minsu 생성·원장 운영: [운영 준비 대장](../prod-readiness-ledger.md#minsu-활성화와-복구)

## Auth

| 지표 이름 | 계기 | 태그 | 무엇을 판단하는 값인가 |
| --- | --- | --- | --- |
| `momens.auth.bearer.token.resolutions` | counter | `mode` (`header`, `access_cookie`, `legacy_session_cookie`, `none`) | 보호 체인에서 레거시 쿠키 해석 경로에 도달한 요청의 관찰 기간 증가량이 0인지로 fallback 제거 시점을 판단한다. |

## Minsu

| 지표 이름 | 계기 | 태그 | 무엇을 판단하는 값인가 |
| --- | --- | --- | --- |
| `momens.minsu.llm.config.valid` | gauge | `enabled`, `provider`, `model` | 기능 활성 상태를 고려한 LLM 배포 설정의 유효성을 판단한다. 비활성 상태는 유효한 설정으로 본다. |
| `momens.minsu.llm.generate` | timer | `provider`, `model`, `prompt.version`, `mode`, `outcome`, `fallback.reason`, `finish.reason`, `error` | provider 경계의 소요 시간과 경로·결과별 성공 및 실패 분포를 판단한다. |
| `momens.minsu.llm.generate.active` | long-task timer | 없음 | 현재 진행 중인 provider 경계 호출의 수와 지속 시간을 판단한다. |
| `momens.minsu.llm.tokens` | summary (`tokens`) | `provider`, `model`, `type` (`prompt`, `candidate`, `thoughts`) | 모델·token 종류별 사용량 분포를 판단한다. |
| `momens.minsu.task.draft.requests` | counter | `outcome`, `fallback.reason` | provider를 호출하지 않은 경로까지 포함해 task draft 요청이 어떤 결과로 끝났는지 판단한다. |
| `executor.completed` | counter (`tasks`) | `name=minsu-draft-drain` | 비동기 생성 실행기가 완료한 작업 누적 수를 판단한다. |
| `executor.active` | gauge (`threads`) | `name=minsu-draft-drain` | 비동기 생성 실행기에서 현재 작업을 실행 중인 thread 수를 판단한다. |
| `executor.queued` | gauge (`tasks`) | `name=minsu-draft-drain` | 비동기 생성 실행기에서 시작을 기다리는 작업 수를 판단한다. |
| `executor.queue.remaining` | gauge (`tasks`) | `name=minsu-draft-drain` | 표준 바인더가 등록하지만, 현재 무제한 `LinkedBlockingQueue`에서는 값이 사실상 `Integer.MAX_VALUE`에 가까워 수용량·포화 판단에는 사용하지 않는다. |
| `executor.pool.size` | gauge (`threads`) | `name=minsu-draft-drain` | 비동기 생성 실행기의 현재 thread 수를 판단한다. |
| `executor.pool.core` | gauge (`threads`) | `name=minsu-draft-drain` | 비동기 생성 실행기의 core thread 설정을 판단한다. |
| `executor.pool.max` | gauge (`threads`) | `name=minsu-draft-drain` | 비동기 생성 실행기의 최대 thread 설정을 판단한다. |
| `momens.minsu.drain.hung.attempts` | gauge | 없음 | wall-clock 상한을 넘긴 뒤에도 반환하지 않아 실행 슬롯을 점유한 시도 수를 판단한다. |
| `momens.minsu.drain.heartbeat.age` | gauge (`seconds`) | 없음 | drain scheduler의 마지막 성공 주기 이후 경과 시간으로 정지·지속 실패를 판단한다. |
| `momens.minsu.ledger.generations` | gauge | `status` (`pending`, `processing`) | 미종료 원장의 대기·처리 중 적체를 판단한다. 인스턴스별 같은 전역 값이므로 합이 아니라 최댓값으로 본다. |
| `momens.minsu.ledger.oldest.unfinished.age` | gauge (`seconds`) | 없음 | 가장 오래된 미종료 원장의 나이로 drain 정지를 판단한다. |
| `momens.minsu.ledger.expired.leases` | gauge | 없음 | 현재 lease가 만료된 `processing` 원장의 수를 판단한다. |
| `momens.minsu.ledger.expired.lease.max.age` | gauge (`seconds`) | 없음 | 가장 오래 만료된 lease의 나이로 회수 지연을 판단한다. |
| `momens.minsu.ledger.deadline.exceeded.generations` | gauge | 없음 | `read_deadline_at`을 지났지만 아직 종료되지 않은 원장이 있는지 판단한다. |
| `momens.minsu.ledger.snapshot.age` | gauge (`seconds`) | 없음 | 마지막 성공 스냅샷 이후 경과 시간으로 나머지 원장 gauge의 신선도를 판단한다. |
| `momens.minsu.ledger.completion.attempts` | summary (`attempts`) | `completion.reason` | `_count`로 사유별 종료 건수를, `_sum`으로 종료된 원장이 소모한 claim 수를 판단한다. |
| `momens.minsu.ledger.retry.exhaustions` | counter | `failure.reason` (`timeout`, `provider_error`, `invalid_response`, `invalid_output`, `none`) | 재시도 상한에 도달한 원인이 timeout·provider·응답·출력 중 어디에 치우치는지 판단한다. |
| `momens.minsu.ledger.reclaims` | counter | 없음 | lease 만료로 회수한 작업 수를 판단한다. `stale.results`와 함께 lease 길이를 평가한다. |
| `momens.minsu.ledger.stale.results` | counter | 없음 | claim token 불일치로 버린 결과 수를 판단한다. `reclaims`와 함께 lease 길이를 평가한다. |
| `momens.minsu.ledger.enrollments` | counter | `outcome` (`success`, `failure`) | 원장 적재 실패율로 fail-closed 선택의 운영 비용을 판단한다. |
| `momens.minsu.ledger.deadline.projections` | counter | 없음 | 읽기 투영이 deadline 초과로 `ready`를 반환한 횟수를 판단한다. 0이 정상이다. |
| `momens.minsu.ledger.claim.wait.duration` | timer | 없음 | `next_attempt_at`부터 실제 claim까지의 지연으로 scheduler 적체를 판단한다. |
| `momens.minsu.ledger.generation.duration` | timer | 없음 | 원장 적재부터 task 반영까지의 end-to-end 지연을 판단한다. |

`momens.minsu.llm.generate.active`는 Micrometer의 기본 Observation handler가 자동으로 만드는
companion 지표이며 첫 provider Observation이 시작될 때 등록됩니다. 현재 low-cardinality key가
Observation 시작 뒤에 추가되므로 이 지표에는 커스텀 태그가 붙지 않습니다.

### Minsu 관측 시 해석

provider Observation은 lazy client 생성부터 응답까지 포함한다. provider에 진입하지 않은 fallback은
요청 counter만 남긴다. metric/tag/span/event에 식별자·prompt·입력·응답 원문을 넣지 않는다.
로그에도 source 원문·URL·credential·token을 남기지 않는다.

- 미종료 건수/최대 나이, 만료 lease, deadline 초과, heartbeat와 지표 snapshot age를 함께 본다.
  pending만 보면 processing에서 멈춘 작업을 놓친다.
- retry_exhausted의 사유 구성비, user_edited, claim 대기·전체 생성 지연으로 품질과 속도를 판단한다.
- provider 호출 수 / 종료 건수는 중복 실행 비용의 추정치이며 진행 중 작업이 분모에서 빠진다.
- reclaim과 stale이 함께 오르면 lease 길이·갱신을 검토한다. 적재 실패율은 fail-closed 영향을 본다.
- deadline 읽기 투영은 정상적으로 0이어야 한다. 경보 기준·구현은 **MOM-0832**에서 다룬다.
