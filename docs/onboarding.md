# 온보딩

`momens-server`에 합류한 것을 환영합니다. 이 문서는 처음 합류한 서버 개발자가
**로컬에서 서버를 띄우고 첫 PR을 올리기까지** 필요한 것을 한 번에 안내합니다.

세부 규칙은 각 전용 문서로 링크합니다. 이 문서는 "흐름"을, 전용 문서는 "정확한
규칙"을 담습니다.

---

## 1. momens-server는 무엇인가

`momens-server`는 기존 Go/Gin 기반 `momens-api`를 대체하는 **새 Java Spring 제품 API
서버**입니다. 하나의 배포 가능한 Spring Boot 애플리케이션이며, 내부는 Gradle 멀티모듈
+ Spring Modulith로 경계를 두는 **modular modulith**를 지향합니다.

### 시스템 맥락

Momens는 여러 레포로 나뉘어 있습니다.

| 레포 | 역할 |
| --- | --- |
| [`teams`](https://github.com/Momens-Works/teams) | PRD·ADR·용어집·product language (제품 단일 출처) |
| `momens-api` | 레거시 Go/Gin API (대체 대상) |
| **`momens-server`** | **새 Java Spring 제품 API 서버 (여기)** |
| `momens-worker` | 외부 소스 수집·큐레이션 워커 |
| `momens-retrieval` | 검색 read-model 서버 (gRPC) |
| `momens-proto` | 서버·워커가 공유하는 protobuf 계약 (Git submodule) |
| `k8s` | 쿠버네티스·인프라 정의 |

제품 차원의 배경/용어가 궁금하면 먼저 `teams`를 보세요.

---

## 2. 사전 준비물

- **JDK 21** (Temurin 권장)
- **Docker** (로컬 DB + 테스트의 Testcontainers에 필요 — 반드시 실행 중이어야 함)
- **Git**
- 권장 IDE: **IntelliJ IDEA** (`.editorconfig`/Spotless 자동 적용)

전역 Gradle은 필요 없습니다. 리포에 포함된 **Gradle Wrapper(`./gradlew`)** 를 씁니다.

---

## 3. 처음 한 번 세팅

```bash
# 1) 클론
git clone --recurse-submodules https://github.com/Momens-Works/momens-server.git
cd momens-server

# 이미 클론한 리포라면 submodule 초기화
git submodule update --init --recursive

# 2) 로컬 환경변수 준비 (.env 는 커밋하지 않음)
cp .env.example .env

# 3) 로컬 DB 기동 (pgvector PostgreSQL)
docker compose up -d

# 4) 빌드/테스트로 환경 검증 (Docker 실행 중이어야 함)
./gradlew test

# 5) git 훅 설치 (커밋 전 포맷 검사). core.hooksPath 를 hooks/ 로 설정합니다.
./gradlew installGitHooks
```

`installGitHooks` 는 최초 1회만 실행하면 됩니다. 이후 커밋할 때 스테이징된 Java/Gradle
파일이 있으면 `pre-commit` 훅이 `spotlessCheck` 를 돌려 CI 이전에 포맷 문제를 잡아줍니다.
`spotlessCheck` 는 작업 트리 전체를 검사하므로, 스테이징하지 않은 다른 Java/Gradle 파일의
포맷 문제도 함께 걸립니다.

알아둘 점 두 가지입니다.

- `core.hooksPath` 를 `hooks/` 로 지정하면 **기존 `.git/hooks` 의 훅은 더 이상 실행되지
  않습니다**. 개인 훅을 쓰고 있었다면 `hooks/` 로 옮기거나 별도로 관리해야 합니다.
- 훅을 건너뛰어야 할 때는 `git commit --no-verify` 를 씁니다. 다만 포맷 문제는 CI
  (`spotlessCheck`)에서 다시 걸리므로 임시 우회로만 사용합니다.

줄바꿈 형식은 `.gitattributes`에서 LF로 통일합니다. 저장소를 클론한 뒤 다음 명령으로 워킹
트리의 줄바꿈 상태를 확인합니다.

```bash
git ls-files --eol | grep ' w/crlf' | grep -v 'eol=crlf'
```

출력이 비어 있으면 정상입니다. `eol=crlf`로 지정된 예외 파일은 필터링되기 때문에, 예외가
추가되더라도 동일한 명령을 그대로 사용할 수 있습니다.

만약 파일이 출력된다면 `.gitattributes` 설정이 적용되기 전에 저장소를 클론한 상태일 가능성이
있습니다. 이 경우 아래 명령으로 인덱스 내용을 워킹 트리에 다시 반영합니다.

```bash
git status              # 커밋되지 않은 변경 사항이 없는지 먼저 확인합니다.
git restore .
```

`git restore`는 인덱스는 건드리지 않고 워킹 트리만 다시 체크아웃합니다. 이 과정에서 `eol=lf`
규칙이 적용되면서 CRLF가 LF로 정리됩니다. 다만 커밋되지 않은 변경 사항도 함께 사라지므로,
반드시 `git status` 결과가 비어 있는지 먼저 확인해야 합니다.

`git restore` 이후에도 `w/crlf` 표시가 남아 있다면, 인덱스를 초기화한 뒤 전체 파일을 다시
체크아웃해야 합니다.

```bash
git rm --cached -r .
git reset --hard
```

CRLF가 섞인 상태로 두면 워킹 트리를 검사하는 `spotlessCheck`가 실패합니다. 이 경우 코드를
수정하지 않은 커밋에서도 pre-commit 훅이 차단될 수 있습니다.

`momens-proto`는 private 리포이므로 로컬 GitHub 자격 증명에 읽기 권한이 필요합니다.
GitHub Actions는 같은 권한을 가진 repository secret `CI_SUBMODULES_TOKEN`으로 submodule을
checkout합니다.

`.env` 키 설명과 프로필 구성은 [로컬 개발](local-development.md)을 참고하세요.

---

## 4. 일상 개발 루프

```bash
# 애플리케이션 실행 (local 프로필)
./gradlew bootRun --args='--spring.profiles.active=local'

# 헬스체크 확인
curl http://localhost:8080/actuator/health   # {"status":"UP"}

# 코드 포맷 자동 적용 (커밋 전)
./gradlew spotlessApply

# 테스트
./gradlew test
```

- 포맷은 **Spotless + Google Java Format**. CI는 `spotlessCheck`로 검사하므로,
  커밋 전 `spotlessApply`를 습관화하세요.
- 테스트는 PostgreSQL **Testcontainers**를 사용합니다(H2 미사용). 그래서 **Docker가
  떠 있어야** 테스트가 통과합니다.

---

## 5. 프로젝트 구조

```text
momens-server
├── app/                       # 실행 애플리케이션 모듈 (Spring Boot main)
│   └── src/main/java/works/momens/server/MomensServerApplication.java
├── common/                    # 최소 공유 기반 모듈
├── modules/                   # 기능/capability Gradle 모듈
│   ├── auth/
│   ├── context/
│   ├── minsu/
│   ├── mobile/
│   ├── notification/
│   ├── outbox/
│   ├── project/
│   ├── signal/
│   ├── source/
│   ├── user/
│   └── workspace/
├── third_party/
│   └── momens-proto/          # 공유 protobuf 계약 submodule
├── build.gradle               # 루트(공통 설정)
├── settings.gradle            # 멀티모듈·composite build 정의
├── docker-compose.yml         # 로컬 pgvector PostgreSQL
└── docs/                      # 문서 (이 폴더)
```

- 베이스 패키지: `works.momens.server`
- 실행 모듈은 `app`, 최소 공유 기반은 `common`입니다.
- `modules/`는 물리적 그룹일 뿐 Gradle 프로젝트가 아니며, 기능 모듈의 논리 경로는 `:auth`처럼
  평면으로 유지합니다.
- 확정된 모듈 목록과 책임 경계는 [상세설계 > 모듈 맵](design/module-map.md)을 따릅니다.
- 구조/의존 방향은 [기반 규칙 > 아키텍처](rules/architecture.md)에 정리되어 있습니다.

---

## 6. 협업 플로우

브랜치 전략은 **GitFlow**입니다. `develop`이 기본 개발 브랜치, `main`이 릴리즈
브랜치입니다. 일반 PR 대상은 `develop`입니다.

### 브랜치 이름

작업은 Momens에서 관리합니다. 브랜치를 만들기 전에 기존 Momens 작업을 조회하고, 없으면 Momens
MCP 또는 웹에서 작업을 생성해 발급된 라벨을 브랜치 앞에 둡니다.

```text
<Momens-작업-라벨>-<type>/<work-description>
# 예) MOM-0680-feat/create-category
```

새 작업은 제목, 배경·목적, 작업 범위, 완료 조건을 기록하고 `backlog` 또는 `todo`로 만듭니다. 구현에
착수하면 `in_progress`로 변경합니다. 라벨을 브랜치명에 넣거나 `Fixes MOM-*`를 적어도 Momens 작업이
자동으로 연결·완료되지는 않으므로, 실제 머지 후 Momens에서 직접 상태를 갱신합니다.

### 커밋 메시지

```text
feat(<Momens-작업-라벨>/<domain>): 새로운 기능 추가
fix(<Momens-작업-라벨>/<domain>): 버그 수정
docs(<Momens-작업-라벨>): 문서 수정
```

타입과 형식 전체는 [기반 규칙 > Git](rules/git.md)을 참고하세요.

### PR

- 제목은 `[Feature] / [Bug] / [Refactor] / [Chore] / [Docs]` prefix로 시작합니다.
- PR 템플릿이 자동으로 채워집니다(요약·작업 유형·검증·리뷰 포인트 등).
- 열면 다음 체크가 돌고, **머지하려면 통과해야 합니다**:

| 체크 | 내용 | 머지 차단? |
| --- | --- | --- |
| `build` | `check`(`spotlessCheck`, `verifyDockerModuleBuildScripts`, `verifyDependencyVersionsInCatalog`, `test`) → `bootJar` | 필수 |
| `pr-format` | PR 제목/본문 형식, 브랜치명 정합성 검증 | ✅ 필수 |
| CodeQL | 보안 정적 분석 | ℹ️ 비차단(Security 탭 알림) |

### 머지

- 일반 PR(`*` → `develop`)은 **rebase merge**, 릴리즈 PR(`develop` → `main`)과 hotfix PR
  (`*` → `main`)은 **merge commit**으로 머지합니다. squash merge는 사용하지 않습니다.
- `develop`/`main`은 **PR로만** 변경(직접 push 차단), 리뷰 대화는 모두 resolve 후 머지.
- 머지된 일반 작업 브랜치는 자동 삭제되고, 기본 브랜치인 `develop`은 릴리즈 후에도 유지됩니다.
- 머지하려면 **최소 1명의 승인이 필요합니다**(보호 ruleset 강제). 리뷰어는 PR을
  열 때 직접 지정합니다(자동 요청 없음).

머지 정책 전체는 [기반 규칙 > Git > 머지](rules/git.md#머지)에 있습니다.

---

## 7. 첫 기여 따라하기

```bash
# 1) Momens에서 기존 작업 조회 또는 새 작업 생성 → 라벨 확인 (예: MOM-0680)
# 2) 작업을 in_progress로 변경하고 브랜치 생성
git switch develop && git pull
git switch -c MOM-0680-feat/create-category

# 3) 작업 → 포맷 → 커밋
./gradlew spotlessApply
git commit -m "feat(MOM-0680/category): 카테고리 생성 API 추가"

# 4) 푸시 후 PR 생성 (base: develop)
git push -u origin MOM-0680-feat/create-category

# 5) CI(build, pr-format) 통과 + 승인 1 + 리뷰 대화 resolve → rebase 머지
# 6) 실제 머지 확인 후 Momens 작업을 done으로 변경
```

`Fixes MOM-0680`이나 브랜치 라벨은 Momens 작업을 자동 완료하지 않습니다. PR을 열거나 승인받은 시점이
아니라 실제 머지된 뒤 `done`으로 변경합니다. 머지 없이 닫힌 PR은 완료 처리하지 않습니다.

---

## 8. 문서 지도

| 문서 | 무엇 |
| --- | --- |
| [docs/README.md](README.md) | 문서 인덱스 |
| [local-development.md](local-development.md) | 로컬 실행·DB·프로필·`.env` |
| [기반 규칙](rules/README.md) | 코딩·Git·아키텍처 등 |
| [spec/](spec/) | 서버 API 계약·응답·에러 코드 |
| [adr/](adr/) | 결정 기록 (ADR) |
| [../AGENTS.md](../AGENTS.md) | AI 진입점 (Codex/Claude 공용) |

---

## 9. 에이전트 작업 흐름

이 리포에는 프로젝트 종속 에이전트 스킬을 둡니다. Codex는 `.codex/skills/`, Claude Code는
`.claude/skills/` 아래에 같은 스킬을 둡니다. 스킬은 프로젝트 문서를 복제하지 않고, 필요한 문서를
어떤 순서로 읽고 어떤 검사와 작업 상태 변경을 수행할지 안내합니다.

| 시점 | 스킬 | 목적 |
| --- | --- | --- |
| 맥락 동기화 | `sync` | 프로젝트 맥락, 브랜치 상태, 기존 Momens 작업과 관련 문서 확인 |
| 작업 착수 | `start-work` | Momens 작업 중복 조회·생성, `in_progress` 전환, 작업 브랜치 생성 |
| PR 전 | `diff-review` | Momens 작업 범위와 diff 정합성, 컨벤션, 테스트, 문서, 민감정보, API 계약 점검 |
| PR 머지 후 | `finish-work` | 실제 머지 검증, Momens 작업 `done` 전환, 머지 정보 댓글 기록 |
| 레거시 이관 전 | `migrate-slice` | Go API에서 Spring으로 옮길 최소 수직 슬라이스 계획 |

권장 흐름:

```text
맥락 확인: sync
작업 착수: start-work
PR 전: diff-review
PR 머지 후: finish-work
레거시 기능 이관 전: migrate-slice
```

---

## 10. 막히면

- 로컬 실행/DB 문제 → [local-development.md](local-development.md)
- 규칙/컨벤션 질문 → [기반 규칙](rules/README.md)
- 제품 맥락/용어 → [`teams`](https://github.com/Momens-Works/teams)
- 그래도 막히면 백엔드 팀(`@Momens-Works/momens-backend`)에 물어보세요.
