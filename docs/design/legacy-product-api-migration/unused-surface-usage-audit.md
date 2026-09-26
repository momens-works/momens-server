# 웹 미호출 레거시 표면 사용 조사

관련 작업: MOM-0975 · 조사일: 2026-09-26

상태: 코드·운영 로그 조사 완료, 10건 폐기 결정, 7건 레거시 잔류 결정

## 조사 기준

대상은 티켓의 13건과 추가 확인 H070·H083·H089·H095, 총 17건이다.
실제 HTTP 호출 코드, 서버 내부 호출, worker의 공유 DB 소비, 테스트를 구분했다.
라우터 등록과 서비스 자체 단위·통합 테스트만으로 제품 사용이라고 판정하지 않는다.
대상 12건은 아래 운영 로그 관측 기간에 실제 호출도 0건이었다.
관측 기간 밖의 수동 curl·Postman 호출과 외부 비공개 소비자까지 없다는 증명이나 폐기 확정은 아니다.

원격 fetch 후 확인한 기본 브랜치 기준선:

| 저장소 | commit |
| --- | --- |
| momens-api | `7739a77` |
| momens-fe | `dea6e06` |
| momens-worker | `9c0c8a7` |
| momens-android | `fe8494f` |
| e2e | `eac954d` |
| teams | `abb911c` |

API 로컬 HEAD `66eed8d`는 기준선보다 조직명 변경 커밋 1개가 앞서 있으나,
차이는 README·.gitmodules·배포 workflow에만 있고 조사한 구현은 같다.
Android는 로컬 checkout이 뒤처져 있어 `origin/HEAD`를 직접 검색했다.
FE API client·호출부, Android Retrofit 서비스, 레거시 MCP·Slack,
worker 구현, E2E API client·테스트, teams 제품 문서를 확인했다.

## 1. 호출이 확인된 것: E2E 전용 2건

| ID | 기능 | 확인된 소비자 |
| --- | --- | --- |
| H054 | 프로젝트 decision 생성 | `ApiProjectionToRetrievalE2eTest`, `MinsuQueryE2eTest` → `ApiClient.createDecision` → HTTP POST |
| H066 | task blocker 생성 | `ApiProjectionToRetrievalE2eTest` → `ApiClient.createBlockerForTask` → HTTP POST |

