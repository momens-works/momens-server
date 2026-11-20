# 기반 규칙 (RULES)

프로젝트를 관통하는, 잘 바뀌지 않는 규칙을 성격별로 모읍니다.

## 원칙

- 확실하지 않은 규칙은 **추론하지 않고 합의로** 추가합니다. 미정인 내용은 관련 규칙
  문서나 [ADR](../adr/)에 남기고, 별도 관리가 필요한 사항은 Momens 작업으로 추적합니다.
  결정되면 규칙 문서나 [ADR](../adr/)에 반영합니다.
- "왜 그렇게 정했나"의 근거는 [ADR](../adr/)에 남기고, 여기에는 **상시 지시(무엇을 지킬지)**만
  둡니다.
- 한 파일이 너무 커지면 그때 더 잘게 나눕니다.

## 토픽

- [아키텍처](architecture.md) — modular modulith·모듈 경계·base package
- [Git](git.md) — 브랜치·커밋·PR·머지
- [문서·주석](documentation.md) — 작성·보존 기준과 참조 검증
- [코드](code-conventions.md) — 코딩 스타일·Spring·DTO·로깅·테스트
- [데이터](persistence.md) — 영속성(DB·JPA·Flyway)·시간/식별자
- [설정 · 시크릿](configuration.md) — 설정(Configuration)·시크릿
- [관측성](observability.md) — metrics·traces·logs·로그 상관관계
