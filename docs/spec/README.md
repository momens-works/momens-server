# 서버 명세

서버가 외부와 맺는 계약을 정리합니다.

| 문서 | 내용 |
| --- | --- |
| [API 버저닝](api-versioning.md) | Spring API versioning 적용 대상, 버전 전달 방식, breaking change 기준 |
| [OpenAPI](openapi.md) | Swagger/OpenAPI 문서화 방식, 성공/실패 예시, controller docs interface 규칙, 스냅샷 고정 |
| [OpenAPI 스냅샷](openapi.json) | `/api/v3/api-docs` 커밋본. 직접 편집하지 않고 `./gradlew updateOpenApiSnapshot`으로 갱신합니다 |
| [API 응답과 에러 코드](api-response-error-codes.md) | 성공 응답 형식, 에러 body, 에러 코드 네이밍, HTTP status 사용 기준, 에러 코드 확인 위치 |
| [모바일 API 명세](mobile-api.md) | 모바일 MVP HTTP API 계약과 request/response 예시 |
| [MCP 태스크 목록 읽기 도구](mcp-task-list.md) | list_tasks와 list_tasks_v2의 필터·상태 검증·호환 계약 |
| [MCP 쓰기 도구](mcp-write-tools.md) | 쓰기 도구 6개의 scope·참조·필드 삭제·재시도 계약과 운영 전제 |
| [MCP 연결 조회·폐기 웹 API](mcp-grants.md) | H035·H036, 사용자 권한·응답·사용 시각 계약 |
| [Blocker 쓰기와 outbox](blocker-write.md) | H066·H074·H075 HTTP 계약, 삭제 후 ID 기반 projection과 worker 소비 계약 |