[E2E ApiClient](https://github.com/momens-works/e2e/blob/eac954d/src/test/java/com/momens/e2e/support/ApiClient.java#L59),
[projection 테스트](https://github.com/momens-works/e2e/blob/eac954d/src/test/java/com/momens/e2e/ApiProjectionToRetrievalE2eTest.java#L29),
[Minsu 테스트](https://github.com/momens-works/e2e/blob/eac954d/src/test/java/com/momens/e2e/MinsuQueryE2eTest.java#L87)에 호출이 있다.
이번 조사에서 테스트를 실행하거나 현재 CI 실행 여부를 확인한 것은 아니다.
제품 클라이언트 호출은 발견하지 못했다. 최종 결정에 따라 API와 해당 E2E를 유지한다.

## 2. 호출처를 발견하지 못한 것: 12건

| ID | 기능 | 판단 시 참고 |
| --- | --- | --- |
| H047 | 프로젝트 단건 조회 | 웹 프로젝트 데이터는 snapshot으로 조립한다 |
| H055 | 프로젝트 decision 목록 | 웹의 decision 유형 메모리와 레거시 decision API를 구분해야 한다 |
| H059 | milestone blocker 생성 | E2E의 blocker 생성은 H066이며 이 경로가 아니다 |
| H073 | decision 단건 조회 | 제품·E2E 호출 미발견 |
| H074 | blocker 해결 | 호출 미발견. 폐기 시 retrieval projection 처리 방침 필요 |
| H075 | blocker 삭제 | 호출 미발견. blocker 물리 삭제와 retrieval 문서 soft-delete를 동반 |
| H076 | source connection 단건 조회 | 워크스페이스 연결 목록 H040과 다른 경로 |
| H077 | source connection 수정 | 구현은 `external_workspace_name` 변경이며 일반적인 연결 설정 변경 전체가 아니다 |
| H070 | 기존 source-ref를 task에 연결 | URL로 새 source-ref를 만드는 H069와 다른 경로 |
| H083 | memory candidate 단건 조회 | snapshot·목록 및 confirm/reject 등 액션과 다른 경로 |
| H089 | memory 단건 조회 | snapshot·목록 및 resolve 액션과 다른 경로 |
| H095 | memory에 연결된 task 조회 | task context 조회와 다른 경로 |

[FE client](https://github.com/momens-works/momens-fe/blob/dea6e06/src/api/client.ts),
[snapshot 조립](https://github.com/momens-works/momens-fe/blob/dea6e06/src/api/workspaceSnapshot.ts#L79),
[메모리 유형 매핑](https://github.com/momens-works/momens-fe/blob/dea6e06/src/api/mappers.ts#L298),
[레거시 MCP 도구 등록](https://github.com/momens-works/momens-api/blob/7739a77/internal/mcpserver/tools.go#L18)을 대조했다.
Blocker 데이터는 snapshot에서 실제로 읽지만, 그것이 위 생성·해결·삭제 endpoint의 사용 증거는 아니다.
기존 데이터와 read 경로의 유지 여부를 endpoint 처분과 따로 다뤄야 한다.

### 12건 재조사: 경로와 공유 구현을 구분한 결과

12건의 제품 HTTP 호출은 추가 조사에서도 발견되지 않았다. 다만 H047에는 서버 자체의
handler·service 테스트 호출이 있다. 따라서 “어떤 코드에서도 전혀 호출되지 않는다”는 표현은
H047에 맞지 않는다. 외부 E2E HTTP 소비자와 서버 자체 검증을 구분해야 한다.

| ID | 서버 내부 추적 | 현재 화면·다른 소비 경로 | 결론과 보존할 구현 |
| --- | --- | --- | --- |
| H047 | `project.Handler.Get → Service.Get → Repository.GetByID`. Service.Get의 제품 호출은 해당 handler뿐이며 자체 테스트가 호출한다 | snapshot, MCP, Minsu는 `project.Service.List`를 사용한다. FE는 snapshot의 프로젝트 목록으로 화면을 구성한다 | HTTP 미사용 판단 유지. `GetByID`는 프로젝트 수정에서도 사용하므로 함께 제거하면 안 된다 |
| H055 | `decision.Handler.List → Service.List → ListByProjectID`. 다른 서비스 소비자 미발견 | FE의 Decisions는 candidate·confirmed memory를 `memory_type`으로 분류한다. 레거시 `decisions` 목록을 읽지 않는다 | HTTP 및 해당 조회 service의 제품 사용 미발견 |
| H073 | `decision.Handler.Get → Service.Get → GetByID`. 다른 서비스 소비자 미발견 | FE 메모리 상세는 로드한 memories에서 label로 찾는다. 레거시 decision 단건 조회를 호출하지 않는다 | HTTP 및 해당 조회 service의 제품 사용 미발견 |
| H059 | `CreateForMilestone → handler.create → blocker.Service.Create`. milestone을 지정하는 호출은 이 handler뿐 | E2E는 H066 task blocker 생성 경로를 쓴다 | HTTP 미사용. 공용 `Create`와 projector는 H066이 사용하므로 유지 필요 |
| H074 | `blocker.Handler.Resolve → Service.Resolve → Repository.Resolve → UpsertBlocker`. handler 외 호출 미발견 | snapshot은 `blocker.Service.List`로 데이터를 읽는다. UI에 표시되는 blocked 상태가 이 resolve API 사용을 뜻하지 않는다 | HTTP 호출 미발견. 최종 결정에 따라 해결 기능과 projection을 유지한다 |
| H075 | `blocker.Handler.Delete → Service.Delete → Repository.Delete + DeleteBlocker`. handler 외 호출 미발견 | snapshot 조회는 별도 경로다 | HTTP 호출 미발견. 최종 결정에 따라 삭제 기능과 검색 문서 삭제 처리를 유지한다 |
| H076 | `source.Handler.Get → Service.Get → Repository.GetByID`. Service.Get의 다른 소비자 미발견 | 연결 목록 H040과 OAuth 설치 흐름은 별도다 | HTTP 미사용. repository `GetByID`는 Update·Disable·ConfigureFigma에서도 사용한다 |
| H077 | `source.Handler.Update → Service.Update → UpdateName → GetByID`. 다른 서비스 소비자 미발견 | 변경 가능한 값은 연결 이름 `external_workspace_name`뿐이다. OAuth·worker 설정 변경 전체를 담당하지 않는다 | HTTP 및 이름 변경 service의 제품 사용 미발견 |
| H070 | `relation.Handler.LinkTaskSourceRef → Service.LinkTaskSourceRef → Repository.Link`. 다른 서비스 소비자 미발견 | FE는 `createTaskSourceRef`로 H069를 호출한다. H069의 `CreateAndLinkTaskSourceRef`는 H070 service를 호출하지 않고 직접 repository에 연결을 쓴다 | HTTP 미사용. 공용 `Repository.Link`는 실제 사용 중 |
| H083 | `CandidateHandler.Get → Service.GetCandidate → Repository.GetCandidate`. service의 다른 소비자·자체 테스트 호출 미발견 | FE의 후보 상세는 `inboxSuggestions.find`로 찾는다. confirm/reject/merge 등은 별도 액션을 호출한다 | HTTP 미사용. 후보 액션이 사용하는 `GetCandidateForUpdate`는 별도 유지 |
| H089 | `MemoryHandler.Get → Service.GetMemory → Repository.GetMemory`. service의 다른 소비자·자체 테스트 호출 미발견 | FE의 상세는 `memories.find`로 찾는다. resolve 등 쓰기 경로는 별도다 | HTTP 미사용. repository의 `GetMemory`와 `GetMemoryForUpdate`는 다른 쓰기 경로에서도 사용 |
| H095 | `relation.Handler.LinkedTasks → Service.LinkedTasks → LinkingEntityIDs → TasksByIDs`. 다른 서비스·테스트 소비자 미발견 | FE는 snapshot의 task_contexts 및 H072 task context를 사용한다. 그 방향의 조회가 H095 역조회 호출을 의미하지 않는다 | HTTP 및 해당 역조회 service의 제품 사용 미발견 |

#### 재조사한 범위와 증거

- FE에서 `fetch`·`fetcher`·`axios`·`XMLHttpRequest` 사용을 검색했다. 제품 HTTP 호출은
  `src/api/client.ts`의 공통 request로 모인다. 대상 12건의 wrapper는 없으며, UI가 같은 이름의
  데이터를 다루는 경우 snapshot·로컬 배열 조회인지 추적했다.
- FE 기본 브랜치 이력에서도 `getProject`, `getDecision`, `listDecisions`, `createBlocker`,
  `resolveBlocker`, `deleteBlocker`, `getSourceConnection`, `updateSourceConnection`,
  `linkTaskSourceRef`, `getMemoryCandidate`, `getMemory`, `linkedTasks` 메서드의 추가·삭제를
  `git log -G`로 검색했으나 발견되지 않았다. 이는 해당 이름의 이력 검색 결과이며, 모든 과거
  임의 이름·동적 URL 호출 부재를 증명하지는 않는다.
- Android 원격 기본 브랜치의 Retrofit 계약, MCP 등록 도구 및 주입된 service 호출,
  Slack/Minsu action, worker, E2E를 대조했다. H059와 H066, H070과 H069처럼 URL이나
  공용 구현이 유사한 경로는 HTTP method와 호출하는 메서드까지 구분했다.
- 레거시 API의 `cmd/` 및 API·FE·worker·E2E·k8s·dev 환경·teams의 스크립트와 문서를
  검색했다. API 명세의 경로 목록은 존재하지만 이 12건을 실행하는 운영 스크립트는 발견하지 못했다.
- `project/handler_test.go`는 HTTP router를 거치지 않고 `Handler.Get`을 직접 호출한다.
  `project/service_integration_test.go`도 `Service.Get`을 호출한다. 이를 제품 호출과 별도로 기록한다.

상세 코드 근거:

- [FE 프로젝트 구성과 메모리·후보 상세 선택](https://github.com/momens-works/momens-fe/blob/dea6e06/src/app/useWorkspaceController.ts#L894)
- [FE 공통 HTTP 요청](https://github.com/momens-works/momens-fe/blob/dea6e06/src/api/client.ts#L468)
- [project 자체 handler 테스트](https://github.com/momens-works/momens-api/blob/7739a77/internal/project/handler_test.go#L63)
- [project 조회와 수정](https://github.com/momens-works/momens-api/blob/7739a77/internal/project/service.go#L163)
- [decision 조회 service](https://github.com/momens-works/momens-api/blob/7739a77/internal/decision/service.go#L79)
- [blocker 공용 생성 handler](https://github.com/momens-works/momens-api/blob/7739a77/internal/blocker/handler.go#L43)
- [blocker 해결·삭제 service](https://github.com/momens-works/momens-api/blob/7739a77/internal/blocker/service.go#L94)
- [source 조회·이름 변경](https://github.com/momens-works/momens-api/blob/7739a77/internal/source/service.go#L104)
- [기존 source-ref 연결과 URL 생성 경로](https://github.com/momens-works/momens-api/blob/7739a77/internal/relation/service.go#L81)
- [memory 연결 task 역조회](https://github.com/momens-works/momens-api/blob/7739a77/internal/relation/service.go#L259)
- [candidate 조회](https://github.com/momens-works/momens-api/blob/7739a77/internal/memory/service.go#L98)
- [memory 조회와 쓰기에서의 repository 사용](https://github.com/momens-works/momens-api/blob/7739a77/internal/memory/service.go#L423)

#### 운영 로그 확인 (2026-09-26)

`PROD-momens` context와 `momens-prod` namespace를 명시해 운영 API pod
`momens-api-69685659d4-p6gt4`의 현재·이전 컨테이너 로그를 조회했다.
클러스터 전체 pod 목록은 권한이 없지만 해당 namespace 조회와 pod 로그 읽기는 성공했다.

원문 로그를 파일로 보관하지 않고 스트림에서 HTTP method·경로 패턴·응답 상태를 집계했다.
출력에는 원문 URL의 식별자, IP, 요청 본문을 포함하지 않았다.

| 항목 | 결과 |
| --- | --- |
| 현재 로그 최초 시각 | 2026-09-23 08:07:58 UTC / 17:07:58 KST |
| 현재 로그 최종 시각 | 2026-09-26 11:50:18 UTC / 20:50:18 KST |
| 반환된 로그 행 | 68,054 |
| Gin HTTP 요청으로 파싱한 행 | 66,465 |
| 비교: `/health` | 40,882건 |
| 비교: `/mcp` | 22,838건 |
| 비교: `/workspaces/{id}/snapshot` | 25건 |
| 대상 H047·H055·H059·H073·H074·H075·H076·H077·H070·H083·H089·H095 | **각각 0건** |

URL과 HTTP method를 함께 비교했다. 특히 H055 GET은 H054 POST와,
H070 POST는 H071 DELETE와 구분했다. 응답 상태에 관계없이 대상 method·path 요청을 셌다.

현재 로그에는 `--since=336h`를 요청했지만 실제 반환 범위는 위 약 75시간 42분이다.
이전 컨테이너 로그는 2026-09-11 15:27:04 UTC의 7행뿐이며 HTTP 요청은 없었다.
따라서 **14일간 미사용으로 확대 해석하지 않는다**. 삭제된 과거 pod, 로그 rotation으로 사라진
기간, 외부 로그 저장소는 이번 조회에 포함되지 않는다. 테스트나 제품 API 요청을 새로 발생시키지 않았다.

현재 결론은 **12건 모두 조사한 제품 코드에 소비자가 없고, 확인 가능한 운영 로그 기간에도
호출이 없다**는 것이다. 공유 service·repository·데이터 전체가 미사용이라는 뜻은 아니며,
과거·간헐적 수동 사용 및 향후 제품 필요성에 대한 판단은 남는다.

## 3. 직접 호출은 미발견이나 기능 의존이 있는 것: 3건

| ID | 기능 | 확인된 의존 | 남은 판단 |
| --- | --- | --- | --- |
| H078 | source connection 비활성화 | API가 `DISABLED`로 변경한다. worker webhook 매칭과 backfill claim은 `ACTIVE` 연결만 선택한다. Figma는 webhook 삭제도 시도한다 | 운영에서 수집 중지를 사용하는지, 앞으로 어느 표면에서 제공할지 |
| H079 | source connection 재동기화 | API가 `resync_requested_at`을 기록한다. worker claim이 `last_synced_at`보다 새 요청을 감지해 backfill을 다시 시작한다 | 수동·외부 호출 여부와 재동기화 기능의 owner |
| H080 | Figma 연결 설정 | webhook 등록, `file_keys` 저장, `PENDING → ACTIVE` 전환. worker Figma connector는 `file_keys`로 이벤트를 매칭한다 | Figma 연결의 실제 운영 여부와 이 설정 단계를 대체할 경로 |

근거:

- [API Disable·Resync](https://github.com/momens-works/momens-api/blob/7739a77/internal/source/service.go#L145)
- [API resync 요청 기록](https://github.com/momens-works/momens-api/blob/7739a77/internal/source/repository.go#L214)
- [worker ACTIVE 연결 조회](https://github.com/momens-works/momens-worker/blob/9c0c8a7/internal/source/connection_repository.go#L32)
- [worker backfill claim](https://github.com/momens-works/momens-worker/blob/9c0c8a7/internal/source/backfill_run_repository.go#L49)
- [API Figma 설정](https://github.com/momens-works/momens-api/blob/7739a77/internal/source/service.go#L331)
- [worker Figma 매칭](https://github.com/momens-works/momens-worker/blob/9c0c8a7/internal/connectors/figma/connector.go#L127)

worker는 이 세 HTTP endpoint를 호출하지 않고 결과를 공유 DB에서 소비한다.
연결을 비활성화했을 때 이미 실행 중인 작업까지 즉시 중단되는지는 이 조사에서 검증하지 않았다.

## 전용 조회 기능 폐기 결정 (2026-09-26)

사용자가 “사용되지 않고 연관 의존성도 없는 항목부터 폐기”하도록 결정했다.
전용 기능의 다른 소비자가 없는 **H055·H073·H083·H095, 4건을 폐기로 확정**한다.
공용 DB·인가·스캔 helper에 대한 의존 자체가 없다는 뜻은 아니다. 이 전용 조회 경로를
종료해도 다른 기능의 실행 경로를 제거할 필요가 없다는 의미다.

| ID | 폐기 결정 대상 | 유지할 것 |
| --- | --- | --- |
| H055 | decision 목록 HTTP endpoint와 전용 목록 조회 | H054 생성 API·E2E, decision 모델·데이터·projection |
| H073 | decision 단건 HTTP endpoint와 전용 단건 조회 | H054 생성 API·E2E, decision 모델·데이터·projection |
| H083 | candidate 단건 HTTP endpoint와 전용 `GetCandidate` 조회 | 후보 목록·review 액션·`GetCandidateForUpdate`·공용 scan helper |
| H095 | linked-tasks HTTP endpoint와 전용 역조회 | task context·공용 relation 기능·기존 연결 데이터 |

[원장](ledger.md)에 폐기 결정과 근거를 반영했다. 실제 route·코드·데이터는 변경하지 않았다.
실행 전이므로 `traced`를 유지하고 제거 또는 비활성을 확인한 후 `retired`로 전환한다.

## 공유 구현을 유지하는 HTTP 경로 폐기 결정 (2026-09-26)

사용자 동의로 **H047·H059·H070·H076·H077·H089, 6건의 HTTP 경로를 폐기**한다.
제품 호출처가 없고 관측한 운영 로그에서도 각각 0건이었다. 신규 서버로 이관하지 않는다.

| ID | 폐기할 HTTP 기능 | 유지할 공용 구현 |
| --- | --- | --- |
| H047 | 프로젝트 단건 조회 | 수정 기능에서 사용하는 repository 조회 |
| H059 | milestone blocker 생성 | H066의 blocker 생성 service·projection |
| H070 | 기존 source-ref 연결 | H069의 연결 repository·기존 연결 데이터 |
| H076·H077 | source 연결 단건 조회·이름 변경 | 다른 연결 관리 기능의 repository 조회 |
| H089 | memory 단건 조회 | 다른 쓰기 기능의 repository 조회·기존 메모리 데이터 |

H047의 전용 handler/service 테스트는 실제 제거 범위에 맞춰 정리한다.
공용 구현은 유지한다. route 제거는 별도 실행이며 현재 상태는 `traced`다.
전용 조회 4건과 합쳐 **총 10건 폐기 결정·실행 대기**다.

## 최종 유지 결정 (2026-09-26)

사용자는 **H054·H066·H074·H075·H078·H079·H080 모두 유지**하기로 결정했다.
H054는 결정 개념과 현재 API·구현을 함께 유지한다.

| ID | 유지 기능 | 함께 유지할 경로 |
| --- | --- | --- |
| H054 | Decision 생성 | E2E의 생성 호출, 모델·데이터, 생성 시 retrieval projection |
| H066 | Task blocker 생성 | E2E, 공용 blocker 생성 service·projection |
| H074·H075 | Blocker 해결·삭제 | 기존 blocker 데이터 처리, 검색 문서 갱신·삭제 |
| H078 | 수집 중지 | 연결 상태 변경, worker ACTIVE 필터, Figma webhook 삭제 |
| H079 | 재동기화 | resync 요청 기록과 worker backfill 소비 |
| H080 | Figma 설정 | webhook 등록·연결 활성화·file_keys 저장과 worker 소비 |

현재 API owner는 **레거시 `momens-api`**다. 이 7건을 레거시에 잔류시켜 유지하며,
신규 서버 이관이나 다른 시스템으로의 owner 이전은 이번 결정에 포함하지 않는다.
레거시 전체 종료 전에 최종 owner와 전환·종료 조건을 별도로 결정해야 한다.
worker는 기존처럼 공유 상태·설정을 소비한다. E2E와 projection 경로도 현재대로 유지한다.

## 최종 집계

- **폐기 결정·실행 대기 10건:** H047·H055·H059·H070·H073·H076·H077·H083·H089·H095.
- **레거시 잔류·유지 7건:** H054·H066·H074·H075·H078·H079·H080.
- 대상 17건의 처분을 기록했다. 실제 route·구현·데이터 삭제나 이관은 실행하지 않았다.
- 원장 runtime 상태는 `traced`를 유지한다. 폐기 실행과 잔류 기능의 최종 owner 결정은 후속 작업이다.
